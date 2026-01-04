package dev.crec.beacon

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.BoolArgumentType.bool
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType.integer
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import dev.crec.beacon.Beacon.Companion.gameRootDir
import dev.crec.beacon.Beacon.Companion.holoApi
import dev.crec.beacon.Beacon.Companion.logger
import dev.crec.beacon.Beacon.Companion.outputPath
import dev.crec.beacon.Beacon.Companion.scannerPath
import net.minecraft.commands.CommandBuildContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.commands.arguments.blocks.BlockStateArgument
import net.minecraft.commands.arguments.blocks.BlockStateArgument.block
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos
import net.minecraft.commands.arguments.coordinates.Coordinates
import net.minecraft.commands.arguments.item.ItemArgument
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.material.PushReaction
import net.minecraft.world.level.storage.LevelResource
import java.lang.ProcessBuilder.Redirect
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.system.measureTimeMillis

class Command {
    enum class CommandType {
        BLOCK,
        ITEM,
        WORLD_EATER,
        QUARRY
    }

    val allItems: List<Item>
    val allBlocks: List<BlockState>
    val antiWorldEaterBlocks: List<String>
    val antiQuarryBlocks: List<BlockState>


    constructor() {
        this.allItems = BuiltInRegistries.ITEM.listElements().map { it.value() }.toList()
        this.allBlocks = BuiltInRegistries.BLOCK.listElements().map { it.value().defaultBlockState() }.toList()

        this.antiWorldEaterBlocks = allBlocks.map { state ->
            val blockName = state.block.descriptionId.split(".").reversed()[0]
            if (state.`is`(Blocks.LAVA) || state.`is`(Blocks.WATER) || state.`is`(Blocks.BEDROCK)) return@map null
            if (state.block.explosionResistance > 10) return@map blockName
            if (state.hasProperty(BlockStateProperties.WATERLOGGED)){
                return@map "$blockName[waterlogged=true]"
            }
            return@map null
        }.filterNotNull()

        this.antiQuarryBlocks = allBlocks.filter { state ->
            val block = state.block
            if (block.defaultDestroyTime() == -1.0F) {
                return@filter false
            }
            return@filter state.hasBlockEntity()
                || state.pistonPushReaction == PushReaction.BLOCK
                || block == Blocks.OBSIDIAN
                || block == Blocks.CRYING_OBSIDIAN
                || block == Blocks.RESPAWN_ANCHOR
                || block == Blocks.REINFORCED_DEEPSLATE
                || block == Blocks.MOVING_PISTON
                || block == Blocks.PISTON_HEAD
        }
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>, ctx: CommandBuildContext) {
        dispatcher.register(
            literal("beacon")
                .then(argument("block", block(ctx))
                    .then(blockPosRangeArgument(CommandType.BLOCK))
                )
                .then(literal("anti-world-eater")
                    .then(blockPosRangeArgument(CommandType.WORLD_EATER))
                )
                .then(literal("clear").executes {
                    holoApi.unregisterAllDisplays()
                    holoApi.unregisterAllHolograms()
                })
//                .then(literal("anti-quarry")
//                    .then(blockPosRangeArgument(CommandType.QUARRY))
//                )
        )
    }

    private fun blockPosRangeArgument(commandType: CommandType): RequiredArgumentBuilder<CommandSourceStack, Coordinates> =
        argument("from", blockPos())
            .then(argument("to", blockPos())
                .then(argument("label_y", integer())
                    .then(argument("print_waypoints", bool())
                        .executes { ctx ->
                            scanAndOutput(ctx, commandType)
                        }
                    )
                )
            )

    private fun scanAndOutput(ctx: CommandContext<CommandSourceStack>, cmdType: CommandType): Int {
        val fromPos = BlockPosArgument.getBlockPos(ctx, "from")
        val toPos = BlockPosArgument.getBlockPos(ctx, "to")
        val labelY = IntegerArgumentType.getInteger(ctx, "label_y")
        val printWaypoints = BoolArgumentType.getBool(ctx, "print_waypoints")

        val list = when (cmdType) {
            CommandType.BLOCK -> listOf(BlockStateArgument.getBlock(ctx, "block").state.block)
            CommandType.ITEM -> listOf(ItemArgument.getItem(ctx, "item"))
            CommandType.QUARRY -> antiQuarryBlocks
            CommandType.WORLD_EATER -> antiWorldEaterBlocks
        }

        val worldPath = ctx.source.server.getWorldPath(LevelResource.ROOT)
        val dimensionPath = worldPath.resolve(mapDim(ctx.source.level.dimension().location().path))
        val regionDir = dimensionPath.resolve("region").normalize()

        Thread {
            val time = measureTimeMillis {
                scannerCommandBuilder(list, cmdType, fromPos, toPos, regionDir)
            }
            processOutput(ctx, fromPos, toPos, labelY, time, printWaypoints)
        }.start()

        return 0
    }

    private fun mapDim(dim: String) =
        when (dim) {
            "overworld" -> "."
            "the_nether" -> "DIM-1"
            "the_end" -> "DIM1"
            else -> throw IllegalArgumentException("Unknown dimension $dim")
        }

    // Using generic just because I want to use it for both Item and Block types, I cant figure out a proper way to union without it
    private fun <T> scannerCommandBuilder(
        list: List<T>,
        cmdType: CommandType,
        fromPos: BlockPos,
        toPos: BlockPos,
        regionDir: Path
    ) {
        val cmd = mutableListOf("java", "-jar", scannerPath.toString())

        val argName = if (cmdType == CommandType.ITEM) "-i" else "-b"
        list.forEach {
            val name = when (cmdType) {
                CommandType.ITEM -> BuiltInRegistries.ITEM.getKey(it as Item).path
                CommandType.WORLD_EATER -> it.toString()
                CommandType.BLOCK, CommandType.QUARRY -> (it as BlockState).toString()
            }
            cmd.add(argName)
            cmd.add(name)
        }

        val fromChunk = SectionPos.of(fromPos).chunk()
        val toChunk = SectionPos.of(toPos).chunk()

        val minRx = min(fromChunk.regionX, toChunk.regionX)
        val maxRx = max(fromChunk.regionX, toChunk.regionX)
        val minRz = min(fromChunk.regionZ, toChunk.regionZ)
        val maxRz = max(fromChunk.regionZ, toChunk.regionZ)

        for (x in minRx..maxRx) {
            for (z in minRz..maxRz) {
                cmd.add("-p")
                cmd.add(regionDir.resolve("r.$x.$z.mca").toString())
            }
        }
        cmd.add("-o")
        cmd.add(outputPath.toString())

//        logger.info(cmd.toString())

        val proc = ProcessBuilder(*cmd.toTypedArray())
            .directory(gameRootDir.toFile())
            .redirectOutput(Redirect.PIPE)
            .redirectError(Redirect.PIPE)
            .start()

        proc.waitFor(2, TimeUnit.MINUTES)

        val stdout = proc.inputStream.bufferedReader().readText()
        logger.info(stdout)
    }
}