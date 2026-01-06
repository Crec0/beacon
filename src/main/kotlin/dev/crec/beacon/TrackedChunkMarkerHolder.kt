package dev.crec.beacon

import eu.pb4.polymer.virtualentity.api.ElementHolder
import eu.pb4.polymer.virtualentity.api.elements.TextDisplayElement
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Display
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f

class TrackedChunkMarkersHolder(
    private val pos: ChunkPos,
    private val level: ServerLevel
) : ElementHolder() {
    private val blockCounts = Reference2IntOpenHashMap<BlockState>()
    private val countElements = Reference2ObjectOpenHashMap<BlockState, TextDisplayElement>()
    private val markerElements = Object2ObjectOpenHashMap<BlockPos, TextDisplayElement>()

    fun createMarkerElement(pos: BlockPos) {
        val element = TextDisplayElement().apply {
            text = Component.literal("!!").withStyle(Style.EMPTY.withBold(true))
            billboardMode = Display.BillboardConstraints.CENTER
            offset = Vec3(pos.x + 0.5, pos.y + 7.5, pos.z + 0.5)
            scale = Vector3f(2F, 2F, 2F)
            shadow = false
            viewRange = 64F
            seeThrough = true
        }
        this.markerElements[pos] = element
        this.addElement(element)
    }

    fun onBlockBroken(pos: BlockPos, block: BlockState) {
        val element = markerElements.remove(pos) ?: return
        this.removeElement(element)
        this.blockCounts.addTo(block, -1)
        this.updateCountElement(block)
    }

    private fun updateCountElement(block: BlockState) {
        val count = this.blockCounts.getInt(block)
        val element = this.countElements[block]
        if (count <= 0) {
            this.countElements.remove(block)
            this.removeElement(element)
        } else if (element != null) {
            element.text = Component.literal("...")
        }
    }
}

