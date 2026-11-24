package com.example.engine.render

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLUtils
import com.example.model.MaterialTile
import com.example.model.Vec3
import com.example.model.clamp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

object ProceduralAtlas {

    const val TILE_SIZE = 256
    const val TILE_PAD = 12
    const val INNER_TILE = TILE_SIZE - TILE_PAD * 2 // 232px inner tileable area
    const val GRID_DIM = 5
    const val ATLAS_SIZE = TILE_SIZE * GRID_DIM // 1280x1280 -> 25 slots (0..24)
    const val NORMAL_MAP_SIZE = 512

    private const val TAU = (2.0 * PI).toFloat()
    private const val SAMPLE_RES = 256

    data class AtlasTexturePair(
        val albedoAoTexId: Int,
        val pbrTexId: Int
    )

    fun createAndUploadAtlas(): Int = createAndUploadAtlasPair().albedoAoTexId

    /**
     * Generates and uploads a synchronized mipmapped 1280x1280 PBR atlas pair:
     * 1. Albedo + AO Atlas (RGB = tileable sRGB surface color, A = baked crevice Ambient Occlusion)
     * 2. PBR Material Atlas (RG = height-derived tangent-space Normal XY, B = Roughness, A = Metallic)
     */
    fun createAndUploadAtlasPair(): AtlasTexturePair {
        val albedoPixels = IntArray(ATLAS_SIZE * ATLAS_SIZE)
        val pbrPixels = IntArray(ATLAS_SIZE * ATLAS_SIZE)

        val tileColor = IntArray(SAMPLE_RES * SAMPLE_RES)
        val tileHeight = FloatArray(SAMPLE_RES * SAMPLE_RES)
        val tileRough = FloatArray(SAMPLE_RES * SAMPLE_RES)
        val tileMetal = FloatArray(SAMPLE_RES * SAMPLE_RES)
        val tileAo = FloatArray(SAMPLE_RES * SAMPLE_RES)

        for (tile in 0..18) {
            generateTileSurface(tile, tileColor, tileHeight, tileRough, tileMetal, tileAo)

            val tx = (tile % GRID_DIM) * TILE_SIZE
            val ty = (tile / GRID_DIM) * TILE_SIZE
            val normalScale = when (tile) {
                MaterialTile.GLASS -> 0.55f
                MaterialTile.PLASTIC -> 0.90f
                MaterialTile.TURF, MaterialTile.PINE_NEEDLE -> 1.45f
                MaterialTile.BRICK, MaterialTile.CORRUGATED_METAL, MaterialTile.RIVER_STONE -> 2.85f
                else -> 2.15f
            }

            for (y in 0 until TILE_SIZE) {
                val vRaw = (y - TILE_PAD).toFloat() / INNER_TILE
                val v = ((vRaw % 1f) + 1f) % 1f
                val sy = (v * SAMPLE_RES).toInt().coerceIn(0, SAMPLE_RES - 1)
                val syPrev = (sy - 1 + SAMPLE_RES) % SAMPLE_RES
                val syNext = (sy + 1) % SAMPLE_RES

                for (x in 0 until TILE_SIZE) {
                    val uRaw = (x - TILE_PAD).toFloat() / INNER_TILE
                    val u = ((uRaw % 1f) + 1f) % 1f
                    val sx = (u * SAMPLE_RES).toInt().coerceIn(0, SAMPLE_RES - 1)
                    val sxPrev = (sx - 1 + SAMPLE_RES) % SAMPLE_RES
                    val sxNext = (sx + 1) % SAMPLE_RES

                    val sIdx = sy * SAMPLE_RES + sx
                    val rgb = tileColor[sIdx]
                    val aoByte = (clamp(tileAo[sIdx], 0.35f, 1f) * 255f).toInt()
                    val atlasIdx = (ty + y) * ATLAS_SIZE + (tx + x)
                    albedoPixels[atlasIdx] = (aoByte shl 24) or (rgb and 0x00FFFFFF)

                    val hL = tileHeight[sy * SAMPLE_RES + sxPrev]
                    val hR = tileHeight[sy * SAMPLE_RES + sxNext]
                    val hD = tileHeight[syPrev * SAMPLE_RES + sx]
                    val hU = tileHeight[syNext * SAMPLE_RES + sx]
                    val n = Vec3((hL - hR) * normalScale, (hD - hU) * normalScale, 1.0f).normalized()

                    val nxB = (clamp(n.x * 0.5f + 0.5f, 0f, 1f) * 255f).toInt()
                    val nyB = (clamp(n.y * 0.5f + 0.5f, 0f, 1f) * 255f).toInt()
                    val roughB = (clamp(tileRough[sIdx], 0.04f, 1f) * 255f).toInt()
                    val metalB = (clamp(tileMetal[sIdx], 0f, 1f) * 255f).toInt()
                    pbrPixels[atlasIdx] = Color.argb(metalB, nxB, nyB, roughB)
                }
            }
        }

        val albedoTex = uploadMipmappedAtlas(albedoPixels)
        val pbrTex = uploadMipmappedAtlas(pbrPixels)
        return AtlasTexturePair(albedoAoTexId = albedoTex, pbrTexId = pbrTex)
    }

    private fun uploadMipmappedAtlas(pixels: IntArray): Int {
        val bmp = Bitmap.createBitmap(pixels, ATLAS_SIZE, ATLAS_SIZE, Bitmap.Config.ARGB_8888)
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        val texId = texIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        bmp.recycle()
        return texId
    }

    /**
     * Generates a tileable 512x512 RGB wave normal map modeled after Three.js Water.js normal textures.
     */
    fun createAndUploadWaterNormalMap(): Int {
        val size = NORMAL_MAP_SIZE
        val heights = FloatArray(size * size)

        for (y in 0 until size) {
            val v = y.toFloat() / size
            for (x in 0 until size) {
                val u = x.toFloat() / size
                var h = 0f
                h += sin((u * 3f + v * 2f) * TAU) * 0.34f
                h += cos((u * 5f - v * 4f) * TAU + 0.8f) * 0.24f
                h += sin((u * 9f + v * 7f) * TAU + 1.9f) * 0.14f
                h += cos((u * 13f - v * 11f) * TAU + 0.4f) * 0.08f
                h += (tileableNoise(u, v, 16) - 0.5f) * 0.28f
                h += (tileableNoise(u, v, 32) - 0.5f) * 0.14f
                heights[y * size + x] = h
            }
        }

        val pixels = IntArray(size * size)
        val strength = 3.2f
        for (y in 0 until size) {
            val yPrev = (y - 1 + size) % size
            val yNext = (y + 1) % size
            for (x in 0 until size) {
                val xPrev = (x - 1 + size) % size
                val xNext = (x + 1) % size

                val dX = (heights[y * size + xNext] - heights[y * size + xPrev]) * strength
                val dY = (heights[yNext * size + x] - heights[yPrev * size + x]) * strength
                val n = Vec3(-dX, -dY, 1.0f).normalized()

                val r = (clamp(n.x * 0.5f + 0.5f, 0f, 1f) * 255f).toInt()
                val g = (clamp(n.y * 0.5f + 0.5f, 0f, 1f) * 255f).toInt()
                val b = (clamp(n.z * 0.5f + 0.5f, 0f, 1f) * 255f).toInt()
                pixels[y * size + x] = Color.rgb(r, g, b)
            }
        }

        val bmp = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        val texId = texIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        bmp.recycle()
        return texId
    }

    /**
     * Generates a 256x256 (2x2 quadrants of 128x128) pre-baked volumetric particle sprite sheet:
     * (0,0) = Soft multi-lobe billowing smoke puff
     * (1,0) = Turbulent combustion fireball / flame tongue
     * (0,1) = Airy wispy collapse dust mist
     * (1,1) = Crisp radial spark / droplet glow
     */
    fun createAndUploadParticleAtlas(): Int {
        val size = 256
        val tile = 128
        val pixels = IntArray(size * size)

        val lobes = arrayOf(
            floatArrayOf(0.0f, 0.0f, 0.50f, 0.95f),
            floatArrayOf(-0.24f, 0.14f, 0.36f, 0.84f),
            floatArrayOf(0.26f, 0.10f, 0.35f, 0.86f),
            floatArrayOf(-0.14f, -0.22f, 0.34f, 0.78f),
            floatArrayOf(0.18f, -0.20f, 0.33f, 0.76f),
            floatArrayOf(0.02f, 0.28f, 0.30f, 0.92f)
        )

        for (q in 0 until 4) {
            val qx = (q % 2) * tile
            val qy = (q / 2) * tile
            for (y in 0 until tile) {
                val ny = (y.toFloat() / (tile - 1)) * 2f - 1f
                for (x in 0 until tile) {
                    val nx = (x.toFloat() / (tile - 1)) * 2f - 1f
                    val u = x.toFloat() / tile
                    val v = y.toFloat() / tile
                    val r = hypot(nx, ny)
                    val borderFade = clamp((0.92f - r) / 0.28f, 0f, 1f)
                    val smoothEdge = borderFade * borderFade * (3f - 2f * borderFade)

                    val argb = when (q) {
                        0 -> {
                            var lobeMask = 0f
                            var topHighlight = 0f
                            for (lb in lobes) {
                                val d = hypot(nx - lb[0], ny - lb[1]) / lb[2]
                                val contrib = clamp(1f - d * d, 0f, 1f)
                                lobeMask = maxOf(lobeMask, contrib * lb[3])
                                val localShade = clamp(0.62f + (ny - lb[1]) * 0.48f, 0.45f, 1.0f)
                                topHighlight = maxOf(topHighlight, contrib * localShade)
                            }
                            val curl = fbm(u * 2f, v * 2f, 6)
                            val fineCurl = tileableNoise(u, v, 16)
                            val density = clamp((lobeMask * 0.78f + (curl - 0.38f) * 0.56f) * smoothEdge, 0f, 1f)
                            val volShade = clamp(0.54f + topHighlight * 0.38f + (curl - 0.5f) * 0.24f, 0.42f, 1.0f)
                            val rimScatter = clamp(0.30f + (ny * 0.5f + 0.5f) * 0.48f + fineCurl * 0.22f, 0f, 1f)
                            Color.argb(
                                (density * 255f).toInt(),
                                (volShade * 255f).toInt(),
                                (rimScatter * 255f).toInt(),
                                (clamp(curl, 0f, 1f) * 255f).toInt()
                            )
                        }
                        1 -> {
                            val n = fbm(u * 2f + 0.31f, v * 2f + 0.17f, 8)
                            val edgeD = clamp((0.88f - r + (n - 0.5f) * 0.42f) / 0.48f, 0f, 1f) * smoothEdge
                            val coreHeat = clamp((0.56f - r + (n - 0.45f) * 0.28f) / 0.45f, 0f, 1f)
                            Color.argb(
                                (edgeD * 255f).toInt(),
                                (coreHeat * 255f).toInt(),
                                (clamp(n * 1.15f, 0f, 1f) * 255f).toInt(),
                                (clamp(edgeD * 0.9f, 0f, 1f) * 255f).toInt()
                            )
                        }
                        2 -> {
                            val n = fbm(u * 1.8f + 0.57f, v * 1.8f + 0.41f, 6)
                            val falloff = clamp(1f - r * r * 1.15f, 0f, 1f)
                            val wisps = clamp((falloff * 0.74f + (n - 0.42f) * 0.52f) * smoothEdge, 0f, 1f)
                            val shade = clamp(0.70f + (ny * 0.25f) + (n - 0.5f) * 0.22f, 0.50f, 1.0f)
                            Color.argb(
                                (wisps * 255f).toInt(),
                                (shade * 255f).toInt(),
                                (clamp(n, 0f, 1f) * 255f).toInt(),
                                (clamp(falloff, 0f, 1f) * 255f).toInt()
                            )
                        }
                        else -> {
                            val gCore = kotlin.math.exp((-r * r * 6.5f).toDouble()).toFloat() * smoothEdge
                            val halo = kotlin.math.exp((-r * r * 2.6f).toDouble()).toFloat() * smoothEdge
                            val alpha = clamp(maxOf(gCore, halo * 0.72f), 0f, 1f)
                            Color.argb(
                                (alpha * 255f).toInt(),
                                (clamp(gCore * 1.15f, 0f, 1f) * 255f).toInt(),
                                (clamp(halo, 0f, 1f) * 255f).toInt(),
                                255
                            )
                        }
                    }
                    pixels[(qy + y) * size + (qx + x)] = argb
                }
            }
        }

        val bmp = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        val texId = texIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        bmp.recycle()
        return texId
    }

    private fun hash2(x: Int, y: Int): Float {
        var n = x * 374761393 + y * 668265263
        n = (n xor (n ushr 13)) * 1274126177
        return ((n xor (n ushr 16)) and 0xFFFF) / 65535f
    }

    private fun tileableNoise(u: Float, v: Float, period: Int): Float {
        val fx = u * period
        val fy = v * period
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val x1 = (x0 + 1) % period
        val y1 = (y0 + 1) % period
        val mx = ((x0 % period) + period) % period
        val my = ((y0 % period) + period) % period
        val tx = fx - x0
        val ty = fy - y0
        val sx = tx * tx * (3f - 2f * tx)
        val sy = ty * ty * (3f - 2f * ty)

        val n00 = hash2(mx, my)
        val n10 = hash2(x1, my)
        val n01 = hash2(mx, y1)
        val n11 = hash2(x1, y1)
        val nx0 = n00 + (n10 - n00) * sx
        val nx1 = n01 + (n11 - n01) * sx
        return nx0 + (nx1 - nx0) * sy
    }

    private fun fbm(u: Float, v: Float, baseFreq: Int): Float {
        return tileableNoise(u, v, baseFreq) * 0.50f +
            tileableNoise(u, v, baseFreq * 2) * 0.28f +
            tileableNoise(u, v, baseFreq * 4) * 0.14f +
            tileableNoise(u, v, baseFreq * 8) * 0.08f
    }

    private fun pack(r: Float, g: Float, b: Float): Int {
        val ri = (clamp(r, 0f, 1f) * 255f).toInt()
        val gi = (clamp(g, 0f, 1f) * 255f).toInt()
        val bi = (clamp(b, 0f, 1f) * 255f).toInt()
        return Color.rgb(ri, gi, bi)
    }

    /**
     * Generates wrapped 256x256 Albedo, Height, Roughness, Metallic, and Crevice AO for a tile.
     * All sinusoidal frequencies are strictly integer multiples of TAU so every tile wraps with ZERO grid seams.
     */
    private fun generateTileSurface(
        tile: Int,
        colorOut: IntArray,
        heightOut: FloatArray,
        roughOut: FloatArray,
        metalOut: FloatArray,
        aoOut: FloatArray
    ) {
        for (py in 0 until SAMPLE_RES) {
            val v = py.toFloat() / SAMPLE_RES
            for (px in 0 until SAMPLE_RES) {
                val u = px.toFloat() / SAMPLE_RES
                val idx = py * SAMPLE_RES + px
                val n = fbm(u, v, 8)
                val fine = tileableNoise(u, v, 32)
                val micro = tileableNoise(u, v, 64)

                var col: Int
                var h = 0.5f
                var rough = 0.85f
                var metal = 0.0f
                var ao = 1.0f

                when (tile) {
                    MaterialTile.TURF -> {
                        // Strictly TAU-periodic grass blades + clover specks -> zero UV tile grid seams
                        val bladeA = sin(u * 12f * TAU + (n - 0.5f) * 3.8f) * 0.032f
                        val bladeB = cos(v * 10f * TAU + (fine - 0.5f) * 2.5f) * 0.025f
                        val tuft = (fine - 0.5f) * 0.13f + (micro - 0.5f) * 0.08f
                        val drySpeck = if (fine > 0.83f && n < 0.45f) 0.06f else 0f
                        col = pack(
                            0.21f + n * 0.11f + tuft + bladeA + drySpeck,
                            0.36f + n * 0.13f + tuft + bladeA + bladeB,
                            0.14f + n * 0.06f + (micro - 0.5f) * 0.04f
                        )
                        h = 0.45f + n * 0.25f + fine * 0.22f + bladeA * 2.5f
                        rough = clamp(0.86f + (micro - 0.5f) * 0.12f, 0.72f, 0.98f)
                        metal = 0.0f
                        ao = clamp(0.84f + (fine * 0.14f) + n * 0.08f, 0.70f, 1.0f)
                    }
                    MaterialTile.SOIL -> {
                        val grit = (fine - 0.5f) * 0.15f + (micro - 0.5f) * 0.08f
                        val pebble = if (micro > 0.84f) 0.09f else 0f
                        col = pack(0.35f + n * 0.12f + grit + pebble, 0.27f + n * 0.09f + grit + pebble * 0.8f, 0.19f + n * 0.06f + grit)
                        h = 0.42f + n * 0.28f + fine * 0.20f + pebble * 1.4f
                        rough = clamp(0.90f - pebble * 0.22f, 0.68f, 0.98f)
                        ao = clamp(0.80f + n * 0.18f + (fine - 0.5f) * 0.12f, 0.65f, 1.0f)
                    }
                    MaterialTile.ASPHALT -> {
                        val agg = if (fine > 0.78f) 0.09f else (fine - 0.5f) * 0.07f
                        val tarCrack = if (abs(n - 0.5f) < 0.012f && micro > 0.65f) -0.08f else 0f
                        val base = 0.19f + n * 0.07f + agg + tarCrack
                        col = pack(base, base + 0.01f, base + 0.02f)
                        h = 0.48f + (fine - 0.5f) * 0.26f + (micro - 0.5f) * 0.18f + tarCrack * 2.0f
                        rough = clamp(0.76f + (micro - 0.5f) * 0.18f - (if (fine > 0.82f) 0.14f else 0f), 0.54f, 0.94f)
                        metal = 0.02f
                        ao = clamp(0.86f + tarCrack * 2.5f + fine * 0.10f, 0.60f, 1.0f)
                    }
                    MaterialTile.RIVER_STONE -> {
                        val cobble = abs(sin(u * 4f * TAU + (n - 0.5f) * 2.4f) * cos(v * 4f * TAU - (n - 0.5f) * 2.4f))
                        val base = 0.28f + cobble * 0.18f + fine * 0.07f
                        col = pack(base * 0.96f, base * 0.98f, base * 1.03f)
                        h = 0.25f + cobble * 0.62f + fine * 0.10f
                        rough = clamp(0.64f - cobble * 0.20f + (micro - 0.5f) * 0.10f, 0.36f, 0.86f)
                        metal = 0.04f
                        ao = clamp(0.58f + cobble * 0.44f, 0.50f, 1.0f)
                    }
                    MaterialTile.CONCRETE -> {
                        val isSeam = px % 128 < 2 || py % 128 < 2
                        val isTieHole = ((px % 64) in 14..17) && ((py % 64) in 14..17)
                        val seam = if (isSeam) -0.10f else 0f
                        val tieHole = if (isTieHole) -0.14f else 0f
                        val dampStain = if (v > 0.70f) -0.09f * ((v - 0.70f) / 0.30f) * (0.6f + n * 0.4f) else 0f
                        val base = 0.62f + (n - 0.5f) * 0.15f + (fine - 0.5f) * 0.08f + (micro - 0.5f) * 0.04f + seam + tieHole + dampStain
                        col = pack(base, base * 0.99f, base * 0.95f)
                        h = 0.55f + (n - 0.5f) * 0.18f + (fine - 0.5f) * 0.14f + seam * 2.8f + tieHole * 3.0f
                        rough = clamp(0.84f + (fine - 0.5f) * 0.14f + dampStain * 0.8f, 0.64f, 0.96f)
                        metal = 0.02f
                        ao = clamp(0.96f + seam * 2.6f + tieHole * 2.8f + (n - 0.5f) * 0.10f, 0.52f, 1.0f)
                    }
                    MaterialTile.STUCCO -> {
                        val isLap = py % 24 < 2
                        val lap = if (isLap) -0.08f else 0f
                        val wash = (micro - 0.5f) * 0.05f - (if (v < 0.12f) 0.04f else 0f)
                        val base = 0.76f + (n - 0.5f) * 0.11f + (fine - 0.5f) * 0.05f + lap + wash
                        col = pack(base * 1.02f, base * 0.97f, base * 0.89f)
                        h = 0.56f + (fine - 0.5f) * 0.16f + (micro - 0.5f) * 0.12f + lap * 2.4f
                        rough = clamp(0.86f + (fine - 0.5f) * 0.10f, 0.72f, 0.96f)
                        ao = clamp(0.95f + lap * 2.2f, 0.68f, 1.0f)
                    }
                    MaterialTile.BRICK -> {
                        val rows = 12f
                        val cols = 6f
                        val rIdx = floor(v * rows).toInt()
                        val fv = (v * rows) - rIdx
                        val uShift = u + (rIdx % 2) * (0.5f / cols)
                        val cIdx = floor(uShift * cols).toInt()
                        val fu = (uShift * cols) - cIdx

                        val isMortar = fv < 0.13f || fu < 0.08f
                        if (isMortar) {
                            val m = 0.60f + (fine - 0.5f) * 0.09f + (micro - 0.5f) * 0.05f
                            col = pack(m, m * 0.98f, m * 0.94f)
                            h = 0.22f + (fine - 0.5f) * 0.12f
                            rough = 0.93f
                            ao = 0.64f
                        } else {
                            val shade = hash2(cIdx, rIdx) * 0.14f + (n - 0.5f) * 0.08f + (micro - 0.5f) * 0.05f
                            val soot = if (v < 0.15f) -0.05f else 0f
                            val edgeDist = minOf(fv - 0.13f, 1f - fv, fu - 0.08f, 1f - fu)
                            val bevel = clamp(edgeDist / 0.12f, 0f, 1f)
                            col = pack(0.56f + shade + soot, 0.26f + shade * 0.5f + soot, 0.20f + shade * 0.4f + soot)
                            h = 0.56f + bevel * 0.26f + (fine - 0.5f) * 0.12f
                            rough = clamp(0.66f + (fine - 0.5f) * 0.14f, 0.48f, 0.88f)
                            ao = clamp(0.76f + bevel * 0.24f, 0.68f, 1.0f)
                        }
                    }
                    MaterialTile.AWNING_TRIM -> {
                        val stripe = if (((u * 8f).toInt() % 2) == 0) 0.16f else -0.10f
                        val weave = (fine - 0.5f) * 0.06f + sin(v * 24f * TAU) * 0.02f
                        col = pack(0.64f + stripe + weave, 0.28f + stripe * 0.6f + weave, 0.24f + stripe * 0.5f + weave)
                        h = 0.50f + sin(v * 24f * TAU) * 0.08f + sin(u * 24f * TAU) * 0.08f
                        rough = 0.84f
                        ao = 0.92f
                    }
                    MaterialTile.ROOF_SLATE -> {
                        val row = floor(v * 12f).toInt()
                        val colIdx = floor((u + (row % 2) * 0.08f) * 8f).toInt()
                        val isEdge = (py % 21) < 2
                        val edge = if (isEdge) -0.11f else 0f
                        val vary = hash2(colIdx, row) * 0.10f
                        val moss = if (n > 0.66f) 0.03f else 0f
                        val b = 0.21f + vary + n * 0.07f + (micro - 0.5f) * 0.04f + edge
                        col = pack(b * 1.04f, b * 0.93f + moss, b * 0.90f)
                        val lapPos = ((py % 21) / 21f)
                        h = if (isEdge) 0.24f else (0.45f + lapPos * 0.34f + (fine - 0.5f) * 0.10f)
                        rough = clamp(0.60f + (fine - 0.5f) * 0.16f + moss * 4f, 0.42f, 0.88f)
                        metal = 0.04f
                        ao = if (isEdge) 0.60f else clamp(0.76f + lapPos * 0.24f, 0.65f, 1.0f)
                    }
                    MaterialTile.TIMBER -> {
                        val plankSlot = px / 64
                        val isPlankEdge = py % 32 < 2 || px % 64 < 2
                        val plankEdge = if (isPlankEdge) -0.14f else 0f
                        val plankTint = (hash2(plankSlot, py / 32) - 0.5f) * 0.06f
                        val knot = if (tileableNoise(u, v, 10) > 0.84f) -0.09f else 0f
                        val grain = sin(u * 4f * TAU + sin(v * 8f * TAU) * 0.7f + n * 5f) * 0.07f + (micro - 0.5f) * 0.04f
                        val base = 0.48f + grain + plankTint + n * 0.08f + plankEdge + knot
                        col = pack(base * 1.13f, base * 0.82f, base * 0.55f)
                        h = if (isPlankEdge) 0.20f else (0.52f + grain * 1.8f + (fine - 0.5f) * 0.08f)
                        rough = clamp(0.68f + (micro - 0.5f) * 0.16f - grain * 0.6f, 0.46f, 0.88f)
                        ao = if (isPlankEdge) 0.58f else clamp(0.88f + grain * 0.6f, 0.75f, 1.0f)
                    }
                    MaterialTile.PINE_BARK -> {
                        val furrow = sin(u * 10f * TAU + (n - 0.5f) * 4.5f) * 0.09f
                        val b = 0.28f + furrow + (fine - 0.5f) * 0.09f + (micro - 0.5f) * 0.04f
                        col = pack(b * 1.15f, b * 0.84f, b * 0.64f)
                        h = 0.50f + furrow * 2.8f + (fine - 0.5f) * 0.14f
                        rough = 0.92f
                        ao = clamp(0.76f + furrow * 2.0f, 0.54f, 1.0f)
                    }
                    MaterialTile.PINE_NEEDLE -> {
                        val cluster = tileableNoise(u, v, 16)
                        val needle = (fine - 0.5f) * 0.15f + (micro - 0.5f) * 0.05f
                        col = pack(0.11f + cluster * 0.08f, 0.28f + cluster * 0.14f + needle, 0.14f + cluster * 0.06f)
                        h = 0.45f + cluster * 0.35f + (fine - 0.5f) * 0.24f
                        rough = 0.84f
                        ao = clamp(0.74f + cluster * 0.26f, 0.60f, 1.0f)
                    }
                    MaterialTile.STEEL -> {
                        val brush = sin(u * 24f * TAU) * 0.022f + (n - 0.5f) * 0.11f + (micro - 0.5f) * 0.04f
                        val isRivet = (px % 48 in 4..7) && (py % 48 in 4..7)
                        val isSeam = px % 128 < 2 || py % 128 < 2
                        val isWeld = px % 128 in 2..4 || py % 128 in 2..4
                        val rivet = if (isRivet) -0.14f else 0f
                        val seam = if (isSeam) -0.14f else 0f
                        val weldHi = if (isWeld) 0.08f else 0f
                        val b = 0.53f + brush + rivet + seam + weldHi
                        col = pack(b * 0.94f, b * 0.99f, b * 1.07f)
                        h = when {
                            isSeam -> 0.16f
                            isRivet -> 0.86f
                            isWeld -> 0.70f
                            else -> 0.52f + brush * 1.5f
                        }
                        rough = when {
                            isSeam -> 0.62f
                            isRivet -> 0.22f
                            else -> clamp(0.28f + (n - 0.5f) * 0.14f + (micro - 0.5f) * 0.06f, 0.16f, 0.45f)
                        }
                        metal = if (isSeam) 0.48f else 0.90f
                        ao = when {
                            isSeam -> 0.54f
                            isRivet -> 0.82f
                            else -> clamp(0.93f + (n - 0.5f) * 0.07f, 0.80f, 1.0f)
                        }
                    }
                    MaterialTile.CORRUGATED_METAL -> {
                        val ribWave = sin(u * 20f * TAU)
                        val rib = ribWave * 0.11f
                        val isRust = fine > 0.80f
                        val ox = if (isRust) 0.05f else 0f
                        val isSeam = py % 128 < 2
                        val seam = if (isSeam) -0.10f else 0f
                        val b = 0.42f + rib + (n - 0.5f) * 0.11f + (micro - 0.5f) * 0.04f + seam
                        col = pack(b * 0.92f + ox, b * 0.98f + ox * 0.3f, b * 0.90f)
                        h = 0.50f + ribWave * 0.36f + (if (isSeam) -0.22f else 0f)
                        rough = if (isRust) 0.84f else clamp(0.36f + (n - 0.5f) * 0.14f, 0.24f, 0.56f)
                        metal = if (isRust) 0.18f else 0.76f
                        ao = clamp(0.84f + ribWave * 0.16f + (if (isSeam) -0.24f else 0f), 0.54f, 1.0f)
                    }
                    MaterialTile.GLASS -> {
                        val isFrame = px % 128 < 4 || py % 128 < 4
                        val isMullion = px % 64 < 2 && !isFrame
                        val isBevel = !isFrame && !isMullion && (px % 128 in 4..5 || py % 128 in 4..5)
                        if (isFrame || isMullion) {
                            val frameTone = if (isFrame) 0.14f else 0.22f
                            col = pack(frameTone, frameTone + 0.02f, frameTone + 0.04f)
                            h = if (isFrame) 0.78f else 0.70f
                            rough = 0.28f
                            metal = 0.88f
                            ao = 0.82f
                        } else {
                            val bevel = if (isBevel) 0.08f else 0f
                            val microTint = (n - 0.5f) * 0.025f
                            col = pack(0.36f + microTint + bevel, 0.58f + microTint + bevel, 0.74f + microTint + bevel)
                            h = if (isBevel) 0.58f else 0.44f
                            rough = 0.04f
                            metal = 0.16f
                            ao = 0.99f
                        }
                    }
                    MaterialTile.SANDBAG -> {
                        val weave = sin(u * 22f * TAU) * sin(v * 22f * TAU) * 0.03f
                        val bagRow = floor(v * 6f).toInt()
                        val bagCol = floor((u + (bagRow % 2) * 0.25f) * 3f).toInt()
                        val isSeam = (py % 42) < 3 || ((px + (bagRow % 2) * 42) % 85) < 3
                        val seam = if (isSeam) -0.14f else 0f
                        val vary = hash2(bagCol, bagRow) * 0.07f
                        val b = 0.56f + weave + vary + (n - 0.5f) * 0.08f + seam
                        col = pack(b * 1.08f, b * 0.96f, b * 0.74f)
                        h = if (isSeam) 0.22f else (0.58f + weave * 2.2f)
                        rough = 0.93f
                        ao = if (isSeam) 0.56f else 0.92f
                    }
                    MaterialTile.PLASTIC -> {
                        val isBorder = px % 128 < 3 || py % 128 < 3
                        val isRib = !isBorder && (px % 128 in 3..5 || py % 128 in 3..5)
                        val border = if (isBorder) -0.12f else 0f
                        val rib = if (isRib) 0.08f else 0f
                        val mFine = (fine - 0.5f) * 0.025f
                        val b = 0.80f + mFine + border + rib
                        col = pack(b * 0.96f, b * 0.99f, b * 1.02f)
                        h = when {
                            isBorder -> 0.28f
                            isRib -> 0.68f
                            else -> 0.52f
                        }
                        rough = if (isBorder) 0.64f else 0.36f
                        metal = 0.04f
                        ao = if (isBorder) 0.70f else 0.96f
                    }
                    MaterialTile.STAIR_TREAD -> {
                        val stepSlot = py % 32
                        val nosing = stepSlot in 0..4
                        if (nosing) {
                            col = pack(0.86f, 0.66f, 0.22f)
                            h = 0.74f
                            rough = 0.48f
                            metal = 0.24f
                            ao = 0.94f
                        } else {
                            val isGroove = stepSlot % 6 < 2
                            val groove = if (isGroove) -0.10f else 0.03f
                            val b = 0.40f + groove + (n - 0.5f) * 0.06f
                            col = pack(b * 1.06f, b * 0.95f, b * 0.84f)
                            h = if (isGroove) 0.28f else 0.56f
                            rough = 0.62f
                            metal = 0.16f
                            ao = if (isGroove) 0.66f else 0.92f
                        }
                    }
                    else -> {
                        // MaterialTile.CLOCK_FACE
                        val dx = u - 0.5f
                        val dy = v - 0.5f
                        val r = hypot(dx, dy)
                        val angle = atan2(dy, dx)
                        val isRim = r in 0.40f..0.46f
                        val isTick = r in 0.32f..0.39f && abs(sin(angle * 6f)) < 0.08f
                        val isMinuteHand = r < 0.30f && abs(dx) < 0.022f && dy < 0.03f
                        val isHourHand = r < 0.21f && abs(dy) < 0.024f && dx > -0.02f
                        when {
                            isRim || isTick || isMinuteHand || isHourHand -> {
                                col = pack(0.12f, 0.14f, 0.18f)
                                h = 0.74f
                                rough = 0.28f
                                metal = 0.82f
                            }
                            r > 0.46f -> {
                                col = pack(0.54f, 0.48f, 0.36f)
                                h = 0.50f
                                rough = 0.76f
                            }
                            else -> {
                                col = pack(0.95f, 0.93f, 0.84f)
                                h = 0.45f
                                rough = 0.42f
                            }
                        }
                    }
                }

                colorOut[idx] = col
                heightOut[idx] = h
                roughOut[idx] = rough
                metalOut[idx] = metal
                aoOut[idx] = ao
            }
        }
    }
}
