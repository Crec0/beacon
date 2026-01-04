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

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.brigadier.context.CommandContext
import dev.crec.beacon.Beacon.Companion.holoApi
import dev.crec.beacon.Beacon.Companion.outputPath
import net.minecraft.commands.CommandSourceStack
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import kotlin.io.path.readText
import kotlin.math.max
import kotlin.math.min

private fun JsonObject.obj(name: String): JsonObject? =
    get(name)?.takeIf(JsonElement::isJsonObject)?.asJsonObject

private fun JsonObject.str(name: String): String? =
    get(name)?.takeIf(JsonElement::isJsonPrimitive)?.asString

private fun JsonObject.int(name: String): Int? =
    get(name)?.takeIf(JsonElement::isJsonPrimitive)?.asInt

private fun inRange(x: Int, y: Int, z: Int, from: BlockPos, to: BlockPos): Boolean {
    val minX = min(from.x, to.x);
    val maxX = max(from.x, to.x)
    val minY = min(from.y, to.y);
    val maxY = max(from.y, to.y)
    val minZ = min(from.z, to.z);
    val maxZ = max(from.z, to.z)
    return x in minX..maxX && y in minY..maxY && z in minZ..maxZ
}

fun processOutput(ctx: CommandContext<CommandSourceStack>, from: BlockPos, to: BlockPos, time: Long) {
    val fileContents = outputPath.readText()
    val tree = JsonParser.parseString(fileContents)

    val elements: Iterable<JsonElement> = when {
        tree.isJsonArray -> tree.asJsonArray
        tree.isJsonObject -> listOf(tree)
        else -> emptyList()
    }

    if (!elements.iterator().hasNext()) {
        ctx.source.sendFailure(Component.literal("No blocks found in range $from to $to"))
        return
    }

    var counter = 0

    holoApi.unregisterAllDisplays()
    holoApi.unregisterAllHolograms()

    for (elem in elements) {
        val obj = elem.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: continue

        val location = obj.obj("location") ?: continue
        val clazz = obj.str("class") ?: continue
        if (!clazz.endsWith("BlockPos")) continue

        val dimension = location.str("dimension") ?: "overworld"
        val x = location.int("x") ?: continue
        val y = location.int("y") ?: continue
        val z = location.int("z") ?: continue

        val count = obj.int("count") ?: continue

        if (!inRange(x, y, z, from, to)) continue

        counter += count

        holoApi.createTextDisplay("obsidian$counter") {
            it.text("<b>!!</b>")
            it.scale(2F, 2F, 2F)
            it.backgroundColor("000000", 0)
            it.billboardMode("center")
            it.seeThrough(true)
        }

        val holo = holoApi.createHologramBuilder()
            .world("minecraft:$dimension")
            .addDisplay("obsidian$counter")
            .position(x.toFloat() + 0.5F, y.toFloat() + 0.75F, z.toFloat() + 0.5F)
            .viewRange(256.toDouble())
            .build()

        holoApi.registerHologram("obsidian$counter", holo)
    }

    if (counter == 0) {
        ctx.source.sendFailure(Component.literal("No blocks found in range $from to $to"))
    } else {
        ctx.source.sendSuccess(
            { Component.literal("Total matching blocks in range $from..$to: $counter. Took ${time / 1000}s") },
            false
        )
    }
}





