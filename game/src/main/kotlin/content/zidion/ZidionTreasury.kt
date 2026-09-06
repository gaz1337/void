package content.zidion

import com.github.michaelbull.logging.InlineLogger
import content.entity.player.bank.bank
import world.gregs.voidps.engine.client.PlayerAccountLoader
import world.gregs.voidps.engine.data.AccountManager
import world.gregs.voidps.engine.data.SaveQueue
import world.gregs.voidps.engine.data.Settings
import world.gregs.voidps.engine.data.Storage
import world.gregs.voidps.engine.data.definition.AccountDefinitions
import world.gregs.voidps.engine.data.definition.InventoryDefinitions
import world.gregs.voidps.engine.entity.Entity
import world.gregs.voidps.engine.entity.World
import world.gregs.voidps.engine.entity.character.player.Players
import world.gregs.voidps.engine.entity.item.Item
import world.gregs.voidps.engine.event.AuditLog
import world.gregs.voidps.engine.get
import world.gregs.voidps.engine.inv.addToLimit
import world.gregs.voidps.engine.inv.removeToLimit
import world.gregs.voidps.network.login.PasswordManager

/**
 * The central Zidion treasury: one real account's bank, named by `zidion.treasury.account`.
 *
 * It is the house. Everything the world skims lands here as coins - PvP bot tribute, the
 * exchange's tax - and the market traders trade *with* it: their bids are funded from this bank,
 * their sales pay into it, and the stock they buy is banked here to be sold again. The owner can
 * watch the whole economy breathe from their own bank screen, and starve or flood the market by
 * moving coins in and out of it.
 *
 * Online, the owner's live bank is used directly. Offline, the save is edited on disk the same
 * way [content.social.report.Ban] edits an offline player's variables: load, copy, save. The bank
 * array is kept as a [snapshot] between edits so a busy trading floor does not re-read the save
 * for every glance at the balance; every change is still written through immediately. The one
 * hazard is the owner's own logout save still being written, so while [SaveQueue] reports one
 * pending nothing is touched: deposits wait in [backlog] and are retried by a world queue,
 * withdrawals and takes simply report zero and the caller skips its move this tick.
 *
 * Scripts must call [invalidate] when the owner logs in or out (ZidionMarket does), so the
 * snapshot never shadows a bank that has since been loaded into the game or re-saved by it.
 *
 * Shared between scripts as an object, which is how Void scripts share state
 * (wiki: Scripts - "state is shared through Kotlin objects").
 */
object ZidionTreasury {

    private val logger = InlineLogger("ZidionTreasury")

    /** Items that could not be banked yet: owner's save mid-write, or a bank with no room. */
    private val backlog = mutableMapOf<String, Long>()

    /** Consecutive failed attempts per backlogged item; past [MAX_RETRIES] it is written off. */
    private val retries = mutableMapOf<String, Int>()

    /** The owner's saved bank while they are offline; null means read it again before use. */
    private var snapshot: Array<Item>? = null

    /** Display name of the owning account. */
    val owner: String
        get() = Settings["zidion.treasury.account", ""]

    /**
     * Make sure the treasury account exists; returns true if it had to be created just now.
     * Created exactly like a login-server account - home tile, `new_player`, bcrypt hash of
     * `zidion.treasury.password` - and queued for saving, so the caller should allow a few ticks
     * before relying on the save file.
     */
    fun createAccount(loader: PlayerAccountLoader, accounts: AccountManager, definitions: AccountDefinitions, saves: SaveQueue): Boolean {
        val account = owner
        if (account.isBlank() || loader.exists(account) || saves.saving(account)) {
            return false
        }
        if (definitions.get(account) != null || definitions.getByAccount(account) != null) {
            return false
        }
        val password = Settings["zidion.treasury.password", ""]
        if (password.isBlank()) {
            logger.warn { "Treasury account '$account' has no save and zidion.treasury.password is not set; not created." }
            return false
        }
        val player = accounts.create(account, PasswordManager(loader).encrypt(account, password))
        definitions.add(player)
        saves.save(player)
        logger.info { "Created treasury account '$account'." }
        return true
    }

    /** Pay [coins] into the treasury. Returns true if they were banked or safely queued. */
    fun deposit(coins: Int, source: Entity, reason: String): Boolean = store(COINS, coins, source, reason)

    /** Take up to [coins] out of the treasury. Returns what was actually taken. */
    fun withdraw(coins: Int, source: Entity, reason: String): Int = take(COINS, coins, source, reason)

    /** Coins currently banked, ignoring any [backlog] still waiting to land. */
    fun balance(): Long = stock(COINS).toLong()

    /** Bank [amount] of [item] in the treasury. Returns true if it was banked or safely queued. */
    fun store(item: String, amount: Int, source: Entity, reason: String): Boolean {
        if (amount <= 0 || item.isBlank()) {
            return false
        }
        val name = owner
        if (name.isBlank()) {
            logger.warn { "No zidion.treasury.account set; $amount $item from $source ($reason) discarded." }
            return false
        }
        AuditLog.event(source, "zidion_treasury_store", "$item x$amount", reason)
        val online = Players.find(name)
        val banked = if (online != null) {
            online.bank.addToLimit(item, amount).toLong()
        } else {
            offline(name) { bank -> add(bank, item, amount) } ?: 0L
        }
        val remainder = amount - banked
        if (remainder > 0) {
            defer(item, remainder)
        } else {
            retries.remove(item)
        }
        return true
    }

    /** Take up to [amount] of [item] out of the treasury. Returns what was actually taken. */
    fun take(item: String, amount: Int, source: Entity, reason: String): Int {
        if (amount <= 0 || item.isBlank()) {
            return 0
        }
        val name = owner
        if (name.isBlank()) {
            return 0
        }
        val online = Players.find(name)
        val taken = if (online != null) {
            online.bank.removeToLimit(item, amount).toLong()
        } else {
            // A save mid-write means we cannot see the true holdings; take nothing this tick.
            offline(name) { bank -> remove(bank, item, amount) } ?: 0L
        }
        if (taken > 0) {
            AuditLog.event(source, "zidion_treasury_take", "$item x$taken", reason)
        }
        return taken.toInt()
    }

    /** How much of [item] the treasury holds, ignoring any [backlog] still waiting to land. */
    fun stock(item: String): Int {
        val name = owner
        if (name.isBlank()) {
            return 0
        }
        val online = Players.find(name)
        if (online != null) {
            return online.bank.count(item)
        }
        val bank = snapshot ?: load(name) ?: return 0
        return bank.firstOrNull { it.id == item }?.amount ?: 0
    }

    /** Value of everything banked except coins, at current prices. */
    fun stockValue(price: (String) -> Int): Long {
        val name = owner
        if (name.isBlank()) {
            return 0
        }
        val bank = Players.find(name)?.bank?.items ?: snapshot ?: load(name) ?: return 0
        var total = 0L
        for (item in bank) {
            if (item.isEmpty() || item.id == COINS) {
                continue
            }
            total += price(item.id).toLong() * item.amount
        }
        return total
    }

    /** Forget the offline snapshot: the owner has logged in, or their logout save is being written. */
    fun invalidate() {
        snapshot = null
    }

    /** Re-attempt anything held back by [defer]. */
    fun flush() {
        if (backlog.isEmpty()) {
            return
        }
        val pending = backlog.toMap()
        backlog.clear()
        for ((item, amount) in pending) {
            store(item, amount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), World, "backlog")
        }
    }

    private fun defer(item: String, amount: Long) {
        val attempt = (retries[item] ?: 0) + 1
        retries[item] = attempt
        if (attempt > MAX_RETRIES) {
            // A full bank or a capped stack is not going to clear itself; stop pretending it will.
            retries.remove(item)
            logger.warn { "Treasury could not bank $amount $item after $MAX_RETRIES attempts; written off." }
            return
        }
        backlog[item] = (backlog[item] ?: 0L) + amount
        logger.info { "Treasury holding $amount $item back; retrying in $RETRY_TICKS ticks." }
        World.queue(FLUSH_QUEUE, initialDelay = RETRY_TICKS) {
            flush()
        }
    }

    /**
     * Apply [block] to the owner's saved bank and write it back. Null means the save is being
     * written right now and nothing was touched; otherwise the item delta [block] reported.
     */
    private fun offline(name: String, block: (Array<Item>) -> Long): Long? {
        val account = accountOf(name)
        val saves: SaveQueue = get()
        if (saves.saving(account)) {
            snapshot = null
            return null
        }
        val storage: Storage = get()
        val save = storage.load(account)
        if (save == null) {
            logger.warn { "Treasury owner '$name' has no save to pay into." }
            return 0
        }
        val bank = snapshot ?: save.inventories[BANK]?.copyOf() ?: Array(InventoryDefinitions.get(BANK).length) { Item.EMPTY }
        val changed = block(bank)
        if (changed != 0L) {
            storage.save(listOf(save.copy(inventories = save.inventories + (BANK to bank.copyOf()))))
        }
        snapshot = bank
        return changed
    }

    private fun load(name: String): Array<Item>? {
        val account = accountOf(name)
        val saves: SaveQueue = get()
        if (saves.saving(account)) {
            return null
        }
        val storage: Storage = get()
        val bank = storage.load(account)?.inventories?.get(BANK)?.copyOf() ?: return null
        snapshot = bank
        return bank
    }

    private fun accountOf(displayName: String): String {
        val accounts: AccountDefinitions = get()
        return accounts.get(displayName)?.accountName ?: displayName
    }

    /** Merge into the existing stack (the bank stacks everything), else the first free slot. Returns amount placed. */
    private fun add(bank: Array<Item>, item: String, amount: Int): Long {
        val index = bank.indexOfFirst { it.id == item }
        if (index != -1) {
            val current = bank[index].amount
            val added = minOf(Int.MAX_VALUE - current, amount)
            if (added <= 0) {
                return 0
            }
            bank[index] = Item(item, current + added)
            return added.toLong()
        }
        val free = bank.indexOfFirst { it.isEmpty() }
        if (free == -1) {
            return 0
        }
        bank[free] = Item(item, amount)
        return amount.toLong()
    }

    /** Returns amount removed, never more than the stack holds. */
    private fun remove(bank: Array<Item>, item: String, amount: Int): Long {
        val index = bank.indexOfFirst { it.id == item }
        if (index == -1) {
            return 0
        }
        val current = bank[index].amount
        val removed = minOf(current, amount)
        bank[index] = if (removed == current) Item.EMPTY else Item(item, current - removed)
        return removed.toLong()
    }

    private const val COINS = "coins"
    private const val BANK = "bank"
    private const val FLUSH_QUEUE = "zidion_treasury_flush"
    private const val RETRY_TICKS = 50
    private const val MAX_RETRIES = 5
}
