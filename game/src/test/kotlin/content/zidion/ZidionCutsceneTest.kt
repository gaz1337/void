package content.zidion

import WorldTest
import content.quest.startCutscene
import org.junit.jupiter.api.Test
import world.gregs.voidps.engine.entity.character.mode.PauseMode
import world.gregs.voidps.engine.entity.character.move.tele
import world.gregs.voidps.type.Tile
import kotlin.test.assertTrue

/**
 * The execution scene's one hard requirement: the victim's client must actually be able to see
 * the killer standing in the instance.
 */
class ZidionCutsceneTest : WorldTest() {

    @Test
    fun `a player moved into a cutscene instance is drawn for the victim`() {
        val scene = Tile(3174, 3440)
        val (victim, _) = createClient("victim", scene)
        val killer = createPlayer(Tile(3175, 3440), "killer")
        tick(2)
        val cutscene = victim.startCutscene("zidion_test", scene.region)
        // Same geometry as ZidionKnights.Stage: victim behind the camera, killer on the dock.
        val dock = cutscene.convert(scene.add(1, 0))
        val wings = cutscene.convert(scene.add(7, -7))
        killer.tele(dock)
        killer.mode = PauseMode
        victim.tele(wings)
        victim.viewport?.loaded = true
        tick(3)
        val viewport = victim.viewport!!
        val locals = viewport.players.locals.take(viewport.players.localCount)
        assertTrue(killer.tile == dock, "killer not on the dock: ${killer.tile}")
        assertTrue(victim.tile == wings, "victim not in the wings: ${victim.tile}")
        assertTrue(killer.index in locals, "victim's client does not track the killer (locals=$locals, killer index ${killer.index}, distance ${victim.tile.distanceTo(killer.tile)})")
    }
}
