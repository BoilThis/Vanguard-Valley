package com.example.engine.physics

import com.example.model.FractureStyle
import com.example.model.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

data class PolyFace(
    val vertices: List<Vec3>,
    val normal: Vec3,
    val vertexNormals: List<Vec3>? = null
)

data class ConvexPolyhedron(
    val faces: List<PolyFace>,
    val volume: Float,
    val halfExtents: Vec3,
    val boundRadius: Float = halfExtents.len()
)

object VoronoiPolyhedron {

    fun createBox(hx: Float, hy: Float, hz: Float): ConvexPolyhedron {
        val p000 = Vec3(-hx, -hy, -hz)
        val p100 = Vec3(hx, -hy, -hz)
        val p110 = Vec3(hx, hy, -hz)
        val p010 = Vec3(-hx, hy, -hz)
        val p001 = Vec3(-hx, -hy, hz)
        val p101 = Vec3(hx, -hy, hz)
        val p111 = Vec3(hx, hy, hz)
        val p011 = Vec3(-hx, hy, hz)

        val faces = listOf(
            PolyFace(listOf(p001, p101, p111, p011), Vec3(0f, 0f, 1f)),
            PolyFace(listOf(p100, p000, p010, p110), Vec3(0f, 0f, -1f)),
            PolyFace(listOf(p101, p100, p110, p111), Vec3(1f, 0f, 0f)),
            PolyFace(listOf(p000, p001, p011, p010), Vec3(-1f, 0f, 0f)),
            PolyFace(listOf(p011, p111, p110, p010), Vec3(0f, 1f, 0f)),
            PolyFace(listOf(p000, p100, p101, p001), Vec3(0f, -1f, 0f))
        )
        return ConvexPolyhedron(faces, 8f * hx * hy * hz, Vec3(hx, hy, hz))
    }

    fun createPitchedRoof(hx: Float, hy: Float, hz: Float): ConvexPolyhedron {
        // Triangular prism along X axis (ridge runs along X)
        val b00 = Vec3(-hx, -hy, -hz)
        val b10 = Vec3(hx, -hy, -hz)
        val b11 = Vec3(hx, -hy, hz)
        val b01 = Vec3(-hx, -hy, hz)
        val r0 = Vec3(-hx, hy, 0f)
        val r1 = Vec3(hx, hy, 0f)

        val nFront = Vec3(0f, hz, 2f * hy).normalized()
        val nBack = Vec3(0f, hz, -2f * hy).normalized()

        val faces = listOf(
            PolyFace(listOf(b00, b10, b11, b01), Vec3(0f, -1f, 0f)),
            PolyFace(listOf(b01, b11, r1, r0), nFront),
            PolyFace(listOf(b10, b00, r0, r1), nBack),
            PolyFace(listOf(b00, b01, r0), Vec3(-1f, 0f, 0f)),
            PolyFace(listOf(b11, b10, r1), Vec3(1f, 0f, 0f))
        )
        return ConvexPolyhedron(faces, 4f * hx * hy * hz, Vec3(hx, hy, hz))
    }

    fun createCylinderApprox(radius: Float, hy: Float, sides: Int = 10): ConvexPolyhedron {
        val top = ArrayList<Vec3>(sides)
        val bottom = ArrayList<Vec3>(sides)
        val radial = ArrayList<Vec3>(sides)
        for (i in 0 until sides) {
            val angle = (2.0 * Math.PI * i / sides).toFloat()
            val c = cos(angle)
            val s = sin(angle)
            top.add(Vec3(c * radius, hy, s * radius))
            bottom.add(Vec3(c * radius, -hy, s * radius))
            radial.add(Vec3(c, 0f, s))
        }
        val faces = ArrayList<PolyFace>(sides + 2)
        faces.add(PolyFace(top.reversed(), Vec3(0f, 1f, 0f)))
        faces.add(PolyFace(bottom, Vec3(0f, -1f, 0f)))
        for (i in 0 until sides) {
            val next = (i + 1) % sides
            val v0 = bottom[i]
            val v1 = bottom[next]
            val v2 = top[next]
            val v3 = top[i]
            val n = ((v3 - v0).cross(v1 - v0)).normalized()
            val rn0 = radial[i]
            val rn1 = radial[next]
            faces.add(PolyFace(listOf(v0, v3, v2, v1), n, listOf(rn0, rn0, rn1, rn1)))
        }
        return ConvexPolyhedron(
            faces,
            (Math.PI * radius * radius * hy * 2f).toFloat(),
            Vec3(radius, hy, radius)
        )
    }

    fun createTaperedCanopy(radiusBottom: Float, radiusTop: Float, hy: Float, sides: Int = 9): ConvexPolyhedron {
        val top = ArrayList<Vec3>(sides)
        val bottom = ArrayList<Vec3>(sides)
        val radial = ArrayList<Vec3>(sides)
        val slopeY = (radiusBottom - radiusTop) / max(0.2f, 2f * hy)
        for (i in 0 until sides) {
            val angle = (2.0 * Math.PI * i / sides).toFloat()
            val c = cos(angle)
            val s = sin(angle)
            top.add(Vec3(c * radiusTop, hy, s * radiusTop))
            bottom.add(Vec3(c * radiusBottom, -hy, s * radiusBottom))
            radial.add(Vec3(c, slopeY, s).normalized())
        }
        val faces = ArrayList<PolyFace>(sides + 2)
        if (radiusTop > 0.05f) {
            faces.add(PolyFace(top.reversed(), Vec3(0f, 1f, 0f)))
        }
        faces.add(PolyFace(bottom, Vec3(0f, -1f, 0f)))
        for (i in 0 until sides) {
            val next = (i + 1) % sides
            val v0 = bottom[i]
            val v1 = bottom[next]
            val v2 = top[next]
            val v3 = top[i]
            val n = ((v3 - v0).cross(v1 - v0)).normalized()
            val rn0 = radial[i]
            val rn1 = radial[next]
            faces.add(PolyFace(listOf(v0, v3, v2, v1), n, listOf(rn0, rn0, rn1, rn1)))
        }
        val avgR = (radiusBottom + radiusTop) * 0.5f
        return ConvexPolyhedron(
            faces,
            (Math.PI * avgR * avgR * hy * 2f).toFloat(),
            Vec3(max(radiusBottom, radiusTop), hy, max(radiusBottom, radiusTop))
        )
    }

    /**
     * Clips a closed convex polyhedron against the half-space dot(p, planeN) <= planeD.
     * Reconstructs a properly wound cap face on the cut plane.
     */
    fun clipPolyhedronHalfSpace(
        poly: ConvexPolyhedron,
        planeN: Vec3,
        planeD: Float
    ): ConvexPolyhedron? {
        val eps = 1e-4f
        val newFaces = ArrayList<PolyFace>(poly.faces.size + 1)
        val capPoints = ArrayList<Vec3>()

        for (face in poly.faces) {
            val clipped = clipPolygonHalfSpace(face.vertices, planeN, planeD, eps, capPoints)
            if (clipped.size >= 3) {
                newFaces.add(PolyFace(clipped, face.normal))
            }
        }

        if (newFaces.size < 3) return null

        // Deduplicate cap points and build sorted cap face if >= 3 unique points
        val uniqueCap = ArrayList<Vec3>()
        for (pt in capPoints) {
            if (uniqueCap.none { (it - pt).lenSq() < 1e-5f }) {
                uniqueCap.add(pt)
            }
        }

        if (uniqueCap.size >= 3) {
            var center = Vec3.ZERO
            for (pt in uniqueCap) center += pt
            center /= uniqueCap.size.toFloat()

            val ref = if (abs(planeN.y) < 0.9f) Vec3(0f, 1f, 0f) else Vec3(1f, 0f, 0f)
            val tangent = planeN.cross(ref).normalized()
            val bitangent = planeN.cross(tangent).normalized()

            val sorted = uniqueCap.sortedBy { pt ->
                val d = pt - center
                atan2(d.dot(bitangent), d.dot(tangent))
            }
            newFaces.add(PolyFace(sorted, planeN.normalized()))
        }

        if (newFaces.size < 4) return null
        return recenterAndMeasure(newFaces)
    }

    private fun clipPolygonHalfSpace(
        verts: List<Vec3>,
        planeN: Vec3,
        planeD: Float,
        eps: Float,
        capOut: MutableList<Vec3>
    ): List<Vec3> {
        if (verts.isEmpty()) return emptyList()
        val out = ArrayList<Vec3>(verts.size + 1)
        var prev = verts.last()
        var prevDist = prev.dot(planeN) - planeD
        var prevInside = prevDist <= eps

        for (curr in verts) {
            val currDist = curr.dot(planeN) - planeD
            val currInside = currDist <= eps

            if (currInside != prevInside) {
                val denom = prevDist - currDist
                val t = if (abs(denom) > 1e-6f) (prevDist / denom).coerceIn(0f, 1f) else 0.5f
                val inter = prev + (curr - prev) * t
                out.add(inter)
                capOut.add(inter)
            } else if (abs(currDist) <= eps) {
                capOut.add(curr)
            }

            if (currInside) {
                out.add(curr)
            }

            prev = curr
            prevDist = currDist
            prevInside = currInside
        }

        val clean = ArrayList<Vec3>(out.size)
        for (v in out) {
            if (clean.isEmpty() || (v - clean.last()).lenSq() > 1e-6f) {
                clean.add(v)
            }
        }
        if (clean.size >= 2 && (clean.first() - clean.last()).lenSq() <= 1e-6f) {
            clean.removeAt(clean.lastIndex)
        }
        return clean
    }

    private fun recenterAndMeasure(faces: List<PolyFace>): ConvexPolyhedron? {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var count = 0

        for (f in faces) {
            for (v in f.vertices) {
                count++
                minX = min(minX, v.x); maxX = max(maxX, v.x)
                minY = min(minY, v.y); maxY = max(maxY, v.y)
                minZ = min(minZ, v.z); maxZ = max(maxZ, v.z)
            }
        }
        if (count == 0) return null
        val hx = (maxX - minX) * 0.5f
        val hy = (maxY - minY) * 0.5f
        val hz = (maxZ - minZ) * 0.5f
        if (hx < 0.015f || hy < 0.015f || hz < 0.015f) return null

        val approxVol = max(1e-4f, 8f * hx * hy * hz * 0.55f)
        return ConvexPolyhedron(faces, approxVol, Vec3(hx, hy, hz))
    }

    fun recentroid(poly: ConvexPolyhedron): Pair<Vec3, ConvexPolyhedron> {
        var sum = Vec3.ZERO
        var count = 0
        for (f in poly.faces) {
            for (v in f.vertices) {
                sum += v
                count++
            }
        }
        val offset = if (count > 0) sum / count.toFloat() else Vec3.ZERO
        val shifted = poly.faces.map { f ->
            PolyFace(f.vertices.map { it - offset }, f.normal)
        }
        return offset to (recenterAndMeasure(shifted) ?: poly)
    }

    /**
     * Splits a convex polyhedron into material-faithful convex splinters or shards
     * (elongated wood planks, flat triangular glass shards, torn metal/plastic sheets,
     * sheared steel beams, or chunky masonry rubble).
     */
    fun shatter(
        poly: ConvexPolyhedron,
        localImpact: Vec3,
        maxPieces: Int,
        rng: Random,
        fractureStyle: FractureStyle = FractureStyle.CRUMBLE_MASONRY
    ): List<Pair<Vec3, ConvexPolyhedron>> {
        var pieces = mutableListOf(Vec3.ZERO to poly)
        val isLargeParent = poly.boundRadius > 1.15f
        val cuts = when (fractureStyle) {
            FractureStyle.SHEAR_STEEL -> when {
                maxPieces >= 10 || (isLargeParent && maxPieces >= 6) -> 3
                maxPieces >= 4 -> 2
                else -> 2
            }
            else -> when {
                maxPieces >= 10 || (isLargeParent && maxPieces >= 7) -> 4
                maxPieces >= 5 -> 3
                else -> 2
            }
        }

        repeat(cuts) { cutIdx ->
            if (pieces.size >= maxPieces) return@repeat
            val nextPieces = ArrayList<Pair<Vec3, ConvexPolyhedron>>(pieces.size * 2)
            for (idx in pieces.indices) {
                val (offset, piece) = pieces[idx]
                val minHalfDim = min(piece.halfExtents.x, min(piece.halfExtents.y, piece.halfExtents.z))
                val maxHalfDim = max(piece.halfExtents.x, max(piece.halfExtents.y, piece.halfExtents.z))
                val remainingSrc = pieces.size - idx
                val capReached = nextPieces.size + remainingSrc >= maxPieces + 1
                // Always prioritize cutting oversized >1.2m segments unless budget is tightly full
                if ((capReached && maxHalfDim <= 0.62f) ||
                    nextPieces.size + remainingSrc >= maxPieces + 3 ||
                    maxHalfDim < 0.11f
                ) {
                    nextPieces.add(offset to piece)
                    continue
                }

                val (n, d) = chooseStyleCutPlane(
                    piece = piece,
                    offset = offset,
                    localImpact = localImpact,
                    cutIdx = cutIdx,
                    minHalfDim = minHalfDim,
                    style = fractureStyle,
                    rng = rng
                )

                val left = clipPolyhedronHalfSpace(piece, n, d)
                val right = clipPolyhedronHalfSpace(piece, -n, -d)

                if (left != null && right != null) {
                    val (lShift, lClean) = recentroid(left)
                    val (rShift, rClean) = recentroid(right)
                    nextPieces.add((offset + lShift) to lClean)
                    nextPieces.add((offset + rShift) to rClean)
                } else {
                    nextPieces.add(offset to piece)
                }
            }
            pieces = nextPieces
        }
        return if (pieces.size > maxPieces) pieces.subList(0, maxPieces) else pieces
    }

    private fun chooseStyleCutPlane(
        piece: ConvexPolyhedron,
        offset: Vec3,
        localImpact: Vec3,
        cutIdx: Int,
        minHalfDim: Float,
        style: FractureStyle,
        rng: Random
    ): Pair<Vec3, Float> {
        val he = piece.halfExtents
        val minAxis = when {
            he.x <= he.y && he.x <= he.z -> 0
            he.y <= he.z -> 1
            else -> 2
        }
        val maxAxis = when {
            he.x >= he.y && he.x >= he.z -> 0
            he.y >= he.z -> 1
            else -> 2
        }

        val rawN = when (style) {
            FractureStyle.SPLINTER_PLANK -> {
                // Keep planks/stakes long along the primary grain axis on 3 of 4 cuts
                val crossCut = (cutIdx == 3 && max(he.x, max(he.y, he.z)) > 0.7f)
                if (crossCut) {
                    axisPerturbedNormal(maxAxis, 0.35f, rng)
                } else {
                    planarGrainNormal(longAxis = maxAxis, axialSlop = 0.18f, rng = rng)
                }
            }
            FractureStyle.SHATTER_TRIANGLE -> {
                // Radial cracks restricted to the 2 broad pane axes -> thin flat triangular/wedge glass shards
                val angle = (cutIdx * 1.18f) + (rng.nextFloat() - 0.5f) * 0.72f
                radialPlanarNormal(thinAxis = minAxis, angle = angle)
            }
            FractureStyle.TEAR_SHEET -> {
                // Preserve thin sheet axis while shearing corrugated metal / plastic strips
                val angle = (cutIdx * 0.95f) + (rng.nextFloat() - 0.5f) * 0.85f
                val base = radialPlanarNormal(thinAxis = minAxis, angle = angle)
                perturbAxis(base, minAxis, (rng.nextFloat() - 0.5f) * 0.18f)
            }
            FractureStyle.SHEAR_STEEL -> {
                // Break long steel beams/plates cleanly across their long axis into sub-meter segments
                val base = axisPerturbedNormal(maxAxis, 0.48f, rng)
                Vec3(base.x, base.y + (rng.nextFloat() - 0.5f) * 0.35f, base.z).normalized()
            }
            FractureStyle.CRUMBLE_MASONRY -> {
                val maxExtent = max(he.x, max(he.y, he.z))
                val sortedDims = floatArrayOf(he.x, he.y, he.z).apply { sort() }
                val aspectRatio = maxExtent / max(0.05f, sortedDims[1])
                if (aspectRatio > 1.55f || maxExtent > 0.55f) {
                    axisPerturbedNormal(maxAxis, 0.45f, rng)
                } else {
                    Vec3(
                        rng.nextFloat() * 2f - 1f,
                        rng.nextFloat() * 1.5f - 0.75f,
                        rng.nextFloat() * 2f - 1f
                    ).normalized()
                }
            }
        }

        val spreadScale = when (style) {
            FractureStyle.SHATTER_TRIANGLE -> max(he.x, max(he.y, he.z)) * 0.16f
            FractureStyle.SPLINTER_PLANK -> max(0.08f, minHalfDim) * 0.55f
            else -> max(0.08f, minHalfDim) * 0.38f
        }
        val jitter = (rng.nextFloat() - 0.5f) * 2f * spreadScale

        val impactBias = when {
            style == FractureStyle.SHATTER_TRIANGLE && cutIdx <= 1 -> 0.52f
            cutIdx == 0 -> 0.38f
            else -> 0.08f
        }
        val clampedRelImpact = Vec3(
            (localImpact.x - offset.x).coerceIn(-he.x * 0.72f, he.x * 0.72f),
            (localImpact.y - offset.y).coerceIn(-he.y * 0.72f, he.y * 0.72f),
            (localImpact.z - offset.z).coerceIn(-he.z * 0.72f, he.z * 0.72f)
        )
        val targetPoint = clampedRelImpact * impactBias + rawN * jitter
        return rawN to targetPoint.dot(rawN)
    }

    private fun planarGrainNormal(longAxis: Int, axialSlop: Float, rng: Random): Vec3 {
        val a = rng.nextFloat() * 2f - 1f
        val b = rng.nextFloat() * 2f - 1f
        val c = (rng.nextFloat() * 2f - 1f) * axialSlop
        val v = when (longAxis) {
            0 -> Vec3(c, a, b)
            1 -> Vec3(a, c, b)
            else -> Vec3(a, b, c)
        }
        return if (v.lenSq() > 1e-5f) v.normalized() else Vec3(0f, 1f, 0f)
    }

    private fun radialPlanarNormal(thinAxis: Int, angle: Float): Vec3 {
        val c = cos(angle)
        val s = sin(angle)
        return when (thinAxis) {
            0 -> Vec3(0f, c, s)
            1 -> Vec3(c, 0f, s)
            else -> Vec3(c, s, 0f)
        }.normalized()
    }

    private fun axisPerturbedNormal(axis: Int, spread: Float, rng: Random): Vec3 {
        val u = (rng.nextFloat() * 2f - 1f) * spread
        val v = (rng.nextFloat() * 2f - 1f) * spread
        return when (axis) {
            0 -> Vec3(1f, u, v)
            1 -> Vec3(u, 1f, v)
            else -> Vec3(u, v, 1f)
        }.normalized()
    }

    private fun perturbAxis(v: Vec3, axis: Int, delta: Float): Vec3 = when (axis) {
        0 -> Vec3(v.x + delta, v.y, v.z)
        1 -> Vec3(v.x, v.y + delta, v.z)
        else -> Vec3(v.x, v.y, v.z + delta)
    }.normalized()
}
