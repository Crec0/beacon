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
import dev.crec.beacon.Beacon.holoApi
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
        holoApi.unregisterAllDisplays()
        holoApi.unregisterAllHolograms()
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
        val regionDir = dimensionPath.resolve("region").normalize()

        val dispatcher = source.server.asCoroutineDispatcher()
        val scope = CoroutineScope(dispatcher + Job() + CoroutineName("BeaconScan"))
        scope.launch {
            val startExecutionTime = System.currentTimeMillis()
            val results = withContext(Dispatchers.Default) {
                runBeaconScan(needles, fromPos, toPos, regionDir)
            }
            val executionTime = (System.currentTimeMillis() - startExecutionTime).milliseconds
            displayScanResults(source, fromPos, toPos, labelY, printWaypoints, results, executionTime)
        }
        return Command.SINGLE_SUCCESS
    }

    private suspend fun runBeaconScan(
        needles: List<Needle>, fromPos: BlockPos, toPos: BlockPos, regionsDir: Path
    ): List<SearchResult> = coroutineScope {
        val fromChunk = ChunkPos(fromPos)
        val toChunk = ChunkPos(toPos)

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

        // TODO: Filter results that are within the search area
        regionFiles.map { file ->
            async { file.scan(needles, false) }
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
        holoApi.unregisterAllDisplays()
        holoApi.unregisterAllHolograms()

        val blockTally = Object2IntOpenHashMap<Needle>()
        val locationTally = Object2IntOpenHashMap<Needle>()

        var holoCounter = 0

        results.forEach { result ->
            when (result.location) {
                is ScannerBlockPos -> {
                    blockTally.addTo(result.needle, result.count.toInt())
                }
                is ScannerChunkPos -> {}
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