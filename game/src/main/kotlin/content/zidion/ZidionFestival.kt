package content.zidion

import com.github.michaelbull.logging.InlineLogger
import content.entity.gfx.areaGfx
import content.entity.player.dialogue.Happy
import content.entity.player.dialogue.Neutral
import content.entity.player.dialogue.type.choice
import content.entity.player.dialogue.type.intEntry
import content.entity.player.dialogue.type.npc
import content.entity.player.dialogue.type.statement
import world.gregs.voidps.engine.Script
import world.gregs.voidps.engine.client.command.adminCommand
import world.gregs.voidps.engine.client.message
import world.gregs.voidps.engine.client.variable.hasClock
import world.gregs.voidps.engine.client.variable.start
import world.gregs.voidps.engine.data.Settings
import world.gregs.voidps.engine.data.definition.Tables
import world.gregs.voidps.engine.entity.World
import world.gregs.voidps.engine.entity.character.npc.NPC
import world.gregs.voidps.engine.entity.character.npc.NPCs
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.character.player.Players
import world.gregs.voidps.engine.entity.obj.GameObject
import world.gregs.voidps.engine.entity.obj.GameObjects
import world.gregs.voidps.engine.entity.obj.remove
import world.gregs.voidps.engine.inv.add
import world.gregs.voidps.engine.inv.inventory
import world.gregs.voidps.engine.inv.remove
import world.gregs.voidps.engine.timer.Timer
import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Tile
import world.gregs.voidps.type.random
import kotlin.math.abs

/**
 * The Zidion Jubilee - the Grand Exchange festival (spec:
 * docs/superpowers/specs/2026-09-04-zidion-jubilee-festival-design.md).
 *
 * A marquee, recurring, holiday-style event set at the Grand Exchange. For the festival window the
 * GE becomes a fairground: decorations, a host (party_pete), stalls that pay Jubilee Tokens, a
 * reward exchange for festive cosmetics, and a treasury-funded Grand Draw finale.
 *
 * Everything is scripts + data, no core edits (project rule):
 *  - Tokens are a player variable ([TOKENS]), not an item - no cache work, can't be dropped.
 *  - Rewards are existing cosmetics that are NOT market commodities, so handing them out never
 *    disturbs the simulated economy.
 *  - The host is the existing party_pete (id 659), spawned at the GE and marked [HOST_FLAG] so its
 *    festival dialogue never touches the real one in Falador.
 *  - Numbers, lines, decorations and rewards all live in zidion_jubilee.tables.toml.
 */
class ZidionFestival : Script {

    private val logger = InlineLogger("ZidionFestival")

    init {
        worldSpawn {
            if (ZidionFestival.active()) {
                open()
            }
            World.timers.start(TIMER)
            World.timers.start(AMBIENCE)
        }

        worldTimerStart(TIMER) { rule("draw_interval_ticks").coerceAtLeast(50) }
        worldTimerTick(TIMER) {
            if (ZidionFestival.active()) {
                Script.launch { grandDraw() }
            }
            Timer.CONTINUE
        }

        // The fair should never feel dead: a steady patter of fireworks, the host shouting hype,
        // and the keepers breaking into a dance.
        worldTimerStart(AMBIENCE) { rule("ambience_ticks").coerceAtLeast(4) }
        worldTimerTick(AMBIENCE) {
            if (ZidionFestival.active()) {
                fireworks(big = false)
                val host = ZidionFestival.host
                if (host != null && random.nextInt(100) < rule("announce_percent")) {
                    host.say("<col=ffcc00>${line("announce")}</col>")
                }
                if (random.nextInt(100) < rule("dance_percent")) {
                    host?.anim(DANCE.random(random))
                    ZidionFestival.attendants.randomOrNull(random)?.anim(DANCE.random(random))
                }
            }
            Timer.CONTINUE
        }

        // Reset a player's daily token cap and clear stale tokens at login (tokens don't persist).
        playerSpawn {
            this[TOKENS_TODAY] = 0
        }

        // ---- the host -----------------------------------------------------------------------
        npcApproach("Talk-to", HOST) { (target) ->
            approachRange(2)
            if (!target[HOST_FLAG, false] || !ZidionFestival.active()) {
                return@npcApproach
            }
            // A stall keeper runs its one game; the central host runs the overview menu.
            when (target[STALL, ""]) {
                "guess" -> guessThePrice()
                "dip" -> luckyDip()
                "dig" -> treasureDig()
                "bonfire" -> lightBonfire()
                "fortune" -> fortuneTeller()
                "strength" -> testOfStrength()
                "ring" -> ringToss()
                else -> {
                    npc<Happy>(line("welcome"))
                    hostMenu()
                }
            }
        }

        // ---- admin --------------------------------------------------------------------------
        adminCommand("jubilee", desc = "Zidion Jubilee: start|stop|status|draw|tokens <n>") { args ->
            when (args.getOrNull(0)) {
                "start" -> {
                    ZidionFestival.override = true
                    open()
                    message("Jubilee started.")
                }
                "stop" -> {
                    ZidionFestival.override = false
                    close()
                    message("Jubilee stopped.")
                }
                "draw" -> Script.launch { grandDraw() }
                "tokens" -> {
                    val amount = args.getOrNull(1)?.toIntOrNull() ?: 100
                    ZidionFestival.award(this, amount)
                    message("Granted $amount Jubilee Tokens (now ${ZidionFestival.tokens(this)}).")
                }
                else -> message(
                    "Jubilee ${if (ZidionFestival.active()) "ON" else "OFF"} | your tokens ${ZidionFestival.tokens(this)} | entries ${ZidionFestival.entries.size}",
                )
            }
        }
    }

    // =============================================================================================
    // Host dialogue
    // =============================================================================================

    private suspend fun Player.hostMenu() {
        choice {
            option<Neutral>("How does the Jubilee work?") {
                npc<Happy>(line("explain"))
                hostMenu()
            }
            option<Happy>("Guess the Price!") {
                guessThePrice()
                hostMenu()
            }
            option<Happy>("Lucky Coin Dip.") {
                luckyDip()
                hostMenu()
            }
            option<Neutral>("What can I win? (rewards)") {
                rewardMenu()
            }
            option<Neutral>("How many tokens do I have?") {
                npc<Happy>(line("balance").replace("\$tokens", ZidionFestival.tokens(this@hostMenu).toString()))
                hostMenu()
            }
        }
    }

    // =============================================================================================
    // Stalls
    // =============================================================================================

    /** The signature stall: guess a live market price. Reads the real ZidionMarket. */
    private suspend fun Player.guessThePrice() {
        if (onCooldown()) {
            npc<Neutral>(line("cooldown"))
            return
        }
        val commodity = ZidionMarket.commodities.values.toList().randomOrNull(random)
        if (commodity == null) {
            npc<Neutral>("The market's quiet - come back in a moment.")
            return
        }
        val item = commodity.item
        val price = ZidionMarket.price(item)
        val name = item.replace('_', ' ')
        npc<Happy>(line("guess_intro").replace("\$item", name))
        val guess = intEntry("What's a $name worth right now? (coins)")
        startCooldown()
        val off = if (price > 0) abs(guess - price) * 100.0 / price else 100.0
        val (row, tokens) = when {
            off <= rule("guess_close_percent") -> "guess_close" to rule("guess_tokens_close")
            off <= rule("guess_near_percent") -> "guess_near" to rule("guess_tokens_near")
            else -> "guess_miss" to rule("guess_tokens_miss")
        }
        ZidionFestival.award(this, tokens)
        npc<Happy>("${line(row)} It was ${price} coins. +$tokens tokens.")
    }

    /** A light gamble: pay coins, dip for a random token payout with a jackpot chance. */
    private suspend fun Player.luckyDip() {
        if (onCooldown()) {
            npc<Neutral>(line("cooldown"))
            return
        }
        val cost = rule("dip_cost_coins")
        if (!inventory.contains("coins", cost)) {
            npc<Neutral>("The dip costs $cost coins - come back when you're flush!")
            return
        }
        inventory.remove("coins", cost)
        startCooldown()
        if (random.nextInt(100) < rule("dip_jackpot_percent")) {
            val tokens = rule("dip_jackpot_tokens")
            ZidionFestival.award(this, tokens)
            npc<Happy>("${line("dip_win")} +$tokens tokens!")
        } else {
            val tokens = random.nextInt(rule("dip_tokens_min"), rule("dip_tokens_max").coerceAtLeast(rule("dip_tokens_min") + 1))
            ZidionFestival.award(this, tokens)
            npc<Happy>("${line("dip_normal")} +$tokens tokens.")
        }
    }

    /** Dig a marked spot for a handful of tokens, with a chance of a lucky bonus. */
    private suspend fun Player.treasureDig() {
        if (onCooldown()) {
            npc<Neutral>(line("cooldown"))
            return
        }
        startCooldown()
        var tokens = random.nextInt(rule("dig_tokens_min"), rule("dig_tokens_max").coerceAtLeast(rule("dig_tokens_min") + 1))
        var trinket = ""
        if (random.nextInt(100) < rule("dig_trinket_percent")) {
            tokens += rule("dig_tokens_min")
            trinket = " ${line("dig_trinket")}"
        }
        ZidionFestival.award(this, tokens)
        npc<Happy>("${line("dig_hit")}$trinket +$tokens tokens.")
    }

    /** Stoke the Jubilee bonfire: a firework, and a token. */
    private suspend fun Player.lightBonfire() {
        if (onCooldown()) {
            npc<Neutral>(line("cooldown"))
            return
        }
        startCooldown()
        areaGfx(FIREWORKS.random(random), tile, height = 500)
        val tokens = rule("bonfire_tokens")
        ZidionFestival.award(this, tokens)
        npc<Happy>("${line("bonfire")} +$tokens tokens.")
    }

    /** The fortune teller: gaze into the orb for a random handful of tokens. */
    private suspend fun Player.fortuneTeller() {
        if (onCooldown()) {
            npc<Neutral>(line("cooldown"))
            return
        }
        startCooldown()
        val tokens = random.nextInt(rule("fortune_tokens_min"), rule("fortune_tokens_max").coerceAtLeast(rule("fortune_tokens_min") + 1))
        ZidionFestival.award(this, tokens)
        npc<Happy>("${line("fortune")} +$tokens tokens.")
    }

    /** Test of strength: swing the hammer, ring the bell, win by how hard you hit. */
    private suspend fun Player.testOfStrength() {
        if (onCooldown()) {
            npc<Neutral>(line("cooldown"))
            return
        }
        startCooldown()
        val roll = random.nextInt(100)
        val tokens = (rule("strength_tokens_max") * roll / 100).coerceAtLeast(1)
        ZidionFestival.award(this, tokens)
        val row = if (roll > 80) "strength_bell" else "strength_hit"
        npc<Happy>("${line(row)} +$tokens tokens.")
    }

    /** Ring toss: land the ring and win big, miss and get a consolation. */
    private suspend fun Player.ringToss() {
        if (onCooldown()) {
            npc<Neutral>(line("cooldown"))
            return
        }
        startCooldown()
        val win = random.nextInt(100) < rule("ring_win_percent")
        val tokens = if (win) rule("ring_tokens_win") else rule("ring_tokens_miss")
        ZidionFestival.award(this, tokens)
        npc<Happy>("${line(if (win) "ring_win" else "ring_miss")} +$tokens tokens.")
    }

    private fun Player.onCooldown(): Boolean = hasClock(STALL_CLOCK)

    private fun Player.startCooldown() = start(STALL_CLOCK, rule("stall_cooldown_ticks").coerceAtLeast(1))

    // =============================================================================================
    // Reward exchange
    // =============================================================================================

    private suspend fun Player.rewardMenu() {
        val rewards = Tables.getOrNull(REWARDS)?.rows()?.sortedBy { it.int("tier") } ?: emptyList()
        // choice{} holds 2..5 options; page the rewards if there are more.
        val shown = rewards.take(4)
        choice {
            for (reward in shown) {
                val item = reward.string("item")
                val cost = reward.int("cost")
                option("${item.replace('_', ' ')} - $cost tokens") {
                    buyReward(item, cost)
                    rewardMenu()
                }
            }
            option("Maybe later.") {
                npc<Happy>("Come back when you've earned more - the crowd awaits!")
            }
        }
    }

    private suspend fun Player.buyReward(item: String, cost: Int) {
        if (!ZidionFestival.spend(this, cost)) {
            npc<Neutral>(line("no_tokens"))
            return
        }
        val added = inventory.add(item)
        if (!added) {
            ZidionFestival.award(this, cost) // refund if no room
            npc<Neutral>("You've no room for that - free up some space and try again.")
            return
        }
        npc<Happy>(line("reward_done"))
    }

    // =============================================================================================
    // The Grand Draw - treasury-funded jackpot + fireworks + server-wide broadcast
    // =============================================================================================

    private suspend fun grandDraw() {
        broadcast(line("draw_soon"))
        World.queue("zidion_jubilee_draw", initialDelay = rule("countdown_ticks").coerceAtLeast(1)) {
            Script.launch { drawWinner() }
        }
    }

    private suspend fun drawWinner() {
        fireworks(big = true)
        val entries = ZidionFestival.entries.toList()
        if (entries.isEmpty()) {
            broadcast(line("draw_empty"))
            return
        }
        // Weighted random over entries.
        val total = entries.sumOf { it.second }
        var roll = random.nextInt(total.coerceAtLeast(1))
        var winnerName = entries.first().first
        for ((name, weight) in entries) {
            roll -= weight
            if (roll < 0) {
                winnerName = name
                break
            }
        }
        ZidionFestival.entries.clear()
        val winner = Players.findByAccount(winnerName)
        val jackpot = jackpot()
        if (winner != null && jackpot > 0) {
            val paid = ZidionTreasury.withdraw(jackpot, winner, "jubilee_draw")
            if (paid > 0) {
                winner.inventory.add("coins", paid)
                broadcast(line("draw_winner").replace("\$name", winnerName).replace("\$amount", paid.toString()))
                logger.info { "Jubilee Grand Draw: $winnerName won $paid coins." }
                return
            }
        }
        broadcast(line("draw_empty"))
    }

    /** A capped, floored slice of the treasury - generous but never a drain. */
    private fun jackpot(): Int {
        val slice = ZidionTreasury.balance() * rule("jackpot_percent") / 100
        return slice.coerceIn(rule("jackpot_min").toLong(), rule("jackpot_max").toLong()).toInt()
    }

    private fun fireworks(big: Boolean = true) {
        val count = if (big) 24 else 12
        repeat(count) {
            // Bursts high in the sky over the GE - big height so they read as aerial fireworks,
            // not floor flashes.
            val tile = Tile(3150 + random.nextInt(34), 3474 + random.nextInt(34))
            areaGfx(FIREWORKS.random(random), tile, delay = it, height = 450 + random.nextInt(500))
        }
    }

    // =============================================================================================
    // The fairground - spawn / despawn
    // =============================================================================================

    private fun open() {
        if (ZidionFestival.host != null) {
            return
        }
        // Fixed, verified-walkable tiles from the tables (collision isn't loaded at worldSpawn, so
        // runtime tile-finding would fail and stack everything on one fallback tile - the bug where
        // all the keepers ended up in one place). Decorations first.
        Tables.getOrNull(DECOR)?.rows()?.forEach { row ->
            ZidionFestival.decorations.add(GameObjects.add(row.string("obj"), Tile(row.int("x"), row.int("y")), rotation = row.int("rotation")))
        }
        // The host, front and centre in the north plaza.
        val host = NPCs.add(HOST, Tile(3165, 3509), Direction.SOUTH)
        host[HOST_FLAG] = true
        ZidionFestival.host = host
        // Each stall: a structure plus a keeper manning it, spread around the ring.
        Tables.getOrNull(STALLS)?.rows()?.forEach { row ->
            val stallTile = Tile(row.int("x"), row.int("y"))
            val obj = row.string("obj")
            if (obj.isNotEmpty()) {
                ZidionFestival.decorations.add(GameObjects.add(obj, stallTile, rotation = row.int("rotation")))
            }
            // The keeper stands on its own tile beside the stall, turned to face it.
            val keeper = NPCs.add(HOST, Tile(row.int("kx"), row.int("ky")), Direction.SOUTH)
            keeper[HOST_FLAG] = true
            keeper[STALL] = row.string("game")
            keeper.face(stallTile)
            ZidionFestival.attendants.add(keeper)
        }
        logger.info { "Zidion Jubilee opened at the Grand Exchange with ${ZidionFestival.attendants.size} stalls." }
    }

    private fun close() {
        ZidionFestival.decorations.forEach { it.remove() }
        ZidionFestival.decorations.clear()
        ZidionFestival.host?.let { NPCs.remove(it) }
        ZidionFestival.host = null
        ZidionFestival.attendants.forEach { NPCs.remove(it) }
        ZidionFestival.attendants.clear()
        logger.info { "Zidion Jubilee closed." }
    }

    private fun broadcast(text: String) {
        if (text.isBlank()) {
            return
        }
        Players.forEach { it.message(text) }
    }

    private fun rule(name: String): Int = Tables.intOrNull("$RULES.$name.value") ?: 0

    private fun line(row: String): String = Tables.stringListOrNull("$LINES.$row.lines")?.randomOrNull(random) ?: ""

    companion object {
        const val TIMER = "zidion_jubilee"
        const val HOST = "party_pete"
        const val HOST_FLAG = "zidion_festival_host"
        const val TOKENS = "zidion_jubilee_tokens"
        const val TOKENS_TODAY = "zidion_jubilee_tokens_today"
        const val STALL_CLOCK = "zidion_jubilee_stall"
        const val STALL = "zidion_jubilee_stall_game"
        // Big, bright bursts (magic-impact graphics read as fireworks in the air) mixed with sparkles.
        val FIREWORKS = listOf(
            "chinchompa_explode",
            "iban_blast",
            "arctic_blast",
            "wind_blast",
            "druid_shooting_star",
        )
        const val AMBIENCE = "zidion_jubilee_ambience"
        val DANCE = listOf("emote_dance", "emote_jig", "emote_snowman_dance", "emote_turkey_dance")

        const val RULES = "zidion_jubilee_rules"
        const val LINES = "zidion_jubilee_lines"
        const val DECOR = "zidion_jubilee_decor"
        const val REWARDS = "zidion_jubilee_rewards"
        const val STALLS = "zidion_jubilee_stalls"

        /** Runtime override of the config switch (admin ::jubilee start/stop). Null = follow config. */
        var override: Boolean? = null

        /** Decorations currently spawned, so they can be cleared on close. */
        val decorations = mutableListOf<GameObject>()

        /** The festival host npc while open. */
        var host: NPC? = null

        /** The stall-keeper npcs while open. */
        val attendants = mutableListOf<NPC>()

        /** Grand Draw entries this round: account name -> weight (tokens earned). */
        val entries = LinkedHashMap<String, Int>()

        fun active(): Boolean = override ?: Settings["zidion.jubilee.active", false]

        fun tokens(player: Player): Int = player[TOKENS, 0]

        fun award(player: Player, amount: Int) {
            if (amount <= 0) {
                return
            }
            val cap = Tables.intOrNull("zidion_jubilee_rules.token_daily_cap.value") ?: Int.MAX_VALUE
            val today = player[TOKENS_TODAY, 0]
            val allowed = (cap - today).coerceAtLeast(0)
            val granted = amount.coerceAtMost(allowed)
            if (granted <= 0) {
                return
            }
            player[TOKENS] = tokens(player) + granted
            player[TOKENS_TODAY] = today + granted
            // Earning tokens buys entries into the next Grand Draw.
            entries[player.accountName] = (entries[player.accountName] ?: 0) + granted
        }

        fun spend(player: Player, amount: Int): Boolean {
            if (tokens(player) < amount) {
                return false
            }
            player[TOKENS] = tokens(player) - amount
            return true
        }
    }
}
