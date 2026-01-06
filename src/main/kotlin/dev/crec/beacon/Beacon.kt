package dev.crec.beacon

import com.google.common.collect.MapMaker
import com.mojang.logging.LogUtils
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.minecraft.world.level.ChunkPos
import org.slf4j.Logger
import java.util.concurrent.ConcurrentMap

object Beacon : ModInitializer {
    const val MOD_ID = "beacon"

    val logger: Logger = LogUtils.getLogger()
    val beacons: ConcurrentMap<ChunkPos, TrackedChunkMarkersHolder> = MapMaker()
        .weakValues()
        .makeMap()

    override fun onInitialize() {
        CommandRegistrationCallback.EVENT.register { dispatcher, ctx, _ ->
            BeaconCommand.register(dispatcher, ctx)
        }
        PlayerBlockBreakEvents.AFTER.register { world, player, pos, state, blockEntity ->
            val chunkPos = ChunkPos(pos)
            beacons[chunkPos]?.onBlockBroken(pos, state)
        }
    }

    fun clear() {
        for (holder in beacons.values) {
            holder.destroy()
        }
        beacons.clear()
    }
}
