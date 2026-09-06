package content.zidion

import world.gregs.voidps.engine.entity.character.Character
import world.gregs.voidps.engine.map.collision.Collisions
import world.gregs.voidps.type.Tile
import world.gregs.voidps.type.random

/**
 * Colourful bot chat. Each Zidion bot (pvp robbers, market traders) is given a persistent chat
 * colour the first time it speaks, so its overhead lines read like a real player using chat
 * colours rather than a wall of identical white text. The colour is stored on the bot
 * (`zidion_chat_colour`) so it stays the same for that bot's whole life.
 */
object ZidionChat {
    private val palette = listOf(
        "ff5555", "5cff5c", "5ca8ff", "ffd23f", "ff7bd5",
        "5cf0f0", "ff9d3f", "b98bff", "9dff9d", "ffffff",
    )

    fun colour(character: Character): String {
        val existing: String = character["zidion_chat_colour", ""]
        if (existing.isNotEmpty()) {
            return existing
        }
        val hex = palette.random(random)
        character["zidion_chat_colour"] = hex
        return hex
    }
}

/** Say [text] in this character's persistent Zidion chat colour. */
fun Character.colourSay(text: String) = say("<col=${ZidionChat.colour(this)}>$text</col>")

/**
 * A random WALKABLE tile inside the Grand Exchange, for placing festival bots and stalls. The GE
 * has a solid clerk platform in the middle (x~3154-3175, y~3479-3500) that is not walkable; the
 * players (and the festival) live in the ring and plazas around it. Collision-checking keeps
 * everything on that floor and out of the walls and the platform. Null if nothing free was found.
 */
fun randomGeFloorTile(): Tile? {
    repeat(60) {
        val x = 3148 + random.nextInt(38)
        val y = 3472 + random.nextInt(38)
        if (Collisions[x, y, 0] == 0) {
            return Tile(x, y)
        }
    }
    return null
}
