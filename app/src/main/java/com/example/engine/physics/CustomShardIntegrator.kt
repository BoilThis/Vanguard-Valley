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

/** Custom rigid-body step used when Jolt is unavailable.
 */

internal fun StructuralWorld.orientedHalfExtents(he: Vec3, orientation: Quat): Vec3 {
        val ax = orientation.rotate(Vec3(he.x, 0f, 0f))
        val ay = orientation.rotate(Vec3(0f, he.y, 0f))
        val az = orientation.rotate(Vec3(0f, 0f, he.z))
        return Vec3(
            abs(ax.x) + abs(ay.x) + abs(az.x),
            abs(ax.y) + abs(ay.y) + abs(az.y),
            abs(ax.z) + abs(ay.z) + abs(az.z)
        )
    }

internal data class LowestVertexContact(
    val minY: Float,
    val lowestX: Float,
    val lowestZ: Float,
    val arm: Vec3,
    val uniqueSupportCount: Int,
    val nextEdgeGap: Float = 0f
)

internal fun StructuralWorld.flatGapTolerance(shard: DynamicShard): Float =
        max(0.042f, shard.poly.halfExtents.len() * 0.055f)

    /**
     * Detects any elongated shard (beam, plank, column, slab strip) tilted steeper than ~10.4 degrees
     * from the resting surface plane and returns the unit torque axis that pulls its raised end
     * straight down onto a flat resting face.
     */

internal fun StructuralWorld.tiltedElongatedTorqueDir(shard: DynamicShard, surfaceNormal: Vec3 = Vec3.UP): Vec3? {
        val he = shard.poly.halfExtents
        val maxDim: Float
        val midDim: Float
        val localLong: Vec3
        if (he.x >= he.y && he.x >= he.z) {
            maxDim = he.x
            midDim = max(he.y, he.z)
            localLong = Vec3(1f, 0f, 0f)
        } else if (he.y >= he.z) {
            maxDim = he.y
            midDim = max(he.x, he.z)
            localLong = Vec3(0f, 1f, 0f)
        } else {
            maxDim = he.z
            midDim = max(he.x, he.y)
            localLong = Vec3(0f, 0f, 1f)
        }
        if (maxDim <= midDim * 1.55f) return null

        var longWorld = shard.orientation.rotate(localLong)
        val longProj = longWorld.dot(surfaceNormal)
        if (abs(longProj) <= 0.18f) return null
        if (longProj < 0f) longWorld = -longWorld

        val tangentLong = longWorld - surfaceNormal * longProj
        val tLen = tangentLong.len()
        return if (tLen > 0.02f) {
            surfaceNormal.cross(tangentLong / tLen).normalized()
        } else {
            val s = if ((shard.id and 1) == 0) 1f else -1f
            val ref = Vec3(s * 0.35f, 0f, s)
            surfaceNormal.cross(ref).normalized()
        }
    }

internal fun StructuralWorld.applyInvInertiaWorld(invInertiaLocal: Vec3, orientation: Quat, worldVec: Vec3): Vec3 {
        val local = orientation.conjugate().rotate(worldVec)
        val scaled = Vec3(local.x * invInertiaLocal.x, local.y * invInertiaLocal.y, local.z * invInertiaLocal.z)
        return orientation.rotate(scaled)
    }

internal fun StructuralWorld.isCrushableMaterial(tileId: Int): Boolean = when (tileId) {
        MaterialTile.GLASS,
        MaterialTile.PLASTIC,
        MaterialTile.TIMBER,
        MaterialTile.AWNING_TRIM,
        MaterialTile.PINE_NEEDLE,
        MaterialTile.BRICK -> true
        else -> false
    }

internal fun StructuralWorld.computeTipTorque(
        shard: DynamicShard,
        contact: LowestVertexContact,
        surfaceNormal: Vec3,
        dt: Float
    ): Vec3 {
        val tiltDir = tiltedElongatedTorqueDir(shard, surfaceNormal)
        val flatTol = flatGapTolerance(shard)
        if (tiltDir != null) {
            val invI = applyInvInertiaWorld(shard.invInertiaLocal, shard.orientation, tiltDir).dot(tiltDir)
            val inertiaScale = clamp(invI * shard.massEstimate * 0.42f, 0.35f, 1.35f)
            val tipAlpha = (14.5f * inertiaScale / max(0.55f, shard.boundRadius)).coerceIn(4.5f, 16.0f)
            return tiltDir * (tipAlpha * dt)
        }
        if (contact.uniqueSupportCount < 3 && contact.nextEdgeGap >= flatTol) {
            val gapScale = clamp(contact.nextEdgeGap / (flatTol * 1.8f), 0f, 1f)
            val armTang = contact.arm - surfaceNormal * contact.arm.dot(surfaceNormal)
            val lever = armTang.len()
            if (lever > 0.025f) {
                val axis = (armTang / lever).cross(surfaceNormal).normalized()
                val invI = applyInvInertiaWorld(shard.invInertiaLocal, shard.orientation, axis).dot(axis)
                val gravityTorque = lever * shard.massEstimate * 9.5f
                val tipAlpha = (gravityTorque * invI).coerceIn(3.5f, 16.0f)
                return axis * (tipAlpha * gapScale * dt)
            } else {
                val nudgeDir = if ((shard.id and 1) == 0) 1f else -1f
                return Vec3(nudgeDir, 0f, nudgeDir * 0.5f) * (5.0f * gapScale * dt)
            }
        }
        return Vec3.ZERO
    }

internal fun StructuralWorld.releaseClusterPieces(
        shard: DynamicShard,
        splitIds: HashSet<Int>,
        toRemove: ArrayList<DynamicShard>,
        secondarySplits: ArrayList<DynamicShard>
    ): Boolean {
        val pieces = shard.clusterPieces ?: return false
        if (fractureRoom() <= 0 && ballisticRoom() <= 0) return false
        if (!splitIds.add(shard.id)) return false
        toRemove.add(shard)
        val profile = MaterialProfiles.forTile(shard.tileId)
        collapseFailureEvents.add(CollapseFailureEvent(shard.center, shard.tileId, isClusterBreak = true))
        val ranked = ArrayList(pieces)
        ranked.sortByDescending { it.boundRadius }
        var simLeft = min(2, fractureRoom())
        var heavyAdded = 0
        for (i in ranked.indices) {
            val piece = ranked[i]
            val wantsHeavy = simLeft > 0 && piece.boundRadius >= 0.55f
            val queuedBallistic = secondarySplits.count { it.ballistic }
            if (!wantsHeavy && ballisticRoom() - queuedBallistic <= 0) continue
            val worldOff = shard.orientation.rotate(piece.localOffset)
            val pointVel = shard.vel + shard.angVel.cross(worldOff)
            val scatter = Vec3(
                (rng.nextFloat() - 0.5f) * 1.8f,
                0.10f + rng.nextFloat() * 0.35f,
                (rng.nextFloat() - 0.5f) * 1.8f
            )
            secondarySplits.add(
                DynamicShard(
                    id = nextId++,
                    poly = piece.poly,
                    center = shard.center + worldOff + Vec3(0f, 0.03f, 0f),
                    orientation = shard.orientation,
                    uvOrigin = shard.uvOrigin,
                    tileId = shard.tileId,
                    tint = shard.tint,
                    vel = pointVel * 0.45f + scatter,
                    angVel = shard.angVel * 0.65f + Vec3(
                        (rng.nextFloat() - 0.5f) * 2.8f,
                        (rng.nextFloat() - 0.5f) * 2.8f,
                        (rng.nextFloat() - 0.5f) * 2.8f
                    ),
                    canSecondarySplit = piece.boundRadius > 0.78f,
                    restitution = profile.restitution,
                    drag = profile.drag,
                    massEstimate = piece.mass,
                    uvLocalOffset = piece.uvLocalOffset,
                    splitDepth = 1,
                    boundRadius = piece.boundRadius,
                    invInertiaLocal = computeInvInertiaLocal(piece.poly.halfExtents, piece.mass),
                    ballistic = !wantsHeavy
                )
            )
            if (wantsHeavy) {
                simLeft--
                heavyAdded++
            }
        }
        reservedFractures += heavyAdded
        return true
    }

    /** Bonded slabs only stay intact for a moment, then they become loose chunks. */
internal fun StructuralWorld.releaseMatureClusters() {
        val due = ArrayList<DynamicShard>()
        for (i in shards.indices) {
            val shard = shards[i]
            if (shard.clusterPieces != null && !shard.sleeping && shard.age >= 0.10f) due.add(shard)
        }
        if (due.isEmpty()) return
        val spawned = ArrayList<DynamicShard>()
        val remove = ArrayList<DynamicShard>()
        val ids = HashSet<Int>()
        for (i in due.indices) {
            releaseClusterPieces(due[i], ids, remove, spawned)
        }
        if (remove.isNotEmpty()) {
            val rmIds = ArrayList<Int>(remove.size)
            var anySleeping = false
            for (s in remove) {
                if (s.sleeping) anySleeping = true
                rmIds.add(s.id)
            }
            if (anySleeping) sleepingRubbleVersion++
            shards.removeAll(remove.toSet())
            wakeDependentsOfShards(rmIds)
        }
        for (i in spawned.indices) {
            trimShardPoolIfNeeded(incomingSizeHint = spawned[i].boundRadius)
            shards.add(spawned[i])
        }
        reservedFractures = 0
    }

internal fun StructuralWorld.queueSecondarySplit(
        shard: DynamicShard,
        localImpact: Vec3,
        pieces: Int,
        splitIds: HashSet<Int>,
        toRemove: ArrayList<DynamicShard>,
        secondarySplits: ArrayList<DynamicShard>
    ): Boolean {
        if (shard.tileId == MaterialTile.GLASS) {
            if (!splitIds.add(shard.id)) return false
            toRemove.add(shard)
            collapseFailureEvents.add(CollapseFailureEvent(shard.center, shard.tileId, isClusterBreak = false))
            return true
        }
        if (shard.clusterPieces != null) {
            return releaseClusterPieces(shard, splitIds, toRemove, secondarySplits)
        }
        val room = fractureRoom()
        val glass = shard.tileId == MaterialTile.GLASS
        if (glass && shard.splitDepth >= 1) return false
        val visualPieces = if (glass) 8 else min(pieces, 8).coerceAtLeast(2)
        if (room <= 0 && ballisticRoom() <= 0) return false
        if (!shard.canSecondarySplit || shard.splitDepth >= 2 || !splitIds.add(shard.id)) return false
        toRemove.add(shard)
        val nextDepth = shard.splitDepth + 1
        val style = MaterialProfiles.forTile(shard.tileId).fractureStyle
        val fractured = VoronoiPolyhedron.shatter(shard.poly, localImpact, visualPieces, rng, style)
        var simLeft = min(if (glass) 1 else 2, room)
        var heavyAdded = 0
        for ((off, subPoly) in fractured) {
            val side = pieceLongestSide(subPoly)
            val wantsHeavy = if (glass) {
                simLeft > 0 && side in 0.18f..0.55f
            } else {
                simLeft > 0 && subPoly.boundRadius >= 0.55f
            }
            if (!wantsHeavy && ballisticRoom() <= 0) continue
            val subMass = estimateShardMass(subPoly, shard.tileId)
            secondarySplits.add(
                DynamicShard(
                    id = nextId++,
                    poly = subPoly,
                    center = shard.center + shard.orientation.rotate(off) + Vec3(0f, 0.04f, 0f),
                    orientation = shard.orientation,
                    uvOrigin = shard.uvOrigin,
                    tileId = shard.tileId,
                    tint = shard.tint,
                    vel = Vec3(
                        shard.vel.x * 0.40f + (rng.nextFloat() - 0.5f) * 2.0f,
                        0.12f + rng.nextFloat() * 0.40f,
                        shard.vel.z * 0.40f + (rng.nextFloat() - 0.5f) * 2.0f
                    ),
                    angVel = Vec3(
                        (rng.nextFloat() - 0.5f) * 3.2f,
                        (rng.nextFloat() - 0.5f) * 3.2f,
                        (rng.nextFloat() - 0.5f) * 3.2f
                    ),
                    canSecondarySplit = if (glass) false else nextDepth < 2 && subPoly.boundRadius > 0.75f,
                    restitution = shard.restitution,
                    drag = shard.drag,
                    massEstimate = subMass,
                    uvLocalOffset = shard.uvLocalOffset + off,
                    splitDepth = nextDepth,
                    boundRadius = subPoly.boundRadius,
                    invInertiaLocal = computeInvInertiaLocal(subPoly.halfExtents, subMass),
                    ballistic = !wantsHeavy
                )
            )
            if (wantsHeavy) {
                simLeft--
                heavyAdded++
            }
        }
        reservedFractures += heavyAdded
        if (glass) {
            collapseFailureEvents.add(CollapseFailureEvent(shard.center, shard.tileId, isClusterBreak = false))
        }
        return true
    }

    /** Loose glass panes become a dust puff. Boxes of glass never read as broken glass. */
internal fun StructuralWorld.breakOversizedGlass() {
        val due = ArrayList<DynamicShard>(8)
        for (i in shards.indices) {
            val shard = shards[i]
            if (shard.tileId != MaterialTile.GLASS) continue
            due.add(shard)
            if (due.size >= 8) break
        }
        if (due.isEmpty()) return
        var anySleeping = false
        val rmIds = ArrayList<Int>(due.size)
        for (i in due.indices) {
            val shard = due[i]
            if (shard.sleeping) anySleeping = true
            rmIds.add(shard.id)
            collapseFailureEvents.add(CollapseFailureEvent(shard.center, shard.tileId, isClusterBreak = false))
        }
        if (anySleeping) sleepingRubbleVersion++
        shards.removeAll(due.toSet())
        wakeDependentsOfShards(rmIds)
    }

    /**
     * Finds the true lowest world support contact across the rotated polyhedron (or compound cluster)
     * vertices, measuring flatness and support polygon along [surfaceNormal].
     */
internal fun StructuralWorld.computeLowestVertexContact(shard: DynamicShard, surfaceNormal: Vec3 = Vec3.UP): LowestVertexContact {
        val cluster = shard.clusterPieces
        val faces = shard.poly.faces
        if (cluster == null && faces.isEmpty()) {
            val wHe = orientedHalfExtents(shard.poly.halfExtents, shard.orientation)
            return LowestVertexContact(-wHe.y, 0f, 0f, Vec3.ZERO, 4, 0f)
        }
        var minY = Float.POSITIVE_INFINITY
        var minWorldX = 0f
        var minWorldZ = 0f
        var minH = Float.POSITIVE_INFINITY
        var minHVx = 0f
        var minHVy = 0f
        var minHVz = 0f

        fun scanVert(w: Vec3) {
            if (w.y < minY) {
                minY = w.y
                minWorldX = w.x
                minWorldZ = w.z
            }
            val h = w.dot(surfaceNormal)
            if (h < minH) {
                minH = h
                minHVx = w.x
                minHVy = w.y
                minHVz = w.z
            }
        }

        if (cluster != null) {
            for (pi in cluster.indices) {
                val p = cluster[pi]
                val off = p.localOffset
                val pFaces = p.poly.faces
                for (fi in pFaces.indices) {
                    val verts = pFaces[fi].vertices
                    for (vi in verts.indices) {
                        val lv = verts[vi]
                        scanVert(shard.orientation.rotate(Vec3(off.x + lv.x, off.y + lv.y, off.z + lv.z)))
                    }
                }
            }
        } else {
            for (fi in faces.indices) {
                val verts = faces[fi].vertices
                for (vi in verts.indices) {
                    scanVert(shard.orientation.rotate(verts[vi]))
                }
            }
        }

        if (minY == Float.POSITIVE_INFINITY) {
            val wHe = orientedHalfExtents(shard.poly.halfExtents, shard.orientation)
            return LowestVertexContact(-wHe.y, 0f, 0f, Vec3.ZERO, 4, 0f)
        }
        var sumX = 0f
        var sumY = 0f
        var sumZ = 0f
        var count = 0
        var minDistantH = Float.POSITIVE_INFINITY
        val maxCap = contactUniqX.size

        fun collectVert(w: Vec3) {
            val h = w.dot(surfaceNormal)
            val dx0 = w.x - minHVx
            val dy0 = w.y - minHVy
            val dz0 = w.z - minHVz
            val dProj = dx0 * surfaceNormal.x + dy0 * surfaceNormal.y + dz0 * surfaceNormal.z
            val latSq = (dx0 * dx0 + dy0 * dy0 + dz0 * dz0) - dProj * dProj
            if (latSq > 0.01f && h > minH + 0.018f && h < minDistantH) {
                minDistantH = h
            }
            if (h <= minH + 0.018f) {
                var duplicate = false
                for (ui in 0 until count) {
                    val dx = w.x - contactUniqX[ui]
                    val dy = w.y - contactUniqY[ui]
                    val dz = w.z - contactUniqZ[ui]
                    if (dx * dx + dy * dy + dz * dz < 0.0025f) {
                        duplicate = true
                        break
                    }
                }
                if (!duplicate && count < maxCap) {
                    contactUniqX[count] = w.x
                    contactUniqY[count] = w.y
                    contactUniqZ[count] = w.z
                    count++
                    sumX += w.x
                    sumY += w.y
                    sumZ += w.z
                }
            }
        }

        if (cluster != null) {
            for (pi in cluster.indices) {
                val p = cluster[pi]
                val off = p.localOffset
                val pFaces = p.poly.faces
                for (fi in pFaces.indices) {
                    val verts = pFaces[fi].vertices
                    for (vi in verts.indices) {
                        val lv = verts[vi]
                        collectVert(shard.orientation.rotate(Vec3(off.x + lv.x, off.y + lv.y, off.z + lv.z)))
                    }
                }
            }
        } else {
            for (fi in faces.indices) {
                val verts = faces[fi].vertices
                for (vi in verts.indices) {
                    collectVert(shard.orientation.rotate(verts[vi]))
                }
            }
        }

        val arm = if (count > 0) Vec3(sumX / count, sumY / count, sumZ / count) else Vec3(0f, minY, 0f)
        val gap = if (count >= 3 || minDistantH == Float.POSITIVE_INFINITY) 0f else (minDistantH - minH).coerceAtLeast(0f)
        return LowestVertexContact(minY, minWorldX, minWorldZ, arm, count, gap)
    }

internal fun StructuralWorld.projectPolyRangeInto(
        shard: DynamicShard,
        invQ: Quat,
        worldAxis: Vec3
    ) {
        val localAxis = invQ.rotate(worldAxis)
        val centerOffset = shard.center.dot(worldAxis)
        val faces = shard.poly.faces
        if (faces.size == 6) {
            val he = shard.poly.halfExtents
            val ext = abs(localAxis.x) * he.x + abs(localAxis.y) * he.y + abs(localAxis.z) * he.z
            projMinOut = centerOffset - ext
            projMaxOut = centerOffset + ext
            return
        }
        var minP = Float.POSITIVE_INFINITY
        var maxP = Float.NEGATIVE_INFINITY
        for (fi in faces.indices) {
            val verts = faces[fi].vertices
            for (vi in verts.indices) {
                val p = verts[vi].dot(localAxis)
                if (p < minP) minP = p
                if (p > maxP) maxP = p
            }
        }
        if (minP == Float.POSITIVE_INFINITY) {
            val he = shard.poly.halfExtents
            val ext = abs(localAxis.x) * he.x + abs(localAxis.y) * he.y + abs(localAxis.z) * he.z
            minP = -ext
            maxP = ext
        }
        projMinOut = minP + centerOffset
        projMaxOut = maxP + centerOffset
    }

    /**
     * Zero-allocation exact SAT contact test (fast O(1) projection for 6-face OBB boxes,
     * exact vertex projection for Voronoi shards). Populates [satNormalX/Y/Z] and [satPen].
     */
internal fun StructuralWorld.findPolySatContact(a: DynamicShard, b: DynamicShard): Boolean {
        val invA = a.orientation.conjugate()
        val invB = b.orientation.conjugate()
        var bestPen = Float.POSITIVE_INFINITY
        var bestNx = 0f
        var bestNy = 1f
        var bestNz = 0f

        fun testAxis(ax: Float, ay: Float, az: Float): Boolean {
            val lSq = ax * ax + ay * ay + az * az
            if (lSq < 1e-6f) return true
            val invL = 1f / kotlin.math.sqrt(lSq)
            val axis = Vec3(ax * invL, ay * invL, az * invL)

            projectPolyRangeInto(a, invA, axis)
            val minA = projMinOut
            val maxA = projMaxOut

            projectPolyRangeInto(b, invB, axis)
            val minB = projMinOut
            val maxB = projMaxOut

            val overlap = min(maxA, maxB) - max(minA, minB)
            if (overlap <= 0.001f) return false

            if (overlap < bestPen) {
                bestPen = overlap
                val sign = if ((minA + maxA) >= (minB + maxB)) 1f else -1f
                bestNx = axis.x * sign
                bestNy = axis.y * sign
                bestNz = axis.z * sign
            }
            return true
        }

        if (!testAxis(0f, 1f, 0f)) return false

        val facesA = a.poly.faces
        if (facesA.size == 6) {
            val ax = a.orientation.rotate(Vec3(1f, 0f, 0f))
            if (!testAxis(ax.x, ax.y, ax.z)) return false
            val ay = a.orientation.rotate(Vec3(0f, 1f, 0f))
            if (!testAxis(ay.x, ay.y, ay.z)) return false
            val az = a.orientation.rotate(Vec3(0f, 0f, 1f))
            if (!testAxis(az.x, az.y, az.z)) return false
        } else {
            for (i in facesA.indices) {
                val fn = a.orientation.rotate(facesA[i].normal)
                if (!testAxis(fn.x, fn.y, fn.z)) return false
            }
        }

        val facesB = b.poly.faces
        if (facesB.size == 6) {
            val bx = b.orientation.rotate(Vec3(1f, 0f, 0f))
            if (!testAxis(bx.x, bx.y, bx.z)) return false
            val by = b.orientation.rotate(Vec3(0f, 1f, 0f))
            if (!testAxis(by.x, by.y, by.z)) return false
            val bz = b.orientation.rotate(Vec3(0f, 0f, 1f))
            if (!testAxis(bz.x, bz.y, bz.z)) return false
        } else {
            for (i in facesB.indices) {
                val fn = b.orientation.rotate(facesB[i].normal)
                if (!testAxis(fn.x, fn.y, fn.z)) return false
            }
        }

        val dx = a.center.x - b.center.x
        val dy = a.center.y - b.center.y
        val dz = a.center.z - b.center.z
        if (dx * dx + dy * dy + dz * dz > 1e-5f && !testAxis(dx, dy, dz)) return false

        if (bestPen == Float.POSITIVE_INFINITY) return false
        satNormalX = bestNx
        satNormalY = bestNy
        satNormalZ = bestNz
        satPen = bestPen.coerceAtMost(0.32f)
        return true
    }

internal fun StructuralWorld.stepShardsSubstep(dt: Float, terrain: ChunkedTerrain): List<WaterSplashEvent> {
        val splashes = ArrayList<WaterSplashEvent>()
        val secondarySplits = ArrayList<DynamicShard>()
        val splitIds = HashSet<Int>()
        val toRemove = ArrayList<DynamicShard>()
        val crushedBlocks = ArrayList<StructuralBlock>()

        ensureBlockGrid()

        // Reuse pre-allocated world half-extents, shardById map & active count
        shardWorldHeScratch.clear()
        shardById.clear()
        var activeShardCount = 0
        for (idx in shards.indices) {
            val s = shards[idx]
            shardById[s.id] = s
            if (!s.sleeping && !s.ballistic) activeShardCount++
            shardWorldHeScratch.add(orientedHalfExtents(s.poly.halfExtents, s.orientation))
        }

        // Phase 1: Integrate gravity, orientation, intact 6-face OBB collision, and oriented terrain/crater contact
        for (i in shards.indices) {
            val shard = shards[i]
            shard.age += dt
            if (shard.ballistic || shard.sleeping) continue

            shard.isGroundedContact = false
            val wasAboveWater = shard.center.y > ChunkedTerrain.WATER_LEVEL
            val airDrag = (1f - shard.drag * 0.07f * dt).coerceIn(0.86f, 1f)
            shard.vel = Vec3(
                shard.vel.x * airDrag,
                shard.vel.y + StructuralWorld.GRAVITY * dt,
                shard.vel.z * airDrag
            )

            if (shard.center.y < ChunkedTerrain.WATER_LEVEL) {
                shard.vel = shard.vel * (1f - 2.4f * dt)
                shard.angVel = shard.angVel * (1f - 2.4f * dt)
            }

            shard.center += shard.vel * dt
            shard.orientation = Quat.integrateAngular(shard.orientation, shard.angVel, dt)

            if (wasAboveWater && shard.center.y <= ChunkedTerrain.WATER_LEVEL && shard.vel.y < -2.2f) {
                splashes.add(WaterSplashEvent(Vec3(shard.center.x, ChunkedTerrain.WATER_LEVEL, shard.center.z), 1.0f))
            }

            if (abs(shard.center.x) > ChunkedTerrain.MAX_X - 1f || abs(shard.center.z) > ChunkedTerrain.MAX_Z - 1f) {
                toRemove.add(shard)
                continue
            }

            var sHe = orientedHalfExtents(shard.poly.halfExtents, shard.orientation)
            val invMass = 1f / shard.massEstimate.coerceAtLeast(0.18f)

            // 6-Face Oriented Block Collision via 4m Broad-Phase Spatial Grid
            var blockHitsResolved = 0
            val nearbyBlocks = queryNearbyBlocks(shard.center.x, shard.center.z, max(sHe.x, sHe.z) + maxBlockExtent + 0.25f)
            for (bi in nearbyBlocks.indices) {
                val block = nearbyBlocks[bi]
                val bhe = block.poly.halfExtents
                val reach = bhe.len() + shard.boundRadius + 0.15f
                if (abs(shard.center.x - block.center.x) > reach ||
                    abs(shard.center.y - block.center.y) > reach ||
                    abs(shard.center.z - block.center.z) > reach
                ) continue

                val invQ = block.orientation.conjugate()
                val localP = invQ.rotate(shard.center - block.center)
                val localRelRot = invQ * shard.orientation
                val sLocalHe = orientedHalfExtents(shard.poly.halfExtents, localRelRot)

                val penX = (bhe.x + sLocalHe.x) - abs(localP.x)
                val penY = (bhe.y + sLocalHe.y) - abs(localP.y)
                val penZ = (bhe.z + sLocalHe.z) - abs(localP.z)

                if (penX > 0f && penY > 0f && penZ > 0f) {
                    val downSpeed = -shard.vel.y
                    val crushWeight = max(shard.massEstimate, shard.poly.volume * 0.55f)
                    if (downSpeed > 3.2f && (shard.boundRadius > 0.65f || shard.massEstimate > 1.8f) && localP.y > 0f) {
                        val profile = MaterialProfiles.forTile(block.tileId)
                        block.hp -= max(1.2f, crushWeight) * downSpeed * 4.2f * profile.kineticMult
                        if (block.hp <= 0f && !crushedBlocks.contains(block)) {
                            crushedBlocks.add(block)
                            shard.vel = shard.vel * 0.85f
                            continue
                        }
                    }

                    val localN = when {
                        penY <= penX && penY <= penZ -> Vec3(0f, if (localP.y >= 0f) 1f else -1f, 0f)
                        penX <= penZ -> Vec3(if (localP.x >= 0f) 1f else -1f, 0f, 0f)
                        else -> Vec3(0f, 0f, if (localP.z >= 0f) 1f else -1f)
                    }
                    val minPen = min(penX, min(penY, penZ))
                    val worldN = block.orientation.rotate(localN).normalized()
                    val vnPre = shard.vel.dot(worldN)

                    // Slabs/beams hitting intact blocks at speed shatter into smaller chunks
                    if (shard.canSecondarySplit && shard.age > 0.06f && shard.boundRadius > 0.58f &&
                        vnPre < -4.6f && secondarySplits.size < 28
                    ) {
                        val localHit = shard.orientation.conjugate().rotate(-worldN * shard.boundRadius * 0.45f)
                        if (queueSecondarySplit(shard, localHit, 4, splitIds, toRemove, secondarySplits)) {
                            break
                        }
                    }

                    shard.center += worldN * minPen
                    val comOverBlock = abs(localP.x) <= bhe.x + 0.06f && abs(localP.z) <= bhe.z + 0.06f
                    val restAngDamp = kotlin.math.exp(-9.5f * dt)

                    // Multi-point Sutherland-Hodgman clipped manifold between shard and static block face
                    manifoldScratch.collectPolyClippedManifold(
                        polyA = shard.poly,
                        centerA = shard.center,
                        qA = shard.orientation,
                        polyB = block.poly,
                        centerB = block.center,
                        qB = block.orientation,
                        nx = worldN.x,
                        ny = worldN.y,
                        nz = worldN.z,
                        fallbackPen = minPen
                    )
                    val mCount = max(1, manifoldScratch.count)
                    shard.lastManifoldCount = max(shard.lastManifoldCount, mCount)
                    var totalBlockJn = 0f
                    var maxImpactVn = 0f

                    for (iter in 0 until 3) {
                        for (mi in 0 until mCount) {
                            val rArm = Vec3(
                                manifoldScratch.px[mi] - shard.center.x,
                                manifoldScratch.py[mi] - shard.center.y,
                                manifoldScratch.pz[mi] - shard.center.z
                            )
                            val vContact = shard.vel + shard.angVel.cross(rArm)
                            val vnContact = vContact.dot(worldN)
                            if (iter == 0 && -vnContact > maxImpactVn) maxImpactVn = -vnContact
                            val rCrossN = rArm.cross(worldN)
                            val invIAngN = applyInvInertiaWorld(shard.invInertiaLocal, shard.orientation, rCrossN)
                            val denomN = invMass + rCrossN.dot(invIAngN)
                            val bounce = if (iter == 0 && vnContact < -2.2f) shard.restitution * 0.55f else 0f
                            val desiredJn = -(1f + bounce) * vnContact / max(1e-4f, denomN)
                            val prevAcc = manifoldScratch.jnAcc[mi]
                            val nextAcc = max(0f, prevAcc + desiredJn)
                            val dJn = nextAcc - prevAcc
                            manifoldScratch.jnAcc[mi] = nextAcc
                            if (dJn != 0f) {
                                totalBlockJn += dJn
                                shard.vel += worldN * (dJn * invMass)
                                shard.angVel += invIAngN * dJn
                            }
                        }
                    }
                    // Single Coulomb-clamped tangential friction pass using converged normal impulses
                    for (mi in 0 until mCount) {
                        val normalAcc = manifoldScratch.jnAcc[mi]
                        if (normalAcc > 1e-4f) {
                            val rArm = Vec3(
                                manifoldScratch.px[mi] - shard.center.x,
                                manifoldScratch.py[mi] - shard.center.y,
                                manifoldScratch.pz[mi] - shard.center.z
                            )
                            val vPost = shard.vel + shard.angVel.cross(rArm)
                            val vTan = vPost - worldN * vPost.dot(worldN)
                            val tanLen = vTan.len()
                            if (tanLen > 1e-4f) {
                                val tDir = vTan / tanLen
                                val rCrossT = rArm.cross(tDir)
                                val invIAngT = applyInvInertiaWorld(shard.invInertiaLocal, shard.orientation, rCrossT)
                                val denomT = invMass + rCrossT.dot(invIAngT)
                                val jT = -min(tanLen / max(1e-4f, denomT), normalAcc * 0.54f)
                                shard.vel += tDir * (jT * invMass)
                                shard.angVel += invIAngT * jT
                            }
                        }
                    }

                    // Strain bond failure when compound cluster slams into an intact floor/wall
                    if (shard.clusterPieces != null && shard.age > 0.05f &&
                        (maxImpactVn > 3.6f || totalBlockJn > shard.clusterStrainBudget) &&
                        secondarySplits.size < 28
                    ) {
                        if (releaseClusterPieces(shard, splitIds, toRemove, secondarySplits)) {
                            break
                        }
                    }

                    if (maxImpactVn <= 1.2f) {
                        val vNorm = worldN * shard.vel.dot(worldN)
                        val vTanLin = shard.vel - vNorm
                        shard.vel = vNorm + vTanLin * 0.72f
                    }

                    if (worldN.y > 0.55f) {
                        if (!comOverBlock) {
                            val supportLocal = Vec3(
                                localP.x.coerceIn(-bhe.x, bhe.x),
                                bhe.y,
                                localP.z.coerceIn(-bhe.z, bhe.z)
                            )
                            val supportWorld = block.center + block.orientation.rotate(supportLocal)
                            val overhangArm = supportWorld - shard.center
                            val overhangTorque = Vec3(-overhangArm.z, 0f, overhangArm.x) * (18.0f * dt)
                            shard.angVel = shard.angVel * restAngDamp + overhangTorque
                            val slideLen = hypot(overhangArm.x, overhangArm.z)
                            if (slideLen > 0.02f) {
                                shard.vel += Vec3(-overhangArm.x / slideLen, 0f, -overhangArm.z / slideLen) * (2.6f * dt)
                            }
                        } else {
                            val contact = computeLowestVertexContact(shard, worldN)
                            val tiltDir = tiltedElongatedTorqueDir(shard, worldN)
                            val flatTol = flatGapTolerance(shard)
                            val isNearFlat = tiltDir == null && (mCount >= 3 || contact.uniqueSupportCount >= 3 || contact.nextEdgeGap < flatTol)
                            val tipTorque = computeTipTorque(shard, contact, worldN, dt)
                            val dampRate = when {
                                isNearFlat -> 15.0f
                                tiltDir != null -> 2.4f
                                else -> 5.2f
                            }
                            val contactAngDamp = kotlin.math.exp(-dampRate * dt)
                            shard.angVel = shard.angVel * contactAngDamp + tipTorque
                            if (isNearFlat && shard.angVel.lenSq() < 0.16f && shard.vel.lenSq() < 0.10f) {
                                shard.angVel = Vec3.ZERO
                            }
                        }
                    } else {
                        shard.angVel = shard.angVel * restAngDamp
                    }
                    val residualVnBlk = shard.vel.dot(worldN)
                    if (residualVnBlk < 0f) {
                        shard.vel -= worldN * residualVnBlk
                    }

                    if (worldN.y > 0.55f && comOverBlock) {
                        shard.isGroundedContact = true
                        shard.supportedByBlockId = block.id
                        shard.supportedByShardId = null
                    }
                    blockHitsResolved++
                    if (blockHitsResolved >= 2) break
                }
            }

            if (splitIds.contains(shard.id)) continue

            // Oriented Terrain & Crater Surface Multi-Point Contact Manifold Solver
            val groundN = terrain.groundNormal(shard.center.x, shard.center.z)
            val contact = computeLowestVertexContact(shard, groundN)
            val lowestOffsetY = contact.minY
            val contactX = shard.center.x + contact.lowestX
            val contactZ = shard.center.z + contact.lowestZ
            val gyCenter = terrain.groundSurfaceY(shard.center.x, shard.center.z)
            val gyContact = terrain.groundSurfaceY(contactX, contactZ)
            val gy = max(gyCenter, gyContact)
            val lowestWorldY = shard.center.y + lowestOffsetY

            if (lowestWorldY < gy) {
                val impactSpeed = -shard.vel.y
                val penetration = gy - lowestWorldY
                shard.center = Vec3(shard.center.x, shard.center.y + penetration, shard.center.z)
                shard.isGroundedContact = true
                shard.supportedByBlockId = null
                shard.supportedByShardId = null

                if (shard.clusterPieces != null && shard.age > 0.04f && impactSpeed > 3.6f && secondarySplits.size < 28) {
                    if (releaseClusterPieces(shard, splitIds, toRemove, secondarySplits)) {
                        continue
                    }
                }

                if (shard.canSecondarySplit && shard.age > 0.05f && impactSpeed > 4.6f &&
                    shard.boundRadius > 0.58f && secondarySplits.size < 28
                ) {
                    if (queueSecondarySplit(shard, Vec3(0f, lowestOffsetY, 0f), 5, splitIds, toRemove, secondarySplits)) {
                        continue
                    }
                }

                val n = terrain.groundNormal(contactX, contactZ)
                manifoldScratch.collectTerrainManifold(
                    poly = shard.poly,
                    clusterPieces = shard.clusterPieces,
                    center = shard.center,
                    q = shard.orientation,
                    terrain = terrain,
                    groundN = n,
                    minOffsetY = lowestOffsetY,
                    gyFloor = gyCenter
                )
                val mCount = max(1, manifoldScratch.count)
                shard.lastManifoldCount = max(shard.lastManifoldCount, mCount)
                var totalGroundJn = 0f
                var maxGroundImpactVn = 0f

                for (iter in 0 until 3) {
                    for (mi in 0 until mCount) {
                        val rContact = Vec3(
                            manifoldScratch.px[mi] - shard.center.x,
                            manifoldScratch.py[mi] - shard.center.y,
                            manifoldScratch.pz[mi] - shard.center.z
                        )
                        val vContact = shard.vel + shard.angVel.cross(rContact)
                        val vnContact = vContact.dot(n)
                        if (iter == 0 && -vnContact > maxGroundImpactVn) maxGroundImpactVn = -vnContact
                        val rCrossN = rContact.cross(n)
                        val invIAngN = applyInvInertiaWorld(shard.invInertiaLocal, shard.orientation, rCrossN)
                        val denomN = invMass + rCrossN.dot(invIAngN)
                        val bounce = if (iter == 0 && vnContact < -3.4f) {
                            shard.restitution * 0.45f
                        } else 0f
                        val desiredJn = -(1f + bounce) * vnContact / max(1e-4f, denomN)
                        val prevAcc = manifoldScratch.jnAcc[mi]
                        val nextAcc = max(0f, prevAcc + desiredJn)
                        val dJn = nextAcc - prevAcc
                        manifoldScratch.jnAcc[mi] = nextAcc
                        if (dJn != 0f) {
                            totalGroundJn += dJn
                            shard.vel += n * (dJn * invMass)
                            shard.angVel += invIAngN * dJn
                        }
                    }
                }
                // Single Coulomb-clamped tangential friction pass using converged normal impulses
                for (mi in 0 until mCount) {
                    val normalAcc = manifoldScratch.jnAcc[mi]
                    if (normalAcc > 1e-4f) {
                        val rContact = Vec3(
                            manifoldScratch.px[mi] - shard.center.x,
                            manifoldScratch.py[mi] - shard.center.y,
                            manifoldScratch.pz[mi] - shard.center.z
                        )
                        val vPost = shard.vel + shard.angVel.cross(rContact)
                        val vTan = vPost - n * vPost.dot(n)
                        val tanLen = vTan.len()
                        if (tanLen > 1e-4f) {
                            val tDir = vTan / tanLen
                            val rCrossT = rContact.cross(tDir)
                            val invIAngT = applyInvInertiaWorld(shard.invInertiaLocal, shard.orientation, rCrossT)
                            val denomT = invMass + rCrossT.dot(invIAngT)
                            val jT = -min(tanLen / max(1e-4f, denomT), normalAcc * 0.56f)
                            shard.vel += tDir * (jT * invMass)
                            shard.angVel += invIAngT * jT
                        }
                    }
                }

                if (shard.clusterPieces != null && shard.age > 0.04f &&
                    (maxGroundImpactVn > 3.6f || totalGroundJn > shard.clusterStrainBudget) &&
                    secondarySplits.size < 28
                ) {
                    if (releaseClusterPieces(shard, splitIds, toRemove, secondarySplits)) {
                        continue
                    }
                }

                if (maxGroundImpactVn <= 1.2f) {
                    val vNorm = n * shard.vel.dot(n)
                    val vTanLin = shard.vel - vNorm
                    shard.vel = vNorm + vTanLin * 0.72f
                }

                val tiltDir = tiltedElongatedTorqueDir(shard, n)
                val flatTol = flatGapTolerance(shard)
                val isNearFlat = tiltDir == null && (mCount >= 3 || contact.uniqueSupportCount >= 3 || contact.nextEdgeGap < flatTol)
                val tipTorque = computeTipTorque(shard, contact, n, dt)

                val dampRate = when {
                    isNearFlat -> 15.0f
                    tiltDir != null -> 2.4f
                    else -> 5.2f
                }
                val angDamp = kotlin.math.exp(-dampRate * dt)
                shard.angVel = shard.angVel * angDamp + tipTorque
                val residualVnGround = shard.vel.dot(n)
                if (residualVnGround < 0f) {
                    shard.vel -= n * residualVnGround
                }
                if (isNearFlat && shard.angVel.lenSq() < 0.16f && shard.vel.lenSq() < 0.10f) {
                    shard.angVel = Vec3.ZERO
                    shard.vel = Vec3(0f, shard.vel.y.coerceAtLeast(0f), 0f)
                }
            }

            sHe = orientedHalfExtents(shard.poly.halfExtents, shard.orientation)
            shardWorldHeScratch[i] = sHe
        }

        // Phase 2: Fast Spatial-Grid Rubble-on-Rubble Collision, Heavy-on-Light Crushing & Stacking
        if (shards.size > 1 && activeShardCount > 0) {
            val invCell = 0.5f // 1 / 2.0f
            for (k in phase2ActiveKeys.indices) {
                phase2Grid[phase2ActiveKeys[k]]?.let { list ->
                    list.clear()
                    phase2BucketPool.add(list)
                }
                phase2Grid.remove(phase2ActiveKeys[k])
            }
            phase2ActiveKeys.clear()

            for (i in shards.indices) {
                val s = shards[i]
                if (splitIds.contains(s.id)) continue
                val he = shardWorldHeScratch[i]
                val minCx = kotlin.math.floor((s.center.x - he.x) * invCell).toInt()
                val maxCx = kotlin.math.floor((s.center.x + he.x) * invCell).toInt()
                val minCz = kotlin.math.floor((s.center.z - he.z) * invCell).toInt()
                val maxCz = kotlin.math.floor((s.center.z + he.z) * invCell).toInt()
                for (cx in minCx..maxCx) {
                    for (cz in minCz..maxCz) {
                        val key = blockCellKey(cx, cz)
                        var bucket = phase2Grid[key]
                        if (bucket == null) {
                            bucket = if (phase2BucketPool.isNotEmpty()) {
                                phase2BucketPool.removeAt(phase2BucketPool.size - 1)
                            } else {
                                ArrayList(6)
                            }
                            phase2Grid[key] = bucket
                            phase2ActiveKeys.add(key)
                        }
                        bucket.add(i)
                    }
                }
            }

            phase2SeenPairs.clear()
            for (ki in phase2ActiveKeys.indices) {
                val bucket = phase2Grid[phase2ActiveKeys[ki]] ?: continue
                val bSize = bucket.size
                if (bSize < 2) continue
                for (ai in 0 until bSize) {
                    val idxA = bucket[ai]
                    val a = shards[idxA]
                    if (splitIds.contains(a.id)) continue
                    val heA = shardWorldHeScratch[idxA]
                    for (bi in ai + 1 until bSize) {
                        val idxB = bucket[bi]
                        val b = shards[idxB]
                        if (a.ballistic || b.ballistic || (a.sleeping && b.sleeping) || splitIds.contains(a.id) || splitIds.contains(b.id)) continue

                        val lo = min(idxA, idxB)
                        val hi = max(idxA, idxB)
                        val pairKey = (lo.toLong() shl 24) xor hi.toLong()
                        if (!phase2SeenPairs.add(pairKey)) continue

                        val heB = shardWorldHeScratch[idxB]
                        val dx = a.center.x - b.center.x
                        val dy = a.center.y - b.center.y
                        val dz = a.center.z - b.center.z
                        val ox = (heA.x + heB.x) - abs(dx)
                        val oy = (heA.y + heB.y) - abs(dy)
                        val oz = (heA.z + heB.z) - abs(dz)
                        if (ox <= 0f || oy <= 0f || oz <= 0f) continue

                        // Fast bounding-sphere squared reject before full SAT
                        val sumR = a.boundRadius + b.boundRadius
                        if (dx * dx + dy * dy + dz * dz > sumR * sumR) continue

                        if (!findPolySatContact(a, b)) continue
                        val n = Vec3(satNormalX, satNormalY, satNormalZ)
                        val minPen = satPen

                        val relVel = a.vel - b.vel
                        val vn = relVel.dot(n)
                        val invMA = if (a.sleeping) 0f else 1f / a.massEstimate
                        val invMB = if (b.sleeping) 0f else 1f / b.massEstimate
                        val invSum = invMA + invMB
                        if (invSum <= 1e-6f) continue

                        // Heavy-on-light crushing (heavy slab falling onto fragile glass/timber/plastic/brick rubble)
                        if (abs(n.y) > 0.35f && abs(vn) > 2.8f) {
                            val aCrushesB = a.massEstimate > b.massEstimate * 2.5f && isCrushableMaterial(b.tileId)
                            val bCrushesA = b.massEstimate > a.massEstimate * 2.5f && isCrushableMaterial(a.tileId)
                            if (aCrushesB) {
                                if (b.sleeping) {
                                    setShardAwake(b)
                                    wakeDependentsOfShard(b.id)
                                }
                                if (b.canSecondarySplit && b.boundRadius > 0.42f && secondarySplits.size < 28) {
                                    val localHit = b.orientation.conjugate().rotate(a.center - b.center)
                                    if (queueSecondarySplit(b, localHit, 4, splitIds, toRemove, secondarySplits)) {
                                        a.vel = a.vel * 0.88f
                                        continue
                                    }
                                } else if (b.boundRadius <= 0.42f && splitIds.add(b.id)) {
                                    toRemove.add(b)
                                    a.vel = a.vel * 0.88f
                                    continue
                                }
                            } else if (bCrushesA) {
                                if (a.sleeping) {
                                    setShardAwake(a)
                                    wakeDependentsOfShard(a.id)
                                }
                                if (a.canSecondarySplit && a.boundRadius > 0.42f && secondarySplits.size < 28) {
                                    val localHit = a.orientation.conjugate().rotate(b.center - a.center)
                                    if (queueSecondarySplit(a, localHit, 4, splitIds, toRemove, secondarySplits)) {
                                        b.vel = b.vel * 0.88f
                                        continue
                                    }
                                } else if (a.boundRadius <= 0.42f && splitIds.add(a.id)) {
                                    toRemove.add(a)
                                    b.vel = b.vel * 0.88f
                                    continue
                                }
                            }
                        }

                        // High-energy shard-on-shard slam snaps chunks into smaller sub-meter pieces
                        if (abs(vn) > 4.6f && secondarySplits.size < 28) {
                            if (a.canSecondarySplit && a.age > 0.06f && a.boundRadius > 0.58f) {
                                val localHit = a.orientation.conjugate().rotate(b.center - a.center)
                                if (queueSecondarySplit(a, localHit, 4, splitIds, toRemove, secondarySplits)) continue
                            } else if (b.canSecondarySplit && b.age > 0.06f && b.boundRadius > 0.58f) {
                                setShardAwake(b)
                                val localHit = b.orientation.conjugate().rotate(a.center - b.center)
                                if (queueSecondarySplit(b, localHit, 4, splitIds, toRemove, secondarySplits)) continue
                            }
                        }

                        // Wake sleeping rubble if struck meaningfully (and cascade-wake anything stacked above it)
                        val impactMag = abs(vn) + relVel.len() * 0.35f
                        if (b.sleeping && impactMag > 1.35f) {
                            setShardAwake(b)
                            b.isGroundedContact = false
                            b.supportedByShardId = null
                            b.settleTimer = 0f
                            wakeDependentsOfShard(b.id)
                        } else if (a.sleeping && impactMag > 1.35f) {
                            setShardAwake(a)
                            a.isGroundedContact = false
                            a.supportedByShardId = null
                            a.settleTimer = 0f
                            wakeDependentsOfShard(a.id)
                        }

                        val invMAEff = if (a.sleeping) 0f else 1f / a.massEstimate
                        val invMBEff = if (b.sleeping) 0f else 1f / b.massEstimate
                        val effSum = invMAEff + invMBEff
                        if (effSum <= 1e-6f) continue

                        val penCorr = max(0f, minPen - 0.008f)
                        if (penCorr > 0f) {
                            val corr = penCorr * 0.46f / effSum
                            a.center += n * (corr * invMAEff)
                            b.center -= n * (corr * invMBEff)
                        }

                        // CoM-over-support verification prevents wide slabs from resting/sleeping perched on a single narrow beam/shard
                        val bSettled = (b.sleeping || (b.isGroundedContact && b.vel.lenSq() < 0.08f && (b.supportedByBlockId == null || blocks.containsKey(b.supportedByBlockId)))) &&
                            tiltedElongatedTorqueDir(b) == null
                        val aSettled = (a.sleeping || (a.isGroundedContact && a.vel.lenSq() < 0.08f && (a.supportedByBlockId == null || blocks.containsKey(a.supportedByBlockId)))) &&
                            tiltedElongatedTorqueDir(a) == null
                        val aComOverB = abs(a.center.x - b.center.x) <= heB.x * 0.86f + 0.12f &&
                            abs(a.center.z - b.center.z) <= heB.z * 0.86f + 0.12f
                        val bComOverA = abs(b.center.x - a.center.x) <= heA.x * 0.86f + 0.12f &&
                            abs(b.center.z - a.center.z) <= heA.z * 0.86f + 0.12f

                        if (n.y > 0.62f) {
                            if (bSettled && aComOverB) {
                                a.isGroundedContact = true
                                a.supportedByBlockId = b.supportedByBlockId
                                a.supportedByShardId = b.id
                            } else if (!a.sleeping) {
                                val armX = b.center.x - a.center.x
                                val armZ = b.center.z - a.center.z
                                a.angVel += Vec3(-armZ, 0f, armX) * (14.0f * dt)
                                val dXZ = hypot(armX, armZ)
                                if (dXZ > 0.02f) {
                                    a.vel += Vec3(-armX / dXZ, 0f, -armZ / dXZ) * (2.2f * dt)
                                }
                            }
                        } else if (n.y < -0.62f) {
                            if (aSettled && bComOverA) {
                                b.isGroundedContact = true
                                b.supportedByBlockId = a.supportedByBlockId
                                b.supportedByShardId = a.id
                            } else if (!b.sleeping) {
                                val armX = a.center.x - b.center.x
                                val armZ = a.center.z - b.center.z
                                b.angVel += Vec3(-armZ, 0f, armX) * (14.0f * dt)
                                val dXZ = hypot(armX, armZ)
                                if (dXZ > 0.02f) {
                                    b.vel += Vec3(-armX / dXZ, 0f, -armZ / dXZ) * (2.2f * dt)
                                }
                            }
                        }

                        // Multi-point Sutherland-Hodgman clipped manifold + 3-iteration Sequential Impulse solver
                        manifoldScratch.collectPolyClippedManifold(
                            polyA = a.poly,
                            centerA = a.center,
                            qA = a.orientation,
                            polyB = b.poly,
                            centerB = b.center,
                            qB = b.orientation,
                            nx = n.x,
                            ny = n.y,
                            nz = n.z,
                            fallbackPen = minPen
                        )
                        val mCount = max(1, manifoldScratch.count)
                        if (!a.sleeping) a.lastManifoldCount = max(a.lastManifoldCount, mCount)
                        if (!b.sleeping) b.lastManifoldCount = max(b.lastManifoldCount, mCount)

                        val e = min(a.restitution, b.restitution) * 0.55f
                        val mu = 0.55f
                        var totalPairJn = 0f
                        var maxPairImpactVn = 0f

                        for (iter in 0 until 3) {
                            for (mi in 0 until mCount) {
                                val cPt = Vec3(manifoldScratch.px[mi], manifoldScratch.py[mi], manifoldScratch.pz[mi])
                                val rA = cPt - a.center
                                val rB = cPt - b.center
                                val vRel = (a.vel + a.angVel.cross(rA)) - (b.vel + b.angVel.cross(rB))
                                val vnAtPt = vRel.dot(n)
                                if (iter == 0 && -vnAtPt > maxPairImpactVn) maxPairImpactVn = -vnAtPt

                                val rACrossN = rA.cross(n)
                                val rBCrossN = rB.cross(n)
                                val invIAngA = if (a.sleeping) Vec3.ZERO else applyInvInertiaWorld(a.invInertiaLocal, a.orientation, rACrossN)
                                val invIAngB = if (b.sleeping) Vec3.ZERO else applyInvInertiaWorld(b.invInertiaLocal, b.orientation, rBCrossN)
                                val denomN = effSum + rACrossN.dot(invIAngA) + rBCrossN.dot(invIAngB)
                                val restitution = if (iter == 0 && vnAtPt < -1.85f) e else 0f
                                val desiredJn = -(1f + restitution) * vnAtPt / max(1e-4f, denomN)
                                val prevAcc = manifoldScratch.jnAcc[mi]
                                val nextAcc = max(0f, prevAcc + desiredJn)
                                val dJn = nextAcc - prevAcc
                                manifoldScratch.jnAcc[mi] = nextAcc
                                if (dJn != 0f) {
                                    totalPairJn += dJn
                                    val impN = n * dJn
                                    if (!a.sleeping) {
                                        a.vel += impN * invMAEff
                                        a.angVel += invIAngA * dJn
                                    }
                                    if (!b.sleeping) {
                                        b.vel -= impN * invMBEff
                                        b.angVel -= invIAngB * dJn
                                    }
                                }
                            }
                        }
                        // Single Coulomb-clamped tangential friction pass using converged pair normal impulses
                        for (mi in 0 until mCount) {
                            val normalAcc = manifoldScratch.jnAcc[mi]
                            if (normalAcc > 1e-4f) {
                                val cPt = Vec3(manifoldScratch.px[mi], manifoldScratch.py[mi], manifoldScratch.pz[mi])
                                val rA = cPt - a.center
                                val rB = cPt - b.center
                                val vRelPost = (a.vel + a.angVel.cross(rA)) - (b.vel + b.angVel.cross(rB))
                                val vTan = vRelPost - n * vRelPost.dot(n)
                                val tanLen = vTan.len()
                                if (tanLen > 1e-4f) {
                                    val tDir = vTan / tanLen
                                    val rACrossT = rA.cross(tDir)
                                    val rBCrossT = rB.cross(tDir)
                                    val invITanA = if (a.sleeping) Vec3.ZERO else applyInvInertiaWorld(a.invInertiaLocal, a.orientation, rACrossT)
                                    val invITanB = if (b.sleeping) Vec3.ZERO else applyInvInertiaWorld(b.invInertiaLocal, b.orientation, rBCrossT)
                                    val denomT = effSum + rACrossT.dot(invITanA) + rBCrossT.dot(invITanB)
                                    val jT = -min(tanLen / max(1e-4f, denomT), normalAcc * mu)
                                    val impT = tDir * jT
                                    if (!a.sleeping) {
                                        a.vel += impT * invMAEff
                                        a.angVel += invITanA * jT
                                    }
                                    if (!b.sleeping) {
                                        b.vel -= impT * invMBEff
                                        b.angVel -= invITanB * jT
                                    }
                                }
                            }
                        }

                        if (maxPairImpactVn <= 1.1f) {
                            val damp = kotlin.math.exp(-7.5f * dt)
                            if (!a.sleeping) a.angVel = a.angVel * damp
                            if (!b.sleeping) b.angVel = b.angVel * damp
                        }

                        // Compound cluster strain break on heavy shard-on-shard impact
                        if (secondarySplits.size < 28) {
                            if (a.clusterPieces != null && a.age > 0.05f &&
                                (maxPairImpactVn > 3.6f || totalPairJn > a.clusterStrainBudget)
                            ) {
                                releaseClusterPieces(a, splitIds, toRemove, secondarySplits)
                            } else if (b.clusterPieces != null && b.age > 0.05f &&
                                (maxPairImpactVn > 3.6f || totalPairJn > b.clusterStrainBudget)
                            ) {
                                setShardAwake(b)
                                releaseClusterPieces(b, splitIds, toRemove, secondarySplits)
                            }
                        }
                    }
                }
            }
        }

        // Phase 3: Verified Contact + Multi-Point Flat-Face Fast Rubble Sleep Check
        for (i in shards.indices) {
            val shard = shards[i]
            if (shard.ballistic || shard.sleeping || splitIds.contains(shard.id)) continue
            val supId = shard.supportedByBlockId
            if (supId != null && !blocks.containsKey(supId)) {
                shard.supportedByBlockId = null
                shard.supportedByShardId = null
                shard.isGroundedContact = false
                shard.settleTimer = 0f
                continue
            }
            val supShardId = shard.supportedByShardId
            if (supShardId != null) {
                val supShard = shardById[supShardId]
                if (supShard == null || !supShard.sleeping) {
                    if (supShard == null) shard.supportedByShardId = null
                    shard.settleTimer = 0f
                    continue
                }
            }
            val surfNormal = if (shard.supportedByBlockId == null && shard.supportedByShardId == null) {
                terrain.groundNormal(shard.center.x, shard.center.z)
            } else {
                Vec3.UP
            }
            if (tiltedElongatedTorqueDir(shard, surfNormal) != null) {
                shard.settleTimer = 0f
                shard.lastManifoldCount = 0
                continue
            }
            if (shard.isGroundedContact && shard.vel.lenSq() < 0.22f && shard.angVel.lenSq() < 0.26f) {
                val contact = computeLowestVertexContact(shard, surfNormal)
                val armTang = contact.arm - surfNormal * contact.arm.dot(surfNormal)
                val lever = armTang.len()
                val flatTol = flatGapTolerance(shard)
                val isFlatStable =
                    shard.lastManifoldCount >= 3 ||
                        contact.uniqueSupportCount >= 3 ||
                        contact.nextEdgeGap < flatTol ||
                        shard.boundRadius < 0.24f ||
                        (contact.uniqueSupportCount >= 2 && lever < 0.045f)
                if (isFlatStable) {
                    shard.settleTimer += dt
                    val settleThreshold = if (shard.lastManifoldCount >= 3) 0.22f else 0.34f
                    if (shard.settleTimer > settleThreshold) {
                        shard.sleeping = true
                        shard.vel = Vec3.ZERO
                        shard.angVel = Vec3.ZERO
                        sleepingRubbleVersion++
                    }
                } else {
                    shard.settleTimer = 0f
                }
            } else {
                shard.settleTimer = 0f
            }
            shard.lastManifoldCount = 0
        }

        if (crushedBlocks.isNotEmpty()) {
            val crushedIds = ArrayList<Int>(crushedBlocks.size)
            for (cb in crushedBlocks) {
                if (removeBlockInternal(cb.id) != null) {
                    crushedIds.add(cb.id)
                    collapsedCount++
                    spawnShatteredShards(
                        block = cb,
                        impactWorld = cb.center,
                        impulseDir = Vec3(0f, -1f, 0f),
                        impulseSpeed = 5.5f,
                        pieces = clampShardPieces(cb, isPrecision = true)
                    )
                }
            }
            checkStabilityAndCollapseWithMetrics(terrain)
            if (crushedIds.isNotEmpty()) {
                wakeShardsAroundRemoved(crushedIds, crushedBlocks.first().center, 4.0f)
                structureVersion++
            }
        }

        if (toRemove.isNotEmpty()) {
            var anySleepingRemoved = false
            val rmIds = ArrayList<Int>(toRemove.size)
            for (ri in toRemove.indices) {
                val rs = toRemove[ri]
                if (rs.sleeping) anySleepingRemoved = true
                rmIds.add(rs.id)
            }
            if (anySleepingRemoved) sleepingRubbleVersion++
            shards.removeAll(toRemove.toSet())
            wakeDependentsOfShards(rmIds)
        }
        if (secondarySplits.isNotEmpty()) {
            for (s in secondarySplits) {
                trimShardPoolIfNeeded(incomingSizeHint = s.boundRadius)
                shards.add(s)
            }
        }
        reservedFractures = 0
        return splashes
    }
