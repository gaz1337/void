package content.zidion

import com.github.michaelbull.logging.InlineLogger
import content.bot.Bot
import content.bot.BotManager
import content.bot.behaviour.condition.BotEquipmentSetup
import content.bot.behaviour.condition.BotInventorySetup
import content.bot.isBot
import content.entity.combat.dead
import content.entity.combat.inCombat
import content.entity.combat.target
import world.gregs.voidps.engine.client.instruction.handle.interactPlayer
import content.entity.combat.killer
import content.entity.player.combat.special.MAX_SPECIAL_ATTACK
import content.entity.player.combat.special.specialAttack
import content.entity.player.combat.special.specialAttackEnergy
import content.quest.instance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import world.gregs.voidps.engine.Contexts
import world.gregs.voidps.engine.Script
import world.gregs.voidps.engine.client.PlayerAccountLoader
import world.gregs.voidps.engine.client.instruction.handle.interactFloorItem
import world.gregs.voidps.engine.client.instruction.handle.interactNpc
import world.gregs.voidps.engine.client.update.view.Viewport.Companion.VIEW_RADIUS
import world.gregs.voidps.engine.client.variable.hasClock
import world.gregs.voidps.engine.client.variable.start
import world.gregs.voidps.engine.client.variable.stop
import world.gregs.voidps.engine.data.AccountManager
import world.gregs.voidps.engine.data.Settings
import world.gregs.voidps.engine.data.definition.Areas
import world.gregs.voidps.engine.data.definition.EnumDefinitions
import world.gregs.voidps.engine.data.definition.StructDefinitions
import world.gregs.voidps.engine.data.definition.Tables
import world.gregs.voidps.engine.entity.World
import world.gregs.voidps.engine.entity.character.mode.EmptyMode
import world.gregs.voidps.engine.entity.character.mode.combat.CombatMovement
import world.gregs.voidps.engine.entity.character.mode.interact.Interact
import world.gregs.voidps.engine.entity.character.mode.interact.PlayerOnFloorItemInteract
import world.gregs.voidps.engine.entity.character.move.running
import world.gregs.voidps.engine.entity.character.npc.NPC
import world.gregs.voidps.engine.entity.character.npc.NPCs
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.character.player.Players
import world.gregs.voidps.engine.entity.character.player.appearance
import world.gregs.voidps.engine.entity.character.player.sex
import world.gregs.voidps.engine.entity.character.player.name
import world.gregs.voidps.engine.entity.character.player.skill.Skill
import world.gregs.voidps.engine.entity.character.player.skill.level.Level
import world.gregs.voidps.engine.entity.item.Item
import world.gregs.voidps.engine.entity.item.floor.FloorItem
import world.gregs.voidps.engine.entity.item.floor.FloorItems
import world.gregs.voidps.engine.inv.equipment
import world.gregs.voidps.engine.inv.inventory
import world.gregs.voidps.engine.inv.remove
import world.gregs.voidps.engine.inv.transact.operation.AddItem.add
import world.gregs.voidps.engine.inv.transact.operation.ClearItem.clear
import world.gregs.voidps.engine.map.Spiral
import world.gregs.voidps.engine.map.collision.random
import world.gregs.voidps.engine.timer.Timer
import world.gregs.voidps.network.client.DummyClient
import world.gregs.voidps.network.login.protocol.visual.update.player.BodyColour
import world.gregs.voidps.network.login.protocol.visual.update.player.BodyPart
import world.gregs.voidps.type.Tile
import world.gregs.voidps.type.random
import java.io.File

/**
 * The Zidion PvP bot: max level, max gear, a player account that behaves like a player who is
 * out to rob you. One of the three bot types in Zidion (PvP bot, trading bot, white knight npc);
 * this script is the whole of it.
 *
 * ## Population
 * A live population is kept around whoever is actually online, out of view, and cleared away
 * again once nobody is near; white knight escorts (npcs) are spawned with each group. Proximity
 * rather than fixed camps: a fixed camp is somewhere you visit, a roaming world is always already
 * happening wherever you are. The cost is entity density, so `perPlayer` is the dial to turn if
 * the client starts to feel heavy. Three rules the spawning obeys, each one a thing that gives
 * the trick away otherwise:
 *  - **Never in view.** Tiles are drawn from a ring beyond client render distance, so bots are
 *    always walked in, never blinked in.
 *  - **Never in water or walls.** Tiles come from `Area.random(collision)`, which retries until
 *    the tile can hold a character and gives up rather than dumping one in the sea.
 *  - **Never inside a safe zone**, and never seeded around a player standing in one.
 *
 * ## Fighting
 * The shipped bot machinery does it: the `zidion_pvp_bot` activity in `data/bot/` scans,
 * engages, eats, pots and re-engages exactly like any other pvp bot, against other bots, knights
 * and real players alike. The activity pauses (its fight action succeeds and the restart waits)
 * whenever one of these holds, and this script is what raises them:
 *  - `in_pvp == false`      - inside a safe zone; off duty, loitering
 *  - `zidion_hold` clock    - sizing a victim up, waiting on a demand, or having just spared them
 *  - `zidion_loot` clock    - collecting a pile
 *  - `zidion_tribute`       - walking the take to a knight
 *
 * ## Robbing
 * A real player is appraised on the first swing, face to face (`combatPrepare` fires after
 * arrival and a `false` cancels the attack before any hit lands): nothing worth taking and they
 * are dismissed; a coin flip and they are simply killed; otherwise a demand is made and the bot
 * stands watching. What is on the floor when the grace runs out decides it - pay, and the bot
 * collects and may or may not keep its word; refuse, and it attacks. Whatever it picks up is
 * walked to the nearest white knight and handed over, into [ZidionTreasury].
 *
 * Looting is done here rather than by the template's `floor_item` action: that action takes the
 * first item it finds, bones included, into a pack with six free slots, and the shipped kill-loot
 * path only accepts items owned by the killer - which deaths outside the true wilderness never
 * produce (they go under a gravestone, owned by the victim). The `Take` interaction itself has no
 * ownership check, so the bot simply picks the richest thing near the pile until the clock runs
 * out. Kit never drops on death; only the takings do. A dead bot is not re-kitted: it is
 * disposed of and a new one is spawned from scratch.
 *
 * Three loadouts - melee, ranged, mage - drawn by weight from the `zidion_pvp_bot_loadouts`
 * table; each is a bot activity in `data/bot/zidion_pvp_bot.bots.toml`. Levels are maxed for
 * all of them; the gear is strong, standard PvP kit, so a fight is a fight rather than a one-hit.
 *
 * Data: gear in `data/bot/zidion_pvp_bot.templates.toml`, the activities in
 * `zidion_pvp_bot.bots.toml`, every line and number in
 * `data/entity/npc/humanoid/zidion_pvp_bot/zidion_pvp_bot.tables.toml` (tribute exchanges in
 * `zidion_knight.tables.toml`, since both sides speak), population dials in `game.properties`
 * (`zidion.pvpBots.*`). Timing is clocks, timers and world queues per the wiki (Character
 * Variables, Timers, Queues); nothing here sleeps.
 */
class ZidionPvpBots(
    private val loader: PlayerAccountLoader,
    private val manager: BotManager,
    private val accounts: AccountManager,
) : Script {

    private val logger = InlineLogger("ZidionPvpBots")

    /** Owned scope, not GlobalScope: an unowned root coroutine outlives shutdown and resumes against torn-down state. */
    private val scope = CoroutineScope(SupervisorJob() + Contexts.Game)

    private val safeZones = Areas.tagged("safe_zone")

    /** Account names of our bots, for re-kitting on death and for counting them. */
    private val roles = mutableMapOf<String, String>()

    /** Escort knights we spawned, so they can be cleared with their group. */
    private val escorts = mutableListOf<NPC>()

    /** Names reserved by our bots, released on despawn so the pool can never drain. */
    private val taken = mutableSetOf<String>()

    /** Item families the bot spawns with; anything else in its pack is loot. */
    private val kit: Set<String> by lazy { kitFamilies() }

    init {
        // ---- population -----------------------------------------------------------------

        worldTimerStart(MAINTAIN_TIMER) { Settings["zidion.pvpBots.maintainTicks", 25] }
        worldTimerTick(MAINTAIN_TIMER) {
            maintain()
            Timer.CONTINUE
        }

        worldTimerStart(DUEL_TIMER) { Settings["zidion.ge.duelTicks", 5].coerceAtLeast(2) }
        worldTimerTick(DUEL_TIMER) {
            duels()
            Timer.CONTINUE
        }

        // Handlers only register the timer; something has to start it. Same idiom as BotCommands.
        worldSpawn {
            World.timers.start(MAINTAIN_TIMER)
            World.timers.start(DUEL_TIMER)
        }

        playerDespawn {
            if (roles.remove(accountName) != null) {
                taken.remove(accountName)
            }
        }

        worldDespawn {
            scope.cancel()
        }

        playerDeath {
            if (isPvpBot) {
                // Max-gear bots must not drop their kit: twenty sets of Torva on the floor would
                // end the economy before the exchange opens. What players take off them is what
                // they robbed, handed out explicitly here.
                it.dropItems = false
                if (this[ZidionKnights.ARRESTED, false]) {
                    confiscate()
                } else {
                    dropLoot(killer as? Player)
                }
                reset()
                dispose()
                return@playerDeath
            }
            if (isBot) {
                return@playerDeath
            }
            val slayer = killer as? Player ?: return@playerDeath
            if (!slayer.isPvpBot) {
                return@playerDeath
            }
            slayer.speak("kill", this)
            // The pile lands after the death animation; start the sweep once it is there.
            slayer.beginLoot(tile, delay = rule("kill_drop_delay_ticks"))
        }

        // ---- appraisal, demand, taunts ---------------------------------------------------

        combatPrepare("*") { target ->
            if (!isPvpBot) {
                return@combatPrepare true
            }
            val victim = target as? Player ?: return@combatPrepare true
            if (victim.isBot) {
                return@combatPrepare true
            }
            if (hasClock(HOLD)) {
                return@combatPrepare false
            }
            if (this[MARK, ""] == victim.accountName) {
                if (hasClock(OK)) {
                    // Keep the licence alive for as long as the fight lasts.
                    start(OK, rule("ok_ticks"))
                    return@combatPrepare true
                }
                if (hasClock(SPARED)) {
                    spare(victim)
                    return@combatPrepare false
                }
            }
            appraise(victim)
        }

        combatStart { target ->
            if (!isPvpBot) {
                return@combatStart
            }
            // Laying hands on anyone is what the knights come for - a bot is a player to them.
            if (target is Player) {
                val chance = Tables.intOrNull("${ZidionKnights.RULES}.${if (target.isBot) "attack_wanted_bot_percent" else "attack_wanted_percent"}.value") ?: 0
                if (random.nextInt(100) < chance) {
                    start(ZidionKnights.WANTED, Tables.intOrNull("${ZidionKnights.RULES}.wanted_ticks.value") ?: 0)
                }
            }
            if (this[BETRAY, false]) {
                clear(BETRAY)
                speak("betray")
                return@combatStart
            }
            if (hasClock(TAUNT)) {
                return@combatStart
            }
            start(TAUNT, rule("taunt_cooldown_ticks"))
            speak("slur", target as? Player)
        }

        combatDamage { damage ->
            if (!isPvpBot || !hasClock(HOLD)) {
                return@combatDamage
            }
            val attacker = damage.source as? Player ?: return@combatDamage
            if (attacker.isBot || attacker === this) {
                return@combatDamage
            }
            // A demand answered with a hit: the deal is off and so is any mercy.
            clear(DEMAND)
            clearWatch()
            stop(HOLD)
            stop(SPARED)
            this[MARK] = attacker.accountName
            start(OK, rule("ok_ticks"))
            speak("wrong_answer", attacker)
        }

        // ---- looting ---------------------------------------------------------------------

        timerStart(LOOT_TIMER) { _ ->
            rule("loot_step_ticks").coerceAtLeast(1)
        }

        timerTick(LOOT_TIMER) {
            if (dead || !hasClock(LOOT)) {
                finishLoot()
                return@timerTick Timer.CANCEL
            }
            if (mode is PlayerOnFloorItemInteract) {
                return@timerTick Timer.CONTINUE
            }
            val item = richestLoot()
            if (item == null) {
                finishLoot()
                return@timerTick Timer.CANCEL
            }
            interactFloorItem(item, "Take")
            Timer.CONTINUE
        }

        timerStop(LOOT_TIMER) { _ ->
            clear(PILE)
        }

        // ---- tribute ---------------------------------------------------------------------

        variableSet(TRIBUTE) { _, _, to ->
            if (to != true || !isPvpBot) {
                return@variableSet
            }
            val knight = nearestKnight()
            if (knight == null) {
                speak("no_boss")
                clear(TRIBUTE)
                return@variableSet
            }
            interactNpc(knight, TRIBUTE_OPTION)
            val account = accountName
            World.queue("zidion_tribute_timeout_$account", initialDelay = rule("tribute_timeout_ticks")) {
                val bot = Players.findByAccount(account) ?: return@queue
                if (bot[TRIBUTE, false]) {
                    // Never reached the boss; go back to work rather than stand in a doorway.
                    bot.clear(TRIBUTE)
                    bot.mode = EmptyMode
                }
            }
        }

        npcOperate(TRIBUTE_OPTION, "zidion_knight*") {
            if (!isPvpBot) {
                return@npcOperate
            }
            val take = loot()
            if (take.isEmpty()) {
                clear(TRIBUTE)
                return@npcOperate
            }
            var value = 0L
            for (item in take) {
                if (inventory.remove(item.id, item.amount)) {
                    value += item.def.cost.toLong() * item.amount
                }
            }
            say(knightLine("tribute"))
            ZidionTreasury.deposit(value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), this, "tribute")
            delay(2)
            it.target.say(knightLine("tribute_reply"))
            clear(TRIBUTE)
        }

        // ---- safe zones: off duty, not standing still ------------------------------------

        variableSet("in_pvp") { _, _, to ->
            if (!isPvpBot) {
                return@variableSet
            }
            if (to == true) {
                softTimers.stop(LOITER_TIMER)
            } else {
                softTimers.start(LOITER_TIMER)
            }
        }

        timerStart(LOITER_TIMER) { _ ->
            this[LOITER_STEPS] = 0
            rule("loiter_step_ticks").coerceAtLeast(1)
        }

        timerTick(LOITER_TIMER) {
            if (dead || mode !is EmptyMode) {
                return@timerTick Timer.CONTINUE
            }
            val steps = this[LOITER_STEPS, 0] + 1
            this[LOITER_STEPS] = steps
            if (steps > rule("loiter_steps")) {
                val exit = exitTile(rule("loiter_leave_tiles"))
                if (exit != null) {
                    walkTo(exit)
                    return@timerTick Timer.CONTINUE
                }
            }
            val target = tile.toCuboid(rule("loiter_radius")).random(this)
            if (target != null) {
                walkTo(target)
            }
            if (random.nextInt(100) < rule("loiter_chat_percent")) {
                speak("loiter")
            }
            Timer.CONTINUE
        }

        timerStop(LOITER_TIMER) { _ ->
            clear(LOITER_STEPS)
        }
    }

    private val Player.isPvpBot: Boolean
        get() = this[ROLE, ""] == ROLE_VALUE

    // ==========================================================================================
    // Population
    // ==========================================================================================

    /** One pass: clear anything nobody can see, top up around everybody who is online, close in. */
    private fun maintain() {
        // Nobody in a cutscene instance counts as a host: bots seeded around them would land in a
        // copied region that is destroyed a few seconds later.
        val hosts = Players.filter { !it.isBot && !it.dead && it.instance() == null }
        cull(hosts)
        if (hosts.isEmpty()) {
            return
        }
        closeIn(hosts)
        val perPlayer = Settings["zidion.pvpBots.perPlayer", 4]
        val knightsPer = Settings["zidion.pvpBots.knightsPerGroup", 1]
        val keep = Settings["zidion.pvpBots.keepRadius", 30]
        for (host in hosts) {
            if (safeZones.any { host.tile in it.area }) {
                continue
            }
            val nearbyBots = roles.keys.count { name ->
                val bot = Players.findByAccount(name) ?: return@count false
                bot.tile.distanceTo(host.tile) <= keep
            }
            val perPass = rule("spawns_per_pass").coerceAtLeast(0)
            repeat((perPlayer - nearbyBots).coerceAtMost(perPass)) {
                spawnBot(host)
            }
            val nearbyKnights = escorts.count { it.tile.distanceTo(host.tile) <= keep }
            repeat((knightsPer - nearbyKnights).coerceAtMost(perPass)) {
                spawnEscort(host)
            }
        }
    }

    /**
     * Bots are seeded beyond the fight action's scan radius so nobody sees them appear; left
     * alone they would stand there forever. Any idle bot further than `approach_radius` from the
     * nearest real player walks in to a tile beside them, where the activity takes over and does
     * the rest. Bots busy robbing, looting, paying tribute or off duty in a safe zone are left be.
     */
    private fun closeIn(hosts: List<Player>) {
        val approach = rule("approach_radius").coerceAtLeast(1)
        val cutoff = Settings["zidion.pvpBots.despawnRadius", 60]
        for (name in roles.keys) {
            val bot = Players.findByAccount(name) ?: continue
            if (bot.dead || !bot["in_pvp", false]) {
                continue
            }
            if (bot.hasClock(HOLD) || bot.hasClock(LOOT) || bot[TRIBUTE, false]) {
                continue
            }
            val host = hosts.filter { !safeZones.any { zone -> it.tile in zone.area } }
                .minByOrNull { it.tile.distanceTo(bot.tile) } ?: continue
            val distance = host.tile.distanceTo(bot.tile)
            if (distance <= approach || distance > cutoff) {
                continue
            }
            // Within view a fight is left alone; further out than that the bot is dragged in even
            // mid-fight, so the brawl happens around the player instead of out of sight.
            val far = distance > approach * 2
            if (!far && (bot.inCombat || bot.mode !is EmptyMode)) {
                continue
            }
            val target = host.tile.toCuboid(approach - 1).random(bot) ?: continue
            bot.walkTo(target)
        }
        // The escort knights keep up with the group the same way, so there is always law in view.
        for (knight in escorts) {
            // A knight already walking to (or standing in) a fight is left alone: walkTo would
            // replace its combat movement and it would never arrive.
            if (knight.dead || knight.inCombat || knight.mode is CombatMovement || knight.mode is Interact) {
                continue
            }
            val host = hosts.filter { !safeZones.any { zone -> it.tile in zone.area } }
                .minByOrNull { it.tile.distanceTo(knight.tile) } ?: continue
            val distance = host.tile.distanceTo(knight.tile)
            if (distance <= approach * 2 || distance > cutoff) {
                continue
            }
            val target = host.tile.toCuboid(approach + 2).random(knight) ?: continue
            knight.walkTo(target)
        }
    }

    /** Remove bots and escorts that no online player is near, so density stays local. */
    private fun cull(hosts: List<Player>) {
        val cutoff = Settings["zidion.pvpBots.despawnRadius", 60]
        for (name in roles.keys.toList()) {
            val bot = Players.findByAccount(name) ?: continue
            // An arrested bot stands in a cutscene instance, thousands of tiles from anyone; it is
            // the knight's to dispose of, not the cull's.
            if (bot[ZidionKnights.ARRESTED, false]) {
                continue
            }
            if (hosts.any { it.tile.distanceTo(bot.tile) <= cutoff }) {
                continue
            }
            despawn(bot)
        }
        val iterator = escorts.iterator()
        while (iterator.hasNext()) {
            val knight = iterator.next()
            if (hosts.any { it.tile.distanceTo(knight.tile) <= cutoff }) {
                continue
            }
            NPCs.remove(knight)
            iterator.remove()
        }
    }

    /**
     * Log the bot out the way BotCommands does. `Players.remove` alone does not fire
     * `playerDespawn`, so going through the account manager is what keeps our own bookkeeping
     * and BotManager's tick list from leaking.
     */
    private fun despawn(bot: Player) {
        val frame = bot.get<Bot>("bot")
        if (frame != null) {
            manager.remove(frame)
        }
        roles.remove(bot.accountName)
        taken.remove(bot.accountName)
        Script.launch {
            accounts.logout(bot, true)
        }
    }

    private fun spawnBot(host: Player) {
        val activityId = loadout() ?: return
        if (manager.activity(activityId) == null) {
            logger.warn { "No bot activity '$activityId' - is zidion_pvp_bot.bots.toml loaded?" }
            return
        }
        val tile = hiddenTile(host) ?: return
        val name = pickName() ?: return
        roles[name] = activityId
        scope.launch {
            val player = Player(tile = tile, accountName = name)
            val bot = Bot(player)
            player["bot"] = bot
            // Opted in to world PvP by ZidionWorld; ordinary skilling bots carry no role and
            // are left alone so their gear resolvers keep working.
            player[ROLE] = ROLE_VALUE
            loader.connect(player, DummyClient(), viewport = Settings["development.bots.live", false])
            randomiseAppearance(player)
            player.viewport?.loaded = true
            // Nobody arrives swinging. A random hold before the first fight staggers a group so
            // it drifts in over half a minute instead of landing as a wall.
            player.start(HOLD, random.nextInt(rule("spawn_hold_min_ticks"), rule("spawn_hold_max_ticks").coerceAtLeast(rule("spawn_hold_min_ticks") + 1)))
            delay(3)
            equip(bot, activityId)
            manager.add(bot)
            bot.pinned = activityId
            bot.available.clear()
            bot.available.add(activityId)
            bot.blocked.remove(activityId)
            manager.assign(bot, activityId)
            player.running = true
        }
    }

    /**
     * A dead bot is gone for good: logged out once the death sequence has run, name returned to
     * the pool, and the next maintenance pass spawns a brand new one from nothing. Nothing it
     * owned survives it, which is the point.
     */
    private fun Player.dispose() {
        val account = accountName
        World.queue("zidion_dispose_$account", initialDelay = rule("dispose_delay_ticks")) {
            val bot = Players.findByAccount(account) ?: return@queue
            despawn(bot)
        }
    }

    /** A knight walks with each group; it is not summoned by a crime, it is already there. */
    private fun spawnEscort(host: Player) {
        val tile = hiddenTile(host) ?: return
        val id = escortIds().randomOrNull(random) ?: return
        val knight = NPCs.add(id, tile)
        escorts.add(knight)
    }

    /**
     * A tile the host cannot currently see, that a character actually fits on, and that is not
     * inside a safe zone. Null when no such tile exists nearby - better to skip a spawn than to
     * drop someone in the sea or in front of the player.
     */
    private fun hiddenTile(host: Player): Tile? {
        val min = Settings["zidion.pvpBots.spawnMinRadius", 18]
        val max = Settings["zidion.pvpBots.spawnMaxRadius", 26]
        repeat(rule("spawn_tile_attempts").coerceAtLeast(1)) {
            // random(host) is the collision-aware extension; a bare random() resolves to the
            // Cuboid member (members beat extensions) and would happily pick open water.
            val tile = host.tile.toCuboid(max).random(host) ?: return@repeat
            if (tile.distanceTo(host.tile) < min) {
                return@repeat
            }
            if (safeZones.any { tile in it.area }) {
                return@repeat
            }
            return tile
        }
        return null
    }

    /**
     * Sets max levels and hands the bot the kit declared in its activity's `setup` block, so gear
     * changes stay in TOML. Same shape as BotCommands.applyTier, without depending on it.
     */
    private fun equip(bot: Bot, activityId: String) {
        val target = bot.player
        val combat = rule("combat_level").coerceIn(1, 99)
        val prayer = rule("prayer_level").coerceIn(1, 99)
        for (skill in COMBAT_SKILLS) {
            val level = if (skill == Skill.Prayer) prayer else combat
            val stored = if (skill == Skill.Constitution) level * 10 else level
            target.experience.set(skill, Level.experience(skill, stored))
            target.levels.set(skill, stored)
        }
        target.levels.clear(Skill.Constitution)
        target.levels.clear(Skill.Prayer)
        target.specialAttackEnergy = MAX_SPECIAL_ATTACK
        target.specialAttack = false
        target["brew_doses_since_restore"] = 0

        val activity = manager.activity(activityId) ?: return
        target.inventory.transaction { clear() }
        target.equipment.transaction { clear() }
        for (condition in activity.setup) {
            when (condition) {
                is BotEquipmentSetup -> target.equipment.transaction {
                    for ((slot, item) in condition.items) {
                        val id = item.ids.firstOrNull { it != "empty" } ?: continue
                        set(slot.index, Item(id, item.min ?: 1))
                    }
                }
                is BotInventorySetup -> target.inventory.transaction {
                    for (item in condition.items) {
                        val id = item.ids.firstOrNull { it != "empty" } ?: continue
                        add(id, item.min ?: 1)
                    }
                }
                else -> Unit
            }
        }
    }

    /** A loadout activity id drawn by weight from the zidion_pvp_bot_loadouts table. */
    private fun loadout(): String? {
        val rows = Tables.getOrNull(LOADOUTS)?.rows() ?: return null
        val total = rows.sumOf { Tables.intOrNull("$LOADOUTS.${it.rowId}.weight") ?: 0 }
        if (total <= 0) {
            return rows.firstOrNull()?.rowId
        }
        var roll = random.nextInt(total)
        return rows.first { row ->
            roll -= Tables.intOrNull("$LOADOUTS.${row.rowId}.weight") ?: 0
            roll < 0
        }.rowId
    }

    /**
     * Draws from the shared bot name file but tracks its own reservations so a name is always
     * returned on despawn. BotCommands' pool removes names and only refills on settings reload,
     * which is fatal for a population that respawns continuously.
     */
    private fun pickName(): String? {
        val name = NAMES.filter { it !in taken }.randomOrNull(random) ?: return null
        taken.add(name)
        return name
    }

    // ==========================================================================================
    // Robbing
    // ==========================================================================================

    /** First contact with a real player. Returns whether the attack goes ahead. */
    private fun Player.appraise(victim: Player): Boolean {
        this[MARK] = victim.accountName
        if (worth(victim) < rule("min_worth")) {
            speak("dismiss", victim)
            spare(victim)
            return false
        }
        if (random.nextInt(100) < rule("appraisal_percent")) {
            start(OK, rule("ok_ticks"))
            return true
        }
        demand(victim)
        return false
    }

    /**
     * Leave [victim] alone: remember them, pause the activity briefly and get out of its scan
     * radius, otherwise the fight action would re-target the nearest player - them - every tick.
     */
    private fun Player.spare(victim: Player) {
        start(SPARED, rule("spared_ticks"))
        start(HOLD, rule("spare_ticks"))
        walkAwayFrom(victim.tile, rule("walk_away_tiles"))
    }

    /**
     * Demand a drop and hold position. The verdict runs in a world queue at the grace, while the
     * hold clock runs a little longer; the verdict stops the hold itself, so the activity can
     * never resume in the tick before the verdict is in.
     */
    private fun Player.demand(victim: Player) {
        val grace = rule("demand_grace_ticks")
        speak("demand", victim)
        watch(victim)
        this[DEMAND] = victim.accountName
        start(HOLD, grace + rule("hold_margin_ticks"))
        val account = accountName
        World.queue("zidion_demand_$account", initialDelay = grace) {
            Players.findByAccount(account)?.settleDemand()
        }
    }

    private fun Player.settleDemand() {
        val victimAccount: String = this[DEMAND, ""]
        if (victimAccount.isEmpty() || dead) {
            return
        }
        clear(DEMAND)
        clearWatch()
        val victim = Players.findByAccount(victimAccount)
        if (victim == null || victim.dead) {
            stop(HOLD)
            clear(MARK)
            return
        }
        val pile = richestPile(victim.name)
        if (pile == null || pile.value < rule("drop_min_value")) {
            speak("refused")
            stop(HOLD)
            start(OK, rule("ok_ticks"))
            return
        }
        speak("after_drop")
        beginLoot(pile.tile)
        if (random.nextInt(100) < rule("attack_after_drop_percent")) {
            this[BETRAY] = true
            start(OK, rule("loot_ticks") + rule("ok_ticks"))
        } else {
            start(SPARED, rule("spared_ticks"))
        }
        stop(HOLD)
    }

    /** Open the loot phase around [pile]; the timer's first sweep waits [delay] ticks. */
    private fun Player.beginLoot(pile: Tile, delay: Int = 0) {
        this[PILE] = pile.id
        start(LOOT, rule("loot_ticks") + delay)
        if (delay <= 0) {
            softTimers.start(LOOT_TIMER)
            return
        }
        val account = accountName
        World.queue("zidion_loot_$account", initialDelay = delay) {
            Players.findByAccount(account)?.softTimers?.start(LOOT_TIMER)
        }
    }

    private fun Player.finishLoot() {
        stop(LOOT)
        clear(PILE)
        if (!dead && loot().isNotEmpty()) {
            this[TRIBUTE] = true
        }
    }

    /** Whatever the bot carries that it did not spawn with. */
    private fun Player.loot(): List<Item> = inventory.items.filter { it.isNotEmpty() && family(it.id) !in kit }

    /** A dead bot leaves its takings for the killer; the kit itself never drops. */
    private fun Player.dropLoot(slayer: Player?) {
        for (item in loot()) {
            if (!inventory.remove(item.id, item.amount)) {
                continue
            }
            FloorItems.add(
                tile,
                item.id,
                item.amount,
                revealTicks = if (slayer != null) rule("drop_private_ticks") else 0,
                disappearTicks = rule("drop_ticks"),
                owner = slayer,
            )
        }
    }

    /** A knight has this one: whatever it robbed goes to the treasury, not the floor. */
    private fun Player.confiscate() {
        var value = 0L
        for (item in loot()) {
            if (inventory.remove(item.id, item.amount)) {
                value += item.def.cost.toLong() * item.amount
            }
        }
        if (value > 0) {
            ZidionTreasury.deposit(value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), this, "confiscated")
        }
    }

    private fun Player.reset() {
        clear(ZidionKnights.ARRESTED)
        clear(MARK)
        clear(DEMAND)
        clear(PILE)
        clear(BETRAY)
        clear(TRIBUTE)
        stop(HOLD)
        stop(OK)
        stop(SPARED)
        stop(LOOT)
        stop(TAUNT)
        clearWatch()
    }

    /** What the victim is wearing - the bot sizes you up by your gear, not your pockets. */
    private fun worth(player: Player): Long {
        var total = 0L
        for (item in player.equipment.items) {
            if (item.isNotEmpty()) {
                total += item.def.cost.toLong() * item.amount
            }
        }
        return total
    }

    private class Pile(val tile: Tile, val value: Long)

    /** The tile near the bot holding the most value dropped by [owner]. */
    private fun Player.richestPile(owner: String): Pile? {
        var best: Pile? = null
        for (spot in Spiral.spiral(tile, radius("loot_radius"))) {
            var value = 0L
            for (item in FloorItems.at(spot)) {
                if (item.owner == owner) {
                    value += item.value
                }
            }
            if (value > 0 && (best == null || value > best.value)) {
                best = Pile(spot, value)
            }
        }
        return best
    }

    /** The single most valuable thing near the pile that will fit in the pack. */
    private fun Player.richestLoot(): FloorItem? {
        val centre = get<Int>(PILE)?.let { Tile(it) } ?: tile
        val floor = rule("loot_min_item_value")
        var best: FloorItem? = null
        for (spot in Spiral.spiral(centre, radius("loot_radius"))) {
            for (item in FloorItems.at(spot)) {
                if (item.value < floor || !fits(item)) {
                    continue
                }
                if (best == null || item.value > best.value) {
                    best = item
                }
            }
        }
        return best
    }

    private fun Player.fits(item: FloorItem): Boolean = inventory.spaces > 0 || (item.def.stackable == 1 && inventory.contains(item.id))

    private fun Player.nearestKnight(): NPC? {
        var best: NPC? = null
        var bestDistance = Int.MAX_VALUE
        for (zone in Spiral.spiral(tile.zone, radius("tribute_zones"))) {
            for (npc in NPCs.at(zone)) {
                if (!npc.id.startsWith(KNIGHT_PREFIX) || npc.dead) {
                    continue
                }
                val distance = tile.distanceTo(npc.tile)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = npc
                }
            }
        }
        return best
    }

    /** Walk to the reachable tile within [distance] that puts the most ground between us and [from]. */
    private fun Player.walkAwayFrom(from: Tile, distance: Int) {
        val area = tile.toCuboid(distance)
        var best: Tile? = null
        repeat(rule("walk_tile_attempts").coerceAtLeast(1)) {
            val candidate = area.random(this) ?: return@repeat
            val current = best
            if (current == null || candidate.distanceTo(from) > current.distanceTo(from)) {
                best = candidate
            }
        }
        walkTo(best ?: return)
    }

    /** A reachable tile within [distance] that is outside every safe zone, if there is one. */
    private fun Player.exitTile(distance: Int): Tile? {
        val area = tile.toCuboid(distance)
        repeat(rule("walk_tile_attempts").coerceAtLeast(1)) {
            val candidate = area.random(this) ?: return@repeat
            if (safeZones.none { candidate in it.area }) {
                return candidate
            }
        }
        return null
    }

    /** Say a line from [row]; never the one said last, with `$name` filled in from [about]. */
    private fun Player.speak(row: String, about: Player? = null) {
        val options = Tables.stringListOrNull("$LINES.$row.lines")
        if (options.isNullOrEmpty()) {
            return
        }
        val last: String = this[LAST_LINE, ""]
        val fresh = if (options.size > 1) options.filter { it != last } else options
        val text = fresh.random(random)
        this[LAST_LINE] = text
        colourSay(text.replace("\$name", about?.name ?: "noob"))
    }

    private fun knightLine(row: String): String = pick(Tables.stringListOrNull("$KNIGHT_LINES.$row.lines"))

    private fun pick(lines: List<String>?): String = if (lines.isNullOrEmpty()) "" else lines.random(random)

    private fun rule(row: String): Int = Tables.intOrNull("$RULES.$row.value") ?: 0

    /** Knight npc ids from the zidion_pvp_bot_escorts table. */
    private fun escortIds(): List<String> = Tables.getOrNull(ESCORTS)?.rows()?.map { it.rowId } ?: emptyList()

    private fun radius(row: String): Int = rule(row).coerceIn(0, VIEW_RADIUS)

    /**
     * Kit ids from the activity's `setup` block, with dose suffixes stripped so a half-drunk
     * `saradomin_brew_2` still counts as the bot's own.
     */
    private fun kitFamilies(): Set<String> {
        val ids = mutableSetOf<String>()
        val loadouts = Tables.getOrNull(LOADOUTS)?.rows()?.map { it.rowId } ?: emptyList()
        for (activityId in loadouts) {
            val activity = manager.activity(activityId)
            if (activity == null) {
                logger.warn { "No $activityId activity; its kit cannot be told apart from loot." }
                continue
            }
            for (condition in activity.setup) {
                when (condition) {
                    is BotEquipmentSetup -> condition.items.values.forEach { ids.addAll(it.ids) }
                    is BotInventorySetup -> condition.items.forEach { ids.addAll(it.ids) }
                    else -> Unit
                }
            }
        }
        ids.remove("empty")
        return ids.mapTo(mutableSetOf()) { family(it) }
    }

    private fun family(id: String): String = id.replace(DOSE_SUFFIX, "")

    // ==========================================================================================
    // GE duels: pvp bots that fight each other in the safe-zone Grand Exchange, as a show. They
    // are kept OUT of the roles map so the maintain/cull/close-in passes leave them alone, and
    // ZidionWorld keeps them pvp-flagged even in the safe zone (they can never hit, or be hit by,
    // a real player - safe-zone rules see to that). Healed when low so a bout lasts; a rare KO
    // just respawns a fresh pair.
    // ==========================================================================================

    private val duels = mutableListOf<Pair<String, String>>()

    private fun duels() {
        val target = Settings["zidion.ge.duels", 4].coerceAtLeast(0)
        duels.removeAll { (a, b) ->
            val gone = Players.findByAccount(a) == null || Players.findByAccount(b) == null
            if (gone) {
                taken.remove(a)
                taken.remove(b)
            }
            gone
        }
        for ((a, b) in duels) {
            val pa = Players.findByAccount(a) ?: continue
            val pb = Players.findByAccount(b) ?: continue
            healIfLow(pa)
            healIfLow(pb)
            keepDuelling(pa, pb)
            keepDuelling(pb, pa)
        }
        while (duels.size < target) {
            if (!spawnDuel(duels.size)) {
                break
            }
        }
    }

    private fun healIfLow(fighter: Player) {
        val max = fighter.levels.getMax(Skill.Constitution)
        if (fighter.levels.get(Skill.Constitution) < max * 4 / 10) {
            fighter.levels.set(Skill.Constitution, max)
        }
    }

    private fun keepDuelling(fighter: Player, foe: Player) {
        fighter["in_pvp"] = true
        fighter["in_multi_combat"] = true
        if (!fighter.inCombat || fighter.target !== foe) {
            fighter.interactPlayer(foe, "Attack")
        }
    }

    private fun spawnDuel(index: Int): Boolean {
        val activityA = loadout() ?: return false
        val activityB = loadout() ?: return false
        val nameA = pickName() ?: return false
        val nameB = pickName() ?: return false
        val base = Tile(3157 + (index % 6) * 5, 3474 + (index / 6) * 3)
        duels.add(nameA to nameB)
        scope.launch {
            val a = connectDuelist(nameA, base, activityA)
            val b = connectDuelist(nameB, base.add(1, 0), activityB)
            delay(3)
            a.face(b)
            b.face(a)
            a.interactPlayer(b, "Attack")
            b.interactPlayer(a, "Attack")
        }
        return true
    }

    private suspend fun connectDuelist(name: String, tile: Tile, activityId: String): Player {
        val player = Player(tile = tile, accountName = name)
        val bot = Bot(player)
        player["bot"] = bot
        player[ROLE] = ROLE_VALUE
        player["zidion_duel"] = true
        loader.connect(player, DummyClient(), viewport = Settings["development.bots.live", false])
        randomiseAppearance(player)
        player.viewport?.loaded = true
        equip(bot, activityId)
        player["in_pvp"] = true
        player["in_multi_combat"] = true
        player.running = true
        return player
    }

    companion object {
        /**
         * Same draw the character-creation screen makes: random sex, hair, beard, outfit and
         * colours. Public because the trading bots need a body too and this is the only place
         * that knows how to make one.
         */
        fun randomiseAppearance(player: Player) {
            val male = random.nextBoolean()
            player.body.male = male
            val key = "look_hair_${if (male) "male" else "female"}"
            player.body.setLook(BodyPart.Hair, EnumDefinitions.getStruct(key, random.nextInt(0, EnumDefinitions.get(key).length), "body_look_id"))
            player.body.setLook(BodyPart.Beard, if (male) EnumDefinitions.get("look_beard_male").randomInt() else -1)
            val size = EnumDefinitions.get("character_styles").length
            val style = EnumDefinitions.getStruct("character_styles", (0 until size).random(), "character_creation_sub_style_${player.sex}_0", -1)
            val struct = StructDefinitions.get(style)
            player.body.setLook(BodyPart.Chest, struct["character_style_top"])
            player.body.setLook(BodyPart.Arms, struct["character_style_arms"])
            player.body.setLook(BodyPart.Hands, struct["character_style_wrists"])
            player.body.setLook(BodyPart.Legs, struct["character_style_legs"])
            player.body.setLook(BodyPart.Feet, struct["character_style_shoes"])
            val offset = random.nextInt(0, 8)
            player.body.setColour(BodyColour.Hair, EnumDefinitions.get("colour_hair").randomInt())
            player.body.setColour(BodyColour.Top, struct["character_style_colour_top_$offset"])
            player.body.setColour(BodyColour.Legs, struct["character_style_colour_legs_$offset"])
            player.body.setColour(BodyColour.Feet, struct["character_style_colour_shoes_$offset"])
            player.body.setColour(BodyColour.Skin, EnumDefinitions.get("character_skin").randomInt())
            player.appearance.emote = 1426
        }

        const val LOADOUTS = "zidion_pvp_bot_loadouts"
        const val MAINTAIN_TIMER = "zidion_pvp_bot_maintain"
        const val DUEL_TIMER = "zidion_pvp_duel"
        const val ROLE = "zidion_role"
        const val ROLE_VALUE = "pvp_bot"

        const val LINES = "zidion_pvp_bot_lines"
        const val RULES = "zidion_pvp_bot_rules"
        const val ESCORTS = "zidion_pvp_bot_escorts"
        const val KNIGHT_LINES = "zidion_knight_lines"
        const val KNIGHT_PREFIX = "zidion_knight"
        const val TRIBUTE_OPTION = "Tribute"

        // Variables
        const val MARK = "zidion_mark"
        const val DEMAND = "zidion_demand"
        const val PILE = "zidion_pile"
        const val BETRAY = "zidion_betray"
        const val TRIBUTE = "zidion_tribute"
        const val LOITER_STEPS = "zidion_loiter_steps"
        const val LAST_LINE = "zidion_last_line"

        // Clocks (named in the bot template's success / wait_if conditions)
        const val HOLD = "zidion_hold"
        const val LOOT = "zidion_loot"
        const val OK = "zidion_ok"
        const val SPARED = "zidion_spared"
        const val TAUNT = "zidion_taunt"

        // Soft timers
        const val LOOT_TIMER = "zidion_loot"
        const val LOITER_TIMER = "zidion_loiter"

        val COMBAT_SKILLS = listOf(
            Skill.Attack,
            Skill.Strength,
            Skill.Defence,
            Skill.Ranged,
            Skill.Magic,
            Skill.Constitution,
            Skill.Prayer,
        )

        val NAMES: List<String> by lazy {
            File(Settings["bots.names", "./data/bot_names.txt"]).readLines().filter { it.isNotBlank() }
        }

        val DOSE_SUFFIX = Regex("_\\d+$")
    }
}
