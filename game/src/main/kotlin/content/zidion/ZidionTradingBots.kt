package content.zidion

import com.github.michaelbull.logging.InlineLogger
import content.bot.Bot
import content.bot.isBot
import content.social.trade.exchange.GrandExchange
import content.zidion.ZidionMarket.Campaign
import content.zidion.ZidionMarket.Commodity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import world.gregs.voidps.engine.Contexts
import world.gregs.voidps.engine.Script
import world.gregs.voidps.engine.client.PlayerAccountLoader
import world.gregs.voidps.engine.client.variable.hasClock
import world.gregs.voidps.engine.client.variable.start
import world.gregs.voidps.engine.data.Settings
import world.gregs.voidps.engine.data.definition.Tables
import world.gregs.voidps.engine.data.exchange.ExchangeOffer
import world.gregs.voidps.engine.data.exchange.OpenOffer
import world.gregs.voidps.engine.entity.World
import world.gregs.voidps.engine.entity.character.mode.EmptyMode
import world.gregs.voidps.engine.entity.character.mode.Rest
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.character.player.Players
import world.gregs.voidps.engine.entity.item.Item
import world.gregs.voidps.engine.inv.clear
import world.gregs.voidps.engine.map.collision.random
import world.gregs.voidps.engine.timer.Timer
import world.gregs.voidps.network.client.DummyClient
import world.gregs.voidps.type.Tile
import world.gregs.voidps.type.random
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The trading bots on the exchange floor.
 *
 * They are player accounts logged in through the same [PlayerAccountLoader] any bot uses, standing
 * at fixed spots around the desk, and they trade through the real exchange: `GrandExchange.buy`
 * and `sell` into the same order book real players use, with the same collection boxes, tax and
 * buy limits. Nothing is faked at the book level - when you sell into a bid, a trader bought it.
 *
 * Their money is the house's. Every bid is escrowed out of the treasury bank before it is
 * placed; every sale is stock taken from that bank; whatever lands in their collection boxes -
 * bought items, sale proceeds, refunds - is swept straight back into it. Strict: if the
 * treasury cannot fund a bid, there is no bid.
 *
 * One pass every `pass_ticks`, and at most ONE new offer per pass. The exchange samples
 * same-priced offers weighted by age and throws when two were created in the same millisecond;
 * one offer per tick cannot collide. Each pass, in order: spawn a missing trader, sweep the
 * boxes, cancel quotes the market has moved away from, take a player offer sitting inside the
 * band, act a campaign out, or refresh a two-sided quote on one of the busiest commodities.
 *
 * Bots are never saved, so at shutdown every open trader offer is pulled and its escrow returned
 * to the treasury; at boot anything a crash left in the saved book under a trader name is purged.
 */
class ZidionTradingBots(
    private val exchange: GrandExchange,
    private val loader: PlayerAccountLoader,
) : Script {

    private val logger = InlineLogger("ZidionTradingBots")

    /** Owned scope, not GlobalScope: an unowned root coroutine outlives shutdown and resumes against torn-down state. */
    private val scope = CoroutineScope(SupervisorJob() + Contexts.Game)

    private class Trader(var name: String, val style: String, val tile: Tile) {
        var player: Player? = null
        var pending = false
    }

    private val traders = mutableListOf<Trader>()

    /** Account names of the traders; anything in the book under one of these is ours. */
    private val names = mutableSetOf<String>()

    /** Rotates through the quoted commodities so every pass refreshes a different one. */
    private var cursor = 0

    init {
        worldSpawn {
            load()
            purge()
            World.timers.start(TIMER)
        }

        worldTimerStart(TIMER) { rule("pass_ticks").coerceAtLeast(1) }

        worldTimerTick(TIMER) {
            pass()
            Timer.CONTINUE
        }

        playerDespawn {
            val trader = traders.firstOrNull { it.player === this } ?: return@playerDespawn
            trader.player = null
        }

        worldDespawn {
            unwind()
            scope.cancel()
        }
    }

    private fun load() {
        traders.clear()
        names.clear()
        val table = Tables.getOrNull(ZidionMarket.TRADERS) ?: return
        for (row in table.rows()) {
            val name = row.rowId
            val path = "${ZidionMarket.TRADERS}.$name"
            traders.add(Trader(name, Tables.stringOrNull("$path.style") ?: MAKER, Tables.tileOrNull("$path.tile") ?: Tile(3164, 3487)))
            names.add(name)
        }
        // A crowd of ambient bots so the Grand Exchange looks packed with players. They wander,
        // chat (colourfully) and emote like the traders, but never touch the market - placer()
        // skips them - so the economy is unaffected. Count is tunable for performance.
        val crowd = Settings["zidion.market.crowd", 90]
        var added = 0
        var attempts = 0
        while (added < crowd && attempts < crowd * 8) {
            attempts++
            val name = ZidionPvpBots.NAMES.random(random)
            if (name in names) {
                continue
            }
            val tile = Tile(3146 + random.nextInt(42), 3470 + random.nextInt(42))
            traders.add(Trader(name, CROWD, tile))
            names.add(name)
            added++
        }
    }

    /** A pool name not already used by a trader or a live player, for a crowd bot to re-pick with. */
    private fun freshCrowdName(): String? {
        repeat(30) {
            val name = ZidionPvpBots.NAMES.random(random)
            if (name !in names && Players.findByAccount(name) == null) {
                names.add(name)
                return name
            }
        }
        return null
    }

    /** Offers a crash left in the saved book under a trader name. Their escrow died with the process. */
    private fun purge() {
        var count = 0
        for (sell in listOf(true, false)) {
            val byItem = if (sell) exchange.offers.sellByItem else exchange.offers.buyByItem
            val stale = mutableListOf<Triple<Int, String, Int>>()
            for ((item, prices) in byItem) {
                for ((price, offers) in prices) {
                    for (offer in offers) {
                        if (offer.account in names) {
                            stale.add(Triple(offer.id, item, price))
                        }
                    }
                }
            }
            for ((id, item, price) in stale) {
                exchange.offers.remove(id, item, price, sell)
                count++
            }
        }
        if (count > 0) {
            logger.info { "Purged $count trader offers from the saved book; their escrow was returned at the last shutdown unless the server crashed." }
        }
    }

    private fun pass() {
        // Fill the crowd fast: spawn a batch of missing traders per pass, not one, so the GE
        // packs out in seconds instead of minutes.
        val missing = traders.filter { it.player == null && !it.pending }
        if (missing.isNotEmpty()) {
            missing.take(Settings["zidion.market.spawnPerPass", 12].coerceAtLeast(1)).forEach { spawn(it) }
            return
        }
        if (ZidionMarket.commodities.isEmpty()) {
            return
        }
        sweep()
        cancelStale()
        if (!take() && !play()) {
            quote()
        }
        chat()
        idle()
        banter()
    }

    private fun spawn(trader: Trader) {
        if (Players.findByAccount(trader.name) != null) {
            if (trader.style != CROWD) {
                return
            }
            trader.name = freshCrowdName() ?: return
        }
        trader.pending = true
        // Crowd bots pack the walkable GE floor; the named traders keep their table tile.
        val tile = if (trader.style == CROWD) (randomGeFloorTile() ?: trader.tile) else trader.tile
        scope.launch {
            val player = Player(tile = tile, accountName = trader.name)
            // Marked as a bot so it is never saved and so every bot-aware script treats it as one.
            player["bot"] = Bot(player)
            player["zidion_role"] = TRADER
            loader.connect(player, DummyClient(), viewport = Settings["development.bots.live", false])
            ZidionPvpBots.randomiseAppearance(player)
            player.viewport?.loaded = true
            trader.player = player
            trader.pending = false
        }
    }

    // ------------------------------------------------------------------------------------------
    // Collection boxes: everything that lands there belongs to the house.
    // ------------------------------------------------------------------------------------------

    private fun sweep() {
        for (trader in traders) {
            val player = trader.player ?: continue
            for (slot in 0 until SLOTS) {
                val box = player.inventories.inventory("collection_box_$slot")
                if (!box.isEmpty()) {
                    for (item in box.items) {
                        if (item.isEmpty()) {
                            continue
                        }
                        if (item.id == COINS) {
                            ZidionTreasury.deposit(item.amount, player, "trade")
                        } else {
                            ZidionTreasury.store(item.id, item.amount, player, "trade")
                        }
                    }
                    box.clear()
                }
                val offer = player.offers[slot]
                if (!offer.isEmpty() && offer.state.cancelled && box.isEmpty()) {
                    // Same tidy-up the "Back" button does once a finished offer has been collected.
                    player.offers[slot] = ExchangeOffer.EMPTY
                    exchange.offers.remove(offer)
                    exchange.refresh(player, slot)
                }
            }
        }
    }

    /** A quote the market has walked away from is pulled; the engine returns its escrow to the box next tick. */
    private fun cancelStale() {
        val limit = rule("requote_bps")
        var cancelled = 0
        for (trader in traders) {
            val player = trader.player ?: continue
            for (slot in 0 until SLOTS) {
                val offer = player.offers[slot]
                if (offer.isEmpty() || !offer.state.open) {
                    continue
                }
                val price = ZidionMarket.price(offer.item)
                if (price <= 0 || abs(offer.price - price) * 10_000L / price <= limit) {
                    continue
                }
                exchange.cancel(player, slot)
                if (++cancelled >= rule("max_cancels").coerceAtLeast(1)) {
                    return
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Taking: player offers resting inside the band get filled.
    // ------------------------------------------------------------------------------------------

    private fun take(): Boolean {
        val band = rule("take_bps") / 10_000.0
        for ((item, prices) in exchange.offers.sellByItem) {
            val commodity = ZidionMarket.commodity(item) ?: continue
            val ceiling = commodity.price * (1.0 + band + bias(commodity, buying = true))
            for ((price, offers) in prices) {
                if (price > ceiling) {
                    break
                }
                val resting = offers.firstOrNull { it.account !in names && it.remaining > 0 } ?: continue
                if (buy(SCALPER, commodity, price, minOf(resting.remaining, appetite(commodity)))) {
                    return true
                }
            }
        }
        for ((item, prices) in exchange.offers.buyByItem) {
            val commodity = ZidionMarket.commodity(item) ?: continue
            val floor = commodity.price * (1.0 - band - bias(commodity, buying = false))
            for ((price, offers) in prices.descendingMap()) {
                if (price < floor) {
                    break
                }
                val resting = offers.firstOrNull { it.account !in names && it.remaining > 0 } ?: continue
                if (sell(SCALPER, commodity, price, minOf(resting.remaining, appetite(commodity)))) {
                    return true
                }
            }
        }
        return false
    }

    /** A running play widens the band on its side: a pump pays up for stock, a dump sells down. */
    private fun bias(commodity: Commodity, buying: Boolean): Double {
        val campaign = ZidionMarket.campaign(commodity.item) ?: return 0.0
        if (campaign.direction == 0 || (campaign.direction > 0) != buying) {
            return 0.0
        }
        return campaign.move / 100.0 / 2
    }

    // ------------------------------------------------------------------------------------------
    // Campaigns: the whale acts the play out in the book.
    // ------------------------------------------------------------------------------------------

    private fun play(): Boolean {
        val campaign = ZidionMarket.campaigns.filter { it.direction != 0 }.randomOrNull(random) ?: return false
        if (random.nextInt(100) >= rule("play_percent")) {
            return false
        }
        val commodity = campaign.commodity
        return if (campaign.direction > 0) {
            // Bid at the market itself: sellers hit it, the book empties upwards.
            if (ours(commodity, sell = false)) false else buy(WHALE, commodity, commodity.price, appetite(commodity) * 2)
        } else {
            // Undercut: stock goes out below the market and drags it down.
            if (ours(commodity, sell = true)) false else sell(WHALE, commodity, floor(commodity.price * (1.0 - spread())).toInt().coerceAtLeast(1), appetite(commodity) * 2)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Quoting: two-sided markets on the busiest commodities.
    // ------------------------------------------------------------------------------------------

    private fun quote() {
        val quoted = quoted()
        if (quoted.isEmpty()) {
            return
        }
        cursor = (cursor + 1) % quoted.size
        val commodity = quoted[cursor]
        val spread = spread()
        if (!ours(commodity, sell = false)) {
            if (buy(MAKER, commodity, floor(commodity.price * (1.0 - spread)).toInt().coerceAtLeast(1), appetite(commodity))) {
                return
            }
        }
        if (!ours(commodity, sell = true)) {
            sell(MAKER, commodity, ceil(commodity.price * (1.0 + spread)).toInt().coerceAtLeast(1), appetite(commodity))
        }
    }

    /** The N busiest by turnover plus anything with a play on it. */
    private fun quoted(): List<Commodity> {
        val list = ZidionMarket.ranked.take(rule("quoted_items").coerceAtLeast(0)).toMutableList()
        for (campaign in ZidionMarket.campaigns) {
            if (campaign.commodity !in list) {
                list.add(campaign.commodity)
            }
        }
        return list
    }

    /** Do we already have an open offer on this side of this commodity? */
    private fun ours(commodity: Commodity, sell: Boolean): Boolean = traders.any { trader ->
        trader.player?.offers?.any { !it.isEmpty() && it.item == commodity.item && it.sell == sell && (it.state.open || it.state.pending) } == true
    }

    // ------------------------------------------------------------------------------------------
    // Placing offers. Escrow first, offer second, one per pass.
    // ------------------------------------------------------------------------------------------

    private fun buy(style: String, commodity: Commodity, price: Int, wanted: Int): Boolean {
        if (price <= 0 || wanted <= 0) {
            return false
        }
        val (player, slot) = placer(style) ?: return false
        val balance = ZidionTreasury.balance()
        val cap = balance * rule("max_escrow_percent").coerceIn(0, 100) / 100
        val amount = minOf(wanted.toLong(), cap / price, Int.MAX_VALUE.toLong() / price).toInt()
        if (amount <= 0) {
            return false
        }
        val cost = price * amount
        val taken = ZidionTreasury.withdraw(cost, player, "escrow ${commodity.item}")
        if (taken < cost) {
            if (taken > 0) {
                ZidionTreasury.deposit(taken, player, "escrow_refund")
            }
            return false
        }
        place(player, slot, exchange.buy(player, Item(commodity.item, amount), price))
        return true
    }

    private fun sell(style: String, commodity: Commodity, price: Int, wanted: Int): Boolean {
        if (price <= 0 || wanted <= 0) {
            return false
        }
        val (player, slot) = placer(style) ?: return false
        val amount = minOf(wanted.toLong(), Int.MAX_VALUE.toLong() / price).toInt()
        val taken = ZidionTreasury.take(commodity.item, amount, player, "stock ${commodity.item}")
        if (taken <= 0) {
            return false
        }
        place(player, slot, exchange.sell(player, Item(commodity.item, taken), price))
        return true
    }

    private fun place(player: Player, slot: Int, offer: ExchangeOffer) {
        player.offers[slot] = offer
        player.inventories.inventory("collection_box_$slot").clear()
        exchange.refresh(player, slot)
    }

    /** A trader of the wanted style with a free slot, else anyone with one. */
    private fun placer(style: String): Pair<Player, Int>? {
        val online = traders.filter { it.player != null && it.style != CROWD }
        for (trader in online.filter { it.style == style } + online.filter { it.style != style }) {
            val player = trader.player ?: continue
            val slot = player.offers.indexOfFirst { it.isEmpty() }
            if (slot != -1) {
                return player to slot
            }
        }
        return null
    }

    private fun appetite(commodity: Commodity): Int = (commodity.lot.toLong() * rule("quote_lots").coerceAtLeast(1)).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()

    private fun spread(): Double = rule("spread_bps") / 10_000.0

    // ------------------------------------------------------------------------------------------
    // Talk. Hints at the plays, never names them; only when someone real is close enough to hear.
    // ------------------------------------------------------------------------------------------

    private fun chat() {
        val trader = traders.filter { it.player != null }.randomOrNull(random) ?: return
        val player = trader.player ?: return
        val chance = rule("chat_percent") * if (trader.style == CRIER) 2 else 1
        if (random.nextInt(100) >= chance || player.hasClock(CHAT)) {
            return
        }
        val radius = rule("chat_radius")
        if (Players.none { !it.isBot && it.tile.distanceTo(player.tile) <= radius }) {
            return
        }
        val hint = ZidionMarket.campaigns.randomOrNull(random)?.takeIf { random.nextInt(100) < rule("hint_percent") }?.hint()
        val line = hint ?: ZidionMarket.lines(IDLE).randomOrNull(random) ?: return
        player.colourSay(line)
        player.start(CHAT, rule("chat_cooldown_ticks").coerceAtLeast(1))
    }

    // ------------------------------------------------------------------------------------------
    // Idle life: a floor of statues is not a floor. One trader per pass, sometimes, wanders a few
    // tiles from its spot, plays an emote, or sits down; a sitting one gets up again the same way.
    // ------------------------------------------------------------------------------------------

    private fun idle() {
        val trader = traders.filter { it.player != null }.randomOrNull(random) ?: return
        val player = trader.player ?: return
        if (random.nextInt(100) >= rule("idle_percent")) {
            return
        }
        if (player.mode is Rest) {
            player.mode = EmptyMode
            return
        }
        if (player.mode !is EmptyMode) {
            return
        }
        when (random.nextInt(3)) {
            0 -> {
                val spot = trader.tile.toCuboid(rule("wander_radius").coerceAtLeast(1)).random(player) ?: return
                player.walkTo(spot)
            }
            1 -> {
                val emote = ZidionMarket.lines(EMOTES).randomOrNull(random) ?: return
                player.anim(emote)
            }
            else -> player.mode = Rest(player, -1)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Banter: two idle traders near each other turn to face one another and trade a few lines,
    // three ticks apart, from the zidion_market_banter table. Campaign names fill the blanks.
    // ------------------------------------------------------------------------------------------

    private fun banter() {
        if (random.nextInt(100) >= rule("banter_percent")) {
            return
        }
        val radius = rule("banter_radius").coerceAtLeast(1)
        val idle = traders.mapNotNull { it.player }.filter { it.mode is EmptyMode && !it.hasClock(CHAT) }
        val first = idle.randomOrNull(random) ?: return
        val second = idle.filter { it !== first && it.tile.distanceTo(first.tile) <= radius }.randomOrNull(random) ?: return
        val rows = Tables.getOrNull(BANTER)?.rows() ?: return
        val row = rows.randomOrNull(random) ?: return
        val campaign = ZidionMarket.campaigns.randomOrNull(random)
        fun fill(text: String): String = campaign?.fill(text) ?: text.replace("\$item", "anything").replace("\$sector", "whole")
        val path = "$BANTER.${row.rowId}"
        val opening = Tables.stringOrNull("$path.first") ?: return
        val reply = Tables.stringOrNull("$path.reply") ?: ""
        val retort = Tables.stringOrNull("$path.retort") ?: ""
        val cooldown = rule("chat_cooldown_ticks").coerceAtLeast(1)
        first.start(CHAT, cooldown)
        second.start(CHAT, cooldown)
        first.face(second)
        second.face(first)
        first.colourSay(fill(opening))
        val a = first.accountName
        val b = second.accountName
        World.queue("zidion_banter_${a}_reply", initialDelay = BANTER_GAP) {
            Players.findByAccount(b)?.colourSay(fill(reply))
        }
        if (retort.isNotBlank()) {
            World.queue("zidion_banter_${a}_retort", initialDelay = BANTER_GAP * 2) {
                Players.findByAccount(a)?.colourSay(fill(retort))
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Shutdown: pull every open trader offer and give the house its escrow back.
    // ------------------------------------------------------------------------------------------

    private fun unwind() {
        sweep()
        for (trader in traders) {
            val player = trader.player ?: continue
            for (slot in 0 until SLOTS) {
                val offer = player.offers[slot]
                if (offer.isEmpty()) {
                    continue
                }
                exchange.offers.remove(offer)
                val remaining = offer.amount - offer.completed
                if (remaining > 0) {
                    if (offer.sell) {
                        ZidionTreasury.store(offer.item, remaining, player, "unwind")
                    } else {
                        ZidionTreasury.deposit((offer.price.toLong() * remaining).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), player, "unwind")
                    }
                }
                player.offers[slot] = ExchangeOffer.EMPTY
            }
        }
        // AutoSave may already have written the book with our offers still in it (handler order
        // is script load order); saving again here makes the file match what we just did.
        exchange.save()
    }

    private fun rule(name: String): Int = ZidionMarket.rule(name)

    companion object {
        const val TIMER = "zidion_traders"
        const val TRADER = "trader"
        const val SLOTS = 6
        const val COINS = "coins"
        const val CHAT = "zidion_trader_chat"
        const val IDLE = "idle"
        const val EMOTES = "emotes"
        const val BANTER = "zidion_market_banter"
        const val BANTER_GAP = 3
        const val MAKER = "maker"
        const val WHALE = "whale"
        const val SCALPER = "scalper"
        const val CRIER = "crier"
        const val CROWD = "crowd"
    }
}
