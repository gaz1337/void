package content.zidion

import WorldTest
import content.entity.combat.inCombat
import org.junit.jupiter.api.Test
import world.gregs.voidps.engine.GameLoop
import world.gregs.voidps.engine.client.variable.start
import world.gregs.voidps.engine.entity.character.player.skill.Skill
import world.gregs.voidps.engine.timer.setCurrentTime
import world.gregs.voidps.type.Tile
import world.gregs.voidps.type.setRandom
import kotlin.random.Random
import kotlin.test.assertTrue

/**
 * The knights against the real engine: a knight that spots a wanted player goes for them.
 */
class ZidionKnightsTest : WorldTest() {

    @Test
    fun `a knight hunts a wanted player down and kills them`() {
        setCurrentTime { 43_200_000L + GameLoop.tick * 600L }
        // The harness random rolls 0 for everything, which makes every hit a 0; use a real one.
        setRandom(Random(7))
        // Open ground north of the Grand Exchange wall, outside the safe zone.
        val offender = createPlayer(Tile(3170, 3520), "offender")
        repeat(2) { tick() }
        assertTrue(offender["in_pvp", false], "offender not flagged pvp by ZidionWorld")
        val knight = createNPC("zidion_knight", Tile(3178, 3520))
        repeat(3) { tick() }
        assertTrue(knight["in_multi_combat", false], "knight not flagged multi by ZidionKnights")
        assertTrue(!knight.inCombat, "a knight must leave the innocent alone")
        offender.start(ZidionKnights.WANTED, 100)
        var engaged = false
        var lowest = offender.levels.get(Skill.Constitution)
        repeat(25) {
            tick()
            engaged = engaged || knight.inCombat
            lowest = minOf(lowest, offender.levels.get(Skill.Constitution))
        }
        assertTrue(engaged, "the knight never went for the wanted player (tile ${knight.tile}, mode ${knight.mode::class.simpleName})")
        assertTrue(lowest == 0, "the knight engaged but did not kill: lowest hp $lowest")
    }
}
