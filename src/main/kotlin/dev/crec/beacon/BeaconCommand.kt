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
import dev.crec.beacon.utils.collapseConnectedLavaThreats
import dev.crec.beacon.utils.findObsidianGenerationSpotsFlow
import dev.crec.beacon.utils.getDimensionPath
import dev.crec.beacon.utils.literal
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import net.minecraft.commands.CommandBuildContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.literal
import net.minecraft.commands.arguments.blocks.BlockStateArgument
import net.minecraft.commands.arguments.blocks.BlockStateArgument.block
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.Property
import net.minecraft.world.level.material.PushReaction
import java.nio.file.Path
import kotlin.io.path.notExists
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import de.skyrising.mc.scanner.BlockPos as ScannerBlockPos
import de.skyrising.mc.scanner.BlockState as BlockStateNeedle

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
                literal("obsidian-spots") {
                    commonScanArguments {
                        executes(::runObsidianSpots)
                    }
                }
                literal("clear") {
                    executes(::runClearCommand)
                }
            })
    }

    fun <S, T : ArgumentBuilder<S, T>> ArgumentBuilder<S, T>.commonScanArguments(
        block: RequiredArgumentBuilder<S, Int>.() -> Unit
    ) {
        argument("from", blockPos()) {
            argument("to", blockPos()) {
                argument("label_y", integer()) {
                    argument("print_waypoints", bool()) {
                        argument("display_limit", integer(0)) {
                            block()
                        }
                    }
                }
            }
        }
    }

    private fun BlockState.toNeedle(properties: Set<Property<*>> = setOf()): BlockStateNeedle {
        val id = this.blockHolder.unwrapKey().get().location()
        val properties = buildMap {
            for (property in properties) {
                set(property.name, this@toNeedle.getValue(property).toString())
            }
        }
        return BlockStateNeedle(Identifier(id.namespace, id.path), properties)
    }

    private fun runBlockCommand(ctx: CommandContext<CommandSourceStack>): Int {
        val blockInput = BlockStateArgument.getBlock(ctx, "block")
        val needle = blockInput.state.toNeedle(blockInput.definedProperties)
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
                needles.add(state.toNeedle())
                return@forEach
            }
            if (block.stateDefinition.properties.contains(BlockStateProperties.WATERLOGGED)
                && state.pistonPushReaction != PushReaction.DESTROY
            ) {
                val waterLoggedState = state.setValue(BlockStateProperties.WATERLOGGED, true)
                needles.add(waterLoggedState.toNeedle(setOf(BlockStateProperties.WATERLOGGED)))
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
                needles.add(state.toNeedle())
            }
        }
        return this.runBeaconScanCommand(ctx, needles, ScanType.Blocks)
    }

    private fun runObsidianSpots(ctx: CommandContext<CommandSourceStack>): Int {
        val fromPos = BlockPosArgument.getBlockPos(ctx, "from")
        val toPos = BlockPosArgument.getBlockPos(ctx, "to")
        val labelY = IntegerArgumentType.getInteger(ctx, "label_y")
        val printWaypoints = BoolArgumentType.getBool(ctx, "print_waypoints")
        val displayLimit = runCatching { IntegerArgumentType.getInteger(ctx, "display_limit") }.getOrNull() ?: 1000

        val needles = listOf(
            Blocks.LAVA
                .defaultBlockState()
                .setValue(BlockStateProperties.LEVEL, 0)
                .toNeedle(setOf(BlockStateProperties.LEVEL)),
            Blocks.WATER.defaultBlockState().toNeedle()
        )

        val source = ctx.source
        val dimensionPath = source.server.getDimensionPath(source.level.dimension())
        val dimensionName = source.level.dimension().location().path
        val regionDir = dimensionPath.resolve("region").normalize()

        val dispatcher = source.server.asCoroutineDispatcher()
        val scope = CoroutineScope(dispatcher + Job() + CoroutineName("BeaconScan"))
        scope.launch {
            source.server.saveEverything(true, false, true)
            delay(100L)

            Beacon.clear()

            val startExecutionTime = System.currentTimeMillis()
            val results = withContext(Dispatchers.Default) {
                runBeaconScan(needles, fromPos, toPos, dimensionName, regionDir)
            }
            val executionTime = (System.currentTimeMillis() - startExecutionTime).milliseconds

            var counter = 0
            val startObsidianTime = System.currentTimeMillis()
            val obsidianSpots = collapseConnectedLavaThreats(findObsidianGenerationSpotsFlow(results).toList())

            obsidianSpots.forEach { threat ->
                if (counter++ > displayLimit) return@forEach
                createMarker(threat.lavaPos, source, labelY, printWaypoints, Blocks.LAVA)
                createMarker(threat.waterPos, source, labelY, printWaypoints, Blocks.WATER)
            }
            val obsidianTime = (System.currentTimeMillis() - startObsidianTime).milliseconds

            source.sendSystemMessage(Component.literal("Took ${executionTime.inWholeMilliseconds} ms for scanning"))
            source.sendSystemMessage(Component.literal("Took ${obsidianTime.inWholeMilliseconds} ms for obsidian"))
            if (counter == 0) {
                source.sendFailure(Component.literal("No blocks found in range ${fromPos.toShortString()} to ${toPos.toShortString()}"))
            } else {
                source.sendSuccess(
                    {
                        val output = Component.literal("Summary of counter:")
                        output.append("\n")
                        output.append("Found $counter matching spots in range")
                    },
                    false
                )
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun createMarker(
        pos: ScannerBlockPos,
        source: CommandSourceStack,
        labelY: Int,
        printWaypoints: Boolean,
        block: Block
    ) {
        val chunkPos = ChunkPos(pos.sectionX, pos.sectionZ)
        val waterHolder = Beacon.beams.getOrPut(chunkPos) {
            TrackedChunkMarkersHolder(chunkPos, source.level)
        }
        val blockPos = BlockPos(pos.x, pos.y, pos.z)
        waterHolder.createMarkerElement(source, blockPos, block, labelY, printWaypoints)
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
        val displayLimit = runCatching { IntegerArgumentType.getInteger(ctx, "display_limit") }.getOrNull() ?: 1000

        val source = ctx.source
        val dimensionPath = source.server.getDimensionPath(source.level.dimension())
        val dimensionName = source.level.dimension().location().path
        val regionDir = dimensionPath.resolve("region").normalize()

        val dispatcher = source.server.asCoroutineDispatcher()
        val scope = CoroutineScope(dispatcher + Job() + CoroutineName("BeaconScan"))
        scope.launch {
            source.server.saveEverything(true, false, true)
            delay(100L)

            Beacon.clear()

            val startExecutionTime = System.currentTimeMillis()
            val results = withContext(Dispatchers.Default) {
                runBeaconScan(needles, fromPos, toPos, dimensionName, regionDir)
            }
            val executionTime = (System.currentTimeMillis() - startExecutionTime).milliseconds
            displayScanResults(source, fromPos, toPos, labelY, printWaypoints, results, displayLimit, executionTime)
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
        displayLimit: Int,
        executionTime: Duration
    ) {
        val blockTally = Object2IntOpenHashMap<Needle>()

        var counter = 0
        results.forEach { result ->
            if (counter++ > displayLimit) return@forEach

            val resultLocation = result.location
            if (resultLocation !is ScannerBlockPos) return@forEach

            blockTally.addTo(result.needle, result.count.toInt())

            val chunkPos = ChunkPos(resultLocation.sectionX, resultLocation.sectionZ)
            val holder = Beacon.beams.getOrPut(chunkPos) {
                TrackedChunkMarkersHolder(chunkPos, source.level)
            }
            val blockPos = BlockPos(resultLocation.x, resultLocation.y, resultLocation.z)
            val blockState = (result.needle as BlockStateNeedle).id

            val identifier = ResourceLocation.fromNamespaceAndPath(blockState.namespace, blockState.path)
            val block = BuiltInRegistries.BLOCK.getValue(identifier)

            holder.createMarkerElement(source, blockPos, block, labelY, printWaypoints)
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
                            output.append("${(needle as BlockStateNeedle).id} x $count")
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