package content.zidion

import com.github.michaelbull.logging.InlineLogger
import content.bot.BotManager
import content.bot.bot
import content.bot.isBot
import content.entity.combat.dead
import content.entity.combat.inCombat
import content.entity.combat.hit.directHit
import content.entity.combat.killer
import world.gregs.voidps.engine.map.collision.Collisions
import world.gregs.voidps.engine.Script
import world.gregs.voidps.engine.client.clearCamera
import world.gregs.voidps.engine.client.instruction.handle.interactPlayer
import world.gregs.voidps.engine.client.moveCamera
import world.gregs.voidps.engine.client.shakeCamera
import world.gregs.voidps.engine.client.turnCamera
import world.gregs.voidps.engine.client.ui.open
import world.gregs.voidps.engine.client.variable.hasClock
import world.gregs.voidps.engine.client.variable.start
import world.gregs.voidps.engine.client.variable.stop
import world.gregs.voidps.engine.data.Settings
import world.gregs.voidps.engine.data.definition.Areas
import world.gregs.voidps.engine.data.definition.Tables
import world.gregs.voidps.engine.entity.World
import world.gregs.voidps.engine.entity.character.mode.EmptyMode
import world.gregs.voidps.engine.entity.character.mode.combat.CombatMovement
import world.gregs.voidps.engine.entity.character.mode.interact.Interact
import world.gregs.voidps.engine.entity.character.mode.PauseMode
import world.gregs.voidps.engine.entity.character.move.tele
import world.gregs.voidps.engine.entity.character.npc.NPC
import world.gregs.voidps.engine.entity.character.npc.NPCs
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.character.player.Players
import world.gregs.voidps.engine.entity.character.player.appearance
import world.gregs.voidps.engine.entity.character.player.flagAppearance
import world.gregs.voidps.engine.entity.character.player.skill.Skill
import world.gregs.voidps.type.Direction
import world.gregs.voidps.type.Tile
import world.gregs.voidps.type.random

/**
 * Zidion White Knights - the law on the roads.
 *
 * They are not posted anywhere. They turn up where a killing happened, like police, using the
 * same summon-an-npc-beside-a-player idiom the shipped random events use
 * ([content.activity.event.random.RandomEventKidnap.eventHerald]).
 *
 * The knights tolerate robbery - that is the arrangement, and PvP bots pay them a cut. They do
 * not tolerate murder. Two responses:
 *
 *  - **A real player killed by a PvP bot: the execution.** The scene plays out in place, where
 *    the kill happened: the killer locks where it stands (pulled out of its bot driver so it can
 *    never wander), a knight appears beside it and steps up, and puts it down in one hit, leaving
 *    a puff of dust. The camera sits high and looks down so no wall hides it. The bot's takings
 *    are confiscated into the treasury and the bot is then disposed. Camera moves plus
 *    `fade_out` / `fade_in`; no instance, so the killer never teleports and never disappears.
 *  - **Everything else** (a bot attacks you, a bot kills a bot, a real player kills anyone): the
 *    offender is marked wanted, and every knight within hunt range goes for them on its own -
 *    the `zidion_law` hunt mode, Void's own npc aggression (wiki: Hunt modes), with the handler
 *    here deciding that only the wanted are worth a swing. They accuse, attack; bots beg; the
 *    knight forgives or finishes it. Judgement keys on [killer], never on whether the offender is
 *    a bot, so a real player who robs and then kills is hunted exactly like one of their own.
 *
 * Everything spoken and every number lives in
 * `data/entity/npc/humanoid/zidion_knight/zidion_knight.tables.toml`; the npc itself is defined
 * entirely in data by cloning `white_knight`. This file holds only what data cannot say: the
 * judgement flow and the scene.
 */
class ZidionKnights(private val manager: BotManager) : Script {

    private val logger = InlineLogger("ZidionKnights")

    /** Safe zones are where the law already holds, so nothing is policed there. */
    private val safeZones = Areas.tagged("safe_zone")

    private val respawnTile: Tile
        get() = Tile(Settings["world.home.x", 0], Settings["world.home.y", 0], Settings["world.home.level", 0])

    init {
        // A knight can be attacked by several bots at once and attack while others are on it;
        // without this the single-combat gate leaves it following its target and never swinging.
        npcSpawn("zidion_knight*") {
            this["in_multi_combat"] = true
        }

        playerDeath { onDeath ->
            val offender = killer as? Player
            if (offender == null) {
                if (!isBot) {
                    logger.info { "Death of $accountName at $tile: killer is ${killer?.let { it::class.simpleName } ?: "unknown"}, no scene." }
                }
                return@playerDeath
            }
            if (offender === this) {
                return@playerDeath
            }
            val scene = tile
            if (safeZones.any { scene in it.area }) {
                if (!isBot) {
                    logger.info { "Death of $accountName at $scene: safe zone, no scene." }
                }
                return@playerDeath
            }
            val pvpBot = offender[ZidionPvpBots.ROLE, ""] == ZidionPvpBots.ROLE_VALUE
            if (!isBot && !pvpBot) {
                logger.info { "Death of $accountName at $scene: killer ${offender.accountName} is not a pvp bot, no scene." }
            }
            if (!isBot && pvpBot) {
                if (offender[ARRESTED, false]) {
                    logger.info { "Death of $accountName at $scene: killer ${offender.accountName} is already arrested, no scene." }
                    return@playerDeath
                }
                logger.info { "Execution: ${offender.accountName} killed $accountName at $scene." }
                // No instance and no teleport of anyone. The killer locks where it made the kill;
                // the victim stays on the death tile to watch, black and invisible from the instant
                // of death so it never sees itself respawn. onDeath.teleport is the death tile
                // itself, which the death queue applies as a no-op (same tile) and so never clears
                // the fade. The only spawn the victim ever sees is at home, after the scene.
                detain(offender)
                open("fade_out")
                appearance.hidden = true
                flagAppearance()
                onDeath.teleport = tile
                val victim = accountName
                val killerName = offender.accountName
                World.queue("zidion_execution_$victim", initialDelay = rule("execution_delay_ticks")) {
                    Script.launch {
                        Players.findByAccount(victim)?.execution(killerName)
                    }
                }
                return@playerDeath
            }
            // Anything else - a bot killing a bot, a player killing anyone - marks the killer
            // wanted. Every knight within hunt range of a wanted player goes for them.
            if (random.nextInt(100) < rule("response_percent")) {
                offender.start(WANTED, rule("wanted_ticks"))
            }
        }

        // The law itself: the zidion_law hunt mode (zidion_knight.hunt_modes.toml) hands a knight a
        // player in range; only the wanted are touched (wiki: Hunt modes - the handler decides).
        huntPlayer("zidion_knight*", mode = LAW) { target ->
            if (!target.hasClock(WANTED) || mode is CombatMovement || mode is Interact) {
                return@huntPlayer
            }
            engage(this, target)
        }
    }

    /**
     * Take the killer where it stands: flagged, its fight loop held, frozen over the body at full
     * health so the brawl around it cannot finish it off before the camera comes up. The cull and
     * the close-in both leave an arrested bot alone.
     */
    private fun detain(offender: Player) {
        offender[ARRESTED] = true
        offender.start(ZidionPvpBots.HOLD, rule("execution_hold_ticks"))
        // Pull the bot out of its own driver. PauseMode alone is overridden the next time the
        // bot's AI issues a step - which is why it was walking off to the knight - so it also
        // comes out of the manager's tick loop and stops issuing steps entirely. The Player stays
        // logged in and on-screen where it stands: no unspawn, no teleport, no disappearing.
        if (offender.isBot) {
            manager.remove(offender.bot)
        }
        offender.mode = PauseMode
        offender.steps.clear()
        // Off the pvp radar so the brawl around it stops targeting the frozen killer.
        offender.clear("in_pvp")
        offender.levels.set(Skill.Constitution, offender.levels.getMax(Skill.Constitution))
    }

    /** Hold the killer still for one tick; re-asserted through the scene against any stray nudge. */
    private fun freeze(killer: Player) {
        killer.mode = PauseMode
        killer.steps.clear()
    }

    /**
     * A tile behind the camera for the dead watcher to sit on, far enough back that its own body -
     * which its client always draws - is out of the shot. Kept close enough that the killer and
     * knight stay loaded on the watcher's screen. Falls back through nearer tiles if blocked.
     */
    private fun watchTile(stand: Tile): Tile {
        for (back in WATCH_BEHIND downTo 2) {
            val tile = stand.add(0, -(CAMERA_BACK + back))
            if (Collisions[tile.x, tile.y, tile.level] == 0) {
                return tile
            }
        }
        return stand.add(0, -(CAMERA_BACK + 2))
    }

    /** An open tile a couple of steps from the body for the knight to appear on and step in from. */
    private fun knightTile(stand: Tile): Tile {
        for (offset in listOf(Tile(0, STEP_IN), Tile(0, -STEP_IN), Tile(STEP_IN, 0), Tile(-STEP_IN, 0))) {
            val tile = stand.add(offset.x, offset.y)
            if (Collisions[tile.x, tile.y, tile.level] == 0) {
                return tile
            }
        }
        return stand.add(0, STEP_IN)
    }


    /**
     * The execution, played out in place. The killer has not moved since the kill: it stands
     * frozen while a knight appears beside it, steps up, and ends it in one hit. The camera sits
     * high and looks down so no wall can hide the scene, and the killer never teleports, so the
     * viewer never sees it vanish or wander.
     */
    private suspend fun Player.execution(killerName: String) {
        val killer = Players.findByAccount(killerName)
        if (killer == null || killer.dead) {
            logger.warn { "Execution scene for $accountName abandoned: killer $killerName is ${if (killer == null) "gone" else "dead"}." }
            tele(respawnTile)
            return
        }
        val stand = killer.tile
        val focus = rule("camera_focus_height")
        // `appearance.hidden` only hides the watcher from OTHER players - a client always draws its
        // own local player - so on its own the watcher still sees itself. Two things fix that: hide
        // it from everyone else (hidden), AND move its body well out of the camera's shot, back
        // behind the camera. All of it happens under the black opened at the moment of death, so
        // nothing is seen moving; re-assert hidden after the move and again after the fade in.
        clear("in_pvp")
        appearance.hidden = true
        flagAppearance()
        open("fade_out")
        delay(1)
        tele(watchTile(stand), clearInterfaces = false)
        mode = PauseMode
        appearance.hidden = true
        flagAppearance()
        // Wait for the region reload to catch up with the move before aiming the camera. The camera
        // is placed in coordinates relative to the watcher's loaded zone; aim it in the same tick as
        // the teleport and it resolves against the OLD position, which is what threw the shot off
        // and left the bot or knight out of frame. Two ticks under the black is enough.
        delay(2)
        // High, slightly-south camera looking down at the killer - steep enough that walls can't
        // block the shot, which is what put the knight off-screen or behind a wall before.
        moveCamera(stand.add(0, -CAMERA_BACK), rule("camera_height"))
        turnCamera(stand, focus)
        val knight = NPCs.add(knights().random(random), knightTile(stand), Direction.SOUTH)
        // An actor, not a hunter: it would go for the wanted killer early otherwise.
        knight.huntMode = ""
        knight.mode = EmptyMode
        freeze(killer)
        open("fade_in")
        appearance.hidden = true
        flagAppearance()
        delay(1)
        knight.say(line("murder"))
        // The knight is the only thing that moves. It steps up to the frozen killer; the camera
        // stays on the killer the whole way.
        knight.walkTo(stand)
        repeat(rule("approach_ticks").coerceAtLeast(1)) {
            delay(1)
            freeze(killer)
            turnCamera(killer.tile, focus)
        }
        knight.mode = PauseMode
        knight.face(killer)
        killer.face(knight)
        killer.say(line("plead"))
        delay(2)
        if (random.nextInt(100) < rule("forgive_percent")) {
            // Spared: it is handed back to its driver and carries on where it stood.
            knight.say(line("forgive"))
            delay(2)
            killer.say(pvpLine("forgiven"))
            delay(1)
            release(killer, stand)
            finish()
            return
        }
        knight.say(line("execute"))
        knight.anim(EXECUTION_ANIM)
        delay(1)
        killer.gfx(DUST)
        // The knight ends it in one hit - the knight strikes the killer, not the other way round.
        // The killer is still ARRESTED, so ZidionPvpBots' death handler confiscates its takings to
        // the treasury and disposes of it: that is the "then dispose", at the end, through the
        // bot's own lifecycle. EmptyMode first or it dies frozen.
        killer.mode = EmptyMode
        // God-tier one hit, one kill: the killer is the one struck (it is the receiver here - the
        // damaged party - with the knight as the source), and insta_kill on the knight bypasses
        // damage soak so a single blow is always lethal and the bot plays its full death.
        knight["insta_kill"] = true
        killer.directHit(knight, 10_000)
        shakeCamera(intensity = 2, type = 0, cycle = 0, movement = 3, speed = 3)
        delay(rule("execution_linger_ticks").coerceAtLeast(2))
        finish()
    }

    /** End the scene: fade, drop the camera, and put the watcher home for the real respawn. */
    private suspend fun Player.finish() {
        open("fade_out")
        delay(2)
        clearCamera()
        appearance.hidden = false
        flagAppearance()
        tele(respawnTile)
        delay(1)
        open("fade_in")
    }

    /** Back to the road, free to fight again. */
    private fun release(bot: Player, origin: Tile) {
        bot.mode = EmptyMode
        bot.tele(origin)
        bot.clear(ARRESTED)
        bot.stop(ZidionPvpBots.HOLD)
        // Back into the driver; its pinned activity re-assigns on the next tick and it fights on.
        if (bot.isBot) {
            manager.add(bot.bot)
        }
    }

    /** Accuse and attack; bots beg; judgement follows. */
    private fun engage(knight: NPC, offender: Player) {
        logger.info { "Knight ${knight.id} going for ${offender.accountName} (${knight.tile.distanceTo(offender.tile)} tiles)." }
        // Both sides must be in multi-way combat or Target.attackable refuses the knight when the
        // offender is already fighting someone ("that player is already under attack") - the bug
        // where the knight only speaks. The offender can have lost the flag mid-fight (it is only
        // re-asserted on movement); force it back here, since a wanted offender on the roads is
        // never in a safe zone.
        knight["in_multi_combat"] = true
        offender["in_multi_combat"] = true
        knight.say(line("murder"))
        knight.interactPlayer(offender, "Attack")

        // Bots beg; a real player is free to fight, run or talk their way out by surviving.
        if (offender.isBot) {
            World.queue("zidion_plead_${knight.index}", initialDelay = rule("plead_delay_ticks")) {
                if (!offender.dead) {
                    offender.say(line("plead"))
                }
            }
        }

        World.queue("zidion_judgement_${knight.index}", initialDelay = rule("judgement_ticks")) {
            judge(knight, offender)
        }
    }

    /**
     * Forgive or finish it. Forgiveness stops the knight attacking but leaves it standing until
     * its own despawn timer runs out, so the offender sees who spared them.
     */
    private fun judge(knight: NPC, offender: Player) {
        if (offender.dead) {
            knight.say(line("execute"))
            return
        }
        if (random.nextInt(100) < rule("forgive_percent")) {
            knight.say(line("forgive"))
            knight.mode = EmptyMode
            offender.stop(WANTED)
        } else {
            knight.say(line("execute"))
            knight.interactPlayer(offender, "Attack")
        }
    }

    private fun line(row: String): String {
        val lines = Tables.stringListOrNull("$LINES.$row.lines")
        if (lines.isNullOrEmpty()) {
            return ""
        }
        return lines.random(random)
    }

    private fun rule(row: String): Int = Tables.intOrNull("$RULES.$row.value") ?: 0

    /** A PvP bot line, from its own table. */
    private fun pvpLine(row: String): String = Tables.stringListOrNull("${ZidionPvpBots.LINES}.$row.lines")?.randomOrNull(random) ?: ""

    /** Knight npc ids, shared with the PvP bot escorts table so both spawn the same knights. */
    private fun knights(): List<String> = Tables.getOrNull(ZidionPvpBots.ESCORTS)?.rows()?.map { it.rowId }?.takeIf { it.isNotEmpty() } ?: listOf("zidion_knight")

    companion object {
        const val LINES = "zidion_knight_lines"
        const val RULES = "zidion_knight_rules"

        /** Set on a PvP bot the moment a knight has it; cleared when it dies. */
        const val ARRESTED = "zidion_arrested"

        /** Clock on anyone who attacked or killed a player on the roads; what the knights hunt. */
        const val WANTED = "zidion_wanted"
        const val LAW = "zidion_law"

        /** The white knight's own two-handed swing, and the random-event vanish puff. */
        const val EXECUTION_ANIM = "2h_chop"

        /** Tiles the camera sits back from the body, and the gap the knight appears at and steps in. */
        private const val CAMERA_BACK = 3
        private const val STEP_IN = 2
        private const val WATCH_BEHIND = 7
        const val DUST = "random_event_puff"
    }
}
