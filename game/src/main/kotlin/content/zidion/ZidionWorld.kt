package content.zidion

import content.area.wilderness.inMultiCombat
import content.area.wilderness.inPvp
import content.bot.isBot
import world.gregs.voidps.engine.Script
import world.gregs.voidps.engine.data.definition.Areas
import world.gregs.voidps.engine.entity.character.player.Player

/**
 * Makes the whole world player-vs-player, except the areas tagged `safe_zone`.
 *
 * This is done per-player rather than by widening the `[wilderness]` area, for two concrete
 * reasons found by reading the engine:
 *
 *  1. **Cost.** `Areas` indexes an area into the zone map for every level when no `level` is set:
 *     a world-sized rectangle is ~350x262 zones x 4 levels, roughly 367,000 hash-set entries
 *     held for the life of the server.
 *
 *  2. **Correctness.** `Target.attackable` applies a combat-level bracket to anyone who is
 *     `inWilderness`, computed from `wildernessLevel` - which is hardcoded to the real
 *     wilderness tile ranges and is therefore 0 everywhere else. At wilderness level 0 the
 *     bracket is roughly +/-(5 + level/10), so a level 3 player and a level 138 bot could never
 *     touch each other. Flagging `in_pvp` instead skips that bracket entirely: the check is
 *     `if (target.inWilderness)`, and these players are not.
 *
 * Consequence worth knowing: `inFullPvp` stays false outside the genuine wilderness, so deaths
 * out here follow normal rules (keep 3 items) rather than dropping everything. Zidion PvP bots
 * take what they take by looting the pile, not by the engine handing them a full kit.
 */
class ZidionWorld : Script {

    private val safeZones = Areas.tagged("safe_zone")

    init {
        playerSpawn {
            applyPvp()
        }

        moved {
            applyPvp()
        }

        // The shipped MultiCombat script clears in_multi_combat in its own exited("*") handler,
        // which runs AFTER `moved` for the same step (Movement.move fires moved first, then the
        // area exit/enter events). Re-asserting here, registered after it, keeps a player who
        // then stands still - a bot mid-fight, say - in multi-way combat.
        entered("*") {
            applyPvp()
        }

        exited("*") {
            applyPvp()
        }
        // The shipped WildernessIcons script removes the Attack option outright when a player
        // leaves the real wilderness (variableSet("in_wilderness") -> options.remove("Attack")).
        // Registered after it, this puts the option straight back for anyone still in pvp.
        variableSet("in_wilderness") { _, _, _ ->
            applyPvp()
        }
        // A fight can stand still, and in_multi_combat is otherwise only re-asserted on movement -
        // so a stationary brawler could lose it and become single-combat, refusing extra attackers.
        // Re-assert both sides the moment combat starts, so pile-ons always work.
        combatStart { target ->
            applyPvp()
            (target as? Player)?.applyPvp()
        }
    }

    private fun Player.applyPvp() {
        // Ordinary skilling bots are deliberately excluded. DynamicResolvers returns no
        // equipment or inventory resolver for a player who is inPvp:
        //     is BotEquipmentSetup -> if (player.inPvp) null else resolveEquipment(...)
        // so flagging the whole bot population would stop every woodcutter ever fetching a
        // hatchet again. Zidion's own bots carry a `zidion_role` and opt in.
        if (isBot && !contains("zidion_role")) {
            return
        }
        // Duellists put on a show inside the safe-zone Grand Exchange: keep them pvp-flagged
        // everywhere so they can fight there. Real players are never in pvp in a safe zone, so a
        // duellist can neither hit nor be hit by one - only its opponent.
        if (contains("zidion_duel")) {
            if (!inPvp) {
                set("in_pvp", true)
            }
            if (!inMultiCombat) {
                set("in_multi_combat", true)
            }
            return
        }
        // Same pair the shipped Clan Wars arenas use: the flag is what Target.attackable checks,
        // the option is what lets a client (or a bot, whose interactions are validated against
        // its own option list) send an attack at all. One without the other is a world where
        // everybody faces each other and nothing happens.
        val safe = safeZones.any { tile in it.area }
        if (safe) {
            if (inPvp) {
                clear("in_pvp")
            }
            if (inMultiCombat) {
                clear("in_multi_combat")
            }
            options.remove("Attack")
            return
        }
        if (!inPvp) {
            set("in_pvp", true)
        }
        // Multi-way combat, or Target.attackable refuses anyone who is already fighting someone
        // else ("That player is already under attack" / "You are already in combat") and a brawl
        // of twenty collapses into pairs. Re-asserted on every move because the shipped
        // MultiCombat script clears the flag whenever a player leaves any area that is not
        // tagged multi_combat.
        if (!inMultiCombat) {
            set("in_multi_combat", true)
        }
        // The option is checked on its own, never inferred from the flag: other scripts remove it
        // while the flag stays (WildernessIcons on leaving the wilderness), and PlayerOptions is
        // rebuilt empty on every login. A player who is in pvp but cannot attack is the bug this
        // guards against.
        if (options.indexOf("Attack") == -1) {
            if (options.has(1)) {
                options.remove(1)
            }
            options.set(1, "Attack")
        }
    }
}
