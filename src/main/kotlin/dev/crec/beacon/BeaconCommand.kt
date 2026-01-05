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
import dev.crec.beacon.Beacon.gameRootDir
import dev.crec.beacon.Beacon.holoApi
import dev.crec.beacon.Beacon.logger
import dev.crec.beacon.Beacon.outputPath
import dev.crec.beacon.Beacon.scannerPath
import dev.crec.beacon.utils.argument
import dev.crec.beacon.utils.getDimensionPath
import dev.crec.beacon.utils.literal
import net.minecraft.Util
import net.minecraft.commands.CommandBuildContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.commands.arguments.blocks.BlockStateArgument
import net.minecraft.commands.arguments.blocks.BlockStateArgument.block
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.material.PushReaction
import java.lang.ProcessBuilder.Redirect
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.system.measureTimeMillis

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
            }
        )
    }

    private fun commonScanArguments(
        builder: ArgumentBuilder<CommandSourceStack, *>.() -> Unit
    ): RequiredArgumentBuilder<CommandSourceStack, *> {
        return argument("from", blockPos()).apply {
            argument("to", blockPos()) {
                argument("label_y", integer()) {
                    argument("print_waypoints", bool()) {
                        builder()
                    }
                }
            }
        }
    }

    private fun runBlockCommand(context: CommandContext<CommandSourceStack>): Int {
        val block = BlockStateArgument.getBlock(context, "block")
        val id = block.state.blockHolder.unwrapKey().get().location()
        val scannables = listOf(id.toString())
        return this.runBeaconScan(context, scannables, ScanType.Blocks)
    }

    private fun runAntiWorldEaterCommand(context: CommandContext<CommandSourceStack>): Int {
        val scannables = BuiltInRegistries.BLOCK.listElements().map { holder ->
            val id = holder.key().location().toString()
            val block = holder.value()
            if (block == Blocks.LAVA || block == Blocks.WATER || block == Blocks.BEDROCK) return@map null
            if (block.explosionResistance > 10) return@map id
            if (block.stateDefinition.properties.contains(BlockStateProperties.WATERLOGGED)) {
                return@map "$id[waterlogged=true]"
            }
            return@map null
        }.toList().filterNotNull()
        return this.runBeaconScan(context, scannables, ScanType.Blocks)
    }

    private fun runAntiQuarryCommand(context: CommandContext<CommandSourceStack>): Int {
        val scannables = BuiltInRegistries.BLOCK.listElements().filter { holder ->
            val block = holder.value()
            if (block.defaultDestroyTime() == -1.0F) {
                return@filter false
            }
            return@filter block is EntityBlock
                || block.defaultBlockState().pistonPushReaction == PushReaction.BLOCK
                || block == Blocks.OBSIDIAN
                || block == Blocks.CRYING_OBSIDIAN
                || block == Blocks.RESPAWN_ANCHOR
                || block == Blocks.REINFORCED_DEEPSLATE
                || block == Blocks.MOVING_PISTON
                || block == Blocks.PISTON_HEAD
        }.map { holder ->
            holder.key().location().toString()
        }.toList()
        return this.runBeaconScan(context, scannables, ScanType.Blocks)
    }

    @Suppress("unused")
    private fun runClearCommand(context: CommandContext<CommandSourceStack>): Int {
        holoApi.unregisterAllDisplays()
        holoApi.unregisterAllHolograms()
        return Command.SINGLE_SUCCESS
    }

    private fun runBeaconScan(
        context: CommandContext<CommandSourceStack>,
        scannables: List<String>,
        type: ScanType,
    ): Int {
        val fromPos = BlockPosArgument.getBlockPos(context, "from")
        val toPos = BlockPosArgument.getBlockPos(context, "to")
        val labelY = IntegerArgumentType.getInteger(context, "label_y")
        val printWaypoints = BoolArgumentType.getBool(context, "print_waypoints")

        val source = context.source
        val dimensionPath = source.server.getDimensionPath(source.level.dimension())
        val regionDir = dimensionPath.resolve("region").normalize()

        Util.ioPool().execute {
            val time = measureTimeMillis {
                scannerCommandBuilder(scannables, type, fromPos, toPos, regionDir)
            }
            processOutput(context, fromPos, toPos, labelY, time, printWaypoints)
        }
        return Command.SINGLE_SUCCESS
    }

    private fun scannerCommandBuilder(
        scannables: List<String>,
        type: ScanType,
        from: BlockPos,
        to: BlockPos,
        regions: Path
    ) {
        val cmd = mutableListOf("java", "-jar", scannerPath.toString())

        for (scannable in scannables) {
            cmd.add(type.arg())
            cmd.add(scannable)
        }

        val fromChunk = SectionPos.of(from).chunk()
        val toChunk = SectionPos.of(to).chunk()

        val minRx = min(fromChunk.regionX, toChunk.regionX)
        val maxRx = max(fromChunk.regionX, toChunk.regionX)
        val minRz = min(fromChunk.regionZ, toChunk.regionZ)
        val maxRz = max(fromChunk.regionZ, toChunk.regionZ)

        for (x in minRx..maxRx) {
            for (z in minRz..maxRz) {
                cmd.add("-p")
                cmd.add(regions.resolve("r.$x.$z.mca").toString())
            }
        }
        cmd.add("-o")
        cmd.add(outputPath.toString())

        // logger.info(cmd.toString())

        // TODO: Update this
        val proc = ProcessBuilder(*cmd.toTypedArray())
            .directory(gameRootDir.toFile())
            .redirectOutput(Redirect.PIPE)
            .redirectError(Redirect.PIPE)
            .start()

        proc.waitFor(2, TimeUnit.MINUTES)

        val stdout = proc.inputStream.bufferedReader().readText()
        logger.info(stdout)
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