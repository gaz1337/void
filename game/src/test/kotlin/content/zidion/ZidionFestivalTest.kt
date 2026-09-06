package content.zidion

import WorldTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import world.gregs.voidps.type.Tile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The festival's token economy: earning is capped per day, spending is checked, and earning buys
 * Grand Draw entries. The stalls, host and draw are exercised live; this pins the shared logic.
 */
class ZidionFestivalTest : WorldTest() {

    @BeforeEach
    fun clearEntries() {
        ZidionFestival.entries.clear()
    }

    @AfterEach
    fun tidy() {
        ZidionFestival.entries.clear()
        ZidionFestival.override = null
    }

    @Test
    fun `earning tokens grants them, buys draw entries, and respects the daily cap`() {
        val player = createPlayer(Tile(3165, 3487), "reveller")
        tick()
        val cap = 3000 // zidion_jubilee_rules.token_daily_cap
        ZidionFestival.award(player, 100)
        assertEquals(100, ZidionFestival.tokens(player), "tokens not granted")
        assertEquals(100, ZidionFestival.entries[player.accountName], "earning did not buy a draw entry")

        // Blow past the daily cap: only the remainder is granted.
        ZidionFestival.award(player, cap)
        assertEquals(cap, ZidionFestival.tokens(player), "daily cap not enforced: ${ZidionFestival.tokens(player)}")
        // Further earning past the cap grants nothing.
        ZidionFestival.award(player, 500)
        assertEquals(cap, ZidionFestival.tokens(player), "granted tokens beyond the daily cap")
    }

    @Test
    fun `spending checks the balance`() {
        val player = createPlayer(Tile(3165, 3487), "shopper")
        tick()
        ZidionFestival.award(player, 200)
        assertFalse(ZidionFestival.spend(player, 500), "spent tokens the player did not have")
        assertEquals(200, ZidionFestival.tokens(player))
        assertTrue(ZidionFestival.spend(player, 150), "could not spend affordable tokens")
        assertEquals(50, ZidionFestival.tokens(player), "spend did not deduct correctly")
    }
}
