package com.example.engine.physics

import com.example.engine.terrain.ChunkedTerrain
import com.example.model.Quat
import com.example.model.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class ClusterPiece(
    val poly: ConvexPolyhedron,
    var localOffset: Vec3,
    val mass: Float,
    val uvLocalOffset: Vec3,
    val boundRadius: Float = poly.boundRadius
)

data class PendingCollapseNode(
    val blockId: Int,
    var delay: Float,
    val waveDepth: Int,
    val failureOrigin: Vec3,
    val isOverload: Boolean
)

data class CollapseFailureEvent(
    val pos: Vec3,
    val tileId: Int,
    val isClusterBreak: Boolean = false
)

/**
 * Zero-allocation multi-point SAT polygon clipper & Sequential Impulse constraint solver.
 * Produces up to 4 maximally spread corner contact points per collision manifold (Jolt/Teardown pattern)
 * so flat slabs, bricks, beams, and stacked rubble rest stably with realistic rotational friction.
 */
class ManifoldScratch {
    val px = FloatArray(4)
    val py = FloatArray(4)
    val pz = FloatArray(4)
    val depth = FloatArray(4)
    val jnAcc = FloatArray(4)
    var count = 0

    var normalX = 0f
    var normalY = 1f
    var normalZ = 0f

    // Reusable buffers for terrain & polygon clipping (zero heap allocation)
    private val rawX = FloatArray(96)
    private val rawY = FloatArray(96)
    private val rawZ = FloatArray(96)
    private val rawD = FloatArray(96)

    private val clipAX = FloatArray(16)
    private val clipAY = FloatArray(16)
    private val clipAZ = FloatArray(16)
    private val clipBX = FloatArray(16)
    private val clipBY = FloatArray(16)
    private val clipBZ = FloatArray(16)

    private val refVertX = FloatArray(12)
    private val refVertY = FloatArray(12)
    private val refVertZ = FloatArray(12)

    fun reset(nx: Float, ny: Float, nz: Float) {
        count = 0
        normalX = nx
        normalY = ny
        normalZ = nz
        jnAcc[0] = 0f; jnAcc[1] = 0f; jnAcc[2] = 0f; jnAcc[3] = 0f
    }

    private fun reduceToQuad(rawCount: Int) {
        if (rawCount <= 0) {
            count = 0
            return
        }
        if (rawCount <= 4) {
            for (i in 0 until rawCount) {
                px[i] = rawX[i]
                py[i] = rawY[i]
                pz[i] = rawZ[i]
                depth[i] = rawD[i]
                jnAcc[i] = 0f
            }
            count = rawCount
            return
        }

        // Point 0: deepest penetration point
        var i0 = 0
        var bestD = rawD[0]
        for (i in 1 until rawCount) {
            if (rawD[i] > bestD) {
                bestD = rawD[i]
                i0 = i
            }
        }
        val x0 = rawX[i0]; val y0 = rawY[i0]; val z0 = rawZ[i0]

        // Point 1: farthest from Point 0
        var i1 = i0
        var bestDistSq = -1f
        for (i in 0 until rawCount) {
            val dx = rawX[i] - x0
            val dy = rawY[i] - y0
            val dz = rawZ[i] - z0
            val dSq = dx * dx + dy * dy + dz * dz
            if (dSq > bestDistSq) {
                bestDistSq = dSq
                i1 = i
            }
        }
        val x1 = rawX[i1]; val y1 = rawY[i1]; val z1 = rawZ[i1]

        // Point 2: maximizes triangle area |(p - p0) x (p1 - p0)|
        val e01x = x1 - x0; val e01y = y1 - y0; val e01z = z1 - z0
        var i2 = i0
        var bestAreaSq = -1f
        for (i in 0 until rawCount) {
            if (i == i0 || i == i1) continue
            val ax = rawX[i] - x0; val ay = rawY[i] - y0; val az = rawZ[i] - z0
            val cx = ay * e01z - az * e01y
            val cy = az * e01x - ax * e01z
            val cz = ax * e01y - ay * e01x
            val areaSq = cx * cx + cy * cy + cz * cz
            if (areaSq > bestAreaSq) {
                bestAreaSq = areaSq
                i2 = i
            }
        }
        val x2 = rawX[i2]; val y2 = rawY[i2]; val z2 = rawZ[i2]

        // Point 3: maximizes combined spread from (p0, p1, p2) along opposite side of refNormal
        val nx = normalX; val ny = normalY; val nz = normalZ
        val t1x = x1 - x0; val t1y = y1 - y0; val t1z = z1 - z0
        val t2x = x2 - x0; val t2y = y2 - y0; val t2z = z2 - z0
        val triCx = t1y * t2z - t1z * t2y
        val triCy = t1z * t2x - t1x * t2z
        val triCz = t1x * t2y - t1y * t2x
        val triSign = if (triCx * nx + triCy * ny + triCz * nz >= 0f) 1f else -1f

        var i3 = -1
        var bestScore3 = 1e-5f
        for (i in 0 until rawCount) {
            if (i == i0 || i == i1 || i == i2) continue
            val d12x = rawX[i] - x1; val d12y = rawY[i] - y1; val d12z = rawZ[i] - z1
            val e12x = x2 - x1; val e12y = y2 - y1; val e12z = z2 - z1
            val cxA = d12y * e12z - d12z * e12y
            val cyA = d12z * e12x - d12x * e12z
            val czA = d12x * e12y - d12y * e12x
            val signedA = -(cxA * nx + cyA * ny + czA * nz) * triSign

            val d0x = rawX[i] - x0; val d0y = rawY[i] - y0; val d0z = rawZ[i] - z0
            val minD = min(
                d0x * d0x + d0y * d0y + d0z * d0z,
                min(
                    d12x * d12x + d12y * d12y + d12z * d12z,
                    (rawX[i] - x2) * (rawX[i] - x2) + (rawY[i] - y2) * (rawY[i] - y2) + (rawZ[i] - z2) * (rawZ[i] - z2)
                )
            )
            val score = max(signedA, minD * 0.5f)
            if (score > bestScore3) {
                bestScore3 = score
                i3 = i
            }
        }

        px[0] = rawX[i0]; py[0] = rawY[i0]; pz[0] = rawZ[i0]; depth[0] = rawD[i0]; jnAcc[0] = 0f
        var outCount = 1
        if (i1 != i0) {
            px[outCount] = rawX[i1]; py[outCount] = rawY[i1]; pz[outCount] = rawZ[i1]; depth[outCount] = rawD[i1]; jnAcc[outCount] = 0f
            outCount++
        }
        if (i2 != i0 && i2 != i1) {
            px[outCount] = rawX[i2]; py[outCount] = rawY[i2]; pz[outCount] = rawZ[i2]; depth[outCount] = rawD[i2]; jnAcc[outCount] = 0f
            outCount++
        }
        if (i3 >= 0 && i3 != i0 && i3 != i1 && i3 != i2) {
            px[outCount] = rawX[i3]; py[outCount] = rawY[i3]; pz[outCount] = rawZ[i3]; depth[outCount] = rawD[i3]; jnAcc[outCount] = 0f
            outCount++
        }
        count = outCount
    }

    /**
     * Collects up to 4 maximally spread world contact points on a shard (single convex or compound cluster)
     * touching terrain within [heightBand] of the lowest support height along [groundN].
     */
    fun collectTerrainManifold(
        poly: ConvexPolyhedron,
        clusterPieces: List<ClusterPiece>?,
        center: Vec3,
        q: Quat,
        terrain: ChunkedTerrain,
        groundN: Vec3,
        minOffsetY: Float,
        gyFloor: Float,
        heightBand: Float = 0.018f
    ) {
        reset(groundN.x, groundN.y, groundN.z)
        val cx = center.x; val cy = center.y; val cz = center.z
        val gnX = groundN.x; val gnY = groundN.y; val gnZ = groundN.z

        // Pass 1: find min projection along groundN
        var minH = Float.POSITIVE_INFINITY
        if (clusterPieces != null) {
            for (pi in clusterPieces.indices) {
                val piece = clusterPieces[pi]
                val off = piece.localOffset
                val faces = piece.poly.faces
                for (fi in faces.indices) {
                    val verts = faces[fi].vertices
                    for (vi in verts.indices) {
                        val lv = verts[vi]
                        val w = q.rotate(Vec3(off.x + lv.x, off.y + lv.y, off.z + lv.z))
                        val h = w.x * gnX + w.y * gnY + w.z * gnZ
                        if (h < minH) minH = h
                    }
                }
            }
        } else {
            val faces = poly.faces
            for (fi in faces.indices) {
                val verts = faces[fi].vertices
                for (vi in verts.indices) {
                    val w = q.rotate(verts[vi])
                    val h = w.x * gnX + w.y * gnY + w.z * gnZ
                    if (h < minH) minH = h
                }
            }
        }

        if (minH == Float.POSITIVE_INFINITY) {
            rawX[0] = cx; rawY[0] = cy + minOffsetY; rawZ[0] = cz; rawD[0] = 0.01f
            reduceToQuad(1)
            return
        }

        // Pass 2: gather deduplicated vertices actually near ground within heightBand of minH
        var rawCount = 0
        val cap = rawX.size
        fun tryAddVert(wxOffset: Float, wyOffset: Float, wzOffset: Float) {
            val h = wxOffset * gnX + wyOffset * gnY + wzOffset * gnZ
            if (h > minH + heightBand) return
            val wx = cx + wxOffset
            val wy = cy + wyOffset
            val wz = cz + wzOffset
            val localGy = max(gyFloor, terrain.groundSurfaceY(wx, wz))
            // Skip vertices clearly above terrain unless we don't even have a single contact yet
            if (h > minH + 0.004f && wy > localGy + 0.015f) return
            for (k in 0 until rawCount) {
                val dx = wx - rawX[k]; val dy = wy - rawY[k]; val dz = wz - rawZ[k]
                if (dx * dx + dy * dy + dz * dz < 0.0025f) return
            }
            if (rawCount < cap) {
                rawX[rawCount] = wx
                rawY[rawCount] = wy
                rawZ[rawCount] = wz
                rawD[rawCount] = max(0f, localGy - wy)
                rawCount++
            }
        }

        if (clusterPieces != null) {
            for (pi in clusterPieces.indices) {
                val piece = clusterPieces[pi]
                val off = piece.localOffset
                val faces = piece.poly.faces
                for (fi in faces.indices) {
                    val verts = faces[fi].vertices
                    for (vi in verts.indices) {
                        val lv = verts[vi]
                        val w = q.rotate(Vec3(off.x + lv.x, off.y + lv.y, off.z + lv.z))
                        tryAddVert(w.x, w.y, w.z)
                    }
                }
            }
        } else {
            val faces = poly.faces
            for (fi in faces.indices) {
                val verts = faces[fi].vertices
                for (vi in verts.indices) {
                    val w = q.rotate(verts[vi])
                    tryAddVert(w.x, w.y, w.z)
                }
            }
        }

        reduceToQuad(rawCount)
    }

    /**
     * Exact Sutherland-Hodgman face clipping between two convex polyhedra along SAT normal [nx, ny, nz]
     * pointing from B toward A (i.e. push direction separating A from B).
     * Extracts up to 4 maximally spread corner contacts into [px/py/pz/depth/count].
     */
    fun collectPolyClippedManifold(
        polyA: ConvexPolyhedron,
        centerA: Vec3,
        qA: Quat,
        polyB: ConvexPolyhedron,
        centerB: Vec3,
        qB: Quat,
        nx: Float,
        ny: Float,
        nz: Float,
        fallbackPen: Float
    ) {
        reset(nx, ny, nz)
        val facesA = polyA.faces
        val facesB = polyB.faces
        if (facesA.isEmpty() || facesB.isEmpty()) {
            px[0] = (centerA.x + centerB.x) * 0.5f
            py[0] = (centerA.y + centerB.y) * 0.5f
            pz[0] = (centerA.z + centerB.z) * 0.5f
            depth[0] = fallbackPen
            count = 1
            return
        }

        // Find best face on A (most anti-aligned with B->A push normal = facing toward B) and best face on B (facing toward A)
        var bestIdxA = 0
        var bestDotA = Float.POSITIVE_INFINITY
        val invAN = qA.conjugate().rotate(Vec3(nx, ny, nz))
        for (i in facesA.indices) {
            val d = facesA[i].normal.dot(invAN)
            if (d < bestDotA) {
                bestDotA = d
                bestIdxA = i
            }
        }

        var bestIdxB = 0
        var bestDotB = Float.NEGATIVE_INFINITY
        val invBN = qB.conjugate().rotate(Vec3(nx, ny, nz))
        for (i in facesB.indices) {
            val d = facesB[i].normal.dot(invBN)
            if (d > bestDotB) {
                bestDotB = d
                bestIdxB = i
            }
        }

        // Choose reference body whose face is closer to parallel with separating plane
        val useAAsRef = abs(bestDotA) >= abs(bestDotB)
        val refFace = if (useAAsRef) facesA[bestIdxA] else facesB[bestIdxB]
        val refQ = if (useAAsRef) qA else qB
        val refCenter = if (useAAsRef) centerA else centerB
        val incFace = if (useAAsRef) facesB[bestIdxB] else facesA[bestIdxA]
        val incQ = if (useAAsRef) qB else qA
        val incCenter = if (useAAsRef) centerB else centerA

        val refVerts = refFace.vertices
        val incVerts = incFace.vertices
        if (refVerts.size < 3 || incVerts.size < 3 || refVerts.size > refVertX.size || incVerts.size > clipAX.size) {
            px[0] = (centerA.x + centerB.x) * 0.5f
            py[0] = (centerA.y + centerB.y) * 0.5f
            pz[0] = (centerA.z + centerB.z) * 0.5f
            depth[0] = fallbackPen
            count = 1
            return
        }

        val refWNormal = refQ.rotate(refFace.normal).normalized()
        val refNCount = refVerts.size
        for (i in 0 until refNCount) {
            val w = refCenter + refQ.rotate(refVerts[i])
            refVertX[i] = w.x; refVertY[i] = w.y; refVertZ[i] = w.z
        }

        var curCount = incVerts.size
        for (i in 0 until curCount) {
            val w = incCenter + incQ.rotate(incVerts[i])
            clipAX[i] = w.x; clipAY[i] = w.y; clipAZ[i] = w.z
        }

        // Sutherland-Hodgman clip against every side plane of refFace
        var srcIsA = true
        for (ei in 0 until refNCount) {
            val ni = (ei + 1) % refNCount
            val ax = refVertX[ei]; val ay = refVertY[ei]; val az = refVertZ[ei]
            val bx = refVertX[ni]; val by = refVertY[ni]; val bz = refVertZ[ni]
            val ex = bx - ax; val ey = by - ay; val ez = bz - az
            // Outward side normal = edge x refNormal (refFace vertices are CCW looking against refNormal)
            var pnx = ey * refWNormal.z - ez * refWNormal.y
            var pny = ez * refWNormal.x - ex * refWNormal.z
            var pnz = ex * refWNormal.y - ey * refWNormal.x
            val pLenSq = pnx * pnx + pny * pny + pnz * pnz
            if (pLenSq < 1e-8f) continue
            val invPLen = 1f / sqrt(pLenSq)
            pnx *= invPLen; pny *= invPLen; pnz *= invPLen
            // Ensure plane normal points INWARD toward reference polygon center
            val next2 = (ei + 2) % refNCount
            val testDot = (refVertX[next2] - ax) * pnx + (refVertY[next2] - ay) * pny + (refVertZ[next2] - az) * pnz
            if (testDot < 0f) {
                pnx = -pnx; pny = -pny; pnz = -pnz
            }
            val planeD = ax * pnx + ay * pny + az * pnz

            val inX = if (srcIsA) clipAX else clipBX
            val inY = if (srcIsA) clipAY else clipBY
            val inZ = if (srcIsA) clipAZ else clipBZ
            val outX = if (srcIsA) clipBX else clipAX
            val outY = if (srcIsA) clipBY else clipAY
            val outZ = if (srcIsA) clipBZ else clipAZ

            if (curCount == 0) break
            var nextCount = 0
            var sx = inX[curCount - 1]; var sy = inY[curCount - 1]; var sz = inZ[curCount - 1]
            var sd = (sx * pnx + sy * pny + sz * pnz) - planeD
            for (vi in 0 until curCount) {
                val exv = inX[vi]; val eyv = inY[vi]; val ezv = inZ[vi]
                val ed = (exv * pnx + eyv * pny + ezv * pnz) - planeD
                if (ed >= -0.002f) {
                    if (sd < -0.002f && nextCount < 15) {
                        val t = (sd / (sd - ed)).coerceIn(0f, 1f)
                        outX[nextCount] = sx + (exv - sx) * t
                        outY[nextCount] = sy + (eyv - sy) * t
                        outZ[nextCount] = sz + (ezv - sz) * t
                        nextCount++
                    }
                    if (nextCount < 15) {
                        outX[nextCount] = exv; outY[nextCount] = eyv; outZ[nextCount] = ezv
                        nextCount++
                    }
                } else if (sd >= -0.002f && nextCount < 15) {
                    val t = (sd / (sd - ed)).coerceIn(0f, 1f)
                    outX[nextCount] = sx + (exv - sx) * t
                    outY[nextCount] = sy + (eyv - sy) * t
                    outZ[nextCount] = sz + (ezv - sz) * t
                    nextCount++
                }
                sx = exv; sy = eyv; sz = ezv; sd = ed
            }
            curCount = nextCount
            srcIsA = !srcIsA
        }

        val finalX = if (srcIsA) clipAX else clipBX
        val finalY = if (srcIsA) clipAY else clipBY
        val finalZ = if (srcIsA) clipAZ else clipBZ

        val refPlaneD = refVertX[0] * refWNormal.x + refVertY[0] * refWNormal.y + refVertZ[0] * refWNormal.z
        var rawCount = 0
        for (i in 0 until curCount) {
            val vx = finalX[i]; val vy = finalY[i]; val vz = finalZ[i]
            val signedDist = (vx * refWNormal.x + vy * refWNormal.y + vz * refWNormal.z) - refPlaneD
            if (signedDist <= 0.020f && rawCount < rawX.size) {
                rawX[rawCount] = vx
                rawY[rawCount] = vy
                rawZ[rawCount] = vz
                rawD[rawCount] = max(0f, -signedDist)
                rawCount++
            }
        }

        if (rawCount > 0) {
            reduceToQuad(rawCount)
        } else {
            val wA = polyA.boundRadius
            val wB = polyB.boundRadius
            px[0] = (centerA.x * wB + centerB.x * wA) / max(0.05f, wA + wB)
            py[0] = (centerA.y * wB + centerB.y * wA) / max(0.05f, wA + wB)
            pz[0] = (centerA.z * wB + centerB.z * wA) / max(0.05f, wA + wB)
            depth[0] = fallbackPen
            count = 1
        }
    }
}
