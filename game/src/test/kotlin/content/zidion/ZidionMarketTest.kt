package content.zidion

import WorldTest
import dialogueOption
import npcOption
import skipDialogues
import world.gregs.voidps.engine.client.ui.dialogue
import content.entity.player.bank.bank
import content.social.trade.exchange.GrandExchange
import org.junit.jupiter.api.Test
import world.gregs.voidps.engine.GameLoop
import world.gregs.voidps.engine.client.PlayerAccountLoader
import world.gregs.voidps.engine.data.AccountManager
import world.gregs.voidps.engine.data.SaveQueue
import world.gregs.voidps.engine.data.definition.AccountDefinitions
import world.gregs.voidps.engine.data.Settings
import world.gregs.voidps.engine.data.definition.ItemDefinitions
import world.gregs.voidps.engine.entity.World
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.character.player.Players
import world.gregs.voidps.engine.entity.item.Item
import world.gregs.voidps.engine.get
import world.gregs.voidps.engine.timer.setCurrentTime
import world.gregs.voidps.engine.inv.transact.operation.AddItem.add
import world.gregs.voidps.type.Tile
import kotlin.math.floor
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The market against the real engine: full data, real exchange, world timers ticking.
 */
class ZidionMarketTest : WorldTest() {

    private val exchange: GrandExchange
        get() = get()

    private val stepTicks: Int
        get() = ZidionMarket.rule("step_ticks")

    /** The exchange samples offers by age; a frozen clock would make every age zero. */
    private fun advanceClock() {
        setCurrentTime { NOON + GameLoop.tick * 600L }
    }

    private fun ticks(count: Int) = repeat(count) { tick() }

    private fun owner(coins: Int): Player {
        Settings.load(mapOf("zidion.treasury.account" to OWNER))
        val owner = createPlayer(Tile(3165, 3485), OWNER)
        owner.bank.transaction { add("coins", coins) }
        ZidionTreasury.invalidate()
        return owner
    }

    @Test
    fun `prices move, stay inside their bounds and are written into the item definitions`() {
        advanceClock()
        World.timers.start(ZidionMarket.TIMER)
        val before = ZidionMarket.commodities.values.associate { it.item to it.price }
        tick(stepTicks * 4 + 1)
        val moved = ZidionMarket.commodities.values.count { before[it.item] != it.price }
        assertTrue(moved > 0, "no commodity price moved in ${stepTicks * 4} ticks")
        for (commodity in ZidionMarket.commodities.values) {
            val floor = (commodity.base.toLong() * ZidionMarket.rule("floor_percent") / 100).coerceAtLeast(1)
            val ceiling = commodity.base.toLong() * ZidionMarket.rule("ceiling_percent") / 100
            assertTrue(commodity.price in floor..ceiling, "${commodity.item} at ${commodity.price} outside $floor..$ceiling (base ${commodity.base})")
            val definition = ItemDefinitions.get(commodity.item)
            assertEquals(commodity.price, definition["price", -1], "${commodity.item} definition price not updated")
        }
    }

    @Test
    fun `synthetic trades land in the exchange history and rank the board`() {
        advanceClock()
        World.timers.start(ZidionMarket.TIMER)
        tick(stepTicks * 3 + 1)
        val recorded = ZidionMarket.commodities.keys.count { exchange.history.history.containsKey(it) }
        assertTrue(recorded > 0, "nothing printed into the price history")
        assertTrue(ZidionMarket.ranked.isNotEmpty(), "board not ranked")
        assertTrue(ZidionMarket.ranked.first().turnover > 0, "top commodity has no turnover")
        assertTrue(ZidionMarket.ranked.zipWithNext().all { (a, b) -> a.turnover >= b.turnover }, "board not sorted by turnover")
    }

    @Test
    fun `traders log in and quote bids funded from the treasury`() {
        advanceClock()
        val owner = owner(coins = 200_000_000)
        World.timers.start(ZidionMarket.TIMER)
        World.timers.start(ZidionTradingBots.TIMER)
        val passTicks = ZidionMarket.rule("pass_ticks")
        // One trader logs in per pass, then one offer per pass. Ticked singly on purpose: the
        // harness runs a tick(n) loop inside one runBlocking on the game thread, and a bot login
        // is a coroutine on that same thread - it can only progress between runBlocking calls,
        // exactly as it progresses between real ticks on the live server.
        ticks(passTicks * 12 + stepTicks + 5)
        assertTrue(Players.any { it.accountName == "Mercator" }, "traders did not log in")
        assertTrue(Players.any { it.accountName == "Sable" && it["zidion_role", ""] == ZidionTradingBots.TRADER }, "trader role missing")
        ticks(passTicks * 20)
        assertTrue(exchange.offers.buyByItem.isNotEmpty(), "no bids in the book")
        assertTrue(owner.bank.count("coins") < 200_000_000, "bids were not escrowed from the treasury")
    }

    @Test
    fun `exchange tax on a sale lands in the treasury and the sale moves the price`() {
        advanceClock()
        // The test properties ship with tax off; the live server runs 2% capped at 5m.
        Settings.load(mapOf("grandExchange.tax" to "0.02", "grandExchange.tax.limit" to "5000000"))
        val owner = owner(coins = 0)
        val seller = createPlayer(Tile(3165, 3480), "seller")
        val buyer = createPlayer(Tile(3166, 3480), "buyer")
        val price = 250
        // Two hours of coal's normal volume: enough to move the price, not just the tax.
        val amount = 5000
        val coalBefore = ZidionMarket.price("coal")
        buyer.offers[0] = exchange.buy(buyer, Item("coal", amount), price)
        tick(2)
        seller.offers[0] = exchange.sell(seller, Item("coal", amount), price)
        tick(2)
        assertTrue(ZidionMarket.price("coal") < coalBefore, "a sale did not push the price down: $coalBefore -> ${ZidionMarket.price("coal")}")
        val rate = Settings["grandExchange.tax", 0.0]
        assertTrue(rate > 0.0, "test properties have no exchange tax")
        val expected = floor(price * amount * rate).toInt()
        assertEquals(expected, owner.bank.count("coins"), "treasury did not receive the tax")
        assertEquals(price * amount - expected, seller.inventories.inventory("collection_box_0").count("coins"), "seller not paid net of tax")
    }

    @Test
    fun `the bank account is created like a login account and the treasury can use it offline`() {
        advanceClock()
        // Test saves persist between runs, so the account name must be fresh each time.
        val bank = "zb${System.nanoTime() % 1_000_000_000}"
        Settings.load(mapOf("zidion.treasury.account" to bank, "zidion.treasury.password" to "secret"))
        val loader: PlayerAccountLoader = get()
        val definitions: AccountDefinitions = get()
        assertTrue(ZidionTreasury.createAccount(loader, get<AccountManager>(), definitions, get<SaveQueue>()), "account not created")
        assertTrue(definitions.get(bank) != null, "display name not registered")
        tickIf(limit = 50) { !loader.exists(bank) }
        // The save is written off-thread; the queue only forgets it once the write has finished.
        tickIf(limit = 50) { get<SaveQueue>().saving(bank) }
        assertTrue(loader.password(bank)?.startsWith("$2a$") == true, "password not bcrypt hashed")
        assertTrue(!ZidionTreasury.createAccount(loader, get<AccountManager>(), definitions, get<SaveQueue>()), "created twice")
        ZidionTreasury.invalidate()
        assertTrue(ZidionTreasury.deposit(1234, World, "test"), "deposit refused")
        assertEquals(1234L, ZidionTreasury.balance(), "offline bank did not receive the coins")
        assertEquals(1000, ZidionTreasury.withdraw(1000, World, "test"), "offline withdraw failed")
        assertEquals(234L, ZidionTreasury.balance())
    }

    @Test
    fun `the market clerk opens the menu and the report`() {
        advanceClock()
        val clerk = createNPC("zidion_market_clerk", Tile(3165, 3488))
        val shopper = createPlayer(Tile(3165, 3486), "shopper")
        shopper.npcOption(clerk, "Talk-to")
        tick(3)
        // Past the welcome line; what is left open must be the choice box (five options at most,
        // or the dialogue throws and nothing opens).
        shopper.skipDialogues()
        assertTrue(shopper.dialogue != null, "clerk menu did not open")
        val menu = shopper.dialogue
        shopper.dialogueOption(1)
        tick(2)
        assertTrue(shopper.interfaces.contains("quest_scroll"), "market report journal not opened (menu was $menu, dialogue now ${shopper.dialogue}, open: ${shopper.interfaces})")
    }

    companion object {
        const val OWNER = "Bomboclat"
        const val NOON = 43_200_000L
    }
}
