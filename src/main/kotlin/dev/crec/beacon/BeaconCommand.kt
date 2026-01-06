package dev.crec.beacon

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.BoolArgumentType.bool
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType.integer
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import de.skyrising.mc.scanner.Identifier
import de.skyrising.mc.scanner.Needle
import de.skyrising.mc.scanner.RegionFile
import de.skyrising.mc.scanner.SearchResult
import dev.crec.beacon.utils.argument
import dev.crec.beacon.utils.getDimensionPath
import dev.crec.beacon.utils.literal
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import kotlinx.coroutines.*
import net.minecraft.commands.CommandBuildContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.literal
import net.minecraft.commands.arguments.blocks.BlockInput
import net.minecraft.commands.arguments.blocks.BlockStateArgument
import net.minecraft.commands.arguments.blocks.BlockStateArgument.block
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.material.PushReaction
import java.nio.file.Path
import kotlin.io.path.notExists
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import de.skyrising.mc.scanner.BlockPos as ScannerBlockPos
import de.skyrising.mc.scanner.BlockState as BlockStateNeedle
import de.skyrising.mc.scanner.ChunkPos as ScannerChunkPos

object BeaconCommand {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>, buildContext: CommandBuildContext) {
        dispatcher.register(
            literal("beacon").apply {
                literal("block") {
                    argument("block", block(buildContext)) {
                        commonScanArguments {
                            executes(::runBlockCommand)
                        }
                    }
                }
                literal("anti-world-eater") {
                    commonScanArguments {
                        executes(::runAntiWorldEaterCommand)
                    }
                }
                literal("anti-quarry") {
                    commonScanArguments {
                        executes(::runAntiQuarryCommand)
                    }
                }
                literal("clear") {
                    executes(::runClearCommand)
                }
            })
    }

    fun <S, T : ArgumentBuilder<S, T>> ArgumentBuilder<S, T>.commonScanArguments(
        block: RequiredArgumentBuilder<S, Boolean>.() -> Unit
    ) {
        argument("from", blockPos()) {
            argument("to", blockPos()) {
                argument("label_y", integer()) {
                    argument("print_waypoints", bool()) {
                        block()
                    }
                }
            }
        }
    }

    private fun BlockInput.toNeedle(): BlockStateNeedle {
        val block = this
        val id = block.state.blockHolder.unwrapKey().get().location()
        val properties = buildMap {
            for (property in block.definedProperties) {
                set(property.name, block.state.getValue(property).toString())
            }
        }
        return BlockStateNeedle(Identifier(id.namespace, id.path), properties)
    }

    private fun runBlockCommand(ctx: CommandContext<CommandSourceStack>): Int {
        val block = BlockStateArgument.getBlock(ctx, "block")
        val needle = block.toNeedle()
        return this.runBeaconScanCommand(ctx, listOf(needle), ScanType.Blocks)
    }

    private fun runAntiWorldEaterCommand(ctx: CommandContext<CommandSourceStack>): Int {
        val needles = mutableListOf<BlockStateNeedle>()
        BuiltInRegistries.BLOCK.listElements().forEach { holder ->
            val block = holder.value()
            val state = block.defaultBlockState()
            if (block == Blocks.LAVA || block == Blocks.WATER || block.defaultDestroyTime() == -1.0F) {
                return@forEach
            }
            if (block.explosionResistance > 10) {
                needles.add(BlockInput(state, setOf(), null).toNeedle())
                return@forEach
            }
            if (block.stateDefinition.properties.contains(BlockStateProperties.WATERLOGGED)
                && block.defaultBlockState().pistonPushReaction != PushReaction.DESTROY
            ) {
                needles.add(BlockInput(state, setOf(BlockStateProperties.WATERLOGGED), null).toNeedle())
            }
        }
        return this.runBeaconScanCommand(ctx, needles, ScanType.Blocks)
    }

    private fun runAntiQuarryCommand(ctx: CommandContext<CommandSourceStack>): Int {
        val needles = mutableListOf<BlockStateNeedle>()
        BuiltInRegistries.BLOCK.listElements().forEach { holder ->
            val block = holder.value()
            val state = block.defaultBlockState()
            if (block == Blocks.LAVA || block == Blocks.WATER || block.defaultDestroyTime() == -1.0F) {
                return@forEach
            }
            if (state.pistonPushReaction == PushReaction.BLOCK
                || block is EntityBlock
                || block == Blocks.OBSIDIAN
                || block == Blocks.CRYING_OBSIDIAN
                || block == Blocks.RESPAWN_ANCHOR
                || block == Blocks.REINFORCED_DEEPSLATE
                || block == Blocks.MOVING_PISTON
                || block == Blocks.PISTON_HEAD
            ) {
                needles.add(BlockInput(state, setOf(), null).toNeedle())
            }
        }
        return this.runBeaconScanCommand(ctx, needles, ScanType.Blocks)
    }

    @Suppress("unused")
    private fun runClearCommand(ctx: CommandContext<CommandSourceStack>): Int {
        Beacon.clear()
        return Command.SINGLE_SUCCESS
    }

    private fun runBeaconScanCommand(
        ctx: CommandContext<CommandSourceStack>,
        needles: List<Needle>,
        type: ScanType,
    ): Int {
        val fromPos = BlockPosArgument.getBlockPos(ctx, "from")
        val toPos = BlockPosArgument.getBlockPos(ctx, "to")
        val labelY = IntegerArgumentType.getInteger(ctx, "label_y")
        val printWaypoints = BoolArgumentType.getBool(ctx, "print_waypoints")

        val source = ctx.source
        val dimensionPath = source.server.getDimensionPath(source.level.dimension())
        val dimensionName = source.level.dimension().location().path
        val regionDir = dimensionPath.resolve("region").normalize()

        val dispatcher = source.server.asCoroutineDispatcher()
        val scope = CoroutineScope(dispatcher + Job() + CoroutineName("BeaconScan"))
        scope.launch {
            source.server.saveEverything(true, false, true)
            val startExecutionTime = System.currentTimeMillis()
            val results = withContext(Dispatchers.Default) {
                runBeaconScan(needles, fromPos, toPos, dimensionName, regionDir)
            }
            val executionTime = (System.currentTimeMillis() - startExecutionTime).milliseconds
            displayScanResults(source, fromPos, toPos, labelY, printWaypoints, results, executionTime)
        }
        return Command.SINGLE_SUCCESS
    }

    private suspend fun runBeaconScan(
        needles: List<Needle>, from: BlockPos, to: BlockPos, dimName: String, regionsDir: Path
    ): List<SearchResult> = coroutineScope {
        val scannerFromPos = ScannerBlockPos(dimName, min(from.x, to.x), min(from.y, to.y), min(from.z, to.z))
        val scannerToPos = ScannerBlockPos(dimName, max(from.x, to.x), max(from.y, to.y), max(from.z, to.z))
        val fromChunk = ChunkPos(from)
        val toChunk = ChunkPos(to)

        val minRx = min(fromChunk.regionX, toChunk.regionX)
        val maxRx = max(fromChunk.regionX, toChunk.regionX)
        val minRz = min(fromChunk.regionZ, toChunk.regionZ)
        val maxRz = max(fromChunk.regionZ, toChunk.regionZ)

        val regionFiles = buildList {
            for (x in minRx..maxRx) {
                for (z in minRz..maxRz) {
                    val regionFile = regionsDir.resolve("r.$x.$z.mca")
                    if (regionFile.notExists()) continue
                    add(RegionFile(regionFile))
                }
            }
        }

        regionFiles.map { file ->
            async { file.scanChunks(needles, false, scannerFromPos, scannerToPos) }
        }.awaitAll().flatten()
    }

    private fun displayScanResults(
        source: CommandSourceStack,
        fromPos: BlockPos,
        toPos: BlockPos,
        labelY: Int,
        printWaypoints: Boolean,
        results: List<SearchResult>,
        executionTime: Duration
    ) {
        val blockTally = Object2IntOpenHashMap<Needle>()
        Object2IntOpenHashMap<Needle>()

        results.forEach { result ->

            // collect the needles by ChunkPos, then for each chunk pos create an ElementHolder
            // and a ChunkAttachment. Then create TextDisplayElements for each needle in that
            // chunk and set their offset to their relative chunk position,
            // and set all the other relevant data. Add each element to the holder,
            // and add the holder to the attachment at the chunk origin

            when (result.location) {
                is ScannerBlockPos -> {
                    blockTally.addTo(result.needle, result.count.toInt())
                }

                is ScannerChunkPos -> {
//                    val (x, z) = listOf(coordinates.get(0), coordinates.get(1)).map { it.asInt * 16 + 8 }
//                    if (!inRange(x, z, from, to)) continue
//
//                    holoApi.createTextDisplay("$MOD_ID$holoCounter") {
//                        it.text("<b>$id ($count)</b>")
//                        it.scale(2F, 2F, 2F)
//                        it.backgroundColor("000000", 0)
//                        it.billboardMode("center")
//                        it.seeThrough(true)
//                    }
//
//                    val key = "$x,$labelY,$z"
//                    locationTally.put(key, locationTally.getOrDefault(key, 0) + 1)
//
//                    val holo = holoApi.createHologramBuilder()
//                        .world("minecraft:$dimension")
//                        .addDisplay("$MOD_ID$holoCounter")
//                        .position(x.toFloat() + 0.5F, labelY.toFloat() + (0.75F * locationTally.getOrDefault(key, 0)), z.toFloat() + 0.5F)
//                        .viewRange(256.toDouble())
//                        .build()
//
//                    holoApi.registerHologram("$MOD_ID$holoCounter", holo)
//                    if (printWaypoints) {
//                        ctx.source.sendSystemMessage(
//                            Component.literal("xaero-waypoint:$id ($count):${id.first()}:$x:$labelY:$z:13:true:0:Internal-$dimension-waypoints:scarpet-destination"),
//                        )
//                    }
                }

                else -> {}
            }
        }

        source.sendSystemMessage(Component.literal("Took ${executionTime.inWholeMilliseconds} ms"))
        if (blockTally.isEmpty()) {
            source.sendFailure(Component.literal("No blocks found in range $fromPos to $toPos"))
        } else {
            source.sendSuccess(
                {
                    val output = Component.literal("Summary of counter:")
                    output.append("\n")
                    blockTally.object2IntEntrySet()
                        .sortedBy { (_, count) -> -count }
                        .forEach { (needle, count) ->
                            output.append("$needle x $count")
                            output.append("\n")
                        }
                    output.append("Found ${blockTally.values.sum()} matching blocks in range")
                },
                false
            )
        }
    }

    private enum class ScanType {
        Items, Blocks;

        fun arg(): String {
            return when (this) {
                Items -> "-i"
                Blocks -> "-b"
            }
        }
    }
}