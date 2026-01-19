package dev.crec.beacon

import eu.pb4.polymer.virtualentity.api.ElementHolder
import eu.pb4.polymer.virtualentity.api.attachment.ChunkAttachment
import eu.pb4.polymer.virtualentity.api.elements.TextDisplayElement
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Display
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f

class TrackedChunkMarkersHolder(
    private val chunkPos: ChunkPos,
    private val level: ServerLevel
) : ElementHolder() {
    private val blockCounts = Reference2IntOpenHashMap<Block>()
    private val countElements = Reference2ObjectOpenHashMap<Block, TextDisplayElement>()
    private val markerElements = Object2ObjectOpenHashMap<BlockPos, TextDisplayElement>()

    private var isWaypointPrinted = false

    init {
        val chunkOrigin = BlockPos(chunkPos.x shl 4, 0, chunkPos.z shl 4)
        this.attachment = ChunkAttachment.ofTicking(this, level, chunkOrigin)
    }

    fun createMarkerElement(
        source: CommandSourceStack,
        pos: BlockPos,
        block: Block,
        labelY: Int,
        printWaypoints: Boolean
    ) {
        val color = when (block) {
            Blocks.LAVA -> ChatFormatting.YELLOW
            Blocks.WATER -> ChatFormatting.AQUA
            else -> ChatFormatting.WHITE
        }
        val blockElement = TextDisplayElement().apply {
            text = Component.literal("!!").withStyle(Style.EMPTY.withBold(true).withColor(color))
            billboardMode = Display.BillboardConstraints.CENTER
            offset = Vec3((pos.x and 15).toDouble(), pos.y.toDouble() + 0.75, (pos.z and 15).toDouble())
            scale = Vector3f(2F, 2F, 2F)
            shadow = false
            defaultBackground = false
            viewRange = 64F
            seeThrough = true
        }
        this.markerElements[pos] = blockElement
        this.addElement(blockElement)

        val uniqueSize = this.countElements.size
        val blockCount = this.blockCounts.addTo(block, 1)

        val blockId = block.toId()
        this.countElements.computeIfAbsent(block) {
            val element = TextDisplayElement().apply {
                text = Component.literal("$blockId ($blockCount)").withStyle(Style.EMPTY.withBold(true))
                billboardMode = Display.BillboardConstraints.CENTER
                offset = Vec3(8.0, labelY + 0.5 * uniqueSize, 8.0)
                scale = Vector3f(2F, 2F, 2F)
                shadow = false
                defaultBackground = false
                viewRange = 64F
                seeThrough = true
            }
            this.addElement(element)
        }

        if (!isWaypointPrinted) {
            val dimension = level.dimension().location().path
            if (printWaypoints) {
                source.sendSystemMessage(
                    Component.literal("xaero-waypoint:$blockId:${blockId.first()}:${(chunkPos.x shl 4) + 8}:$labelY:${(chunkPos.z shl 4) + 8}:13:true:0:Internal-$dimension-waypoints:scarpet-destination"),
                )
            }
            isWaypointPrinted = true
        }

        this.updateCountElement(block)
    }

    fun onBlockBroken(pos: BlockPos, block: Block) {
        val element = markerElements.remove(pos) ?: return
        this.removeElement(element)
        this.blockCounts.addTo(block, -1)
        this.updateCountElement(block)
    }

    private fun updateCountElement(block: Block) {
        val count = this.blockCounts.getOrDefault(block, 0)
        val element = this.countElements[block]
        if (count <= 0) {
            this.countElements.remove(block)
            this.removeElement(element)
        } else if (element != null) {
            element.text = Component.literal("${block.toId()} ($count)")
        }
    }
}

