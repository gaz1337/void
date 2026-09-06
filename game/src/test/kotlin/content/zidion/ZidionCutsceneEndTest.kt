package content.zidion

import WorldTest
import content.quest.instance
import content.quest.startCutscene
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import world.gregs.voidps.engine.Contexts
import world.gregs.voidps.engine.data.Settings
import world.gregs.voidps.engine.entity.character.move.tele
import world.gregs.voidps.type.Tile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZidionCutsceneEndTest : WorldTest() {
    @Test
    fun `ending a cutscene puts the client back on the static map at home`() {
        val scene = Tile(3174, 3440)
        val (victim, _) = createClient("victim2", scene)
        tick(2)
        val cutscene = victim.startCutscene("zidion_end_test", scene.region)
        victim.tele(cutscene.convert(scene.add(7, -7)))
        tick(2)
        assertTrue(victim.viewport!!.dynamic, "client not switched to dynamic map inside the instance")
        val home = Tile(Settings["world.home.x", 0], Settings["world.home.y", 0], Settings["world.home.level", 0])
        cutscene.onEnd { victim.tele(home) }
        runBlocking(Contexts.Game) { cutscene.end() }
        tick(3)
        assertEquals(home, victim.tile, "victim not sent home")
        assertTrue(victim.instance() == null, "instance variable still set")
        assertTrue(!victim.viewport!!.dynamic, "client left in dynamic map mode after the cutscene")
    }
}
