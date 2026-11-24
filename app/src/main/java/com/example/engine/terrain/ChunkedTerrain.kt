package com.example.engine.terrain

import com.example.model.MaterialTile
import com.example.model.Vec3
import com.example.model.WorldConfig
import com.example.model.ZoneId
import com.example.model.clamp
import com.example.model.lerp
import com.example.model.smoothstep
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class ChunkedTerrain(var config: WorldConfig) {

    companion object {
        const val CHUNK_CELLS = 32
        const val CHUNKS_X = 16
        const val CHUNKS_Z = 12
        const val GRID_CELLS_X = CHUNK_CELLS * CHUNKS_X // 512
        const val GRID_CELLS_Z = CHUNK_CELLS * CHUNKS_Z // 384
        const val GRID_VERTS_X = GRID_CELLS_X + 1       // 513
        const val GRID_VERTS_Z = GRID_CELLS_Z + 1       // 385
        const val CELL_SIZE = 1.0f

        const val MIN_X = -GRID_CELLS_X * 0.5f          // -256f
        const val MAX_X = GRID_CELLS_X * 0.5f           // +256f
        const val MIN_Z = -GRID_CELLS_Z * 0.5f          // -192f
        const val MAX_Z = GRID_CELLS_Z * 0.5f           // +192f

        const val WATER_LEVEL = 1.35f
        const val VERTEX_STRIDE_FLOATS = 13
    }

    val heights = FloatArray(GRID_VERTS_X * GRID_VERTS_Z)
    val baseHeights = FloatArray(GRID_VERTS_X * GRID_VERTS_Z)
    val scorch = FloatArray(GRID_VERTS_X * GRID_VERTS_Z)
    val chunkVersions = IntArray(CHUNKS_X * CHUNKS_Z) { 1 }

    private var riverPhase = 0f

    init {
        regenerate(config)
    }

    fun regenerate(newConfig: WorldConfig) {
        config = newConfig
        riverPhase = ((newConfig.seed % 1000) / 1000f) * (2f * PI.toFloat())
        scorch.fill(0f)

        for (iz in 0 until GRID_VERTS_Z) {
            val z = MIN_Z + iz * CELL_SIZE
            for (ix in 0 until GRID_VERTS_X) {
                val x = MIN_X + ix * CELL_SIZE
                val h = computeAnalyticHeight(x, z)
                val idx = iz * GRID_VERTS_X + ix
                heights[idx] = h
                baseHeights[idx] = h
            }
        }
        markAllChunksDirty()
    }

    fun riverCenterX(z: Float): Float {
        return sin(z * 0.052f + riverPhase) * 6.2f + cos(z * 0.025f - riverPhase * 0.5f) * 2.1f
    }

    fun riverHalfWidth(): Float = 5.8f * clamp(config.riverWidth, 0.55f, 1.85f)

    fun isMainRoad(x: Float, z: Float): Boolean {
        val northCivicBlvd = abs(z + 112f) < 3.4f && x in -216f..-18f
        val northCrossingBlvd = abs(z + 56f) < 3.6f && x in -216f..208f
        val centralBlvd = abs(z) < 3.8f && x in -216f..208f
        val southCrossingBlvd = abs(z - 56f) < 3.6f && x in -216f..208f
        val southMetroBlvd = abs(z - 112f) < 3.4f && x in -216f..208f

        val downtownAve = abs(x + 58f) < 3.4f && z in -152f..112f
        val midtownAve = abs(x + 104f) < 3.2f && (z in -152f..4f || z in 52f..112f)
        val westCampusAve = abs(x + 150f) < 3.2f && z in -152f..152f
        val outerWestAve = abs(x + 196f) < 3.2f && z in -152f..152f

        val eastBaseAve = abs(x - 56f) < 3.2f && z in -126f..112f
        val eastTechAve = abs(x - 108f) < 3.2f && z in -126f..112f
        val eastOuterAve = abs(x - 162f) < 3.2f && z in -126f..112f

        return northCivicBlvd || northCrossingBlvd || centralBlvd || southCrossingBlvd || southMetroBlvd ||
            downtownAve || midtownAve || westCampusAve || outerWestAve ||
            eastBaseAve || eastTechAve || eastOuterAve
    }

    fun isSidewalk(x: Float, z: Float): Boolean {
        if (isMainRoad(x, z)) return false
        val rx = riverCenterX(z)
        if (abs(x - rx) < riverHalfWidth() + 3.2f) return false
        val clearOfRiver = x < rx - riverHalfWidth() - 2f || x > rx + riverHalfWidth() + 2f

        val alongNorthCivic = abs(z + 112f) in 3.4f..5.6f && x in -218f..-18f
        val alongNorthCross = abs(z + 56f) in 3.6f..5.8f && x in -218f..210f && clearOfRiver
        val alongCentral = abs(z) in 3.8f..6.2f && x in -218f..210f && clearOfRiver
        val alongSouthCross = abs(z - 56f) in 3.6f..5.8f && x in -218f..210f && clearOfRiver
        val alongSouthMetro = abs(z - 112f) in 3.4f..5.6f && x in -218f..210f && clearOfRiver

        val alongDowntown = abs(x + 58f) in 3.4f..5.6f && z in -154f..114f
        val alongMidtown = abs(x + 104f) in 3.2f..5.4f && (z in -154f..4f || z in 52f..114f)
        val alongWestCampus = abs(x + 150f) in 3.2f..5.4f && z in -154f..154f
        val alongOuterW = abs(x + 196f) in 3.2f..5.4f && z in -154f..154f

        val alongEastBase = abs(x - 56f) in 3.2f..5.4f && z in -128f..114f
        val alongEastTech = abs(x - 108f) in 3.2f..5.4f && z in -128f..114f
        val alongEastOuter = abs(x - 162f) in 3.2f..5.4f && z in -128f..114f

        val downtownPlaza = (x in -146f..-20f && z in -52f..-6f)
        val civicPlaza = (x in -102f..-62f && z in -108f..-60f)
        val bankApron = (x in -54f..-20f && z in 6f..50f)
        val mallPlaza = (x in -100f..-62f && z in 60f..74f)
        val universityQuad = (x in -146f..-108f && z in 60f..108f)
        val eastTechPlaza = (x in 60f..158f && z in 6f..50f)
        val parkBorder = (x in -146f..-62f && z in 6f..50f) &&
            (x < -142f || x > -66f || z < 10f || z > 46f)

        return alongNorthCivic || alongNorthCross || alongCentral || alongSouthCross || alongSouthMetro ||
            alongDowntown || alongMidtown || alongWestCampus || alongOuterW ||
            alongEastBase || alongEastTech || alongEastOuter ||
            downtownPlaza || civicPlaza || bankApron || mallPlaza || universityQuad || eastTechPlaza || parkBorder
    }

    fun isParkPath(x: Float, z: Float): Boolean {
        if (x !in -142f..-66f || z !in 10f..46f) return false
        val dx = x - (-104f)
        val dz = z - 28f
        val crossPath = abs(dx) < 2.2f || abs(dz) < 2.2f
        val ringDist = kotlin.math.sqrt(dx * dx + dz * dz)
        val centralRing = ringDist in 8.5f..11.5f
        return crossPath || centralRing
    }

    fun isCrosswalkStripe(x: Float, z: Float): Boolean {
        if (!isMainRoad(x, z)) return false
        val roadZs = floatArrayOf(-112f, -56f, 0f, 56f, 112f)
        val roadXs = floatArrayOf(-196f, -150f, -104f, -58f, 56f, 108f, 162f)
        for (rz in roadZs) {
            for (rx in roadXs) {
                if (rz < -90f && rx > 0f) continue
                if (rz > 90f && rx in -120f..-40f) continue
                val adx = abs(x - rx)
                val adz = abs(z - rz)
                if (adz < 3.4f && adx in 4.3f..6.8f) {
                    return floor(z * 1.4f).toInt() and 1 == 0
                }
                if (adx < 3.2f && adz in 4.5f..7.0f) {
                    return floor(x * 1.4f).toInt() and 1 == 0
                }
            }
        }
        return false
    }

    fun classifyZone(x: Float, z: Float): ZoneId {
        val rx = riverCenterX(z)
        val rw = riverHalfWidth() + 3.5f
        if (abs(x - rx) < rw) return ZoneId.RIVER_CROSSING
        if (x in -146f..-62f && z in 6f..50f) return ZoneId.CENTRAL_PARK
        if (x in -102f..-60f && z in -110f..-58f) return ZoneId.CIVIC_PLAZA
        if (x in -148f..-18f && z in -54f..52f) return ZoneId.DOWNTOWN_CORE
        if (x in -194f..-106f && z in 58f..110f) return ZoneId.UNIVERSITY
        if ((x in -148f..-56f && z in 114f..162f) || (x in 22f..204f && z in 114f..162f)) return ZoneId.STADIUM_RAIL
        if (x in -216f..-16f && z in -156f..156f) return ZoneId.SMALL_TOWN
        if (x in 20f..206f && z in 4f..112f) return ZoneId.INDUSTRIAL_DISTRICT
        if (x in 20f..166f && z in -126f..-4f) return ZoneId.ARMY_BASE
        if (abs(z) > 152f || x < -212f || x > 206f) return ZoneId.PINE_FOREST
        return ZoneId.VALLEY_OUTSKIRTS
    }

    private fun computeAnalyticHeight(x: Float, z: Float): Float {
        val s1 = riverPhase
        val rolling =
            sin(x * 0.040f + s1) * cos(z * 0.038f - s1 * 0.7f) * 1.75f +
                sin((x + z) * 0.075f + s1 * 1.3f) * 0.45f

        var h = 3.05f + rolling * 0.45f

        // West Bank Urban Grid Plateau (Downtown, Midtown, Central Park, University, Stadium): flat 3.0m
        val townMask = (1f - smoothstep(94f, 114f, abs(x + 114f))) *
            (1f - smoothstep(148f, 166f, abs(z)))
        h = lerp(h, 3.0f, townMask * 0.97f)

        // East Bank North Ridge (Army Base): elevated 4.35m plateau
        val baseMask = (1f - smoothstep(70f, 88f, abs(x - 94f))) *
            (1f - smoothstep(56f, 70f, abs(z + 64f)))
        h = lerp(h, 4.35f, baseMask * 0.96f)

        // East Bank South Terrace (Corporate Tech Plaza, Industrial, Warehouses, Rail Yard): flat 3.35m yard
        val indMask = (1f - smoothstep(88f, 108f, abs(x - 114f))) *
            (1f - smoothstep(74f, 92f, abs(z - 82f)))
        h = lerp(h, 3.35f, indMask * 0.96f)

        // Triple Bridge approach ramps along z ~ -56, z ~ 0, and z ~ +56
        val northBand = 1f - smoothstep(3.8f, 9.5f, abs(z + 56f))
        val centralBand = 1f - smoothstep(3.8f, 9.5f, abs(z))
        val southBand = 1f - smoothstep(3.8f, 9.5f, abs(z - 56f))
        val bridgeBand = max(northBand, max(centralBand, southBand))
        if (x in -24f..-11f) {
            val t = smoothstep(-24f, -12f, x)
            h = lerp(h, lerp(3.0f, 3.25f, t), bridgeBand * 0.94f)
        } else if (x in 11f..28f) {
            val t = smoothstep(12f, 26f, x)
            val eastTarget = lerp(4.35f, 3.35f, smoothstep(-18f, 18f, z))
            h = lerp(h, lerp(3.25f, eastTarget, t), bridgeBand * 0.94f)
        }

        // Outer Pine Forest hills & valley rim
        val northHills = smoothstep(-146f, -186f, z) *
            (7.4f + sin(x * 0.055f + s1) * 2.6f + cos(z * 0.08f) * 1.6f)
        val southHills = smoothstep(154f, 188f, z) *
            (5.8f + cos(x * 0.055f - s1) * 2.4f)
        val outerRimX = smoothstep(208f, 250f, abs(x)) * 8.0f
        h += northHills + southHills + outerRimX

        // River channel carving down the valley spine
        val rx = riverCenterX(z)
        val distToRiver = abs(x - rx)
        val halfW = riverHalfWidth()
        val riverCut = 1f - smoothstep(halfW * 0.52f, halfW + 4.8f, distToRiver)
        val riverbedDepth = -1.75f + sin(x * 0.45f + z * 0.35f) * 0.22f
        h = lerp(h, riverbedDepth, riverCut)

        return h
    }

    fun groundSurfaceY(x: Float, z: Float): Float {
        val gx = clamp((x - MIN_X) / CELL_SIZE, 0f, GRID_CELLS_X.toFloat())
        val gz = clamp((z - MIN_Z) / CELL_SIZE, 0f, GRID_CELLS_Z.toFloat())
        val ix = min(floor(gx).toInt(), GRID_CELLS_X - 1)
        val iz = min(floor(gz).toInt(), GRID_CELLS_Z - 1)
        val fx = gx - ix
        val fz = gz - iz

        val h00 = heights[iz * GRID_VERTS_X + ix]
        val h10 = heights[iz * GRID_VERTS_X + ix + 1]
        val h01 = heights[(iz + 1) * GRID_VERTS_X + ix]
        val h11 = heights[(iz + 1) * GRID_VERTS_X + ix + 1]

        return lerp(lerp(h00, h10, fx), lerp(h01, h11, fx), fz)
    }

    fun groundNormal(x: Float, z: Float): Vec3 {
        val e = 0.6f
        val hL = groundSurfaceY(x - e, z)
        val hR = groundSurfaceY(x + e, z)
        val hD = groundSurfaceY(x, z - e)
        val hU = groundSurfaceY(x, z + e)
        return Vec3(hL - hR, 2f * e, hD - hU).normalized()
    }

    fun applyCrater(cx: Float, cz: Float, radius: Float, depth: Float, scorchAmount: Float = 0.9f) {
        val rimRadius = radius * 1.42f
        val minIx = max(1, floor((cx - rimRadius - MIN_X) / CELL_SIZE).toInt())
        val maxIx = min(GRID_VERTS_X - 2, floor((cx + rimRadius - MIN_X) / CELL_SIZE).toInt() + 1)
        val minIz = max(1, floor((cz - rimRadius - MIN_Z) / CELL_SIZE).toInt())
        val maxIz = min(GRID_VERTS_Z - 2, floor((cz + rimRadius - MIN_Z) / CELL_SIZE).toInt() + 1)

        if (minIx > maxIx || minIz > maxIz) return

        for (iz in minIz..maxIz) {
            val z = MIN_Z + iz * CELL_SIZE
            val dz = z - cz
            for (ix in minIx..maxIx) {
                val x = MIN_X + ix * CELL_SIZE
                val dx = x - cx
                val dist = sqrt(dx * dx + dz * dz)
                if (dist > rimRadius) continue

                val idx = iz * GRID_VERTS_X + ix
                if (dist <= radius) {
                    val u = dist / radius
                    val bowl = cos(u * (PI.toFloat() * 0.5f))
                    val delta = depth * bowl * bowl
                    heights[idx] = max(-3.8f, heights[idx] - delta)
                    scorch[idx] = clamp(scorch[idx] + scorchAmount * (1f - u * 0.55f), 0f, 1f)
                } else {
                    val u = (dist - radius) / (rimRadius - radius)
                    val rim = sin(u * PI.toFloat()) * depth * 0.20f
                    heights[idx] += rim
                    scorch[idx] = clamp(scorch[idx] + scorchAmount * 0.35f * (1f - u), 0f, 1f)
                }
            }
        }

        relaxSoil(minIx - 1, maxIx + 1, minIz - 1, maxIz + 1, iterations = 2)
        markDirtyRect(minIx - 2, maxIx + 2, minIz - 2, maxIz + 2)
    }

    fun sculptBrush(cx: Float, cz: Float, radius: Float, deltaY: Float) {
        val minIx = max(1, floor((cx - radius - MIN_X) / CELL_SIZE).toInt())
        val maxIx = min(GRID_VERTS_X - 2, floor((cx + radius - MIN_X) / CELL_SIZE).toInt() + 1)
        val minIz = max(1, floor((cz - radius - MIN_Z) / CELL_SIZE).toInt())
        val maxIz = min(GRID_VERTS_Z - 2, floor((cz + radius - MIN_Z) / CELL_SIZE).toInt() + 1)
        if (minIx > maxIx || minIz > maxIz) return

        for (iz in minIz..maxIz) {
            val z = MIN_Z + iz * CELL_SIZE
            for (ix in minIx..maxIx) {
                val x = MIN_X + ix * CELL_SIZE
                val dist = sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz))
                if (dist >= radius) continue
                val falloff = 0.5f * (1f + cos(PI.toFloat() * dist / radius))
                val idx = iz * GRID_VERTS_X + ix
                heights[idx] = clamp(heights[idx] + deltaY * falloff, -3.8f, 18f)
            }
        }
        relaxSoil(minIx - 1, maxIx + 1, minIz - 1, maxIz + 1, iterations = 1)
        markDirtyRect(minIx - 2, maxIx + 2, minIz - 2, maxIz + 2)
    }

    private fun relaxSoil(x0: Int, x1: Int, z0: Int, z1: Int, iterations: Int) {
        val minX = max(1, x0)
        val maxX = min(GRID_VERTS_X - 2, x1)
        val minZ = max(1, z0)
        val maxZ = min(GRID_VERTS_Z - 2, z1)
        val maxRise = 0.80f * CELL_SIZE

        repeat(iterations) {
            for (iz in minZ..maxZ) {
                for (ix in minX..maxX) {
                    val idx = iz * GRID_VERTS_X + ix
                    val h = heights[idx]
                    val neighbors = intArrayOf(idx - 1, idx + 1, idx - GRID_VERTS_X, idx + GRID_VERTS_X)
                    for (nIdx in neighbors) {
                        val diff = h - heights[nIdx]
                        if (diff > maxRise) {
                            val transfer = (diff - maxRise) * 0.22f
                            heights[idx] -= transfer
                            heights[nIdx] += transfer
                        }
                    }
                }
            }
        }
    }

    private fun markAllChunksDirty() {
        for (i in chunkVersions.indices) chunkVersions[i]++
    }

    private fun markDirtyRect(minIx: Int, maxIx: Int, minIz: Int, maxIz: Int) {
        val cMinX = clamp((minIx / CHUNK_CELLS).toFloat(), 0f, (CHUNKS_X - 1).toFloat()).toInt()
        val cMaxX = clamp((maxIx / CHUNK_CELLS).toFloat(), 0f, (CHUNKS_X - 1).toFloat()).toInt()
        val cMinZ = clamp((minIz / CHUNK_CELLS).toFloat(), 0f, (CHUNKS_Z - 1).toFloat()).toInt()
        val cMaxZ = clamp((maxIz / CHUNK_CELLS).toFloat(), 0f, (CHUNKS_Z - 1).toFloat()).toInt()
        for (cz in cMinZ..cMaxZ) {
            for (cx in cMinX..cMaxX) {
                chunkVersions[cz * CHUNKS_X + cx]++
            }
        }
    }

    private fun vertexNormal(ix: Int, iz: Int): Vec3 {
        val xL = max(0, ix - 1)
        val xR = min(GRID_VERTS_X - 1, ix + 1)
        val zD = max(0, iz - 1)
        val zU = min(GRID_VERTS_Z - 1, iz + 1)
        val hL = heights[iz * GRID_VERTS_X + xL]
        val hR = heights[iz * GRID_VERTS_X + xR]
        val hD = heights[zD * GRID_VERTS_X + ix]
        val hU = heights[zU * GRID_VERTS_X + ix]
        return Vec3(hL - hR, (xR - xL) * CELL_SIZE, hD - hU).normalized()
    }

    fun buildChunkVertices(chunkX: Int, chunkZ: Int): FloatArray {
        val startX = chunkX * CHUNK_CELLS
        val startZ = chunkZ * CHUNK_CELLS
        val totalVerts = CHUNK_CELLS * CHUNK_CELLS * 6
        val out = FloatArray(totalVerts * VERTEX_STRIDE_FLOATS)
        var ptr = 0

        for (lz in 0 until CHUNK_CELLS) {
            val iz = startZ + lz
            val z0 = MIN_Z + iz * CELL_SIZE
            val z1 = z0 + CELL_SIZE
            for (lx in 0 until CHUNK_CELLS) {
                val ix = startX + lx
                val x0 = MIN_X + ix * CELL_SIZE
                val x1 = x0 + CELL_SIZE

                val i00 = iz * GRID_VERTS_X + ix
                val i10 = i00 + 1
                val i01 = i00 + GRID_VERTS_X
                val i11 = i01 + 1

                val y00 = heights[i00]; val y10 = heights[i10]
                val y01 = heights[i01]; val y11 = heights[i11]
                val n00 = vertexNormal(ix, iz); val n10 = vertexNormal(ix + 1, iz)
                val n01 = vertexNormal(ix, iz + 1); val n11 = vertexNormal(ix + 1, iz + 1)

                val avgY = (y00 + y10 + y01 + y11) * 0.25f
                val avgScorch = (scorch[i00] + scorch[i10] + scorch[i01] + scorch[i11]) * 0.25f
                val avgNy = (n00.y + n10.y + n01.y + n11.y) * 0.25f
                val cx = (x0 + x1) * 0.5f
                val cz = (z0 + z1) * 0.5f

                val tile = chooseCellTile(cx, cz, avgY, avgNy, avgScorch)

                ptr = emitTerrainVert(out, ptr, x0, y00, z0, n00, scorch[i00], tile, cx, cz)
                ptr = emitTerrainVert(out, ptr, x0, y01, z1, n01, scorch[i01], tile, cx, cz)
                ptr = emitTerrainVert(out, ptr, x1, y11, z1, n11, scorch[i11], tile, cx, cz)

                ptr = emitTerrainVert(out, ptr, x0, y00, z0, n00, scorch[i00], tile, cx, cz)
                ptr = emitTerrainVert(out, ptr, x1, y11, z1, n11, scorch[i11], tile, cx, cz)
                ptr = emitTerrainVert(out, ptr, x1, y10, z0, n10, scorch[i10], tile, cx, cz)
            }
        }
        return out
    }

    private fun chooseCellTile(cx: Float, cz: Float, avgY: Float, avgNy: Float, avgScorch: Float): Int {
        if (avgY < WATER_LEVEL + 0.45f) return MaterialTile.RIVER_STONE
        if (avgScorch > 0.35f) return MaterialTile.SOIL
        val flatDelta = abs(avgY - baseHeights[clampIndex(cx, cz)])
        if (flatDelta < 0.45f) {
            if (isMainRoad(cx, cz)) return MaterialTile.ASPHALT
            if (isSidewalk(cx, cz)) return MaterialTile.CONCRETE
            if (isParkPath(cx, cz)) return MaterialTile.RIVER_STONE
            if (cx in 22f..162f && cz in -124f..-4f && abs(avgY - 4.35f) < 0.45f) {
                return MaterialTile.CONCRETE
            }
            if (cx in 22f..202f && cz in 4f..158f && abs(avgY - 3.35f) < 0.45f) {
                return MaterialTile.ASPHALT
            }
        }
        if (avgNy < 0.78f) return MaterialTile.SOIL
        return MaterialTile.TURF
    }

    private fun clampIndex(x: Float, z: Float): Int {
        val ix = clamp(floor((x - MIN_X) / CELL_SIZE), 0f, (GRID_VERTS_X - 1).toFloat()).toInt()
        val iz = clamp(floor((z - MIN_Z) / CELL_SIZE), 0f, (GRID_VERTS_Z - 1).toFloat()).toInt()
        return iz * GRID_VERTS_X + ix
    }

    private fun emitTerrainVert(
        out: FloatArray,
        startPtr: Int,
        x: Float,
        y: Float,
        z: Float,
        n: Vec3,
        scorchVal: Float,
        tile: Int,
        cellCx: Float,
        cellCz: Float
    ): Int {
        var r = 1.0f; var g = 1.0f; var b = 1.0f
        if (tile == MaterialTile.TURF) {
            val inPark = cellCx in -144f..-64f && cellCz in 8f..48f
            val inStadiumPitch = cellCx in -122f..-86f && cellCz in 122f..150f
            val patch = 0.92f + 0.08f * sin(x * 0.18f + z * 0.14f)
            if (inStadiumPitch) {
                val stripe = if ((floor(cellCx * 0.25f).toInt() and 1) == 0) 1.04f else 0.92f
                r = stripe * 0.78f; g = stripe * 1.14f; b = stripe * 0.74f
            } else if (inPark) {
                r = patch * 0.86f; g = patch * 1.08f; b = patch * 0.82f
            } else {
                r = patch * 0.92f; g = patch * 1.02f; b = patch * 0.88f
            }
        } else if (tile == MaterialTile.RIVER_STONE && y < WATER_LEVEL + 0.3f) {
            val wet = 0.62f + 0.25f * clamp((y - (WATER_LEVEL - 1.2f)) / 1.5f, 0f, 1f)
            r = wet * 0.92f; g = wet * 0.96f; b = wet
        } else if (tile == MaterialTile.RIVER_STONE) {
            // Central Park gravel footpaths
            r = 1.06f; g = 1.02f; b = 0.94f
        } else if (tile == MaterialTile.CONCRETE) {
            // Sidewalk and plaza tile grid variation
            val paver = ((floor(cellCx * 0.5f).toInt() + floor(cellCz * 0.5f).toInt()) and 1)
            val shade = if (paver == 0) 1.04f else 0.96f
            r = shade * 1.02f; g = shade * 1.01f; b = shade * 0.98f
        } else if (tile == MaterialTile.ASPHALT) {
            val isCrosswalk = isCrosswalkStripe(cellCx, cellCz)
            val isDash = (abs(cellCz) < 0.45f && (floor(abs(cellCx) * 0.35f).toInt() % 2 == 0)) ||
                (abs(cellCz + 112f) < 0.45f && (floor(abs(cellCx) * 0.35f).toInt() % 2 == 0)) ||
                (abs(cellCz + 56f) < 0.45f && (floor(abs(cellCx) * 0.35f).toInt() % 2 == 0)) ||
                (abs(cellCz - 56f) < 0.45f && (floor(abs(cellCx) * 0.35f).toInt() % 2 == 0)) ||
                (abs(cellCz - 112f) < 0.45f && (floor(abs(cellCx) * 0.35f).toInt() % 2 == 0)) ||
                (abs(cellCx + 58f) < 0.45f && (floor(abs(cellCz) * 0.35f).toInt() % 2 == 0)) ||
                (abs(cellCx + 104f) < 0.45f && (cellCz < 4f || cellCz > 52f) && (floor(abs(cellCz) * 0.35f).toInt() % 2 == 0)) ||
                (abs(cellCx + 150f) < 0.45f && (floor(abs(cellCz) * 0.35f).toInt() % 2 == 0)) ||
                (abs(cellCx + 196f) < 0.45f && (floor(abs(cellCz) * 0.35f).toInt() % 2 == 0)) ||
                (abs(cellCx - 56f) < 0.45f && (floor(abs(cellCz) * 0.35f).toInt() % 2 == 0)) ||
                (abs(cellCx - 108f) < 0.45f && (floor(abs(cellCz) * 0.35f).toInt() % 2 == 0)) ||
                (abs(cellCx - 162f) < 0.45f && (floor(abs(cellCz) * 0.35f).toInt() % 2 == 0))
            if (isCrosswalk || isDash) {
                r = 1.24f; g = 1.22f; b = 1.16f
            } else {
                r = 0.90f; g = 0.92f; b = 0.96f
            }
        }

        val scorchDarken = 1f - scorchVal * 0.68f
        r *= scorchDarken; g *= scorchDarken; b *= scorchDarken

        var p = startPtr
        out[p++] = x; out[p++] = y; out[p++] = z
        out[p++] = n.x; out[p++] = n.y; out[p++] = n.z
        out[p++] = r; out[p++] = g; out[p++] = b; out[p++] = 1f
        out[p++] = x * 0.28f; out[p++] = z * 0.28f
        out[p++] = tile.toFloat()
        return p
    }

    fun raycast(origin: Vec3, dir: Vec3, maxDist: Float = 200f): Vec3? {
        val step = 0.45f
        var d = 0.5f
        var prev = origin
        while (d <= maxDist) {
            val p = origin + dir * d
            if (p.x in MIN_X..MAX_X && p.z in MIN_Z..MAX_Z) {
                val gy = groundSurfaceY(p.x, p.z)
                if (p.y <= gy) {
                    // Binary refine
                    var lo = prev
                    var hi = p
                    repeat(5) {
                        val mid = (lo + hi) * 0.5f
                        if (mid.y <= groundSurfaceY(mid.x, mid.z)) hi = mid else lo = mid
                    }
                    return hi
                }
            }
            prev = p
            d += step
        }
        return null
    }
}
