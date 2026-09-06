package content.zidion

import WorldTest
import org.junit.jupiter.api.Test
import world.gregs.voidps.engine.entity.character.move.tele
import world.gregs.voidps.type.Tile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Attack option is what lets a client send an attack at all. It must survive every script
 * that touches player options - the shipped wilderness scripts included - and come and go with
 * the safe zones.
 */
class ZidionWorldTest : WorldTest() {

    @Test
    fun `attack option survives a trip through the wilderness and the safe zones`() {
        val player = createPlayer(Tile(3170, 3520), "brawler")
        tick(2)
        assertTrue(player["in_pvp", false], "not flagged pvp on open ground")
        assertEquals(1, player.options.indexOf("Attack"), "no Attack option on open ground")
        // Into the real wilderness: WildernessIcons sets in_wilderness and its own Attack option.
        player.tele(Tile(3100, 3600))
        tick(2)
        assertTrue(player["in_wilderness", false], "wilderness flag not set by the shipped script")
        assertEquals(1, player.options.indexOf("Attack"), "no Attack option in the wilderness")
        // Back out: WildernessIcons removes the option while in_pvp stays true.
        player.tele(Tile(3170, 3520))
        tick(2)
        assertTrue(player["in_pvp", false])
        assertEquals(1, player.options.indexOf("Attack"), "Attack option lost after leaving the wilderness")
        // A safe zone takes it away, leaving one gives it back.
        player.tele(Tile(3165, 3487))
        tick(2)
        assertEquals(-1, player.options.indexOf("Attack"), "Attack option inside the Grand Exchange safe zone")
        assertTrue(!player["in_pvp", false], "still flagged pvp inside a safe zone")
        player.tele(Tile(3170, 3520))
        tick(2)
        assertEquals(1, player.options.indexOf("Attack"), "Attack option not restored on leaving the safe zone")
    }
}
