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

/** How intact blocks become dynamic shards, and how many the frame can afford.
 */

internal fun StructuralWorld.awakeShardCount(): Int {
        var n = 0
        for (i in shards.indices) {
            val s = shards[i]
            if (!s.sleeping && !s.ballistic) n++
        }
        return n
    }

internal fun StructuralWorld.ballisticRoom(): Int {
        var n = 0
        for (i in shards.indices) if (shards[i].ballistic) n++
        return (StructuralWorld.MAX_BALLISTIC - n).coerceAtLeast(0)
    }

    /** How many new dynamic bodies this collapse can still afford. */
internal fun StructuralWorld.fractureRoom(): Int =
        (StructuralWorld.MAX_ACTIVE_SHARDS - awakeShardCount() - reservedFractures).coerceAtLeast(0)

internal fun StructuralWorld.shatterRoom(): Int =
        (StructuralWorld.SHATTER_CEILING - awakeShardCount() - reservedFractures).coerceAtLeast(0)

internal fun StructuralWorld.clampShardPieces(block: StructuralBlock, isPrecision: Boolean): Int {
        if (block.tileId == MaterialTile.GLASS) {
            val he = block.poly.halfExtents
            val longest = 2f * max(he.x, max(he.y, he.z))
            return if (isPrecision) 8 else if (longest > 1.2f) 16 else 12
        }
        val profile = MaterialProfiles.forTile(block.tileId)
        val ext = block.poly.halfExtents.len()
        val basePieces = if (ext > 1.6f) max(profile.shardPieces, 14) else (profile.shardPieces - 1).coerceAtLeast(8)
        val base = if (isPrecision) {
            (basePieces / 2).coerceIn(6, 10)
        } else {
            basePieces.coerceIn(12, 20)
        }
        val visual = when {
            isPrecision -> min(base, 8)
            ext > 2.2f -> min(base, 14)
            ext > 1.3f -> min(base, 10)
            else -> min(base, 6)
        }
        return visual
    }

internal fun StructuralWorld.estimateShardMass(poly: ConvexPolyhedron, tileId: Int): Float {
        val he = poly.halfExtents
        val vol = (8f * he.x * he.y * he.z).coerceIn(0.08f, 36f)
        val density = when (tileId) {
            MaterialTile.STEEL -> 2.8f
            MaterialTile.CORRUGATED_METAL -> 1.8f
            MaterialTile.CONCRETE, MaterialTile.BRICK, MaterialTile.RIVER_STONE, MaterialTile.STUCCO -> 1.6f
            MaterialTile.TIMBER, MaterialTile.PINE_BARK, MaterialTile.STAIR_TREAD -> 0.65f
            else -> 0.48f
        }
        return (vol * density).coerceIn(0.18f, 60f)
    }

internal fun pieceLongestSide(poly: ConvexPolyhedron): Float {
        val he = poly.halfExtents
        return 2f * max(he.x, max(he.y, he.z))
    }

internal fun StructuralWorld.computeCompoundInertiaLocal(pieces: List<ClusterPiece>): Vec3 {
        var ix = 0f
        var iy = 0f
        var iz = 0f
        for (i in pieces.indices) {
            val p = pieces[i]
            val he = p.poly.halfExtents
            val m = p.mass
            val factor = m * (1f / 3f)
            val rx = p.localOffset.x; val ry = p.localOffset.y; val rz = p.localOffset.z
            ix += factor * (he.y * he.y + he.z * he.z) + m * (ry * ry + rz * rz)
            iy += factor * (he.x * he.x + he.z * he.z) + m * (rx * rx + rz * rz)
            iz += factor * (he.x * he.x + he.y * he.y) + m * (rx * rx + ry * ry)
        }
        return Vec3(1f / max(0.04f, ix), 1f / max(0.04f, iy), 1f / max(0.04f, iz))
    }

internal fun StructuralWorld.computeClusterHullPoly(pieces: List<ClusterPiece>): ConvexPolyhedron {
        var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY; var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY; var maxZ = Float.NEGATIVE_INFINITY
        for (i in pieces.indices) {
            val p = pieces[i]
            val o = p.localOffset
            val he = p.poly.halfExtents
            if (o.x - he.x < minX) minX = o.x - he.x
            if (o.y - he.y < minY) minY = o.y - he.y
            if (o.z - he.z < minZ) minZ = o.z - he.z
            if (o.x + he.x > maxX) maxX = o.x + he.x
            if (o.y + he.y > maxY) maxY = o.y + he.y
            if (o.z + he.z > maxZ) maxZ = o.z + he.z
        }
        val hx = max(0.10f, (maxX - minX) * 0.5f)
        val hy = max(0.10f, (maxY - minY) * 0.5f)
        val hz = max(0.10f, (maxZ - minZ) * 0.5f)
        return VoronoiPolyhedron.createBox(hx, hy, hz)
    }

internal fun StructuralWorld.detachWholeAsFallingShard(block: StructuralBlock, leanOrigin: Vec3? = null) {
        val profile = MaterialProfiles.forTile(block.tileId)
        val he = block.poly.halfExtents
        val maxDim = max(he.x, max(he.y, he.z))
        val tipBiasX = (rng.nextFloat() - 0.5f) * 2.2f
        val tipBiasZ = (rng.nextFloat() - 0.5f) * 2.2f

        val leanDir = if (leanOrigin != null) {
            val dx = leanOrigin.x - block.center.x
            val dz = leanOrigin.z - block.center.z
            val dSq = dx * dx + dz * dz
            if (dSq > 0.04f) Vec3(dx, 0f, dz).normalized() else Vec3.ZERO
        } else Vec3.ZERO

        if (block.tileId == MaterialTile.GLASS) {
            collapseFailureEvents.add(CollapseFailureEvent(block.center, block.tileId, isClusterBreak = false))
            return
        }

        // Large slabs, roofs, walls & girders detach as Chaos-style Clustered Compound Shards
        // holding 2-3 pre-fractured sub-meter Voronoi pieces bonded by breakable strain thresholds.
        if (maxDim > 0.85f || he.len() > 1.15f) {
            val stressPieces = when {
                maxDim > 3.0f -> 12
                maxDim > 1.8f -> 9
                maxDim > 1.1f -> 7
                else -> 5
            }
            val localStress = Vec3(
                (rng.nextFloat() - 0.5f) * he.x * 0.65f,
                (rng.nextFloat() - 0.5f) * he.y * 0.40f,
                (rng.nextFloat() - 0.5f) * he.z * 0.65f
            )
            val split = VoronoiPolyhedron.shatter(
                poly = block.poly,
                localImpact = localStress,
                maxPieces = stressPieces,
                rng = rng,
                fractureStyle = profile.fractureStyle
            )

            // Sort along longest local axis to form spatially cohesive bonded clusters
            val maxAxis = when {
                he.x >= he.y && he.x >= he.z -> 0
                he.y >= he.z -> 1
                else -> 2
            }
            val sortedSplit = split.sortedBy { (off, _) ->
                when (maxAxis) {
                    0 -> off.x
                    1 -> off.y
                    else -> off.z
                }
            }
            val clusterCount = if (sortedSplit.size >= 6) 3 else if (sortedSplit.size >= 4) 2 else 1
            val perCluster = (sortedSplit.size + clusterCount - 1) / clusterCount

            var start = 0
            while (start < sortedSplit.size) {
                val end = min(sortedSplit.size, start + perCluster)
                val groupCount = end - start
                if (groupCount >= 2) {
                    val rawPieces = ArrayList<ClusterPiece>(groupCount)
                    var totalMass = 0f
                    var comX = 0f; var comY = 0f; var comZ = 0f
                    for (gi in start until end) {
                        val (off, pPoly) = sortedSplit[gi]
                        val m = estimateShardMass(pPoly, block.tileId)
                        rawPieces.add(ClusterPiece(pPoly, off, m, block.uvLocalOffset + off))
                        totalMass += m
                        comX += off.x * m; comY += off.y * m; comZ += off.z * m
                    }
                    val invTotalM = 1f / max(0.1f, totalMass)
                    val clusterCom = Vec3(comX * invTotalM, comY * invTotalM, comZ * invTotalM)
                    for (pi in rawPieces.indices) {
                        rawPieces[pi].localOffset = rawPieces[pi].localOffset - clusterCom
                    }
                    val hullPoly = computeClusterHullPoly(rawPieces)
                    trimShardPoolIfNeeded(incomingSizeHint = hullPoly.boundRadius)
                    val worldCenter = block.center + block.orientation.rotate(clusterCom)
                    val leanTorque = if (leanDir != Vec3.ZERO) Vec3.UP.cross(leanDir) * 2.1f else Vec3.ZERO
                    shards.add(
                        DynamicShard(
                            id = nextId++,
                            poly = hullPoly,
                            center = worldCenter,
                            orientation = block.orientation,
                            uvOrigin = block.uvOrigin,
                            tileId = block.tileId,
                            tint = block.tint,
                            vel = Vec3(
                                tipBiasX * 0.45f + leanDir.x * 0.85f,
                                -1.05f - rng.nextFloat() * 0.45f,
                                tipBiasZ * 0.45f + leanDir.z * 0.85f
                            ),
                            angVel = Vec3(
                                (rng.nextFloat() - 0.5f) * 2.2f + tipBiasZ * 0.45f,
                                (rng.nextFloat() - 0.5f) * 0.9f,
                                (rng.nextFloat() - 0.5f) * 2.2f - tipBiasX * 0.45f
                            ) + leanTorque,
                            canSecondarySplit = true,
                            restitution = profile.restitution,
                            drag = profile.drag,
                            massEstimate = totalMass,
                            uvLocalOffset = block.uvLocalOffset + clusterCom,
                            splitDepth = 0,
                            boundRadius = hullPoly.boundRadius,
                            invInertiaLocal = computeCompoundInertiaLocal(rawPieces),
                            clusterPieces = rawPieces,
                            clusterStrainBudget = totalMass * 3.5f
                        )
                    )
                } else {
                    val (offset, piecePoly) = sortedSplit[start]
                    trimShardPoolIfNeeded(incomingSizeHint = piecePoly.boundRadius)
                    val worldCenter = block.center + block.orientation.rotate(offset)
                    val outBias = (worldCenter - block.center).let { d ->
                        if (d.lenSq() > 1e-4f) d.normalized() else Vec3(tipBiasX, 0f, tipBiasZ).normalized()
                    }
                    val mass = estimateShardMass(piecePoly, block.tileId)
                    shards.add(
                        DynamicShard(
                            id = nextId++,
                            poly = piecePoly,
                            center = worldCenter,
                            orientation = block.orientation,
                            uvOrigin = block.uvOrigin,
                            tileId = block.tileId,
                            tint = block.tint,
                            vel = Vec3(
                                tipBiasX * 0.55f + outBias.x * 1.1f + leanDir.x * 0.8f,
                                -1.05f - rng.nextFloat() * 0.6f,
                                tipBiasZ * 0.55f + outBias.z * 1.1f + leanDir.z * 0.8f
                            ),
                            angVel = Vec3(
                                (rng.nextFloat() - 0.5f) * 3.2f + tipBiasZ * 0.6f,
                                (rng.nextFloat() - 0.5f) * 1.4f,
                                (rng.nextFloat() - 0.5f) * 3.2f - tipBiasX * 0.6f
                            ),
                            canSecondarySplit = piecePoly.boundRadius > 0.58f,
                            restitution = profile.restitution,
                            drag = profile.drag,
                            massEstimate = mass,
                            uvLocalOffset = block.uvLocalOffset + offset,
                            splitDepth = 0,
                            boundRadius = piecePoly.boundRadius,
                            invInertiaLocal = computeInvInertiaLocal(piecePoly.halfExtents, mass)
                        )
                    )
                }
                start = end
            }
            return
        }

        trimShardPoolIfNeeded(incomingSizeHint = block.poly.boundRadius)
        val mass = estimateShardMass(block.poly, block.tileId)
        val leanTorque = if (leanDir != Vec3.ZERO) Vec3.UP.cross(leanDir) * 1.8f else Vec3.ZERO
        shards.add(
            DynamicShard(
                id = nextId++,
                poly = block.poly,
                center = block.center,
                orientation = block.orientation,
                uvOrigin = block.uvOrigin,
                tileId = block.tileId,
                tint = block.tint,
                vel = Vec3(tipBiasX * 0.7f + leanDir.x * 0.75f, -0.85f, tipBiasZ * 0.7f + leanDir.z * 0.75f),
                angVel = Vec3(
                    (rng.nextFloat() - 0.5f) * 2.4f + tipBiasZ * 0.55f,
                    (rng.nextFloat() - 0.5f) * 0.9f,
                    (rng.nextFloat() - 0.5f) * 2.4f - tipBiasX * 0.55f
                ) + leanTorque,
                canSecondarySplit = block.poly.boundRadius > 0.58f,
                restitution = profile.restitution,
                drag = profile.drag,
                massEstimate = mass,
                uvLocalOffset = block.uvLocalOffset,
                splitDepth = 0,
                boundRadius = block.poly.boundRadius,
                invInertiaLocal = computeInvInertiaLocal(block.poly.halfExtents, mass)
            )
        )
    }

    /**
     * Carves a localized bullet or rocket breach opening in an intact broad wall/slab box,
     * replacing the parent block with intact surrounding Left/Right/Top/Bottom framing sub-blocks
     * wired into the support graph while shattering only the carved aperture into material-shaped splinters.
     */
internal fun StructuralWorld.tryCarveLocalBreach(
        blk: StructuralBlock,
        impactWorld: Vec3,
        impulseDir: Vec3,
        impulseSpeed: Float,
        holeHalfU: Float,
        holeHalfV: Float,
        isPrecision: Boolean
    ): Boolean {
        if (blk.breachDepth >= 2 || blk.isFuelExplosive || blk.isHydrant || blk.isCoreSupport) return false
        if (blk.poly.faces.size != 6) return false

        val he = blk.poly.halfExtents
        val thinAxis = when {
            he.x <= he.y && he.x <= he.z -> 0
            he.y <= he.z -> 1
            else -> 2
        }
        val minHe = when (thinAxis) {
            0 -> he.x
            1 -> he.y
            else -> he.z
        }
        if (minHe > 0.44f) return false

        val uAxis: Int
        val vAxis: Int
        when (thinAxis) {
            0 -> { uAxis = 2; vAxis = 1 } // wall facing X -> U=Z, V=Y
            1 -> { uAxis = 0; vAxis = 2 } // horizontal slab -> U=X, V=Z
            else -> { uAxis = 0; vAxis = 1 } // wall facing Z -> U=X, V=Y
        }

        fun comp(vec: Vec3, axis: Int): Float = when (axis) {
            0 -> vec.x
            1 -> vec.y
            else -> vec.z
        }

        fun makeLocalVec(uVal: Float, vVal: Float, thinVal: Float): Vec3 {
            val arr = FloatArray(3)
            arr[uAxis] = uVal
            arr[vAxis] = vVal
            arr[thinAxis] = thinVal
            return Vec3(arr[0], arr[1], arr[2])
        }

        val broadU = comp(he, uAxis)
        val broadV = comp(he, vAxis)

        // Small glass panes shatter whole rather than carving miniature glass holes
        if (blk.tileId == MaterialTile.GLASS && (broadU < 1.35f || broadV < 1.15f)) return false
        if (broadU < 1.15f) return false
        if (broadV < 0.34f) return false

        val localHit = blk.orientation.conjugate().rotate(impactWorld - blk.center)
        val marginU = min(0.35f, broadU * 0.45f)
        val marginV = min(0.35f, broadV * 0.45f)
        val hitU = comp(localHit, uAxis).coerceIn(-broadU + marginU, broadU - marginU)
        val hitV = comp(localHit, vAxis).coerceIn(-broadV + marginV, broadV - marginV)

        val hU = min(holeHalfU, broadU * 0.52f).coerceAtLeast(0.26f)
        val u0 = (hitU - hU).coerceIn(-broadU, broadU)
        val u1 = (hitU + hU).coerceIn(-broadU, broadU)

        val strip1DOnly = broadV < 0.72f
        val v0: Float
        val v1: Float
        if (strip1DOnly) {
            v0 = -broadV
            v1 = broadV
        } else {
            val hV = min(holeHalfV, broadV * 0.52f).coerceAtLeast(0.26f)
            v0 = (hitV - hV).coerceIn(-broadV, broadV)
            v1 = (hitV + hV).coerceIn(-broadV, broadV)
        }

        data class SegmentSpec(
            val minU: Float,
            val maxU: Float,
            val minV: Float,
            val maxV: Float,
            val role: Int // 0=Bottom, 1=Left, 2=Right, 3=Top
        )

        val specs = ArrayList<SegmentSpec>(4)
        val minKeep = 0.28f

        // Left & right full-height pillars (or trimmed to breach bounds if bottom/top exist)
        val hasBot = !strip1DOnly && (v0 - (-broadV)) >= minKeep
        val hasTop = !strip1DOnly && (broadV - v1) >= minKeep
        val sideMinV = if (hasBot) v0 else -broadV
        val sideMaxV = if (hasTop) v1 else broadV

        if (hasBot) {
            specs.add(SegmentSpec(-broadU, broadU, -broadV, v0, 0))
        }
        if (u0 - (-broadU) >= minKeep && sideMaxV - sideMinV >= minKeep) {
            specs.add(SegmentSpec(-broadU, u0, sideMinV, sideMaxV, 1))
        }
        if (broadU - u1 >= minKeep && sideMaxV - sideMinV >= minKeep) {
            specs.add(SegmentSpec(u1, broadU, sideMinV, sideMaxV, 2))
        }
        if (hasTop) {
            specs.add(SegmentSpec(-broadU, broadU, v1, broadV, 3))
        }

        if (specs.size < 2) return false

        // Remove parent and spawn carved framing segments preserving support topology
        removeBlockInternal(blk.id)

        val newDepth = blk.breachDepth + 1
        val segHp = max(14f, blk.maxHp * 0.72f)
        val roleToIds = LinkedHashMap<Int, Int>()
        val createdIds = ArrayList<Int>(specs.size)

        fun filterTouchingSupports(subCenter: Vec3, subHe: Vec3, candidates: List<Int>): List<Int> {
            val worldHe = Vec3(
                abs(blk.orientation.rotate(Vec3(subHe.x, 0f, 0f)).x) + abs(blk.orientation.rotate(Vec3(0f, subHe.y, 0f)).x) + abs(blk.orientation.rotate(Vec3(0f, 0f, subHe.z)).x),
                abs(blk.orientation.rotate(Vec3(subHe.x, 0f, 0f)).y) + abs(blk.orientation.rotate(Vec3(0f, subHe.y, 0f)).y) + abs(blk.orientation.rotate(Vec3(0f, 0f, subHe.z)).y),
                abs(blk.orientation.rotate(Vec3(subHe.x, 0f, 0f)).z) + abs(blk.orientation.rotate(Vec3(0f, subHe.y, 0f)).z) + abs(blk.orientation.rotate(Vec3(0f, 0f, subHe.z)).z)
            )
            return candidates.filter { sid ->
                val sb = blocks[sid] ?: return@filter false
                val se = worldHalfExtents(sb)
                abs(subCenter.x - sb.center.x) <= worldHe.x + se.x + 0.42f &&
                    abs(subCenter.y - sb.center.y) <= worldHe.y + se.y + 0.42f &&
                    abs(subCenter.z - sb.center.z) <= worldHe.z + se.z + 0.42f
            }
        }

        fun spawnSub(spec: SegmentSpec): Int {
            val cu = (spec.minU + spec.maxU) * 0.5f
            val cv = (spec.minV + spec.maxV) * 0.5f
            val hu = (spec.maxU - spec.minU) * 0.5f
            val hv = (spec.maxV - spec.minV) * 0.5f
            val subHe = makeLocalVec(hu, hv, minHe)
            val subLocalCenter = makeLocalVec(cu, cv, 0f)
            val subWorldCenter = blk.center + blk.orientation.rotate(subLocalCenter)

            val subSupports = ArrayList<Int>()
            val subGroups = ArrayList<MutableList<Int>>()
            val anchored: Boolean

            if (thinAxis == 1) {
                // Horizontal slab: every surviving strip binds to touching columns/walls below + already-created strips
                anchored = blk.groundAnchored
                val touching = filterTouchingSupports(subWorldCenter, subHe, blk.supportIds)
                subSupports.addAll(touching)
                if (subSupports.isEmpty()) {
                    subSupports.addAll(createdIds)
                    if (subSupports.isEmpty()) subSupports.addAll(blk.supportIds)
                }
            } else {
                when (spec.role) {
                    0 -> { // Bottom full-width sill
                        anchored = blk.groundAnchored
                        subSupports.addAll(blk.supportIds)
                    }
                    1, 2 -> { // Left or Right pier
                        anchored = blk.groundAnchored && !hasBot
                        if (hasBot) {
                            roleToIds[0]?.let { subSupports.add(it) }
                        }
                        subSupports.addAll(filterTouchingSupports(subWorldCenter, subHe, blk.supportIds))
                        if (subSupports.isEmpty() && !hasBot) {
                            subSupports.addAll(blk.supportIds)
                        }
                    }
                    else -> { // Top full-width header/lintel
                        anchored = false
                        val lr = listOfNotNull(roleToIds[1], roleToIds[2])
                        subSupports.addAll(lr)
                        subSupports.addAll(filterTouchingSupports(subWorldCenter, subHe, blk.supportIds))
                        if (lr.isNotEmpty()) {
                            subGroups.add(lr.toMutableList())
                        }
                    }
                }
            }

            val id = addBlock(
                poly = VoronoiPolyhedron.createBox(subHe.x, subHe.y, subHe.z),
                center = subWorldCenter,
                orientation = blk.orientation,
                tileId = blk.tileId,
                tint = blk.tint,
                hp = segHp,
                groundAnchored = anchored,
                supportIds = subSupports.distinct().toMutableList(),
                isFuelExplosive = false,
                isCoreSupport = false,
                targetTag = blk.targetTag,
                pointValue = (blk.pointValue * 0.5f).toInt().coerceAtLeast(6),
                isHydrant = false,
                walkable = blk.walkable,
                supportGroups = subGroups,
                checkCantilever = blk.checkCantilever && (thinAxis == 1),
                breachDepth = newDepth
            )
            blocks[id]?.let { created ->
                val subVol = max(0.05f, subHe.x * subHe.y * subHe.z)
                val parentVol = max(0.05f, he.x * he.y * he.z)
                val volRatio = (subVol / parentVol).coerceIn(0.32f, 0.95f)
                val inheritedCap = if (blk.loadCapacity > 0f) {
                    max(blk.loadCapacity * volRatio, estimateShardMass(created.poly, created.tileId) * 2.40f)
                } else -1f
                blocks[id] = created.copy(
                    uvOrigin = blk.uvOrigin,
                    uvLocalOffset = blk.uvLocalOffset + subLocalCenter,
                    baselineLoad = blk.baselineLoad * volRatio,
                    loadCapacity = inheritedCap
                )
            }
            roleToIds[spec.role] = id
            createdIds.add(id)
            return id
        }

        // Order: Bottom (0), Left (1), Right (2), Top (3)
        for (spec in specs.sortedBy { it.role }) {
            spawnSub(spec)
        }

        // Rewire upper/dependent blocks that referenced the carved parent to its touching surviving segments
        for (other in blocks.values) {
            if (other.id in createdIds) continue
            val oe = worldHalfExtents(other)
            val matching = createdIds.filter { cid ->
                val cb = blocks[cid] ?: return@filter false
                val ce = worldHalfExtents(cb)
                abs(other.center.x - cb.center.x) <= oe.x + ce.x + 0.42f &&
                    abs(other.center.y - cb.center.y) <= oe.y + ce.y + 0.42f &&
                    abs(other.center.z - cb.center.z) <= oe.z + ce.z + 0.42f
            }
            val replacements = matching.ifEmpty { createdIds }
            if (other.supportIds.remove(blk.id)) {
                for (mid in replacements) {
                    if (!other.supportIds.contains(mid)) other.supportIds.add(mid)
                }
            }
            for (grp in other.supportGroups) {
                if (grp.remove(blk.id)) {
                    for (mid in replacements) {
                        if (!grp.contains(mid)) grp.add(mid)
                    }
                }
            }
        }

        // Shatter ONLY the carved aperture volume using the material's FractureStyle
        val holeHe = makeLocalVec((u1 - u0) * 0.5f, (v1 - v0) * 0.5f, minHe)
        val holeLocalOffset = makeLocalVec((u0 + u1) * 0.5f, (v0 + v1) * 0.5f, 0f)
        val holeCenter = blk.center + blk.orientation.rotate(holeLocalOffset)
        val holeProxy = StructuralBlock(
            id = -1,
            poly = VoronoiPolyhedron.createBox(holeHe.x, holeHe.y, holeHe.z),
            center = holeCenter,
            orientation = blk.orientation,
            uvOrigin = blk.uvOrigin,
            tileId = blk.tileId,
            tint = blk.tint,
            hp = 0f,
            maxHp = blk.maxHp,
            groundAnchored = false,
            supportIds = mutableListOf(),
            uvLocalOffset = blk.uvLocalOffset + holeLocalOffset
        )
        spawnShatteredShards(
            block = holeProxy,
            impactWorld = impactWorld,
            impulseDir = impulseDir,
            impulseSpeed = impulseSpeed,
            pieces = if (isPrecision) 6 else 10
        )
        return true
    }

internal fun StructuralWorld.spawnShatteredShards(
        block: StructuralBlock,
        impactWorld: Vec3,
        impulseDir: Vec3,
        impulseSpeed: Float,
        pieces: Int
    ) {
        if (pieces <= 0) return
        if (block.tileId == MaterialTile.GLASS) {
            collapseFailureEvents.add(CollapseFailureEvent(block.center, block.tileId, isClusterBreak = false))
            return
        }
        val profile = MaterialProfiles.forTile(block.tileId)
        val localImpact = block.orientation.conjugate().rotate(impactWorld - block.center)
        val split = VoronoiPolyhedron.shatter(
            poly = block.poly,
            localImpact = localImpact,
            maxPieces = pieces,
            rng = rng,
            fractureStyle = profile.fractureStyle
        )
        var heavyFromThisBlock = 0
        val glassPane = block.tileId == MaterialTile.GLASS
        for ((offset, piecePoly) in split) {
            val side = pieceLongestSide(piecePoly)
            val wantsHeavy = if (glassPane) {
                side in 0.18f..0.55f &&
                    heavyFromThisBlock < 2 &&
                    awakeShardCount() < StructuralWorld.MAX_ACTIVE_SHARDS
            } else {
                piecePoly.boundRadius >= 0.70f &&
                    heavyFromThisBlock < 2 &&
                    awakeShardCount() < StructuralWorld.MAX_ACTIVE_SHARDS
            }
            if (!wantsHeavy && ballisticRoom() <= 0) continue
            trimShardPoolIfNeeded(incomingSizeHint = piecePoly.boundRadius)
            val worldCenter = block.center + block.orientation.rotate(offset)
            val radialOut = Vec3(
                worldCenter.x - block.center.x + (rng.nextFloat() - 0.5f) * 0.45f,
                0f,
                worldCenter.z - block.center.z + (rng.nextFloat() - 0.5f) * 0.45f
            ).let { d ->
                if (d.lenSq() > 1e-4f) d.normalized()
                else Vec3(1f, 0f, 0f)
            }
            val effectiveDir = if (abs(impulseDir.y) > 0.92f) {
                (radialOut * 0.85f + Vec3(0f, 0.25f, 0f)).normalized()
            } else {
                impulseDir
            }
            val scatter = Vec3(
                (rng.nextFloat() - 0.5f) * 1.4f + radialOut.x * 0.65f,
                rng.nextFloat() * 0.45f + 0.08f,
                (rng.nextFloat() - 0.5f) * 1.4f + radialOut.z * 0.65f
            ).normalized()
            val v = (effectiveDir * 0.68f + scatter * 0.52f).normalized() *
                (max(impulseSpeed, 2.6f) * (0.55f + rng.nextFloat() * 0.60f))
            val mass = estimateShardMass(piecePoly, block.tileId)
            shards.add(
                DynamicShard(
                    id = nextId++,
                    poly = piecePoly,
                    center = worldCenter,
                    orientation = block.orientation,
                    uvOrigin = block.uvOrigin,
                    tileId = block.tileId,
                    tint = block.tint,
                    vel = v,
                    angVel = Vec3(
                        (rng.nextFloat() - 0.5f) * 7.6f,
                        (rng.nextFloat() - 0.5f) * 7.6f,
                        (rng.nextFloat() - 0.5f) * 7.6f
                    ),
                    canSecondarySplit = if (glassPane) side > 0.52f else piecePoly.boundRadius > 0.58f,
                    restitution = profile.restitution,
                    drag = profile.drag,
                    massEstimate = mass,
                    uvLocalOffset = block.uvLocalOffset + offset,
                    splitDepth = 0,
                    boundRadius = piecePoly.boundRadius,
                    invInertiaLocal = computeInvInertiaLocal(piecePoly.halfExtents, mass),
                    ballistic = !wantsHeavy
                )
            )
            if (wantsHeavy) heavyFromThisBlock++
        }
    }
