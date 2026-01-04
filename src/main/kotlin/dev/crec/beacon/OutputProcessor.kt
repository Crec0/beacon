package dev.crec.beacon


//{
//    "needle": {
//        "class": "de.skyrising.mc.scanner.BlockState",
//        "id": {
//            "namespace": "minecraft",
//            "path": "obsidian"
//        }
//    },
//    "location": {
//        "class": "de.skyrising.mc.scanner.BlockPos",
//        "dimension": "overworld",
//        "x": 26,
//        "y": 64,
//        "z": -53
//    },
//    "count": 1
//}

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.brigadier.context.CommandContext
import dev.crec.beacon.Beacon.Companion.MOD_NAME
import dev.crec.beacon.Beacon.Companion.holoApi
import dev.crec.beacon.Beacon.Companion.outputPath
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import net.minecraft.commands.CommandSourceStack
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.ChatType
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.OutgoingChatMessage
import net.minecraft.world.level.block.state.BlockState
import kotlin.io.path.readText
import kotlin.math.max
import kotlin.math.min

private fun JsonElement.obj(): JsonObject? =
    takeIf(JsonElement::isJsonObject)?.asJsonObject

private fun JsonObject.obj(name: String): JsonObject? =
    get(name)?.takeIf(JsonElement::isJsonObject)?.asJsonObject

private fun JsonObject.str(name: String): String? =
    get(name)?.takeIf(JsonElement::isJsonPrimitive)?.asString

private fun JsonObject.int(name: String): Int? =
    get(name)?.takeIf(JsonElement::isJsonPrimitive)?.asInt

private fun JsonObject.arr(name: String): JsonArray? =
    get(name)?.takeIf(JsonElement::isJsonArray)?.asJsonArray

private fun inRange(x: Int, y: Int, z: Int, from: BlockPos, to: BlockPos): Boolean {
    val minX = min(from.x, to.x);
    val maxX = max(from.x, to.x)
    val minY = min(from.y, to.y);
    val maxY = max(from.y, to.y)
    val minZ = min(from.z, to.z);
    val maxZ = max(from.z, to.z)
    return x in minX..maxX && y in minY..maxY && z in minZ..maxZ
}

private fun inRange(x: Int, z: Int, from: BlockPos, to: BlockPos): Boolean {
    val minX = min(from.x, to.x);
    val maxX = max(from.x, to.x)
    val minZ = min(from.z, to.z);
    val maxZ = max(from.z, to.z)
    return x in minX..maxX && z in minZ..maxZ
}

fun processOutput(ctx: CommandContext<CommandSourceStack>, from: BlockPos, to: BlockPos, labelY: Int, time: Long, printWaypoints: Boolean) {
    val fileContents = outputPath.readText()
    val tree = JsonParser.parseString(fileContents)

    val elements: Iterable<JsonElement> = when {
        tree.isJsonArray -> tree.asJsonArray
        else -> emptyList()
    }

    if (!elements.iterator().hasNext()) {
        ctx.source.sendFailure(Component.literal("No blocks found in range $from to $to"))
        return
    }

    holoApi.unregisterAllDisplays()
    holoApi.unregisterAllHolograms()

    val blockTally = Object2IntOpenHashMap<String>()
    val locationTally = Object2IntOpenHashMap<String>()

    var holoCounter = 0
    for (elem in elements) {
        val obj = elem.obj() ?: continue
        val coordinates = obj.arr("lc") ?: continue
        val coordinateType = obj.str("lt") ?: continue
        val dimension = obj.str("di") ?: continue
        val count = obj.int("cn") ?: continue
        val id = obj.str("id") ?: continue

        when (coordinateType) {
            "BlockPos" -> {
                val (x, y, z) = coordinates.map { it.asInt }
                if (!inRange(x, y, z, from, to)) continue

                holoApi.createTextDisplay("obsidian$holoCounter") {
                    it.text("<b>!!</b>")
                    it.scale(2F, 2F, 2F)
                    it.backgroundColor("000000", 0)
                    it.billboardMode("center")
                    it.seeThrough(true)
                }

                val holo = holoApi.createHologramBuilder()
                    .world("minecraft:$dimension")
                    .addDisplay("obsidian$holoCounter")
                    .position(x.toFloat() + 0.5F, y.toFloat() + 0.75F, z.toFloat() + 0.5F)
                    .viewRange(64.toDouble())
                    .build()

                holoApi.registerHologram("obsidian$holoCounter", holo)

                blockTally.put(id, blockTally.getOrDefault(id, 0) + count)
                holoCounter += 1
            }

            "ChunkPos" -> {
                val (x, z) = listOf(coordinates.get(0), coordinates.get(1)).map { it.asInt * 16 + 8 }
                if (!inRange(x, z, from, to)) continue

                holoApi.createTextDisplay("$MOD_NAME$holoCounter") {
                    it.text("<b>$id ($count)</b>")
                    it.scale(2F, 2F, 2F)
                    it.backgroundColor("000000", 0)
                    it.billboardMode("center")
                    it.seeThrough(true)
                }

                val key = "$x,$labelY,$z"
                locationTally.put(key, locationTally.getOrDefault(key, 0) + 1)

                val holo = holoApi.createHologramBuilder()
                    .world("minecraft:$dimension")
                    .addDisplay("$MOD_NAME$holoCounter")
                    .position(x.toFloat() + 0.5F, labelY.toFloat() + (0.75F * locationTally.getOrDefault(key, 0)), z.toFloat() + 0.5F)
                    .viewRange(256.toDouble())
                    .build()

                holoApi.registerHologram("$MOD_NAME$holoCounter", holo)
                if (printWaypoints) {
                    ctx.source.sendSystemMessage(
                        Component.literal("xaero-waypoint:$id ($count):${id.first()}:$x:$labelY:$z:13:true:0:Internal-$dimension-waypoints:scarpet-destination"),
                    )
                }
                holoCounter += 1
            }
        }
    }

    if (blockTally.isEmpty()) {
        ctx.source.sendFailure(Component.literal("No blocks found in range $from to $to. Took ${time / 1000}s"))
    } else {
        ctx.source.sendSuccess(
            {
                val output = Component.literal("Summary of counter:")
                output.append("\n")
                blockTally.object2IntEntrySet()
                    .sortedBy { (_, count) -> -count }
                    .forEach { (name, count) ->
                        output.append("$name x $count")
                        output.append("\n")
                    }
                output.append("Found ${blockTally.values.sum()} matching blocks in range. Took ${time / 1000}s")
            },
            false
        )
    }
}





