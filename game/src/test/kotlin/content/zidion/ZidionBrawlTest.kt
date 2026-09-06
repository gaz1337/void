package content.zidion

import WorldTest
import content.entity.combat.damageDealers
import org.junit.jupiter.api.Test
import world.gregs.voidps.engine.GameLoop
import world.gregs.voidps.engine.client.instruction.handle.interactPlayer
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
 * A brawl is many-on-many. Two fighters trading blows must keep hurting each other after a third
 * joins in from range; the single-combat rule ("that player is already under attack") is what
 * breaks that, and ZidionWorld keeps everyone on open ground in multi-way combat.
 */
class ZidionBrawlTest : WorldTest() {

    private fun fighter(name: String, tile: Tile, weapon: String, ammo: String? = null): Player {
        val player = createPlayer(tile, name)
        for (skill in listOf(Skill.Attack, Skill.Strength, Skill.Defence, Skill.Ranged, Skill.Magic)) {
            player.experience.set(skill, Level.experience(skill, 99))
            player.levels.set(skill, 99)
        }
        player.experience.set(Skill.Constitution, Level.experience(Skill.Constitution, 990))
        player.levels.set(Skill.Constitution, 990)
        player.equipment.transaction {
            set(EquipSlot.Weapon.index, Item(weapon))
            if (ammo != null) {
                set(EquipSlot.Ammo.index, Item(ammo, 500))
            }
        }
        return player
    }

    @Test
    fun `two melee fighters keep hurting each other after a third joins from range`() {
        setCurrentTime { 43_200_000L + GameLoop.tick * 600L }
        setRandom(Random(7))
        val a = fighter("brawl_a", Tile(3170, 3520), "abyssal_whip")
        val b = fighter("brawl_b", Tile(3171, 3520), "abyssal_whip")
        tick(2)
        assertTrue(a["in_multi_combat", false] && b["in_multi_combat", false], "open ground is not multi-way")
        a.interactPlayer(b, "Attack")
        b.interactPlayer(a, "Attack")
        repeat(12) { tick() }
        val aOnB = b.damageDealers[a] ?: 0
        val bOnA = a.damageDealers[b] ?: 0
        assertTrue(aOnB > 0 && bOnA > 0, "the pair never hurt each other: a->b $aOnB, b->a $bOnA")

        val c = fighter("brawl_c", Tile(3176, 3520), "magic_shortbow", "rune_arrow")
        c.interactPlayer(a, "Attack")
        repeat(25) { tick() }
        val cOnA = a.damageDealers[c] ?: 0
        assertTrue(cOnA > 0, "the ranger never hit from range (tile ${c.tile}, mode ${c.mode::class.simpleName})")
        val aOnBAfter = b.damageDealers[a] ?: 0
        val bOnAAfter = a.damageDealers[b] ?: 0
        assertTrue(aOnBAfter > aOnB, "a stopped hurting b once c joined: $aOnB -> $aOnBAfter (mode ${a.mode::class.simpleName})")
        assertTrue(bOnAAfter > bOnA, "b stopped hurting a once c joined: $bOnA -> $bOnAAfter (mode ${b.mode::class.simpleName})")
    }
}
