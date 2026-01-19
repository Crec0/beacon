package dev.crec.beacon.utils

import de.skyrising.mc.scanner.BlockPos
import de.skyrising.mc.scanner.BlockState
import de.skyrising.mc.scanner.SearchResult
import it.unimi.dsi.fastutil.ints.IntArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.abs

/**
 * Result containing a lava position and the nearest water position threatening it
 */
data class ObsidianThreat(
    val lavaPos: BlockPos,
    val waterPos: BlockPos
)

@OptIn(ExperimentalCoroutinesApi::class)
fun findObsidianGenerationSpotsFlow(
    blockStates: List<SearchResult>,
    maxHorizontalDistance: Int = 14,
    maxYDistance: Int = 14,
    parallelism: Int = Runtime.getRuntime().availableProcessors()
): Flow<ObsidianThreat> = flow {
    val (lavaList, waterList) = collectSourcesParallel(blockStates)
    if (lavaList.isEmpty() || waterList.isEmpty()) return@flow

    val waterIndex = OptimizedSpatialIndex3D(
        waterPositions = waterList,
        maxHorizontalDistance = maxHorizontalDistance,
        maxYDistance = maxYDistance
    )

    lavaList.asFlow()
        .buffer(2048)
        .flatMapMerge(parallelism) { lavaPos ->
            flow {
                waterIndex.findNearestWater(lavaPos.x, lavaPos.y, lavaPos.z)?.let { waterPos ->
                    emit(ObsidianThreat(lavaPos, waterPos))
                }
            }
        }
        .collect { emit(it) }
}

private suspend fun collectSourcesParallel(
    blockStates: List<SearchResult>
): Pair<List<BlockPos>, List<BlockPos>> = coroutineScope {
    val chunkSize = 10_000
    val chunks = blockStates.chunked(chunkSize)

    val results = chunks.map { chunk ->
        async(Dispatchers.Default) {
            val lava = mutableListOf<BlockPos>()
            val water = mutableListOf<BlockPos>()

            chunk.forEach { (needle, location) ->
                if (needle !is BlockState) return@forEach
                if (location !is BlockPos) return@forEach

                when (needle.id.path) {
                    "lava" -> lava.add(location)
                    "water" -> water.add(location)
                }
            }

            lava to water
        }
    }.awaitAll()

    val allLava = mutableListOf<BlockPos>()
    val allWater = mutableListOf<BlockPos>()
    results.forEach { (lava, water) ->
        allLava.addAll(lava)
        allWater.addAll(water)
    }
    allLava to allWater
}

private class OptimizedSpatialIndex3D(
    waterPositions: List<BlockPos>,
    private val maxHorizontalDistance: Int,
    private val maxYDistance: Int
) {
    private val cellSizeXZ = maxHorizontalDistance.coerceAtLeast(4)
    private val cellSizeY = maxYDistance.coerceAtLeast(4)

    private val cellRadiusXZ = (maxHorizontalDistance / cellSizeXZ) + 1
    private val cellRadiusY = (maxYDistance / cellSizeY) + 1

    private val maxHorizDistSq = maxHorizontalDistance * maxHorizontalDistance

    // XZ cell -> (Y cell -> [x,y,z, x,y,z, ...])
    private val grid = HashMap<Long, HashMap<Int, IntArrayList>>()

    init {
        for (pos in waterPositions) {
            val cx = Math.floorDiv(pos.x, cellSizeXZ)
            val cz = Math.floorDiv(pos.z, cellSizeXZ)
            val cy = Math.floorDiv(pos.y, cellSizeY)

            val xzKey = packXZ(cx, cz)
            val yMap = grid.getOrPut(xzKey) { HashMap() }
            val list = yMap.getOrPut(cy) { IntArrayList() }

            list.add(pos.x)
            list.add(pos.y)
            list.add(pos.z)
        }

        for (yMap in grid.values) {
            for (list in yMap.values) list.trim()
        }
    }

    /**
     * Find the nearest water position above the lava within distance constraints
     */
    fun findNearestWater(x: Int, y: Int, z: Int): BlockPos? {
        val baseCX = Math.floorDiv(x, cellSizeXZ)
        val baseCZ = Math.floorDiv(z, cellSizeXZ)
        val baseCY = Math.floorDiv(y, cellSizeY)

        var nearestWater: BlockPos? = null
        var nearestDistSq = Int.MAX_VALUE

        for (dxCell in -cellRadiusXZ..cellRadiusXZ) {
            val cx = baseCX + dxCell
            for (dzCell in -cellRadiusXZ..cellRadiusXZ) {
                val cz = baseCZ + dzCell
                val yMap = grid[packXZ(cx, cz)] ?: continue

                // Only check Y-cells at/above lava's Y-cell
                for (dyCell in 0..cellRadiusY) {
                    val cy = baseCY + dyCell
                    val positions = yMap[cy] ?: continue

                    var i = 0
                    val size = positions.size
                    while (i < size) {
                        val wx = positions.getInt(i)
                        val wy = positions.getInt(i + 1)
                        val wz = positions.getInt(i + 2)

                        // Water must be above lava, and within maxYDistance
                        val dyUp = wy - y
                        if (dyUp > 0 && dyUp <= maxYDistance) {
                            val dx = x - wx
                            val dz = z - wz
                            val horizDistSq = dx * dx + dz * dz

                            if (horizDistSq <= maxHorizDistSq) {
                                // Total 3D distance for comparison
                                val totalDistSq = horizDistSq + dyUp * dyUp
                                if (totalDistSq < nearestDistSq) {
                                    nearestDistSq = totalDistSq
                                    nearestWater = BlockPos("x", wx, wy, wz)
                                }
                            }
                        }

                        i += 3
                    }
                }
            }
        }

        return nearestWater
    }
}

private fun packXZ(x: Int, z: Int): Long =
    (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)



/**
 * Collapse connected lava sources to 1 representative per connected component.
 *
 * connectivity:
 *  - 6 = face-connected (recommended)
 *  - 26 = all neighbors in 3x3x3 cube (optional if you want diagonals to count)
 */
/**
 * Collapse connected lava into one representative per component,
 * keeping the nearest water to the component's centroid
 */
fun collapseConnectedLavaThreats(
    threats: List<ObsidianThreat>,
    connectivity: Int = 6
): List<ObsidianThreat> {
    if (threats.isEmpty()) return emptyList()

    val lavaPositions = threats.map { it.lavaPos }
    val lavaToWater = threats.associateBy({ it.lavaPos }, { it.waterPos })

    val lavaSet = lavaPositions.associateBy { packPosKey(it.x, it.y, it.z) }
    val visited = mutableSetOf<Long>()
    val queue = ArrayDeque<Long>()

    val collapsedThreats = mutableListOf<ObsidianThreat>()

    for (start in lavaPositions) {
        val startKey = packPosKey(start.x, start.y, start.z)
        if (startKey in visited) continue

        visited.add(startKey)
        queue.add(startKey)

        var sumX = 0L
        var sumY = 0L
        var sumZ = 0L
        var count = 0
        val componentKeys = mutableListOf<Long>()
        val allWaters = mutableListOf<BlockPos>()

        while (queue.isNotEmpty()) {
            val key = queue.removeFirst()
            val (x, y, z) = unpackPosKey(key)
            val pos = lavaSet[key]!!

            componentKeys.add(key)
            count++
            sumX += x.toLong()
            sumY += y.toLong()
            sumZ += z.toLong()

            // Collect water for this lava
            lavaToWater[pos]?.let { allWaters.add(it) }

            // BFS to neighbors
            if (connectivity == 6) {
                listOf(
                    packPosKey(x + 1, y, z), packPosKey(x - 1, y, z),
                    packPosKey(x, y + 1, z), packPosKey(x, y - 1, z),
                    packPosKey(x, y, z + 1), packPosKey(x, y, z - 1)
                ).forEach { nKey ->
                    if (nKey in lavaSet && visited.add(nKey)) {
                        queue.add(nKey)
                    }
                }
            } else {
                for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                    if (dx == 0 && dy == 0 && dz == 0) continue
                    val nKey = packPosKey(x + dx, y + dy, z + dz)
                    if (nKey in lavaSet && visited.add(nKey)) {
                        queue.add(nKey)
                    }
                }
            }
        }

        // Find closest lava to centroid
        val cx = (sumX / count).toInt()
        val cy = (sumY / count).toInt()
        val cz = (sumZ / count).toInt()

        var bestLavaKey = componentKeys[0]
        var bestLavaDist = Int.MAX_VALUE
        for (k in componentKeys) {
            val (x, y, z) = unpackPosKey(k)
            val d = abs(x - cx) + abs(y - cy) + abs(z - cz)
            if (d < bestLavaDist) {
                bestLavaDist = d
                bestLavaKey = k
            }
        }

        val (lx, ly, lz) = unpackPosKey(bestLavaKey)
        val repLava = BlockPos("w", lx, ly, lz)

        // Find nearest water to the centroid
        var nearestWater = allWaters[0]
        var nearestWaterDist = Int.MAX_VALUE
        for (water in allWaters) {
            val d = abs(water.x - cx) + abs(water.y - cy) + abs(water.z - cz)
            if (d < nearestWaterDist) {
                nearestWaterDist = d
                nearestWater = water
            }
        }

        collapsedThreats.add(ObsidianThreat(repLava, nearestWater))
    }

    return collapsedThreats
}

private fun packPosKey(x: Int, y: Int, z: Int): Long {
    val lx = (x.toLong() and 0x3FFFFFFL) shl 38
    val lz = (z.toLong() and 0x3FFFFFFL) shl 12
    val ly = (y.toLong() and 0xFFFL)
    return lx or lz or ly
}

private fun unpackPosKey(key: Long): Triple<Int, Int, Int> {
    val x = (key shr 38).toInt().let { if (it and (1 shl 25) != 0) it or (-1 shl 26) else it }
    val z = ((key shr 12) and 0x3FFFFFFL).toInt().let { if (it and (1 shl 25) != 0) it or (-1 shl 26) else it }
    val y = (key and 0xFFFL).toInt().let { if (it and (1 shl 11) != 0) it or (-1 shl 12) else it }
    return Triple(x, y, z)
}
