package com.openroomai.app.data

import android.content.Context
import com.openroomai.app.model.Furniture
import com.openroomai.app.model.FurnitureType
import com.openroomai.app.model.Opening
import com.openroomai.app.model.OpeningType
import com.openroomai.app.model.Room
import com.openroomai.app.model.Vec2
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class RoomRepository(context: Context) {
    private val dir = File(context.filesDir, "rooms").apply { mkdirs() }
    private val json = JSON

    fun list(): List<Room> = dir.listFiles { f -> f.extension == "json" }
        ?.mapNotNull { runCatching { json.decodeFromString<Room>(it.readText()) }.getOrNull() }
        ?.sortedByDescending { it.updatedAt }
        ?: emptyList()

    fun save(room: Room): Room {
        val r = room.copy(updatedAt = System.currentTimeMillis())
        File(dir, "${r.id}.json").writeText(json.encodeToString(r))
        return r
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }

    companion object {
        /**
         * Rooms saved by earlier versions may carry values that no longer exist (retired goals);
         * coercing them to the defaults keeps those rooms loadable instead of silently dropping them.
         */
        val JSON = Json { ignoreUnknownKeys = true; coerceInputValues = true; prettyPrint = false }

        /** A deliberately awkward living room so people can see the magic without scanning. */
        fun demoRoom(): Room {
            val base = Room.rectangle("Demo living room", 4.6f, 3.8f)
            val furniture = listOf(
                Furniture(type = FurnitureType.SOFA, center = Vec2(2.3f, 1.9f), rotationDeg = 0f, name = "Sofa"),
                Furniture(type = FurnitureType.COFFEE_TABLE, center = Vec2(1.2f, 0.9f), rotationDeg = 90f, name = "Coffee table"),
                Furniture(type = FurnitureType.TV_STAND, center = Vec2(3.9f, 3.0f), rotationDeg = 30f, name = "TV stand"),
                Furniture(type = FurnitureType.BOOKSHELF, center = Vec2(1.0f, 3.55f), rotationDeg = 0f, name = "Bookshelf"),
                Furniture(type = FurnitureType.ARMCHAIR, center = Vec2(3.6f, 0.9f), rotationDeg = 45f, name = "Armchair"),
                Furniture(type = FurnitureType.PLANT, center = Vec2(2.4f, 3.1f), name = "Plant"),
                Furniture(type = FurnitureType.DESK, center = Vec2(0.55f, 2.2f), rotationDeg = 90f, name = "Desk"),
                Furniture(type = FurnitureType.CHAIR, center = Vec2(1.2f, 2.2f), rotationDeg = 270f, name = "Desk chair"),
            )
            val openings = listOf(
                Opening(type = OpeningType.DOOR, wallIndex = 0, t = 0.18f, width = 0.85f),
                Opening(type = OpeningType.WINDOW, wallIndex = 2, t = 0.5f, width = 1.4f),
                Opening(type = OpeningType.WINDOW, wallIndex = 1, t = 0.55f, width = 1.0f),
            )
            // Top of the plan faces roughly north, so the big window (Wall C) gets southern sun.
            return base.copy(furniture = furniture, openings = openings, northDeg = 10f, latitude = 40f)
        }
    }
}
