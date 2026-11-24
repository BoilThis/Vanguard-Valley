package com.example.engine.render

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.example.engine.physics.StructuralWorld
import com.example.engine.sim.DynamicPointLight
import com.example.engine.sim.RenderSnapshot
import com.example.engine.terrain.ChunkedTerrain
import com.example.model.LightingPreset
import com.example.model.Vec3
import com.example.model.clamp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class ValleyRenderer(
    private val snapshotProvider: () -> RenderSnapshot,
    private val onFpsUpdate: (Int) -> Unit
) : GLSurfaceView.Renderer {

    private class WorldHandles(prog: Int) {
        val uMVP = GLES20.glGetUniformLocation(prog, "uMVP")
        val uLightVP = GLES20.glGetUniformLocation(prog, "uLightVP")
        val uSunDir = GLES20.glGetUniformLocation(prog, "uSunDir")
        val uSunColor = GLES20.glGetUniformLocation(prog, "uSunColor")
        val uSkyColor = GLES20.glGetUniformLocation(prog, "uSkyColor")
        val uFogColor = GLES20.glGetUniformLocation(prog, "uFogColor")
        val uCamPos = GLES20.glGetUniformLocation(prog, "uCamPos")
        val uShadowFocus = GLES20.glGetUniformLocation(prog, "uShadowFocus")
        val uShadowStrength = GLES20.glGetUniformLocation(prog, "uShadowStrength")
        val uHasShadow = GLES20.glGetUniformLocation(prog, "uHasShadow")
        val uClipActive = GLES20.glGetUniformLocation(prog, "uClipActive")
        val uClipY = GLES20.glGetUniformLocation(prog, "uClipY")
        val uFastPass = GLES20.glGetUniformLocation(prog, "uFastPass")
        val uPointLightCount = GLES20.glGetUniformLocation(prog, "uPointLightCount")
        val uPointLightPos = GLES20.glGetUniformLocation(prog, "uPointLightPos")
        val uPointLightColor = GLES20.glGetUniformLocation(prog, "uPointLightColor")
        val uAtlas = GLES20.glGetUniformLocation(prog, "uAtlas")
        val uPbrAtlas = GLES20.glGetUniformLocation(prog, "uPbrAtlas")
        val uShadowMap = GLES20.glGetUniformLocation(prog, "uShadowMap")
        val uWaterLevel = GLES20.glGetUniformLocation(prog, "uWaterLevel")
        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
        val aNormal = GLES20.glGetAttribLocation(prog, "aNormal")
        val aColor = GLES20.glGetAttribLocation(prog, "aColor")
        val aUV = GLES20.glGetAttribLocation(prog, "aUV")
        val aTile = GLES20.glGetAttribLocation(prog, "aTile")
    }

    private class ShadowHandles(prog: Int) {
        val uLightVP = GLES20.glGetUniformLocation(prog, "uLightVP")
        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
    }

    private class SkyHandles(prog: Int) {
        val uCamLook = GLES20.glGetUniformLocation(prog, "uCamLook")
        val uCamRight = GLES20.glGetUniformLocation(prog, "uCamRight")
        val uCamUp = GLES20.glGetUniformLocation(prog, "uCamUp")
        val uTanHalfFov = GLES20.glGetUniformLocation(prog, "uTanHalfFov")
        val uAspect = GLES20.glGetUniformLocation(prog, "uAspect")
        val uSunDir = GLES20.glGetUniformLocation(prog, "uSunDir")
        val uZenithColor = GLES20.glGetUniformLocation(prog, "uZenithColor")
        val uHorizonColor = GLES20.glGetUniformLocation(prog, "uHorizonColor")
        val uSunColor = GLES20.glGetUniformLocation(prog, "uSunColor")
        val uTime = GLES20.glGetUniformLocation(prog, "uTime")
        val uTurbidity = GLES20.glGetUniformLocation(prog, "uTurbidity")
        val uMieG = GLES20.glGetUniformLocation(prog, "uMieG")
        val uCloudCover = GLES20.glGetUniformLocation(prog, "uCloudCover")
        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
    }

    private class WaterHandles(prog: Int) {
        val uMVP = GLES20.glGetUniformLocation(prog, "uMVP")
        val uReflVP = GLES20.glGetUniformLocation(prog, "uReflVP")
        val uTime = GLES20.glGetUniformLocation(prog, "uTime")
        val uCamPos = GLES20.glGetUniformLocation(prog, "uCamPos")
        val uSunDir = GLES20.glGetUniformLocation(prog, "uSunDir")
        val uSunColor = GLES20.glGetUniformLocation(prog, "uSunColor")
        val uSkyColor = GLES20.glGetUniformLocation(prog, "uSkyColor")
        val uFogColor = GLES20.glGetUniformLocation(prog, "uFogColor")
        val uHasReflection = GLES20.glGetUniformLocation(prog, "uHasReflection")
        val uReflMix = GLES20.glGetUniformLocation(prog, "uReflMix")
        val uWaterNormal = GLES20.glGetUniformLocation(prog, "uWaterNormal")
        val uReflectionTex = GLES20.glGetUniformLocation(prog, "uReflectionTex")
        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
        val aDepth = GLES20.glGetAttribLocation(prog, "aDepth")
    }

    private class LineHandles(prog: Int) {
        val uMVP = GLES20.glGetUniformLocation(prog, "uMVP")
        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
        val aColor = GLES20.glGetAttribLocation(prog, "aColor")
    }

    private class ParticleHandles(prog: Int) {
        val uMVP = GLES20.glGetUniformLocation(prog, "uMVP")
        val uSunDir = GLES20.glGetUniformLocation(prog, "uSunDir")
        val uParticleTex = GLES20.glGetUniformLocation(prog, "uParticleTex")
        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
        val aNormal = GLES20.glGetAttribLocation(prog, "aNormal")
        val aColor = GLES20.glGetAttribLocation(prog, "aColor")
        val aUV = GLES20.glGetAttribLocation(prog, "aUV")
        val aTile = GLES20.glGetAttribLocation(prog, "aTile")
    }

    private var worldProg = 0
    private var shadowProg = 0
    private var waterProg = 0
    private var skyProg = 0
    private var lineProg = 0
    private var particleProg = 0

    private lateinit var worldH: WorldHandles
    private lateinit var shadowH: ShadowHandles
    private lateinit var waterH: WaterHandles
    private lateinit var skyH: SkyHandles
    private lateinit var lineH: LineHandles
    private lateinit var particleH: ParticleHandles

    private var atlasTexId = 0
    private var pbrTexId = 0
    private var waterNormalTexId = 0
    private var particleTexId = 0

    // Directional Sun Shadow Map FBO (1024x1024 RGBA RG-16bit packed depth)
    private var shadowFbo = 0
    private var shadowTexId = 0
    private var shadowDepthRb = 0
    private var hasShadowFbo = false
    private val shadowSize = 1024

    // Planar Water Reflection FBO (512x512)
    private var reflectionFbo = 0
    private var reflectionTexId = 0
    private var reflectionDepthRb = 0
    private var hasReflectionFbo = false
    private val reflSize = 512

    private var viewWidth = 1
    private var viewHeight = 1

    // Resident GPU VBOs for terrain chunks + bounds for frustum culling
    private val chunkCountTotal = ChunkedTerrain.CHUNKS_X * ChunkedTerrain.CHUNKS_Z
    private val chunkVbos = IntArray(chunkCountTotal)
    private val chunkVertCounts = IntArray(chunkCountTotal)
    private val chunkVersions = IntArray(chunkCountTotal) { -1 }
    private val chunkMinX = FloatArray(chunkCountTotal)
    private val chunkMaxX = FloatArray(chunkCountTotal)
    private val chunkMinY = FloatArray(chunkCountTotal) { -10f }
    private val chunkMaxY = FloatArray(chunkCountTotal) { 20f }
    private val chunkMinZ = FloatArray(chunkCountTotal)
    private val chunkMaxZ = FloatArray(chunkCountTotal)

    // Resident GPU VBOs for 24 building sectors + bounds for frustum culling
    private val sectorCountTotal = StructuralWorld.SECTOR_COUNT
    private val sectorOpaqueVbos = IntArray(sectorCountTotal)
    private val sectorGlassVbos = IntArray(sectorCountTotal)
    private val sectorOpaqueVertCounts = IntArray(sectorCountTotal)
    private val sectorGlassVertCounts = IntArray(sectorCountTotal)
    private val sectorVersions = IntArray(sectorCountTotal) { -1 }
    private val sectorHasGeom = BooleanArray(sectorCountTotal)
    private val sectorMinX = FloatArray(sectorCountTotal)
    private val sectorMinY = FloatArray(sectorCountTotal)
    private val sectorMinZ = FloatArray(sectorCountTotal)
    private val sectorMaxX = FloatArray(sectorCountTotal)
    private val sectorMaxY = FloatArray(sectorCountTotal)
    private val sectorMaxZ = FloatArray(sectorCountTotal)

    // Static resident VBOs
    private var waterVbo = 0
    private var waterVertCount = 0
    private var cachedTerrainVersion = -1
    private var hasAnyGlassVerts = false

    private var sleepingRubbleVbo = 0
    private var sleepingRubbleVertCount = 0
    private var cachedSleepingRubbleVersion = -1

    private var skyVbo = 0

    private var dynamicVbo = 0
    private var dynamicVertCount = 0
    private var lastUploadedSnapshotVersion = -1
    private var shockwaveVbo = 0
    private var particleVbo = 0
    private var lineVbo = 0

    // Adaptive offscreen pass scheduling (Shadow + Planar Water Reflection)
    private var renderFrameIndex = 0
    private var shadowCacheTerrainVer = -1
    private var shadowCacheStructVer = -1
    private var shadowCacheSleepVer = -1
    private var shadowCacheLighting: LightingPreset? = null
    private var lastShadowFocusX = Float.NaN
    private var lastShadowFocusZ = Float.NaN
    private var lastShadowHalfSpan = Float.NaN
    private var lastReflFrame = -10
    private var hasRenderedRefl = false

    // Single reusable native staging buffer for VBO uploads (eliminates per-frame buffer churn)
    private var uploadStaging: FloatBuffer? = null

    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)

    private val lightProjMatrix = FloatArray(16)
    private val lightViewMatrix = FloatArray(16)
    private val lightVpMatrix = FloatArray(16)
    private val renderedLightVpMatrix = FloatArray(16)
    private var renderedShadowFocus = Vec3(0f, 4f, 0f)

    private val reflViewMatrix = FloatArray(16)
    private val reflVpMatrix = FloatArray(16)
    private val renderedReflVpMatrix = FloatArray(16)

    private val frustumPlanes = FloatArray(24) // 6 planes * (a, b, c, d)

    private val pointLightPosArray = FloatArray(12)
    private val pointLightColArray = FloatArray(9)

    private var fpsFrames = 0
    private var fpsLastTime = System.nanoTime()

    init {
        val chunkSpan = ChunkedTerrain.CHUNK_CELLS * ChunkedTerrain.CELL_SIZE
        for (idx in 0 until chunkCountTotal) {
            val cx = idx % ChunkedTerrain.CHUNKS_X
            val cz = idx / ChunkedTerrain.CHUNKS_X
            chunkMinX[idx] = ChunkedTerrain.MIN_X + cx * chunkSpan
            chunkMaxX[idx] = chunkMinX[idx] + chunkSpan
            chunkMinZ[idx] = ChunkedTerrain.MIN_Z + cz * chunkSpan
            chunkMaxZ[idx] = chunkMinZ[idx] + chunkSpan
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.62f, 0.79f, 0.92f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glCullFace(GLES20.GL_BACK)

        val atlasPair = ProceduralAtlas.createAndUploadAtlasPair()
        atlasTexId = atlasPair.albedoAoTexId
        pbrTexId = atlasPair.pbrTexId
        waterNormalTexId = ProceduralAtlas.createAndUploadWaterNormalMap()
        particleTexId = ProceduralAtlas.createAndUploadParticleAtlas()

        worldProg = compileProgram(ValleyShaders.WORLD_VERT_SHADER, ValleyShaders.WORLD_FRAG_SHADER)
        shadowProg = compileProgram(ValleyShaders.SHADOW_VERT_SHADER, ValleyShaders.SHADOW_FRAG_SHADER)
        waterProg = compileProgram(ValleyShaders.WATER_VERT_SHADER, ValleyShaders.WATER_FRAG_SHADER)
        skyProg = compileProgram(ValleyShaders.SKY_VERT_SHADER, ValleyShaders.SKY_FRAG_SHADER)
        lineProg = compileProgram(ValleyShaders.LINE_VERT_SHADER, ValleyShaders.LINE_FRAG_SHADER)
        particleProg = compileProgram(ValleyShaders.PARTICLE_VERT_SHADER, ValleyShaders.PARTICLE_FRAG_SHADER)

        worldH = WorldHandles(worldProg)
        shadowH = ShadowHandles(shadowProg)
        waterH = WaterHandles(waterProg)
        skyH = SkyHandles(skyProg)
        lineH = LineHandles(lineProg)
        particleH = ParticleHandles(particleProg)

        allocateVbos()
        buildSkyQuad()
        setupShadowFbo()
        setupReflectionFbo()

        chunkVersions.fill(-1)
        sectorVersions.fill(-1)
        cachedTerrainVersion = -1
        cachedSleepingRubbleVersion = -1
        lastUploadedSnapshotVersion = -1
        shadowCacheTerrainVer = -1
        shadowCacheStructVer = -1
        shadowCacheSleepVer = -1
        shadowCacheLighting = null
        lastShadowFocusX = Float.NaN
        lastShadowFocusZ = Float.NaN
        lastShadowHalfSpan = Float.NaN
        lastReflFrame = -10
        hasRenderedRefl = false
    }

    private fun allocateVbos() {
        GLES20.glGenBuffers(chunkCountTotal, chunkVbos, 0)
        GLES20.glGenBuffers(sectorCountTotal, sectorOpaqueVbos, 0)
        GLES20.glGenBuffers(sectorCountTotal, sectorGlassVbos, 0)
        val extra = IntArray(7)
        GLES20.glGenBuffers(extra.size, extra, 0)
        waterVbo = extra[0]
        sleepingRubbleVbo = extra[1]
        skyVbo = extra[2]
        dynamicVbo = extra[3]
        shockwaveVbo = extra[4]
        particleVbo = extra[5]
        lineVbo = extra[6]
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width.coerceAtLeast(1)
        viewHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewWidth, viewHeight)
        val aspect = viewWidth.toFloat() / viewHeight.toFloat()
        Matrix.perspectiveM(projMatrix, 0, 62f, aspect, 0.25f, 780f)
    }

    private fun setupShadowFbo() {
        val fbos = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        shadowFbo = fbos[0]

        val texs = IntArray(1)
        GLES20.glGenTextures(1, texs, 0)
        shadowTexId = texs[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, shadowTexId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            shadowSize, shadowSize, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val rbs = IntArray(1)
        GLES20.glGenRenderbuffers(1, rbs, 0)
        shadowDepthRb = rbs[0]
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, shadowDepthRb)
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, shadowSize, shadowSize)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, shadowFbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, shadowTexId, 0
        )
        GLES20.glFramebufferRenderbuffer(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT,
            GLES20.GL_RENDERBUFFER, shadowDepthRb
        )

        hasShadowFbo = (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun setupReflectionFbo() {
        val fbos = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        reflectionFbo = fbos[0]

        val texs = IntArray(1)
        GLES20.glGenTextures(1, texs, 0)
        reflectionTexId = texs[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, reflectionTexId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            reflSize, reflSize, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val rbs = IntArray(1)
        GLES20.glGenRenderbuffers(1, rbs, 0)
        reflectionDepthRb = rbs[0]
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, reflectionDepthRb)
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, reflSize, reflSize)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, reflectionFbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, reflectionTexId, 0
        )
        GLES20.glFramebufferRenderbuffer(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT,
            GLES20.GL_RENDERBUFFER, reflectionDepthRb
        )

        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        hasReflectionFbo = (status == GLES20.GL_FRAMEBUFFER_COMPLETE)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        val snap = snapshotProvider()
        updateFpsCounter()
        syncDirtyBuffers(snap)

        val sunDir = sunDirectionFor(snap.lightingPreset)
        val skyParams = skyParamsFor(snap.lightingPreset)
        val (skyZenith, skyHorizon, sunColor) = lightingColorsFor(snap.lightingPreset)
        val fogColor = acesFilmicToneMap(skyHorizon * 1.08f)
        val shadowStrength = if (snap.lightingPreset == LightingPreset.OVERCAST) 0.25f else 0.56f

        val shakeX = if (snap.shakeIntensity > 0.001f) sin(snap.waterTime * 57f) * snap.shakeIntensity else 0f
        val shakeY = if (snap.shakeIntensity > 0.001f) cos(snap.waterTime * 43f) * snap.shakeIntensity else 0f
        val eye = snap.cameraPos + Vec3(shakeX, shakeY, 0f)
        val look = snap.cameraLookDir.normalized()
        val target = eye + look

        Matrix.setLookAtM(
            viewMatrix, 0,
            eye.x, eye.y, eye.z,
            target.x, target.y, target.z,
            0f, 1f, 0f
        )
        Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)

        // Compute texel-snapped Sun Light Orthographic VP matrix centered ahead of camera (scales smoothly for tall skyscrapers)
        val flatLook = Vec3(look.x, 0f, look.z)
        val flatNorm = if (flatLook.lenSq() > 1e-4f) flatLook.normalized() else Vec3(1f, 0f, 0f)
        val camAlt = (eye.y - 4f).coerceIn(0f, 115f)
        val halfSpan = 62f + camAlt * 0.65f
        val texelWorld = (2f * halfSpan) / shadowSize.toFloat()
        val rawFocusX = eye.x + flatNorm.x * (18f + camAlt * 0.22f)
        val rawFocusZ = eye.z + flatNorm.z * (18f + camAlt * 0.22f)
        val shadowFocus = Vec3(
            kotlin.math.round(rawFocusX / texelWorld) * texelWorld,
            4.0f,
            kotlin.math.round(rawFocusZ / texelWorld) * texelWorld
        )
        val lightEye = shadowFocus + sunDir * (155f + camAlt * 0.85f)
        Matrix.orthoM(lightProjMatrix, 0, -halfSpan, halfSpan, -halfSpan, halfSpan, 4f, 290f + camAlt * 1.35f)
        Matrix.setLookAtM(
            lightViewMatrix, 0,
            lightEye.x, lightEye.y, lightEye.z,
            shadowFocus.x, shadowFocus.y, shadowFocus.z,
            0f, 1f, 0f
        )
        Matrix.multiplyMM(lightVpMatrix, 0, lightProjMatrix, 0, lightViewMatrix, 0)

        renderFrameIndex++

        // PASS 0: Adaptive Directional Sun Shadow Map FBO (renderedLightVpMatrix stays locked to the rendered texture)
        val moveSq = if (lastShadowFocusX.isNaN()) Float.MAX_VALUE else {
            (shadowFocus.x - lastShadowFocusX) * (shadowFocus.x - lastShadowFocusX) +
                (shadowFocus.z - lastShadowFocusZ) * (shadowFocus.z - lastShadowFocusZ)
        }
        val spanChanged = lastShadowHalfSpan.isNaN() || kotlin.math.abs(halfSpan - lastShadowHalfSpan) > 2.5f
        val shadowFocusMoved = moveSq > (texelWorld * 3.5f) * (texelWorld * 3.5f) || spanChanged
        val shadowWorldDirty = shadowCacheTerrainVer != snap.terrainVersion ||
            shadowCacheStructVer != snap.structureVersion ||
            shadowCacheSleepVer != snap.sleepingRubbleVersion ||
            shadowCacheLighting != snap.lightingPreset
        val shadowHasMovingShards = snap.activeShardCount > 0 && ((renderFrameIndex and 1) == 0)
        if (hasShadowFbo && (shadowWorldDirty || shadowFocusMoved || shadowHasMovingShards)) {
            shadowCacheTerrainVer = snap.terrainVersion
            shadowCacheStructVer = snap.structureVersion
            shadowCacheSleepVer = snap.sleepingRubbleVersion
            shadowCacheLighting = snap.lightingPreset
            lastShadowFocusX = shadowFocus.x
            lastShadowFocusZ = shadowFocus.z
            lastShadowHalfSpan = halfSpan
            lightVpMatrix.copyInto(renderedLightVpMatrix)
            renderedShadowFocus = shadowFocus

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, shadowFbo)
            GLES20.glViewport(0, 0, shadowSize, shadowSize)
            GLES20.glClearColor(1f, 1f, 1f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            renderShadowPass()
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glClearColor(0.62f, 0.79f, 0.92f, 1f)
        }

        val waterH = ChunkedTerrain.WATER_LEVEL
        val reflEye = Vec3(eye.x, 2f * waterH - eye.y, eye.z)
        val reflLook = Vec3(look.x, -look.y, look.z).normalized()
        val reflTarget = reflEye + reflLook
        Matrix.setLookAtM(
            reflViewMatrix, 0,
            reflEye.x, reflEye.y, reflEye.z,
            reflTarget.x, reflTarget.y, reflTarget.z,
            0f, 1f, 0f
        )
        Matrix.multiplyMM(reflVpMatrix, 0, projMatrix, 0, reflViewMatrix, 0)

        // PASS 1: Synchronized Planar Water Reflection FBO (renderedReflVpMatrix matches the exact rendered FBO frame)
        val nearOrFacingRiver = kotlin.math.abs(eye.x) < 76f || (eye.x * look.x < -0.05f && kotlin.math.abs(eye.x) < 120f)
        val reflCadence = if (snap.activeShardCount > 35) 2 else 1
        val shouldRenderRefl = nearOrFacingRiver && (renderFrameIndex - lastReflFrame >= reflCadence)
        if (hasReflectionFbo && eye.y > waterH - 0.1f && shouldRenderRefl) {
            lastReflFrame = renderFrameIndex
            reflVpMatrix.copyInto(renderedReflVpMatrix)
            hasRenderedRefl = true
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, reflectionFbo)
            GLES20.glViewport(0, 0, reflSize, reflSize)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

            drawPreethamSky(reflLook, sunDir, skyZenith, skyHorizon, sunColor, snap.waterTime, skyParams)
            drawWorldGeometry(
                mvp = reflVpMatrix,
                eye = reflEye,
                sunDir = sunDir,
                sunColor = sunColor,
                skyTint = skyZenith,
                fogColor = fogColor,
                shadowStrength = shadowStrength * 0.65f,
                clipActive = true,
                clipY = waterH - 0.15f,
                fastPass = true,
                includeDynamic = false,
                pointLights = snap.pointLights
            )
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }

        // PASS 2: Main Viewport (full quality bump, 4-tap PCF shadows, frustum chunk culling, cached VBOs)
        GLES20.glViewport(0, 0, viewWidth, viewHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        drawPreethamSky(look, sunDir, skyZenith, skyHorizon, sunColor, snap.waterTime, skyParams)
        drawWorldGeometry(
            mvp = vpMatrix,
            eye = eye,
            sunDir = sunDir,
            sunColor = sunColor,
            skyTint = skyZenith,
            fogColor = fogColor,
            shadowStrength = shadowStrength,
            clipActive = false,
            clipY = 0f,
            fastPass = false,
            includeDynamic = true,
            pointLights = snap.pointLights
        )

        val distMix = clamp(1f - (kotlin.math.abs(eye.x) - 46f) / 56f, 0f, 1f)
        val dirMix = if (eye.x * look.x < 0.05f || kotlin.math.abs(eye.x) < 42f) 1f else 0.40f
        val reflMix = if (hasRenderedRefl) clamp(distMix * dirMix, 0f, 1f) else 0f
        drawWater(snap, eye, sunDir, sunColor, skyZenith, fogColor, reflMix)

        // Translucent Breakable Window Glass Panes (frustum-culled resident sector VBOs, back-face culled to prevent double-pane overlap)
        if (hasAnyGlassVerts) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glDepthMask(false)
            bindWorldUniforms(vpMatrix, eye, sunDir, sunColor, skyZenith, fogColor, shadowStrength, false, 0f, false, snap.pointLights)
            extractFrustumPlanes(vpMatrix)
            beginWorldAttribs()
            for (s in 0 until sectorCountTotal) {
                val cnt = sectorGlassVertCounts[s]
                if (cnt > 0 && isSectorInFrustum(s)) {
                    drawWorldVbo(sectorGlassVbos[s], cnt)
                }
            }
            endWorldAttribs()
            GLES20.glDepthMask(true)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        // Expanding Blast Shockwave Rings
        val swCount = snap.shockwaveFloatCount / ChunkedTerrain.VERTEX_STRIDE_FLOATS
        if (swCount > 0) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            GLES20.glDepthMask(false)
            GLES20.glUseProgram(lineProg)
            GLES20.glUniformMatrix4fv(lineH.uMVP, 1, false, vpMatrix, 0)
            uploadVbo(shockwaveVbo, snap.shockwaveVerts, GLES20.GL_DYNAMIC_DRAW, snap.shockwaveFloatCount)
            drawColorVbo(shockwaveVbo, swCount, GLES20.GL_TRIANGLES)
            GLES20.glDepthMask(true)
            GLES20.glEnable(GLES20.GL_CULL_FACE)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        // Volumetric Explosion / Smoke / Fire / Dust / Water Splash Billboards
        val pCount = snap.particleFloatCount / ChunkedTerrain.VERTEX_STRIDE_FLOATS
        if (pCount > 0) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            GLES20.glDepthMask(false)
            GLES20.glUseProgram(particleProg)
            GLES20.glUniformMatrix4fv(particleH.uMVP, 1, false, vpMatrix, 0)
            GLES20.glUniform3f(particleH.uSunDir, sunDir.x, sunDir.y, sunDir.z)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, particleTexId)
            GLES20.glUniform1i(particleH.uParticleTex, 0)
            uploadVbo(particleVbo, snap.particleVerts, GLES20.GL_DYNAMIC_DRAW, snap.particleFloatCount)
            drawParticleVbo(particleVbo, pCount)
            GLES20.glDepthMask(true)
            GLES20.glEnable(GLES20.GL_CULL_FACE)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        // Brush / Artillery Target Preview Ring
        val lCount = snap.lineFloatCount / ChunkedTerrain.VERTEX_STRIDE_FLOATS
        if (lCount > 0) {
            GLES20.glUseProgram(lineProg)
            GLES20.glLineWidth(4.0f)
            GLES20.glUniformMatrix4fv(lineH.uMVP, 1, false, vpMatrix, 0)
            uploadVbo(lineVbo, snap.lineVerts, GLES20.GL_DYNAMIC_DRAW, snap.lineFloatCount)
            drawColorVbo(lineVbo, lCount, GLES20.GL_LINES)
        }
    }

    private fun extractFrustumPlanes(m: FloatArray) {
        // Row r of column-major matrix m is [m[r], m[4+r], m[8+r], m[12+r]]
        // Left: row3 + row0
        frustumPlanes[0] = m[3] + m[0]; frustumPlanes[1] = m[7] + m[4]; frustumPlanes[2] = m[11] + m[8]; frustumPlanes[3] = m[15] + m[12]
        // Right: row3 - row0
        frustumPlanes[4] = m[3] - m[0]; frustumPlanes[5] = m[7] - m[4]; frustumPlanes[6] = m[11] - m[8]; frustumPlanes[7] = m[15] - m[12]
        // Bottom: row3 + row1
        frustumPlanes[8] = m[3] + m[1]; frustumPlanes[9] = m[7] + m[5]; frustumPlanes[10] = m[11] + m[9]; frustumPlanes[11] = m[15] + m[13]
        // Top: row3 - row1
        frustumPlanes[12] = m[3] - m[1]; frustumPlanes[13] = m[7] - m[5]; frustumPlanes[14] = m[11] - m[9]; frustumPlanes[15] = m[15] - m[13]
        // Near: row3 + row2
        frustumPlanes[16] = m[3] + m[2]; frustumPlanes[17] = m[7] + m[6]; frustumPlanes[18] = m[11] + m[10]; frustumPlanes[19] = m[15] + m[14]
        // Far: row3 - row2
        frustumPlanes[20] = m[3] - m[2]; frustumPlanes[21] = m[7] - m[6]; frustumPlanes[22] = m[11] - m[10]; frustumPlanes[23] = m[15] - m[14]
    }

    private fun isAabbInFrustum(
        minX: Float, minY: Float, minZ: Float,
        maxX: Float, maxY: Float, maxZ: Float
    ): Boolean {
        var p = 0
        for (i in 0 until 6) {
            val nx = frustumPlanes[p]
            val ny = frustumPlanes[p + 1]
            val nz = frustumPlanes[p + 2]
            val d = frustumPlanes[p + 3]
            val px = if (nx >= 0f) maxX else minX
            val py = if (ny >= 0f) maxY else minY
            val pz = if (nz >= 0f) maxZ else minZ
            if (nx * px + ny * py + nz * pz + d < 0f) return false
            p += 4
        }
        return true
    }

    private fun isChunkInFrustum(idx: Int): Boolean =
        isAabbInFrustum(chunkMinX[idx], chunkMinY[idx], chunkMinZ[idx], chunkMaxX[idx], chunkMaxY[idx], chunkMaxZ[idx])

    private fun isSectorInFrustum(idx: Int): Boolean =
        sectorHasGeom[idx] && isAabbInFrustum(sectorMinX[idx], sectorMinY[idx], sectorMinZ[idx], sectorMaxX[idx], sectorMaxY[idx], sectorMaxZ[idx])

    private fun renderShadowPass() {
        GLES20.glUseProgram(shadowProg)
        GLES20.glUniformMatrix4fv(shadowH.uLightVP, 1, false, lightVpMatrix, 0)
        extractFrustumPlanes(lightVpMatrix)

        val strideBytes = ChunkedTerrain.VERTEX_STRIDE_FLOATS * 4
        val aPos = shadowH.aPos
        GLES20.glEnableVertexAttribArray(aPos)

        for (idx in 0 until chunkCountTotal) {
            val cnt = chunkVertCounts[idx]
            if (cnt > 0 && isChunkInFrustum(idx)) {
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, chunkVbos[idx])
                GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, strideBytes, 0)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, cnt)
            }
        }
        for (s in 0 until sectorCountTotal) {
            val cnt = sectorOpaqueVertCounts[s]
            if (cnt > 0 && isSectorInFrustum(s)) {
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, sectorOpaqueVbos[s])
                GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, strideBytes, 0)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, cnt)
            }
        }
        if (sleepingRubbleVertCount > 0) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, sleepingRubbleVbo)
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, strideBytes, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, sleepingRubbleVertCount)
        }
        if (dynamicVertCount > 0) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, dynamicVbo)
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, strideBytes, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, dynamicVertCount)
        }

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    private fun syncDirtyBuffers(snap: RenderSnapshot) {
        for (chunk in snap.chunkMeshes) {
            val idx = chunk.chunkIndex
            if (chunkVersions[idx] != chunk.version) {
                chunkVersions[idx] = chunk.version
                uploadVbo(chunkVbos[idx], chunk.vertices, GLES20.GL_STATIC_DRAW)
                chunkVertCounts[idx] = chunk.vertices.size / ChunkedTerrain.VERTEX_STRIDE_FLOATS
                var minY = Float.POSITIVE_INFINITY
                var maxY = Float.NEGATIVE_INFINITY
                var p = 1
                while (p < chunk.vertices.size) {
                    val y = chunk.vertices[p]
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    p += ChunkedTerrain.VERTEX_STRIDE_FLOATS
                }
                chunkMinY[idx] = if (minY == Float.POSITIVE_INFINITY) -6f else minY - 0.5f
                chunkMaxY[idx] = if (maxY == Float.NEGATIVE_INFINITY) 20f else maxY + 0.5f
            }
        }
        if (cachedTerrainVersion != snap.terrainVersion) {
            cachedTerrainVersion = snap.terrainVersion
            uploadVbo(waterVbo, snap.waterVerts, GLES20.GL_STATIC_DRAW)
            waterVertCount = snap.waterVerts.size / 4
        }
        var glassChanged = false
        for (sec in snap.sectorMeshes) {
            val s = sec.sectorIndex
            if (sectorVersions[s] != sec.version) {
                sectorVersions[s] = sec.version
                sectorHasGeom[s] = sec.hasGeometry
                sectorMinX[s] = sec.minX; sectorMinY[s] = sec.minY; sectorMinZ[s] = sec.minZ
                sectorMaxX[s] = sec.maxX; sectorMaxY[s] = sec.maxY; sectorMaxZ[s] = sec.maxZ
                sectorOpaqueVertCounts[s] = sec.opaqueVertices.size / ChunkedTerrain.VERTEX_STRIDE_FLOATS
                uploadVbo(sectorOpaqueVbos[s], sec.opaqueVertices, GLES20.GL_STATIC_DRAW)
                sectorGlassVertCounts[s] = sec.glassVertices.size / ChunkedTerrain.VERTEX_STRIDE_FLOATS
                uploadVbo(sectorGlassVbos[s], sec.glassVertices, GLES20.GL_STATIC_DRAW)
                glassChanged = true
            }
        }
        if (glassChanged) {
            hasAnyGlassVerts = sectorGlassVertCounts.any { it > 0 }
        }
        if (cachedSleepingRubbleVersion != snap.sleepingRubbleVersion) {
            cachedSleepingRubbleVersion = snap.sleepingRubbleVersion
            uploadVbo(sleepingRubbleVbo, snap.sleepingRubbleVerts, GLES20.GL_STATIC_DRAW)
            sleepingRubbleVertCount = snap.sleepingRubbleVerts.size / ChunkedTerrain.VERTEX_STRIDE_FLOATS
        }
        if (lastUploadedSnapshotVersion != snap.snapshotVersion) {
            lastUploadedSnapshotVersion = snap.snapshotVersion
            dynamicVertCount = snap.dynamicFloatCount / ChunkedTerrain.VERTEX_STRIDE_FLOATS
            if (dynamicVertCount > 0) {
                uploadVbo(dynamicVbo, snap.dynamicVerts, GLES20.GL_DYNAMIC_DRAW, snap.dynamicFloatCount)
            }
        }
    }

    private fun drawPreethamSky(
        lookDir: Vec3,
        sunDir: Vec3,
        zenithColor: Vec3,
        horizonColor: Vec3,
        sunColor: Vec3,
        time: Float,
        params: FloatArray
    ) {
        val look = lookDir.normalized()
        val rawRight = look.cross(Vec3.UP)
        val right = if (rawRight.len() < 1e-4f) Vec3(1f, 0f, 0f) else rawRight.normalized()
        val camUp = right.cross(look).normalized()
        val tanHalfFov = tan(Math.toRadians(31.0)).toFloat()
        val aspect = viewWidth.toFloat() / viewHeight.toFloat()

        GLES20.glDepthMask(false)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glUseProgram(skyProg)
        GLES20.glUniform3f(skyH.uCamLook, look.x, look.y, look.z)
        GLES20.glUniform3f(skyH.uCamRight, right.x, right.y, right.z)
        GLES20.glUniform3f(skyH.uCamUp, camUp.x, camUp.y, camUp.z)
        GLES20.glUniform1f(skyH.uTanHalfFov, tanHalfFov)
        GLES20.glUniform1f(skyH.uAspect, aspect)
        GLES20.glUniform3f(skyH.uSunDir, sunDir.x, sunDir.y, sunDir.z)
        GLES20.glUniform3f(skyH.uZenithColor, zenithColor.x, zenithColor.y, zenithColor.z)
        GLES20.glUniform3f(skyH.uHorizonColor, horizonColor.x, horizonColor.y, horizonColor.z)
        GLES20.glUniform3f(skyH.uSunColor, sunColor.x, sunColor.y, sunColor.z)
        GLES20.glUniform1f(skyH.uTime, time)
        GLES20.glUniform1f(skyH.uTurbidity, params[0])
        GLES20.glUniform1f(skyH.uMieG, params[1])
        GLES20.glUniform1f(skyH.uCloudCover, params[2])

        val posLoc = skyH.aPos
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, skyVbo)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 8, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)
    }

    private fun bindWorldUniforms(
        mvp: FloatArray,
        eye: Vec3,
        sunDir: Vec3,
        sunColor: Vec3,
        skyTint: Vec3,
        fogColor: Vec3,
        shadowStrength: Float,
        clipActive: Boolean,
        clipY: Float,
        fastPass: Boolean,
        pointLights: List<DynamicPointLight> = emptyList()
    ) {
        GLES20.glUseProgram(worldProg)
        GLES20.glUniformMatrix4fv(worldH.uMVP, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(worldH.uLightVP, 1, false, renderedLightVpMatrix, 0)
        GLES20.glUniform3f(worldH.uSunDir, sunDir.x, sunDir.y, sunDir.z)
        GLES20.glUniform3f(worldH.uSunColor, sunColor.x, sunColor.y, sunColor.z)
        GLES20.glUniform3f(worldH.uSkyColor, skyTint.x, skyTint.y, skyTint.z)
        GLES20.glUniform3f(worldH.uFogColor, fogColor.x, fogColor.y, fogColor.z)
        GLES20.glUniform3f(worldH.uCamPos, eye.x, eye.y, eye.z)
        GLES20.glUniform3f(worldH.uShadowFocus, renderedShadowFocus.x, renderedShadowFocus.y, renderedShadowFocus.z)
        GLES20.glUniform1f(worldH.uShadowStrength, shadowStrength)
        GLES20.glUniform1f(worldH.uHasShadow, if (hasShadowFbo) 1f else 0f)
        GLES20.glUniform1f(worldH.uClipActive, if (clipActive) 1f else 0f)
        GLES20.glUniform1f(worldH.uClipY, clipY)
        GLES20.glUniform1f(worldH.uFastPass, if (fastPass) 1f else 0f)

        val lightCount = pointLights.size.coerceAtMost(3)
        pointLightPosArray.fill(0f)
        pointLightColArray.fill(0f)
        for (i in 0 until lightCount) {
            val l = pointLights[i]
            val pBase = i * 4
            pointLightPosArray[pBase] = l.pos.x
            pointLightPosArray[pBase + 1] = l.pos.y
            pointLightPosArray[pBase + 2] = l.pos.z
            pointLightPosArray[pBase + 3] = l.radius
            val cBase = i * 3
            pointLightColArray[cBase] = l.color.x
            pointLightColArray[cBase + 1] = l.color.y
            pointLightColArray[cBase + 2] = l.color.z
        }
        GLES20.glUniform1f(worldH.uWaterLevel, ChunkedTerrain.WATER_LEVEL)
        GLES20.glUniform1f(worldH.uPointLightCount, lightCount.toFloat())
        GLES20.glUniform4fv(worldH.uPointLightPos, 3, pointLightPosArray, 0)
        GLES20.glUniform3fv(worldH.uPointLightColor, 3, pointLightColArray, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTexId)
        GLES20.glUniform1i(worldH.uAtlas, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, shadowTexId)
        GLES20.glUniform1i(worldH.uShadowMap, 1)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pbrTexId)
        GLES20.glUniform1i(worldH.uPbrAtlas, 2)
    }

    private fun drawWorldGeometry(
        mvp: FloatArray,
        eye: Vec3,
        sunDir: Vec3,
        sunColor: Vec3,
        skyTint: Vec3,
        fogColor: Vec3,
        shadowStrength: Float,
        clipActive: Boolean,
        clipY: Float,
        fastPass: Boolean,
        includeDynamic: Boolean,
        pointLights: List<DynamicPointLight> = emptyList()
    ) {
        bindWorldUniforms(mvp, eye, sunDir, sunColor, skyTint, fogColor, shadowStrength, clipActive, clipY, fastPass, pointLights)
        extractFrustumPlanes(mvp)
        beginWorldAttribs()

        for (idx in 0 until chunkCountTotal) {
            val cnt = chunkVertCounts[idx]
            if (cnt > 0 && isChunkInFrustum(idx)) {
                drawWorldVbo(chunkVbos[idx], cnt)
            }
        }
        for (s in 0 until sectorCountTotal) {
            val cnt = sectorOpaqueVertCounts[s]
            if (cnt > 0 && isSectorInFrustum(s)) {
                drawWorldVbo(sectorOpaqueVbos[s], cnt)
            }
        }
        if (sleepingRubbleVertCount > 0) {
            drawWorldVbo(sleepingRubbleVbo, sleepingRubbleVertCount)
        }
        if (includeDynamic && dynamicVertCount > 0) {
            drawWorldVbo(dynamicVbo, dynamicVertCount)
        }

        endWorldAttribs()
    }

    private fun drawWater(
        snap: RenderSnapshot,
        eye: Vec3,
        sunDir: Vec3,
        sunColor: Vec3,
        skyColor: Vec3,
        fogColor: Vec3,
        reflMix: Float
    ) {
        if (waterVertCount <= 0) return

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDepthMask(false)

        GLES20.glUseProgram(waterProg)
        GLES20.glUniformMatrix4fv(waterH.uMVP, 1, false, vpMatrix, 0)
        GLES20.glUniformMatrix4fv(waterH.uReflVP, 1, false, renderedReflVpMatrix, 0)
        GLES20.glUniform1f(waterH.uTime, snap.waterTime)
        GLES20.glUniform3f(waterH.uCamPos, eye.x, eye.y, eye.z)
        GLES20.glUniform3f(waterH.uSunDir, sunDir.x, sunDir.y, sunDir.z)
        GLES20.glUniform3f(waterH.uSunColor, sunColor.x, sunColor.y, sunColor.z)
        GLES20.glUniform3f(waterH.uSkyColor, skyColor.x, skyColor.y, skyColor.z)
        GLES20.glUniform3f(waterH.uFogColor, fogColor.x, fogColor.y, fogColor.z)
        GLES20.glUniform1f(waterH.uHasReflection, if (hasReflectionFbo) 1f else 0f)
        GLES20.glUniform1f(waterH.uReflMix, reflMix)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, waterNormalTexId)
        GLES20.glUniform1i(waterH.uWaterNormal, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, reflectionTexId)
        GLES20.glUniform1i(waterH.uReflectionTex, 1)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, waterVbo)
        GLES20.glEnableVertexAttribArray(waterH.aPos)
        GLES20.glVertexAttribPointer(waterH.aPos, 3, GLES20.GL_FLOAT, false, 16, 0)
        GLES20.glEnableVertexAttribArray(waterH.aDepth)
        GLES20.glVertexAttribPointer(waterH.aDepth, 1, GLES20.GL_FLOAT, false, 16, 12)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, waterVertCount)

        GLES20.glDisableVertexAttribArray(waterH.aPos)
        GLES20.glDisableVertexAttribArray(waterH.aDepth)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun beginWorldAttribs() {
        GLES20.glEnableVertexAttribArray(worldH.aPos)
        GLES20.glEnableVertexAttribArray(worldH.aNormal)
        GLES20.glEnableVertexAttribArray(worldH.aColor)
        GLES20.glEnableVertexAttribArray(worldH.aUV)
        GLES20.glEnableVertexAttribArray(worldH.aTile)
    }

    private fun endWorldAttribs() {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDisableVertexAttribArray(worldH.aPos)
        GLES20.glDisableVertexAttribArray(worldH.aNormal)
        GLES20.glDisableVertexAttribArray(worldH.aColor)
        GLES20.glDisableVertexAttribArray(worldH.aUV)
        GLES20.glDisableVertexAttribArray(worldH.aTile)
    }

    private fun drawWorldVbo(vboId: Int, vertCount: Int) {
        val strideBytes = ChunkedTerrain.VERTEX_STRIDE_FLOATS * 4
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
        GLES20.glVertexAttribPointer(worldH.aPos, 3, GLES20.GL_FLOAT, false, strideBytes, 0)
        GLES20.glVertexAttribPointer(worldH.aNormal, 3, GLES20.GL_FLOAT, false, strideBytes, 12)
        GLES20.glVertexAttribPointer(worldH.aColor, 4, GLES20.GL_FLOAT, false, strideBytes, 24)
        GLES20.glVertexAttribPointer(worldH.aUV, 2, GLES20.GL_FLOAT, false, strideBytes, 40)
        GLES20.glVertexAttribPointer(worldH.aTile, 1, GLES20.GL_FLOAT, false, strideBytes, 48)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertCount)
    }

    private fun drawColorVbo(vboId: Int, vertCount: Int, mode: Int) {
        val strideBytes = ChunkedTerrain.VERTEX_STRIDE_FLOATS * 4
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
        GLES20.glEnableVertexAttribArray(lineH.aPos)
        GLES20.glVertexAttribPointer(lineH.aPos, 3, GLES20.GL_FLOAT, false, strideBytes, 0)
        GLES20.glEnableVertexAttribArray(lineH.aColor)
        GLES20.glVertexAttribPointer(lineH.aColor, 4, GLES20.GL_FLOAT, false, strideBytes, 24)

        GLES20.glDrawArrays(mode, 0, vertCount)

        GLES20.glDisableVertexAttribArray(lineH.aPos)
        GLES20.glDisableVertexAttribArray(lineH.aColor)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun drawParticleVbo(vboId: Int, vertCount: Int) {
        val strideBytes = ChunkedTerrain.VERTEX_STRIDE_FLOATS * 4
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
        GLES20.glEnableVertexAttribArray(particleH.aPos)
        GLES20.glVertexAttribPointer(particleH.aPos, 3, GLES20.GL_FLOAT, false, strideBytes, 0)
        GLES20.glEnableVertexAttribArray(particleH.aNormal)
        GLES20.glVertexAttribPointer(particleH.aNormal, 3, GLES20.GL_FLOAT, false, strideBytes, 12)
        GLES20.glEnableVertexAttribArray(particleH.aColor)
        GLES20.glVertexAttribPointer(particleH.aColor, 4, GLES20.GL_FLOAT, false, strideBytes, 24)
        GLES20.glEnableVertexAttribArray(particleH.aUV)
        GLES20.glVertexAttribPointer(particleH.aUV, 2, GLES20.GL_FLOAT, false, strideBytes, 40)
        GLES20.glEnableVertexAttribArray(particleH.aTile)
        GLES20.glVertexAttribPointer(particleH.aTile, 1, GLES20.GL_FLOAT, false, strideBytes, 48)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertCount)

        GLES20.glDisableVertexAttribArray(particleH.aPos)
        GLES20.glDisableVertexAttribArray(particleH.aNormal)
        GLES20.glDisableVertexAttribArray(particleH.aColor)
        GLES20.glDisableVertexAttribArray(particleH.aUV)
        GLES20.glDisableVertexAttribArray(particleH.aTile)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun buildSkyQuad() {
        val verts = floatArrayOf(
            -1f, -1f, 1f, -1f, 1f, 1f,
            -1f, -1f, 1f, 1f, -1f, 1f
        )
        uploadVbo(skyVbo, verts, GLES20.GL_STATIC_DRAW)
    }

    private fun uploadVbo(vboId: Int, data: FloatArray, usage: Int, floatCount: Int = data.size) {
        if (vboId == 0 || floatCount <= 0) return
        val existing = uploadStaging
        val buf = if (existing != null && existing.capacity() >= floatCount) {
            existing
        } else {
            val allocated = ByteBuffer.allocateDirect(floatCount * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            uploadStaging = allocated
            allocated
        }
        buf.clear()
        buf.put(data, 0, floatCount)
        buf.position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, floatCount * 4, buf, usage)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun updateFpsCounter() {
        fpsFrames++
        val now = System.nanoTime()
        if (now - fpsLastTime >= 600_000_000L) {
            val fps = ((fpsFrames * 1_000_000_000L) / (now - fpsLastTime)).toInt()
            fpsFrames = 0
            fpsLastTime = now
            onFpsUpdate(fps)
        }
    }

    private fun sunDirectionFor(preset: LightingPreset): Vec3 = when (preset) {
        LightingPreset.MORNING -> Vec3(0.84f, 0.28f, 0.46f).normalized()
        LightingPreset.NOON -> Vec3(0.32f, 0.88f, 0.34f).normalized()
        LightingPreset.GOLDEN_HOUR -> Vec3(-0.86f, 0.18f, -0.47f).normalized()
        LightingPreset.OVERCAST -> Vec3(0.30f, 0.72f, 0.40f).normalized()
    }

    private fun skyParamsFor(preset: LightingPreset): FloatArray = when (preset) {
        LightingPreset.MORNING -> floatArrayOf(1.5f, 0.80f, 0.38f)
        LightingPreset.NOON -> floatArrayOf(1.0f, 0.78f, 0.42f)
        LightingPreset.GOLDEN_HOUR -> floatArrayOf(2.1f, 0.85f, 0.50f)
        LightingPreset.OVERCAST -> floatArrayOf(0.8f, 0.60f, 0.90f)
    }

    private fun lightingColorsFor(preset: LightingPreset): Array<Vec3> = when (preset) {
        LightingPreset.MORNING -> arrayOf(
            Vec3(0.24f, 0.50f, 0.88f),
            Vec3(0.95f, 0.79f, 0.67f),
            Vec3(1.12f, 0.86f, 0.64f)
        )
        LightingPreset.NOON -> arrayOf(
            Vec3(0.12f, 0.44f, 0.94f),
            Vec3(0.62f, 0.84f, 0.99f),
            Vec3(1.06f, 1.03f, 0.96f)
        )
        LightingPreset.GOLDEN_HOUR -> arrayOf(
            Vec3(0.18f, 0.28f, 0.58f),
            Vec3(0.98f, 0.54f, 0.30f),
            Vec3(1.18f, 0.68f, 0.36f)
        )
        LightingPreset.OVERCAST -> arrayOf(
            Vec3(0.38f, 0.46f, 0.55f),
            Vec3(0.66f, 0.72f, 0.78f),
            Vec3(0.78f, 0.82f, 0.86f)
        )
    }

    private fun acesFilmicToneMap(c: Vec3): Vec3 {
        fun ch(x: Float): Float = clamp((x * (2.51f * x + 0.03f)) / (x * (2.43f * x + 0.59f) + 0.14f), 0f, 1f)
        return Vec3(ch(c.x), ch(c.y), ch(c.z))
    }

    private fun compileProgram(vsSrc: String, fsSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e("ValleyRenderer", "Program link failed: ${GLES20.glGetProgramInfoLog(prog)}")
        }
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e("ValleyRenderer", "Shader compile failed ($type): ${GLES20.glGetShaderInfoLog(shader)}")
        }
        return shader
    }
}
