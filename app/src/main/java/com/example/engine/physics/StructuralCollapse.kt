package com.example.engine.physics

import com.example.engine.terrain.ChunkedTerrain
import com.example.model.MaterialProfiles
import com.example.model.MaterialSoundKind
import com.example.model.MaterialTile
import com.example.model.Quat
import com.example.model.Vec3
import com.example.model.clamp
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Load paths, cantilever checks, and the staggered collapse queue.
 */

internal fun StructuralWorld.materialLoadSafetyFactor(tileId: Int): Float = when (tileId) {
        MaterialTile.STEEL -> 3.15f
        MaterialTile.CONCRETE, MaterialTile.RIVER_STONE -> 2.45f
        MaterialTile.BRICK, MaterialTile.STUCCO, MaterialTile.ROOF_SLATE, MaterialTile.CORRUGATED_METAL -> 2.25f
        MaterialTile.TIMBER, MaterialTile.STAIR_TREAD -> 2.05f
        else -> 1.85f
    }

internal fun StructuralWorld.blockDeadMass(block: StructuralBlock): Float =
        estimateShardMass(block.poly, block.tileId)

internal fun StructuralWorld.propagateStructuralLoads(activeFilter: ((StructuralBlock) -> Boolean)? = null) {
        for (b in blocks.values) {
            b.sustainedLoad = blockDeadMass(b)
        }
        val sortedDesc = blocks.values.sortedByDescending { it.center.y }
        for (i in sortedDesc.indices) {
            val b = sortedDesc[i]
            if (activeFilter != null && !activeFilter(b)) continue
            if (b.groundAnchored || b.supportIds.isEmpty()) continue
            var validCount = 0
            for (sid in b.supportIds) {
                val sb = blocks[sid] ?: continue
                if (activeFilter == null || activeFilter(sb)) validCount++
            }
            if (validCount <= 0) continue
            val share = b.sustainedLoad / validCount.toFloat()
            for (sid in b.supportIds) {
                val sb = blocks[sid] ?: continue
                if (activeFilter == null || activeFilter(sb)) {
                    sb.sustainedLoad += share
                }
            }
        }
    }

internal fun StructuralWorld.ensureLoadBaselineForNewBlocks() {
        var anyUninitialized = false
        for (b in blocks.values) {
            if (b.baselineLoad < 0f || b.loadCapacity < 0f) {
                anyUninitialized = true
                break
            }
        }
        if (!anyUninitialized) return
        propagateStructuralLoads()
        for (b in blocks.values) {
            if (b.baselineLoad < 0f || b.loadCapacity < 0f) {
                val base = max(blockDeadMass(b), b.sustainedLoad)
                b.baselineLoad = base
                b.loadCapacity = base * materialLoadSafetyFactor(b.tileId)
            }
        }
    }

internal fun StructuralWorld.enqueuePendingCollapse(
        block: StructuralBlock,
        delay: Float,
        waveDepth: Int,
        failureOrigin: Vec3,
        isOverload: Boolean
    ) {
        if (!pendingBlockIds.add(block.id)) return
        pendingCollapses.add(
            PendingCollapseNode(
                blockId = block.id,
                delay = delay,
                waveDepth = waveDepth,
                failureOrigin = failureOrigin,
                isOverload = isOverload
            )
        )
    }

    /**
     * Checks foundation undermining from craters, boolean support loss, and Geo-Mod style
     * load-stress redistribution. Small collapses detach immediately; massive multi-story
     * cascades and overloaded load-bearing members stagger sequentially for realistic buckling.
     */
internal fun StructuralWorld.checkStabilityAndCollapseWithMetrics(
        terrain: ChunkedTerrain,
        failureOriginHint: Vec3? = null
    ): Pair<Int, Int> {
        ensureLoadBaselineForNewBlocks()

        var changed = true
        var detachedCount = 0
        var points = 0
        var originAccX = 0f
        var originAccY = 0f
        var originAccZ = 0f
        var originCount = 0

        // Step 1: Undermined ground anchors (tight ground contact check)
        val undermined = ArrayList<StructuralBlock>()
        for (block in blocks.values) {
            if (!block.groundAnchored || pendingBlockIds.contains(block.id)) continue
            val he = worldHalfExtents(block)
            val hx = he.x * 0.70f
            val hz = he.z * 0.70f
            val c = block.center
            val baseY = c.y - he.y
            val gCenter = terrain.groundSurfaceY(c.x, c.z)
            val gCorners = floatArrayOf(
                terrain.groundSurfaceY(c.x - hx, c.z - hz),
                terrain.groundSurfaceY(c.x + hx, c.z - hz),
                terrain.groundSurfaceY(c.x - hx, c.z + hz),
                terrain.groundSurfaceY(c.x + hx, c.z + hz)
            )
            var maxGy = gCenter
            var supportedCorners = 0
            for (g in gCorners) {
                if (g > maxGy) maxGy = g
                if (g >= baseY - 0.42f) supportedCorners++
            }
            val lostCenterAndCorners = gCenter < baseY - 0.44f && supportedCorners < 2
            if (maxGy < baseY - 0.40f || lostCenterAndCorners) {
                undermined.add(block)
            }
        }
        for (block in undermined) {
            block.groundAnchored = false
            block.supportIds.removeAll { !blocks.containsKey(it) }
            if (block.supportIds.isEmpty()) {
                removeBlockInternal(block.id)
                collapsedCount++
                detachedCount++
                points += block.pointValue
                originAccX += block.center.x; originAccY += block.center.y; originAccZ += block.center.z; originCount++
                detachWholeAsFallingShard(block, failureOriginHint)
            }
        }

        // Step 2: Propagate connectivity & cantilever failure (staggered beyond 6 simultaneous blocks)
        val immediateLimit = 6
        val staggeredCandidates = ArrayList<StructuralBlock>()
        while (changed) {
            changed = false
            val fallen = ArrayList<StructuralBlock>()
            for (block in blocks.values) {
                if (block.groundAnchored || pendingBlockIds.contains(block.id)) continue
                block.supportIds.removeAll { !blocks.containsKey(it) || pendingBlockIds.contains(it) }
                if (block.supportIds.isEmpty()) {
                    fallen.add(block)
                    continue
                }

                var missingGroup = false
                if (block.supportGroups.isNotEmpty()) {
                    for (grp in block.supportGroups) {
                        grp.removeAll { !blocks.containsKey(it) || pendingBlockIds.contains(it) }
                        if (grp.isEmpty()) {
                            missingGroup = true
                            break
                        }
                    }
                }
                if (missingGroup) {
                    fallen.add(block)
                    continue
                }

                if (block.checkCantilever && isCantileverUnsupported(block)) {
                    fallen.add(block)
                }
            }
            if (fallen.isNotEmpty()) {
                if (detachedCount + fallen.size <= immediateLimit) {
                    changed = true
                    for (block in fallen) {
                        removeBlockInternal(block.id)
                        collapsedCount++
                        detachedCount++
                        points += block.pointValue
                        originAccX += block.center.x; originAccY += block.center.y; originAccZ += block.center.z; originCount++
                        detachWholeAsFallingShard(block, failureOriginHint)
                        if (block.poly.halfExtents.len() > 1.35f) {
                            collapseFailureEvents.add(CollapseFailureEvent(block.center, block.tileId, isClusterBreak = false))
                        }
                    }
                } else {
                    // Massive cascade: detach lowest immediate budget now, stagger upper tiers sequentially
                    val sortedUp = fallen.sortedBy { it.center.y }
                    val immediateSlots = (immediateLimit - detachedCount).coerceAtLeast(2)
                    for (idx in sortedUp.indices) {
                        val block = sortedUp[idx]
                        if (idx < immediateSlots) {
                            changed = true
                            removeBlockInternal(block.id)
                            collapsedCount++
                            detachedCount++
                            points += block.pointValue
                            originAccX += block.center.x; originAccY += block.center.y; originAccZ += block.center.z; originCount++
                            detachWholeAsFallingShard(block, failureOriginHint)
                        } else {
                            staggeredCandidates.add(block)
                        }
                    }
                }
            }
        }

        val resolvedOrigin = failureOriginHint ?: if (originCount > 0) {
            Vec3(originAccX / originCount, originAccY / originCount, originAccZ / originCount)
        } else {
            Vec3.ZERO
        }

        if (staggeredCandidates.isNotEmpty()) {
            val byHeight = staggeredCandidates.sortedBy { it.center.y }
            for (idx in byHeight.indices) {
                val b = byHeight[idx]
                val storyStep = max(0f, (b.center.y - resolvedOrigin.y) / 3.2f)
                val tier = idx / 4
                val delay = (0.045f + storyStep * 0.075f + (idx % 4) * 0.014f).coerceAtMost(1.45f)
                enqueuePendingCollapse(
                    block = b,
                    delay = delay,
                    waveDepth = max(tier + 1, storyStep.toInt() + 1),
                    failureOrigin = resolvedOrigin,
                    isOverload = false
                )
            }
        }

        // Step 3: Load-Bearing Stress Redistribution (detects remaining pillars/lintels overloaded by upper mass)
        if (blocks.isNotEmpty()) {
            propagateStructuralLoads { !pendingBlockIds.contains(it.id) }
            val overloaded = ArrayList<StructuralBlock>()
            for (b in blocks.values) {
                if (pendingBlockIds.contains(b.id) || b.loadCapacity <= 0f) continue
                // Only core supports, pillars, lintels, or slabs carrying upper load can buckle from redistribution
                val ratio = b.sustainedLoad / b.loadCapacity
                if (ratio > 1.18f) {
                    overloaded.add(b)
                }
            }
            if (overloaded.isNotEmpty()) {
                overloaded.sortByDescending { it.sustainedLoad / it.loadCapacity }
                for (idx in overloaded.indices) {
                    val b = overloaded[idx]
                    val excess = (b.sustainedLoad / b.loadCapacity) - 1f
                    val delay = (0.060f / max(0.28f, excess)).coerceIn(0.035f, 0.16f) + (idx / 3) * 0.034f
                    enqueuePendingCollapse(
                        block = b,
                        delay = delay,
                        waveDepth = (idx / 3) + 1,
                        failureOrigin = resolvedOrigin,
                        isOverload = true
                    )
                }
            }
        }

        if (detachedCount > 0) {
            wakeOrphanedShards()
            structureVersion++
        }
        return detachedCount to points
    }

internal fun StructuralWorld.stepPendingCollapses(dt: Float, terrain: ChunkedTerrain): Int {
        if (pendingCollapses.isEmpty()) return 0
        var poppedThisTick = 0
        val maxPerTick = 10
        var i = 0
        var lastOrigin = Vec3.ZERO
        while (i < pendingCollapses.size) {
            val node = pendingCollapses[i]
            node.delay -= dt
            if (node.delay <= 0f && poppedThisTick < maxPerTick) {
                pendingCollapses.removeAt(i)
                pendingBlockIds.remove(node.blockId)
                val blk = removeBlockInternal(node.blockId)
                if (blk != null) {
                    collapsedCount++
                    poppedThisTick++
                    lastOrigin = node.failureOrigin
                    detachWholeAsFallingShard(blk, node.failureOrigin)
                    collapseFailureEvents.add(CollapseFailureEvent(blk.center, blk.tileId, isClusterBreak = false))
                }
            } else {
                i++
            }
        }
        if (poppedThisTick > 0) {
            wakeOrphanedShards()
            structureVersion++
            checkStabilityAndCollapseWithMetrics(terrain, lastOrigin)
        }
        return poppedThisTick
    }

internal fun StructuralWorld.wakeShardsAroundRemoved(removedIds: List<Int>, center: Vec3, radius: Float) {
        val removedSet = if (removedIds.isEmpty()) emptySet() else removedIds.toHashSet()
        val rSq = radius * radius
        val wokenIds = ArrayList<Int>()
        for (shard in shards) {
            val lostDirect = shard.supportedByBlockId != null &&
                (shard.supportedByBlockId in removedSet || !blocks.containsKey(shard.supportedByBlockId))
            val nearZone = (shard.center - center).lenSq() <= rSq ||
                (hypot(shard.center.x - center.x, shard.center.z - center.z) <= radius * 0.85f &&
                    shard.center.y >= center.y - 1.5f)
            if (lostDirect || nearZone) {
                setShardAwake(shard)
                shard.isGroundedContact = false
                shard.supportedByBlockId = null
                shard.supportedByShardId = null
                shard.settleTimer = 0f
                wokenIds.add(shard.id)
            }
        }
        if (wokenIds.isNotEmpty()) wakeDependentsOfShards(wokenIds)
    }

internal fun StructuralWorld.wakeOrphanedShards() {
        val wokenIds = ArrayList<Int>()
        for (shard in shards) {
            if (!shard.sleeping) continue
            val supId = shard.supportedByBlockId
            if (supId != null && !blocks.containsKey(supId)) {
                setShardAwake(shard)
                shard.isGroundedContact = false
                shard.supportedByBlockId = null
                shard.supportedByShardId = null
                shard.settleTimer = 0f
                wokenIds.add(shard.id)
            }
        }
        if (wokenIds.isNotEmpty()) wakeDependentsOfShards(wokenIds)
    }

internal fun StructuralWorld.wakeDependentsOfShards(seedIds: List<Int>) {
        if (seedIds.isEmpty() || shards.isEmpty()) return
        val wokenSet = HashSet<Int>(seedIds.size * 2)
        wokenSet.addAll(seedIds)
        var changed = true
        while (changed) {
            changed = false
            for (shard in shards) {
                val supShardId = shard.supportedByShardId
                if (supShardId != null && supShardId in wokenSet) {
                    if (shard.sleeping || shard.isGroundedContact) {
                        setShardAwake(shard)
                        shard.isGroundedContact = false
                        shard.supportedByShardId = null
                        shard.settleTimer = 0f
                    }
                    if (wokenSet.add(shard.id)) {
                        changed = true
                    }
                }
            }
        }
    }

internal fun StructuralWorld.isCantileverUnsupported(block: StructuralBlock): Boolean {
        var supMinX = Float.POSITIVE_INFINITY
        var supMaxX = Float.NEGATIVE_INFINITY
        var supMinZ = Float.POSITIVE_INFINITY
        var supMaxZ = Float.NEGATIVE_INFINITY
        var anyBelow = false

        val be = worldHalfExtents(block)
        val bMinX = block.center.x - be.x - 0.35f
        val bMaxX = block.center.x + be.x + 0.35f
        val bMinZ = block.center.z - be.z - 0.35f
        val bMaxZ = block.center.z + be.z + 0.35f

        for (sid in block.supportIds) {
            val s = blocks[sid] ?: continue
            val se = worldHalfExtents(s)
            // Clamp support contact box against supported block footprint
            val cMinX = max(bMinX, s.center.x - se.x)
            val cMaxX = min(bMaxX, s.center.x + se.x)
            val cMinZ = max(bMinZ, s.center.z - se.z)
            val cMaxZ = min(bMaxZ, s.center.z + se.z)
            if (cMinX <= cMaxX && cMinZ <= cMaxZ) {
                supMinX = min(supMinX, cMinX)
                supMaxX = max(supMaxX, cMaxX)
                supMinZ = min(supMinZ, cMinZ)
                supMaxZ = max(supMaxZ, cMaxZ)
                anyBelow = true
            }
        }
        if (!anyBelow) return true

        val dxOut = max(0f, max(supMinX - block.center.x, block.center.x - supMaxX))
        val dzOut = max(0f, max(supMinZ - block.center.z, block.center.z - supMaxZ))
        val overhang = hypot(dxOut, dzOut)
        return overhang > 2.40f
    }
