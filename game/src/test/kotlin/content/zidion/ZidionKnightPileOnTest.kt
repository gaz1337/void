package content.zidion

import WorldTest
import content.entity.combat.Target
import content.entity.combat.attacker
import world.gregs.voidps.engine.client.instruction.handle.interactPlayer
import world.gregs.voidps.engine.client.variable.start
import org.junit.jupiter.api.Test
import world.gregs.voidps.engine.GameLoop
import world.gregs.voidps.engine.entity.character.player.Player
import world.gregs.voidps.engine.entity.character.player.skill.Skill
import world.gregs.voidps.engine.entity.character.player.skill.level.Level
import world.gregs.voidps.engine.entity.item.Item
import world.gregs.voidps.engine.inv.equipment
import world.gregs.voidps.engine.timer.setCurrentTime
import world.gregs.voidps.network.login.protocol.visual.update.player.EquipSlot
import world.gregs.voidps.type.Tile
import world.gregs.voidps.type.setRandom
import kotlin.random.Random
import kotlin.test.assertTrue

/**
 * The "knights only speak" and "second attacker never lands" bugs were both the single-combat gate:
 * a brawler that had lost its `in_multi_combat` flag became single-combat, so a knight (or a second
 * attacker) was refused with "already under attack". ZidionWorld now re-asserts the flag on both
 * sides at combat start, and the knight re-asserts it in engage, so pile-ons always work.
 */
class ZidionKnightPileOnTest : WorldTest() {

    private fun fighter(name: String, tile: Tile, weapon: String): Player {
        val p = createPlayer(tile, name)
        for (s in listOf(Skill.Attack, Skill.Strength, Skill.Defence)) {
            p.experience.set(s, Level.experience(s, 99)); p.levels.set(s, 99)
        }
        p.experience.set(Skill.Constitution, Level.experience(Skill.Constitution, 990)); p.levels.set(Skill.Constitution, 990)
        p.equipment.transaction { set(EquipSlot.Weapon.index, Item(weapon)) }
        return p
    }

    @Test
    fun `combat start re-asserts multi so a knight can pile onto an already-attacked target`() {
        setCurrentTime { 43_200_000L + GameLoop.tick * 600L }
        setRandom(Random(7))
        val offender = fighter("offender", Tile(3100, 3600), "abyssal_whip")
        val attacker = fighter("firstattacker", Tile(3101, 3600), "abyssal_whip")
        repeat(2) { tick() }
        // Simulate the flag having been lost mid-fight (the live cause of the bug).
        offender.clear("in_multi_combat")
        attacker.clear("in_multi_combat")
        // A first attacker piles on: combat start must re-assert multi on both sides.
        attacker.interactPlayer(offender, "Attack")
        repeat(3) { tick() }
        assertTrue(offender["in_multi_combat", false], "target's multi flag not re-asserted at combat start")
        assertTrue(attacker["in_multi_combat", false], "attacker's multi flag not re-asserted at combat start")

        // Make the offender plainly under attack, then a knight must still be allowed to attack it.
        offender.start("under_attack", 10)
        offender.attacker = attacker
        val knight = createNPC("zidion_knight", Tile(3103, 3600))
        repeat(3) { tick() }
        assertTrue(knight["in_multi_combat", false], "knight not multi on spawn")
        assertTrue(
            Target.attackable(knight, offender, message = false),
            "a knight is refused when the offender is already under attack (the pile-on bug)",
        )
    }
}
