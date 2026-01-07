package dev.crec.beacon

import com.google.common.collect.MapMaker
import com.mojang.logging.LogUtils
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.state.BlockState
import org.slf4j.Logger
import java.util.concurrent.ConcurrentMap

object Beacon : ModInitializer {
    const val MOD_ID = "beacon"

    val logger: Logger = LogUtils.getLogger()
    val beams: ConcurrentMap<ChunkPos, TrackedChunkMarkersHolder> = MapMaker()
        .weakValues()
        .makeMap()

    override fun onInitialize() {
        CommandRegistrationCallback.EVENT.register { dispatcher, ctx, _ ->
            BeaconCommand.register(dispatcher, ctx)
        }
        PlayerBlockBreakEvents.AFTER.register { world, player, pos, state, blockEntity ->
            val chunkPos = ChunkPos(pos)
            beams[chunkPos]?.onBlockBroken(pos, state.toId())
        }
    }

    fun clear() {
        for (holder in beams.values) {
            holder.destroy()
        }
        beams.clear()
    }
}

fun BlockState.toId(): String = this.blockHolder.unwrapKey().map { it.location().path }.orElse("")