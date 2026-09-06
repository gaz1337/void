package content.zidion

import com.github.michaelbull.logging.InlineLogger
import content.entity.player.dialogue.Happy
import content.entity.player.dialogue.Neutral
import content.entity.player.dialogue.type.choice
import content.entity.player.dialogue.type.npc
import content.quest.questJournal
import content.social.trade.exchange.GrandExchange
import world.gregs.voidps.cache.definition.Params
import world.gregs.voidps.engine.Script
import world.gregs.voidps.engine.client.PlayerAccountLoader
import world.gregs.voidps.engine.client.command.adminCommand
import world.gregs.voidps.engine.client.command.stringArg
import world.gregs.voidps.engine.client.message
import world.gregs.voidps.engine.client.ui.chat.toDigitGroupString
import world.gregs.voidps.engine.client.ui.open
import world.gregs.voidps.engine.data.AccountManager
import world.gregs.voidps.engine.data.SaveQueue
import world.gregs.voidps.engine.data.Settings
import world.gregs.voidps.engine.data.definition.AccountDefinitions
import world.gregs.voidps.engine.data.definition.ItemDefinitions
import world.gregs.voidps.engine.data.definition.Tables
import world.gregs.voidps.engine.entity.World
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.character.player.name
import world.gregs.voidps.engine.timer.Timer
import world.gregs.voidps.type.random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * The Zidion market: the simulated commodity exchange, in one script.
 *
 * Void ships a complete exchange - order matching, collection boxes, tax, buy limits, an OHLC
 * price history in 5-minute / hourly / 6-hourly / daily buckets - but its guide prices never
 * move (`ExchangeHistory.calculatePrices` skips any item traded in the last day). This script is
 * the missing market, and every number and line it uses lives in
 * `data/social/trade/exchange/zidion_market/zidion_market.tables.toml`.
 *
 * **Prices.** Every `step_ticks` each listed commodity takes a random-walk step scaled by its
 * volatility, is pulled a little back towards its base price, and is pushed by any [Campaign]
 * aimed at it. The new price is written into the item definition's `price` param - the one
 * value the exchange screens, the +-5% offer band, the price checker and item values all read -
 * so the whole game sees one price and it is this one. A share of commodities then "print" a
 * trade into the exchange's real price history, so volume, 24h change and turnover accumulate
 * in the same buckets real trades land in; the boards rank on them, and prices are restored
 * from the newest close on boot.
 *
 * **Campaigns** are the manipulation: pumps, dumps, squeezes, whales, crashes, rumours, picked
 * by weight from the table, aimed at one commodity each, a couple running at once. The trading
 * bots ([ZidionTradingBots]) act them out in the order book and gossip about them; this script
 * only moves the number.
 *
 * **The desk.** `zidion_market_clerk` (the fourth exchange clerk, see zidion_market.npcs.toml)
 * is how a player reads the market: the report and the Top 100 on the quest journal scroll (302
 * scrollable lines), the Common Item Costs boards the exchange's own price-check npcs use, and
 * the exchange itself.
 *
 * **Tax.** The exchange taxes every sale but the coins vanish; here they land in the treasury.
 * Proceeds arrive in the seller's collection box already net of tax, so the six boxes are
 * watched (wiki: Inventories - slotChanged) and the tax is worked back out of the net amount.
 *
 * **The bank.** `zidion.treasury.account` names a dedicated account, not a person. If it has no
 * save yet it is created here at boot the same way the login server creates one -
 * `AccountManager.create` with a bcrypt hash of `zidion.treasury.password` - so the owner can
 * log into it to look at the books. Everything the house owns lives in that account's bank.
 *
 * Shared state (listed commodities, running campaigns, rankings) lives in the companion so the
 * trading bots read the same market (wiki: Scripts - state is shared through objects).
 */
class ZidionMarket(
    private val exchange: GrandExchange,
    private val loader: PlayerAccountLoader,
    private val accounts: AccountManager,
    private val definitions: AccountDefinitions,
    private val saves: SaveQueue,
) : Script {

    private val logger = InlineLogger("ZidionMarket")

    /** Ticks until another campaign may start. */
    private var gap = 0

    init {
        // ---- engine ---------------------------------------------------------------------

        worldSpawn {
            load()
            restorePrices()
            // Definitions and model must agree from the first tick, including items whose guide
            // price was clamped to MAX_PRICE and would otherwise wait for their first move.
            for (commodity in commodities.values) {
                writePrice(commodity)
            }
            reconcile()
            refreshStats()
            if (ZidionTreasury.createAccount(loader, accounts, definitions, saves)) {
                // A brand new bank is always seeded, whatever the price history says. Its save is
                // being written; give it a few ticks before the seed goes looking for it.
                World.queue("zidion_market_seed", initialDelay = SEED_DELAY_TICKS) {
                    seed()
                }
            } else {
                seedIfEmpty()
            }
            World.timers.start(TIMER)
            logger.info { "Zidion market open: ${commodities.size} commodities listed." }
        }

        worldTimerStart(TIMER) { rule("step_ticks").coerceAtLeast(MIN_STEP_TICKS) }

        worldTimerTick(TIMER) {
            step()
            Timer.CONTINUE
        }

        // The treasury keeps a snapshot of the owner's bank while they are offline; a login or a
        // logout save means that snapshot is no longer the truth.
        playerSpawn {
            if (name == ZidionTreasury.owner) {
                ZidionTreasury.invalidate()
            }
        }

        playerDespawn {
            if (name == ZidionTreasury.owner) {
                ZidionTreasury.invalidate()
            }
        }

        // ---- tax -> treasury ------------------------------------------------------------

        for (slot in 0 until SLOTS) {
            slotChanged("collection_box_$slot") { change ->
                val offer = offers.getOrNull(slot) ?: return@slotChanged
                if (offer.isEmpty()) {
                    return@slotChanged
                }
                val before = if (change.fromItem.id == change.item.id) change.fromItem.amount else 0
                val delta = change.item.amount - before
                if (delta <= 0) {
                    return@slotChanged
                }
                if (change.item.id == COINS) {
                    // Only sales are taxed. Coins landing on a buy are the refund for buying under
                    // the bid price, and those were never taxed.
                    if (!offer.sell) {
                        return@slotChanged
                    }
                    val tax = taxOn(delta)
                    if (tax > 0) {
                        ZidionTreasury.deposit(tax, this, "exchange_tax")
                    }
                    // Coins are what the sale left after tax; work the quantity back out.
                    val rate = Settings["grandExchange.tax", 0.0]
                    val sold = (delta / (offer.price.coerceAtLeast(1) * (1.0 - rate))).roundToLong().coerceAtLeast(1)
                    impact(offer.item, sold, sell = true)
                } else if (change.item.id == offer.item && !offer.sell) {
                    impact(offer.item, delta.toLong(), sell = false)
                }
            }
        }

        // ---- the desk -------------------------------------------------------------------

        npcApproach("Talk-to", CLERK) {
            approachRange(2)
            npc<Happy>(text("clerk_welcome"))
            menu()
        }

        npcApproach("Exchange", CLERK) {
            approachRange(2)
            trade()
        }

        npcApproach("History", CLERK) {
            approachRange(2)
            if (Settings["grandExchange.enabled", false]) {
                open("exchange_history")
            } else {
                message("This feature is currently disabled.")
            }
        }

        npcApproach("Sets", CLERK) {
            approachRange(2)
            open("exchange_item_sets")
        }

        // ---- admin ----------------------------------------------------------------------

        adminCommand("market", desc = "Zidion market status") {
            message("Market: ${commodities.size} listed, treasury ${ZidionTreasury.balance().toDigitGroupString()} gp, stock ${ZidionTreasury.stockValue(::price).toDigitGroupString()} gp.")
            if (campaigns.isEmpty()) {
                message("No campaign running; next in $gap ticks.")
            }
            for (campaign in campaigns) {
                message("${campaign.type} on ${campaign.commodity.name}: ${campaign.remaining} ticks left, price ${campaign.commodity.price.toDigitGroupString()} (${percent(campaign.commodity.premium)} vs base).")
            }
        }

        adminCommand("market_play", stringArg("type"), stringArg("item", optional = true), desc = "Start a market campaign: type [item]") { args ->
            val type = args[0]
            if (Tables.getOrNull(CAMPAIGNS)?.rows()?.none { it.rowId == type } != false) {
                message("No such campaign '$type'. See $CAMPAIGNS.")
                return@adminCommand
            }
            val commodity = args.getOrNull(1)?.let { commodity(it) } ?: pickTarget()
            if (commodity == null) {
                message("Nothing to aim it at.")
                return@adminCommand
            }
            campaigns.removeIf { it.commodity === commodity }
            val campaign = start(type, commodity)
            message("Started ${campaign.type} on ${commodity.name} for ${campaign.duration} ticks, ${campaign.move}% ${if (campaign.direction < 0) "down" else "up"}.")
        }

        adminCommand("market_seed", desc = "Seed the treasury with house stock and coins") {
            seed()
            message("Seeded. Treasury ${ZidionTreasury.balance().toDigitGroupString()} gp, stock ${ZidionTreasury.stockValue(::price).toDigitGroupString()} gp.")
        }

        adminCommand("market_reseed", desc = "Strip every listed commodity and all coins from the treasury, then seed it afresh") {
            unseed()
            seed()
            message("Seeded. Treasury ${ZidionTreasury.balance().toDigitGroupString()} gp, stock ${ZidionTreasury.stockValue(::price).toDigitGroupString()} gp.")
        }
    }

    // ==========================================================================================
    // Engine
    // ==========================================================================================

    /** Read the commodity table; base prices come from the item definitions (their `price` param). */
    private fun load() {
        commodities.clear()
        val table = Tables.getOrNull(ITEMS)
        if (table == null) {
            logger.warn { "No $ITEMS table; the market has nothing to trade." }
            return
        }
        for (row in table.rows()) {
            val item = row.rowId
            val definition = ItemDefinitions.getOrNull(item)
            if (definition == null) {
                logger.warn { "Listed item '$item' does not exist; skipped." }
                continue
            }
            val base = definition["price", definition.cost].coerceIn(1, MAX_PRICE)
            val path = "$ITEMS.$item"
            commodities[item] = Commodity(
                item = item,
                name = definition.name,
                sector = Tables.stringOrNull("$path.sector") ?: "misc",
                volatility = Tables.int("$path.volatility").coerceAtLeast(1),
                volume = Tables.int("$path.volume").coerceAtLeast(1),
                lot = Tables.int("$path.lot").coerceAtLeast(1),
                base = base,
            )
        }
    }

    /** Last price the market closed at, from the exchange's saved history; base price if none. */
    private fun restorePrices() {
        for (commodity in commodities.values) {
            val history = exchange.history.history[commodity.item] ?: continue
            val newest = history.day.maxByOrNull { it.key }
                ?: history.week.maxByOrNull { it.key }
                ?: history.month.maxByOrNull { it.key }
                ?: history.year.maxByOrNull { it.key }
                ?: continue
            val close = newest.value.close
            if (close > 0) {
                commodity.price = clamp(commodity, close.toLong())
                writePrice(commodity)
            }
        }
    }

    private fun step() {
        val stepTicks = rule("step_ticks").coerceAtLeast(MIN_STEP_TICKS)
        val stepsPerDay = (TICKS_PER_DAY / stepTicks).coerceAtLeast(1)
        val noise = rule("noise_percent") / 100.0
        val reversion = rule("reversion_bps") / 10_000.0
        val maxStep = rule("max_step_bps") / 10_000.0
        val printPercent = rule("print_percent").coerceIn(1, 100)
        schedule(stepTicks)
        for (commodity in commodities.values) {
            val campaign = campaign(commodity.item)
            var change = gaussian() * commodity.volatility / 100.0 / sqrt(stepsPerDay.toDouble()) * noise
            change += (commodity.base - commodity.price).toDouble() / commodity.price * reversion
            if (campaign != null) {
                val steps = (campaign.duration / stepTicks).coerceAtLeast(1)
                change += if (campaign.direction == 0) {
                    gaussian() * campaign.move / 100.0 / sqrt(steps.toDouble())
                } else {
                    campaign.direction * (campaign.move / 100.0) / steps
                }
            }
            change = change.coerceIn(-maxStep, maxStep)
            val next = clamp(commodity, (commodity.price * (1.0 + change)).roundToLong())
            if (next != commodity.price) {
                commodity.price = next
                writePrice(commodity)
            }
            if (random.nextInt(100) < printPercent) {
                print(commodity, campaign, stepTicks, printPercent)
            }
        }
        val finished = campaigns.filter { campaign ->
            campaign.remaining -= stepTicks
            campaign.finished
        }
        for (campaign in finished) {
            campaigns.remove(campaign)
            logger.info { "Campaign ${campaign.type} on ${campaign.commodity.name} over; price ${campaign.commodity.price} (${percent(campaign.commodity.premium)} vs base)." }
        }
        refreshStats()
    }

    /** Start plays while there is room for one and the gap since the last has passed. */
    private fun schedule(stepTicks: Int) {
        if (campaigns.size >= rule("campaigns").coerceAtLeast(0)) {
            return
        }
        gap -= stepTicks
        if (gap > 0) {
            return
        }
        val rows = Tables.getOrNull(CAMPAIGNS)?.rows() ?: return
        val total = rows.sumOf { Tables.intOrNull("$CAMPAIGNS.${it.rowId}.weight") ?: 0 }
        if (total <= 0) {
            return
        }
        var roll = random.nextInt(total)
        val type = rows.first { row ->
            roll -= Tables.intOrNull("$CAMPAIGNS.${row.rowId}.weight") ?: 0
            roll < 0
        }.rowId
        val target = pickTarget() ?: return
        start(type, target)
        gap = rule("campaign_gap_ticks")
    }

    /** A commodity nobody is already playing; the rares are left alone - a partyhat crash is a different game. */
    private fun pickTarget(): Commodity? = commodities.values
        .filter { it.sector != RARES && campaign(it.item) == null }
        .randomOrNull(random)

    private fun start(type: String, commodity: Commodity): Campaign {
        val path = "$CAMPAIGNS.$type"
        val campaign = Campaign(
            type = type,
            commodity = commodity,
            direction = Tables.intOrNull("$path.direction") ?: 0,
            move = (Tables.intRangeOrNull("$path.move") ?: 0..0).random(random),
            volume = Tables.intOrNull("$path.volume") ?: 100,
            duration = (Tables.intRangeOrNull("$path.duration") ?: 3000..3000).random(random).coerceAtLeast(MIN_STEP_TICKS),
            report = Tables.stringOrNull("$path.report") ?: "",
            hints = Tables.stringListOrNull("$path.hints") ?: emptyList(),
        )
        campaigns.add(campaign)
        logger.info { "Campaign ${campaign.type} on ${commodity.name}: ${campaign.move}% over ${campaign.duration} ticks, volume x${campaign.volume / 100.0}." }
        return campaign
    }

    /**
     * Record a synthetic trade into the real price history. Sized so that, across the steps that
     * print, an hour adds up to the commodity's `volume` (times the campaign multiplier).
     */
    private fun print(commodity: Commodity, campaign: Campaign?, stepTicks: Int, printPercent: Int) {
        val printsPerHour = TICKS_PER_HOUR.toDouble() / stepTicks * printPercent / 100.0
        val multiplier = (campaign?.volume ?: 100) / 100.0
        val expected = commodity.volume / printsPerHour * multiplier
        val amount = (expected * (0.4 + random.nextDouble() * 1.2)).roundToLong().coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        val jitter = rule("spread_bps") / 10_000.0
        val price = clamp(commodity, (commodity.price * (1.0 + (random.nextDouble() * 2 - 1) * jitter)).roundToLong())
        exchange.history.record(commodity.item, amount, price)
    }

    /** 24h volume, turnover and change from the exchange's day buckets; then rank by turnover. */
    private fun refreshStats() {
        for (commodity in commodities.values) {
            val day = exchange.history.history[commodity.item]?.day
            if (day.isNullOrEmpty()) {
                commodity.traded = 0
                commodity.turnover = 0
                commodity.change = 0.0
                continue
            }
            var traded = 0L
            var turnover = 0L
            for (aggregate in day.values) {
                traded += aggregate.volume
                turnover += aggregate.volume * aggregate.close
            }
            commodity.traded = traded
            commodity.turnover = turnover
            val open = day.minByOrNull { it.key }?.value?.open ?: 0
            commodity.change = if (open > 0) (commodity.price - open) * 100.0 / open else 0.0
        }
        ranked = commodities.values.sortedByDescending { it.turnover }
    }

    /**
     * The exchange keeps a private price cache that its hourly recalculation only ever fills when
     * the newest history bucket is two days old - i.e. after a long downtime - and once filled it
     * shadows the item `price` param for good. There is no public way to empty it, so if the
     * exchange disagrees with the market after boot the cache is cleared reflectively, once.
     */
    private fun reconcile() {
        val stale = commodities.values.count { exchange.history.marketPrice(it.item) != it.price }
        if (stale == 0) {
            return
        }
        try {
            val field = exchange.history.javaClass.getDeclaredField("marketPrices")
            field.isAccessible = true
            (field.get(exchange.history) as MutableMap<*, *>).clear()
            logger.warn { "Exchange had $stale cached prices from before the downtime; cache cleared so the live market shows." }
        } catch (e: Exception) {
            logger.error(e) { "Could not clear the exchange price cache; $stale commodities will show stale prices." }
        }
    }

    // ==========================================================================================
    // Shared helpers
    // ==========================================================================================

    /** An empty house - no coins and none of the listed stock - is seeded at boot, automatically. */
    private fun seedIfEmpty() {
        if (commodities.isEmpty()) {
            return
        }
        if (ZidionTreasury.owner.isBlank()) {
            logger.warn { "No zidion.treasury.account; the house cannot be seeded." }
            return
        }
        if (ZidionTreasury.balance() > 0 || commodities.keys.any { ZidionTreasury.stock(it) > 0 }) {
            return
        }
        seed()
    }

    /** Everything listed, and every coin, out of the treasury bank. Only for [seed] to start over. */
    private fun unseed() {
        for (commodity in commodities.values) {
            ZidionTreasury.take(commodity.item, Int.MAX_VALUE, World, "market_reseed")
        }
        ZidionTreasury.withdraw(Int.MAX_VALUE, World, "market_reseed")
    }

    /** Hours of normal volume of every commodity into the treasury bank, plus coins to bid with. */
    private fun seed() {
        val hours = rule("seed_hours").coerceAtLeast(1)
        var value = 0L
        for (commodity in commodities.values) {
            if (commodity.sector == RARES) {
                continue
            }
            var amount = (commodity.volume.toLong() * hours).coerceIn(1, rule("seed_max_items").coerceAtLeast(1).toLong()).toInt()
            if (commodity.price >= rule("expensive_price")) {
                amount = amount.coerceAtMost(rule("expensive_seed").coerceAtLeast(1))
            }
            if (ZidionTreasury.store(commodity.item, amount, World, "market_seed")) {
                value += commodity.price.toLong() * amount
            }
        }
        val coins = (value * rule("seed_coins_percent") / 100).coerceIn(0, rule("seed_coins_max").coerceAtLeast(0).toLong()).toInt()
        ZidionTreasury.deposit(coins, World, "market_seed")
        logger.info { "House seeded: stock worth ${value.toDigitGroupString()} gp and ${coins.toDigitGroupString()} coins into ${ZidionTreasury.owner}." }
    }

    /** Standard normal via Box-Muller; the engine's Random has no gaussian. */
    private fun gaussian(): Double {
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }

    // ==========================================================================================
    // Tax
    // ==========================================================================================

    /** The tax the exchange took to leave [net] coins, or 0 if tax is off. */
    private fun taxOn(net: Int): Int {
        val rate = Settings["grandExchange.tax", 0.0]
        if (rate <= 0.0) {
            return 0
        }
        val limit = Settings["grandExchange.tax.limit", -1]
        // Several gross amounts can leave the same net (24,999 - 499 and 25,000 - 500 both give
        // 24,500), so try the nearest candidates first and take the closest fit.
        val guess = (net / (1.0 - rate)).roundToLong()
        for (offset in NEAREST) {
            val gross = guess + offset
            if (gross <= 0 || gross > Int.MAX_VALUE) {
                continue
            }
            val tax = taxFor(gross.toInt(), rate, limit)
            if (gross - tax == net.toLong()) {
                return tax
            }
        }
        if (limit > 0) {
            // Capped: gross = net + limit exactly when the uncapped tax would have exceeded the cap.
            val gross = net.toLong() + limit
            if (gross <= Int.MAX_VALUE && taxFor(gross.toInt(), rate, limit) == limit) {
                return limit
            }
        }
        return floor(net * rate / (1.0 - rate)).toInt().coerceAtLeast(0)
    }

    /** Mirrors GrandExchange.claim: floor(gross * rate), capped at limit when one is set. */
    private fun taxFor(gross: Int, rate: Double, limit: Int): Int {
        val tax = floor(gross * rate).toInt()
        return if (limit > 0) tax.coerceAtMost(limit) else tax
    }

    // ==========================================================================================
    // The desk
    // ==========================================================================================

    /** Plain options on purpose: the expression form makes the player repeat the line in a chat box first. */
    private suspend fun Player.menu() {
        val labels = lines("clerk_menu")
        choice {
            option(labels.getOrElse(0) { "Show me the market report." }) {
                report()
            }
            option(labels.getOrElse(1) { "Show me the Top 100." }) {
                top()
            }
            option(labels.getOrElse(2) { "What are the commodity prices?" }) {
                boards()
            }
            option(labels.getOrElse(3) { "I would like to trade." }) {
                trade()
            }
            // Five is the most a choice box holds; closing the dialogue is how you leave.
            option(labels.getOrElse(4) { "How does this market work?" }) {
                explain()
            }
        }
    }

    private fun Player.trade() {
        if (Settings["grandExchange.enabled", false]) {
            open("grand_exchange")
        } else {
            message("This feature is currently disabled.")
        }
    }

    private suspend fun Player.boards() {
        npc<Neutral>(text("clerk_boards"))
        choice {
            for (type in BOARDS) {
                option(type.replaceFirstChar { it.uppercase() }) {
                    set("common_item_costs", type)
                    open("common_item_costs")
                }
            }
        }
    }

    /** Each line of the `clerk_explain` row is one chat box. */
    private suspend fun Player.explain() {
        for (line in lines("clerk_explain")) {
            npc<Neutral>(line)
        }
    }

    /** Treasury, plays on the floor, movers, most traded, sectors. */
    private fun Player.report() {
        val out = mutableListOf<String>()
        out += heading("Treasury")
        out += "  Coins: ${ZidionTreasury.balance().toDigitGroupString()} gp"
        out += "  Stock: ${ZidionTreasury.stockValue(::price).toDigitGroupString()} gp"
        out += ""
        out += heading("On the floor")
        if (campaigns.isEmpty()) {
            out += "  ${text("clerk_quiet")}"
        }
        for (campaign in campaigns) {
            out += wrap(campaign.report(), "  ")
        }
        out += ""
        val movers = rule("report_movers").coerceAtLeast(1)
        out += heading("Biggest gainers (24h)")
        val gainers = gainers(movers)
        if (gainers.isEmpty()) {
            out += "  Nothing up today."
        }
        for (commodity in gainers) {
            out += "  ${commodity.name}  ${coloured(commodity.change)}  ${commodity.price.toDigitGroupString()} gp"
        }
        out += ""
        out += heading("Biggest losers (24h)")
        val losers = losers(movers)
        if (losers.isEmpty()) {
            out += "  Nothing down today."
        }
        for (commodity in losers) {
            out += "  ${commodity.name}  ${coloured(commodity.change)}  ${commodity.price.toDigitGroupString()} gp"
        }
        out += ""
        out += heading("Most traded (24h)")
        for (commodity in ranked.take(movers)) {
            out += "  ${commodity.name}  ${compact(commodity.traded)} traded  ${compact(commodity.turnover)} gp"
        }
        out += ""
        out += heading("Sectors (24h)")
        for (sector in sectors()) {
            out += "  ${sector.replaceFirstChar { it.uppercase() }}  ${coloured(sectorChange(sector))}"
        }
        questJournal("Zidion Market Report", out)
    }

    /** Rank, name, price, 24h change and volume for the busiest commodities by turnover. */
    private fun Player.top() {
        val count = rule("top_items").coerceIn(1, MAX_ENTRIES)
        val out = mutableListOf<String>()
        out += heading("By turnover, last 24 hours")
        out += ""
        for ((index, commodity) in ranked.take(count).withIndex()) {
            out += "${index + 1}. ${commodity.name}"
            out += "    ${commodity.price.toDigitGroupString()} gp  ${coloured(commodity.change)}  ${compact(commodity.traded)} traded"
        }
        if (ranked.isEmpty()) {
            out += "  Nothing has traded yet."
        }
        questJournal("Zidion Market Top $count", out)
    }

    private fun heading(text: String): String = "<col=$GOLD>$text</col>"

    /** Signed percent for logs and chat: +3.2% / -1.8%. */
    private fun percent(value: Double): String = "${if (value >= 0) "+" else ""}${"%.1f".format(value)}%"

    /** The same, coloured for the journal boards. */
    private fun coloured(value: Double): String {
        val colour = when {
            value > 0.05 -> GREEN
            value < -0.05 -> RED
            else -> GREY
        }
        return "<col=$colour>${percent(value)}</col>"
    }

    /** 1234 -> 1.2k, 4567890 -> 4.6M. */
    private fun compact(value: Long): String = when {
        value >= 1_000_000_000 -> "%.1fB".format(value / 1_000_000_000.0)
        value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0)
        value >= 1_000 -> "%.1fk".format(value / 1_000.0)
        else -> value.toString()
    }

    /** The journal has no word wrap; split long report lines at the last space before the width. */
    private fun wrap(text: String, indent: String): List<String> {
        val out = mutableListOf<String>()
        var rest = text
        while (rest.length > WIDTH) {
            val cut = rest.lastIndexOf(' ', WIDTH).takeIf { it > 0 } ?: WIDTH
            out += indent + rest.substring(0, cut)
            rest = rest.substring(cut).trimStart()
        }
        out += indent + rest
        return out
    }

    /** First line of a `zidion_market_lines` row, for single-line clerk text. */
    private fun text(row: String): String = lines(row).firstOrNull() ?: ""

    // ==========================================================================================
    // Shared state - what the trading bots read
    // ==========================================================================================

    /** A listed item. [base] is the guide price it reverts to; [price] is the live market price. */
    class Commodity(
        val item: String,
        val name: String,
        val sector: String,
        val volatility: Int,
        val volume: Int,
        val lot: Int,
        val base: Int,
    ) {
        var price: Int = base

        /** Coins traded in the last 24 hours, at the prices they traded at. */
        var turnover: Long = 0

        /** Items traded in the last 24 hours. */
        var traded: Long = 0

        /** Percent change over the last 24 hours. */
        var change: Double = 0.0

        /** Percent the price sits above (positive) or below its base. */
        val premium: Double
            get() = if (base <= 0) 0.0 else (price - base) * 100.0 / base
    }

    /** One manipulation play, from a `zidion_market_campaigns` row, aimed at one commodity. */
    class Campaign(
        val type: String,
        val commodity: Commodity,
        val direction: Int,
        val move: Int,
        val volume: Int,
        val duration: Int,
        private val report: String,
        private val hints: List<String>,
    ) {
        var remaining: Int = duration

        val finished: Boolean
            get() = remaining <= 0

        /** Fill `$item` and `$sector` in a table line. */
        fun fill(template: String): String = template.replace("\$item", commodity.name).replace("\$sector", commodity.sector)

        fun report(): String = fill(report)

        fun hint(): String? = hints.randomOrNull()?.let { fill(it) }
    }

    companion object {
        /** Listed commodities by item id, in table order. */
        val commodities = LinkedHashMap<String, Commodity>()

        /** Plays currently running. */
        val campaigns = mutableListOf<Campaign>()

        /** Commodities ordered by 24h turnover, busiest first; rebuilt every market step. */
        var ranked: List<Commodity> = emptyList()
            private set

        fun commodity(item: String): Commodity? = commodities[item]

        /** Live price of any item: the market's if listed, else the item's own guide price. */
        fun price(item: String): Int {
            val commodity = commodities[item]
            if (commodity != null) {
                return commodity.price
            }
            val definition = ItemDefinitions.get(item)
            return definition["price", definition.cost]
        }

        fun campaign(item: String): Campaign? = campaigns.firstOrNull { it.commodity.item == item }

        fun gainers(count: Int): List<Commodity> = commodities.values.filter { it.change > 0.0 }.sortedByDescending { it.change }.take(count)

        fun losers(count: Int): List<Commodity> = commodities.values.filter { it.change < 0.0 }.sortedBy { it.change }.take(count)

        fun sectors(): List<String> = commodities.values.map { it.sector }.distinct()

        /** Average 24h change of a sector, percent. */
        fun sectorChange(sector: String): Double {
            val members = commodities.values.filter { it.sector == sector }
            if (members.isEmpty()) {
                return 0.0
            }
            return members.sumOf { it.change } / members.size
        }

        fun rule(name: String): Int = Tables.intOrNull("$RULES.$name.value") ?: 0

        /**
         * A real fill moves the price: sells push it down, buys push it up, by
         * `impact_bps_per_volume` basis points per hour of normal volume traded, capped by
         * `impact_max_bps`. This is how a player's own trading shows up in the market.
         */
        fun impact(item: String, amount: Long, sell: Boolean) {
            val commodity = commodities[item] ?: return
            if (amount <= 0) {
                return
            }
            val share = amount.toDouble() / commodity.volume.coerceAtLeast(1)
            val bps = (share * rule("impact_bps_per_volume")).coerceAtMost(rule("impact_max_bps").toDouble())
            if (bps <= 0.0) {
                return
            }
            val direction = if (sell) -1 else 1
            val next = clamp(commodity, (commodity.price * (1.0 + direction * bps / 10_000.0)).roundToLong())
            if (next != commodity.price) {
                commodity.price = next
                writePrice(commodity)
            }
        }

        /**
         * The one write that makes the market real: the item's `price` param is what every price
         * lookup in the game reads. Params maps are loaded as mutable maps; an item with none gets one.
         */
        fun writePrice(commodity: Commodity) {
            val definition = ItemDefinitions.get(commodity.item)
            @Suppress("UNCHECKED_CAST")
            val params = definition.params as? MutableMap<Int, Any> ?: HashMap<Int, Any>().also { definition.params = it }
            params[Params.PRICE] = commodity.price
        }

        fun clamp(commodity: Commodity, price: Long): Int {
            val floor = (commodity.base.toLong() * rule("floor_percent") / 100).coerceAtLeast(1)
            val ceiling = (commodity.base.toLong() * rule("ceiling_percent") / 100).coerceIn(floor, MAX_PRICE.toLong())
            return price.coerceIn(floor, ceiling).toInt()
        }

        fun lines(row: String): List<String> = Tables.stringListOrNull("$LINES.$row.lines") ?: emptyList()

        const val ITEMS = "zidion_market_items"
        const val CAMPAIGNS = "zidion_market_campaigns"
        const val TRADERS = "zidion_market_traders"
        const val RULES = "zidion_market_rules"
        const val LINES = "zidion_market_lines"

        const val TIMER = "zidion_market"
        const val SEED_DELAY_TICKS = 10
        const val CLERK = "zidion_market_clerk"
        val BOARDS = listOf("ores", "runes", "logs", "herbs", "combat")

        const val TICKS_PER_HOUR = 6000
        const val TICKS_PER_DAY = 144_000
        const val MIN_STEP_TICKS = 5
        const val RARES = "rares"

        /** Above this a price*amount is at risk of overflowing the exchange's Int coin maths. */
        const val MAX_PRICE = 500_000_000

        /** Exchange offer slots per player, and the coin item. */
        const val SLOTS = 6
        const val COINS = "coins"
        val NEAREST = intArrayOf(0, 1, -1, 2, -2, 3, -3)

        /** Journal scroll limits and colours. */
        const val MAX_ENTRIES = 150
        const val WIDTH = 46
        const val GOLD = "ffb000"
        const val GREEN = "00b000"
        const val RED = "d00000"
        const val GREY = "b0b0b0"
    }
}
