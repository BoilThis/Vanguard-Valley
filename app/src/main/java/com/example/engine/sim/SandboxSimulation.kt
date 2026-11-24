package com.example.engine.sim

import com.example.audio.SoundCue
import com.example.engine.physics.FloatArrayBuilder
import com.example.engine.physics.MeshRing
import com.example.engine.physics.StructuralWorld
import com.example.engine.physics.VoronoiPolyhedron
import com.example.engine.physics.ZoneBuilder
import com.example.engine.terrain.ChunkedTerrain
import com.example.model.BrushItem
import com.example.model.BuildMaterial
import com.example.model.CameraMode
import com.example.model.DemolitionMission
import com.example.model.DistrictJumpTarget
import com.example.model.GameMode
import com.example.model.HudUiState
import com.example.model.InteractionTool
import com.example.model.MaterialSoundKind
import com.example.model.MaterialTile
import com.example.model.MissionProgress
import com.example.model.Quat
import com.example.model.Vec3
import com.example.model.WeaponType
import com.example.model.WorldConfig
import com.example.model.clamp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private data class ActiveProjectile(
    val type: WeaponType,
    var pos: Vec3,
    var vel: Vec3,
    var age: Float = 0f,
    var bouncesLeft: Int = 0
)

private data class PlantedC4(
    var worldPos: Vec3,
    val attachedBlockId: Int?,
    val localOffset: Vec3,
    var age: Float = 0f,
    val attachedShardId: Int? = null
)

private data class QueuedBlast(
    var delay: Float,
    val pos: Vec3,
    val radius: Float,
    val craterDepth: Float,
    val damage: Float,
    val impulse: Float,
    val isFuelFire: Boolean = false
)

private enum class ParticleKind {
    SMOKE, FIREBALL, SPARK, DUST, SPLASH, TRACER
}

private data class Particle(
    var pos: Vec3,
    var vel: Vec3,
    var color: Vec3,
    var alpha: Float,
    var size: Float,
    var growthRate: Float,
    var age: Float,
    var maxAge: Float,
    var gravity: Float,
    var kind: ParticleKind,
    var rot: Float,
    var rotSpeed: Float
)

private data class ShockwaveRing(
    val pos: Vec3,
    val maxRadius: Float,
    var age: Float = 0f,
    val duration: Float = 0.42f
)

private data class ActiveGeyser(
    val pos: Vec3,
    var timeLeft: Float = 4.5f,
    var emitAcc: Float = 0f
)

private data class ActiveFireSite(
    val pos: Vec3,
    var radius: Float,
    var timeLeft: Float,
    var isFuelFire: Boolean,
    var emitAcc: Float = 0f
)

private data class ActiveFlashLight(
    val pos: Vec3,
    var age: Float = 0f,
    val duration: Float = 0.48f,
    val radius: Float = 14f,
    val color: Vec3 = Vec3(2.45f, 1.38f, 0.52f)
)

class SandboxSimulation(initialConfig: WorldConfig = WorldConfig()) {

    val terrain = ChunkedTerrain(initialConfig)
    val world = StructuralWorld()

    var config: WorldConfig = initialConfig
        private set

    // Player / Drone state
    var playerPos = Vec3(-22f, 3.2f, 6.5f)
    var playerVel = Vec3.ZERO
    var dronePos = Vec3(-18f, 14f, 18f)
    var yaw = 0.25f
    var pitch = -0.08f
    var cameraMode = CameraMode.THIRD_PERSON
    var isSprinting = false

    // Controls input
    @Volatile var moveInputX = 0f
    @Volatile var moveInputY = 0f
    @Volatile var droneVertical = 0f
    @Volatile var autoFireActive = false
    @Volatile private var jumpQueued = false

    // Tools, Weapons & Materials
    var interactionTool = InteractionTool.WEAPON
    var selectedWeapon = WeaponType.RPG
    var selectedBrush = BrushItem.WALL_PANEL
    var selectedBuildMaterial = BuildMaterial.WOOD

    // Gameplay / Demolition Ops State
    var gameMode = GameMode.FREE_SANDBOX
        private set
    private var currentMission: DemolitionMission = DemolitionOpsCatalog.missions.first()
    private var initialTargetBlocks = 0
    private var missionCompleted = false
    private var missionStarsEarned = 0
    private var score = 0
    private var comboMultiplier = 1
    private var comboTimer = 0f
    private val ordnanceAmmo = LinkedHashMap<WeaponType, Int>()

    var soundListener: ((SoundCue, Float, Float) -> Unit)? = null
    var onMissionCompleted: ((String, Int) -> Unit)? = null

    private val projectiles = ArrayList<ActiveProjectile>()
    private val plantedC4s = ArrayList<PlantedC4>()
    private val queuedBlasts = ArrayList<QueuedBlast>()
    private val particles = ArrayList<Particle>()
    private val shockwaves = ArrayList<ShockwaveRing>()
    private val geysers = ArrayList<ActiveGeyser>()
    private val fireSites = ArrayList<ActiveFireSite>()
    private val flashLights = ArrayList<ActiveFlashLight>()

    val activeFireSiteCount: Int get() = fireSites.size
    val activeParticleCount: Int get() = particles.size

    private var fireCooldown = 0f
    private var waterTime = 0f
    private var walkCyclePhase = 0f
    private var walkCycleAmp = 0f
    private var isPlayerGrounded = true
    private var shakeIntensity = 0f
    private var statusBanner: String? = null
    private var statusTimer = 0f
    private val rng = Random(7731)

    // Mesh caches
    private val EMPTY_FLOATS = FloatArray(0)
    private val chunkMeshes: Array<ChunkMeshData>
    private var cachedChunkMeshesSnapshot: Array<ChunkMeshData>
    private var terrainVersion = 1
    private var cachedWaterVerts = EMPTY_FLOATS
    private var cachedStructVersion = -1
    private val sectorCountTotal = StructuralWorld.SECTOR_COUNT
    private val sectorMeshes = Array(sectorCountTotal) { idx ->
        BuildingSectorMeshData(idx, -1, EMPTY_FLOATS, EMPTY_FLOATS, 0f, 0f, 0f, 0f, 0f, 0f, false)
    }
    private var cachedSectorMeshesSnapshot = sectorMeshes.copyOf()
    private val cachedSectorVersions = IntArray(sectorCountTotal) { -1 }
    private val dirtySectorFlags = BooleanArray(sectorCountTotal)
    private val sectorMinX = FloatArray(sectorCountTotal)
    private val sectorMinY = FloatArray(sectorCountTotal)
    private val sectorMinZ = FloatArray(sectorCountTotal)
    private val sectorMaxX = FloatArray(sectorCountTotal)
    private val sectorMaxY = FloatArray(sectorCountTotal)
    private val sectorMaxZ = FloatArray(sectorCountTotal)
    private val sectorOpaqueBuilders = Array(sectorCountTotal) { FloatArrayBuilder(16384) }
    private val sectorGlassBuilders = Array(sectorCountTotal) { FloatArrayBuilder(4096) }
    private var cachedSleepingRubbleVersion = -1
    private var cachedSleepingRubbleVerts = EMPTY_FLOATS
    private var snapshotVersion = 1
    private val sortedParticleIdx = IntArray(256)
    private val sortedParticleDistSq = FloatArray(256)
    private val sleepingRubbleBuilder = FloatArrayBuilder(65536)
    private val dynamicBuilder = FloatArrayBuilder(65536)
    private val shockwaveBuilder = FloatArrayBuilder(8192)
    private val particleBuilder = FloatArrayBuilder(32768)
    private val lineBuilder = FloatArrayBuilder(4096)
    private val dynamicRing = MeshRing()
    private val shockwaveRing = MeshRing()
    private val particleRing = MeshRing()
    private val lineRing = MeshRing()

    // Pre-allocated polyhedron templates (zero per-frame heap allocation)
    private val wreckingBallPoly = VoronoiPolyhedron.createBox(0.55f, 0.55f, 0.55f)
    private val artilleryPoly = VoronoiPolyhedron.createBox(0.36f, 0.36f, 0.36f)
    private val smallProjPoly = VoronoiPolyhedron.createBox(0.22f, 0.22f, 0.22f)
    private val plantedC4Poly = VoronoiPolyhedron.createBox(0.24f, 0.14f, 0.18f)
    private val avatarLegPoly = VoronoiPolyhedron.createBox(0.095f, 0.31f, 0.105f)
    private val avatarTorsoPoly = VoronoiPolyhedron.createBox(0.24f, 0.33f, 0.145f)
    private val avatarHeadPoly = VoronoiPolyhedron.createBox(0.165f, 0.165f, 0.165f)
    private val avatarArmPoly = VoronoiPolyhedron.createBox(0.082f, 0.25f, 0.085f)
    private val brushToolPoly = VoronoiPolyhedron.createBox(0.14f, 0.04f, 0.19f)
    private val carbinePoly = VoronoiPolyhedron.createBox(0.055f, 0.085f, 0.34f)
    private val heavyRiflePoly = VoronoiPolyhedron.createBox(0.06f, 0.095f, 0.52f)
    private val rpgTubePoly = VoronoiPolyhedron.createBox(0.085f, 0.085f, 0.48f)
    private val rpgWarheadPoly = VoronoiPolyhedron.createBox(0.095f, 0.095f, 0.14f)
    private val c4HeldPoly = VoronoiPolyhedron.createBox(0.11f, 0.07f, 0.09f)
    private val artilleryHeldPoly = VoronoiPolyhedron.createBox(0.11f, 0.08f, 0.18f)
    private val wreckingHeldPoly = VoronoiPolyhedron.createBox(0.14f, 0.14f, 0.14f)

    @Volatile
    var latestSnapshot: RenderSnapshot
        private set

    init {
        ZoneBuilder.populateValley(world, terrain, config)
        placePlayerSafely()
        resetOpsTracking()
        chunkMeshes = Array(ChunkedTerrain.CHUNKS_X * ChunkedTerrain.CHUNKS_Z) { idx ->
            val cx = idx % ChunkedTerrain.CHUNKS_X
            val cz = idx / ChunkedTerrain.CHUNKS_X
            ChunkMeshData(idx, terrain.chunkVersions[idx], terrain.buildChunkVertices(cx, cz))
        }
        cachedChunkMeshesSnapshot = chunkMeshes.copyOf()
        rebuildWaterMesh()
        rebuildStaticStructuresIfNeeded()
        latestSnapshot = createSnapshot()
    }

    private fun placePlayerSafely() {
        val px = -20f
        val pz = 5.8f
        val gy = terrain.groundSurfaceY(px, pz)
        playerPos = Vec3(px, gy + 0.05f, pz)
        playerVel = Vec3.ZERO
        dronePos = Vec3(-16f, 16f, 16f)
        yaw = -2.42f
        pitch = -0.08f
    }

    @Synchronized
    fun rebuildWorld(newConfig: WorldConfig) {
        config = newConfig
        projectiles.clear()
        plantedC4s.clear()
        queuedBlasts.clear()
        particles.clear()
        shockwaves.clear()
        geysers.clear()
        fireSites.clear()
        flashLights.clear()
        world.clear()
        terrain.regenerate(newConfig)
        ZoneBuilder.populateValley(world, terrain, newConfig)
        placePlayerSafely()
        resetOpsTracking()
        showBanner("Valley rebuilt (Seed ${newConfig.seed})")
        refreshCachesAndSnapshot()
    }

    @Synchronized
    fun resetDestruction() {
        rebuildWorld(config)
    }

    @Synchronized
    fun setGameMode(mode: GameMode) {
        if (gameMode == mode) return
        gameMode = mode
        if (mode == GameMode.DEMOLITION_OPS) {
            interactionTool = InteractionTool.WEAPON
            startMission(currentMission)
        } else {
            showBanner("Free Sandbox Mode — Unlimited Ordnance")
        }
    }

    @Synchronized
    fun startMission(mission: DemolitionMission) {
        currentMission = mission
        gameMode = GameMode.DEMOLITION_OPS
        interactionTool = InteractionTool.WEAPON
        projectiles.clear()
        plantedC4s.clear()
        queuedBlasts.clear()
        particles.clear()
        shockwaves.clear()
        geysers.clear()
        fireSites.clear()
        flashLights.clear()
        world.clear()
        terrain.regenerate(config)
        ZoneBuilder.populateValley(world, terrain, config)

        // Position player comfortably within view of the contract zone
        when (mission.targetTag) {
            "HOTEL" -> {
                val px = -127f; val pz = -4.8f
                playerPos = Vec3(px, terrain.groundSurfaceY(px, pz) + 0.05f, pz)
                yaw = -1.57f
            }
            "BRIDGE" -> {
                val px = -15f; val pz = 5.5f
                playerPos = Vec3(px, terrain.groundSurfaceY(px, pz) + 0.05f, pz)
                yaw = -0.32f
            }
            "TOWER_A" -> {
                val px = -24f; val pz = -5.2f
                playerPos = Vec3(px, terrain.groundSurfaceY(px, pz) + 0.05f, pz)
                yaw = -2.30f
            }
            "BANK" -> {
                val px = -44f; val pz = 44f
                playerPos = Vec3(px, terrain.groundSurfaceY(px, pz) + 0.05f, pz)
                yaw = -1.15f
            }
            "FACTORY" -> {
                val px = 58f; val pz = 60f
                playerPos = Vec3(px, terrain.groundSurfaceY(px, pz) + 0.05f, pz)
                yaw = 0.78f
            }
            "SILO", "HQ" -> {
                val px = 56f; val pz = -26f
                playerPos = Vec3(px, terrain.groundSurfaceY(px, pz) + 0.05f, pz)
                yaw = -0.08f
            }
            "APARTMENTS" -> {
                val px = -150f; val pz = 20f
                playerPos = Vec3(px, terrain.groundSurfaceY(px, pz) + 0.05f, pz)
                yaw = 3.14f
            }
            "STADIUM" -> {
                val px = -104f; val pz = 112f
                playerPos = Vec3(px, terrain.groundSurfaceY(px, pz) + 0.05f, pz)
                yaw = 1.57f
            }
            "RAIL_YARD" -> {
                val px = 108f; val pz = 112f
                playerPos = Vec3(px, terrain.groundSurfaceY(px, pz) + 0.05f, pz)
                yaw = 1.57f
            }
            else -> placePlayerSafely()
        }
        resetOpsTracking()
        showBanner("OPS: ${mission.title} — Target: ${mission.targetLabel}")
        refreshCachesAndSnapshot()
    }

    @Synchronized
    fun jumpToDistrict(target: DistrictJumpTarget) {
        val gy = terrain.groundSurfaceY(target.x, target.z)
        playerPos = Vec3(target.x, gy + 0.05f, target.z)
        playerVel = Vec3.ZERO
        dronePos = Vec3(target.x, max(22f, gy + 18f), target.z)
        yaw = target.yaw
        pitch = -0.06f
        showBanner("Jumped to ${target.label}")
        latestSnapshot = createSnapshot()
    }

    private fun resetOpsTracking() {
        score = 0
        comboMultiplier = 1
        comboTimer = 0f
        missionCompleted = false
        missionStarsEarned = 0
        ordnanceAmmo.clear()
        ordnanceAmmo.putAll(currentMission.ammoBudgets)
        initialTargetBlocks = world.blocks.values.count { it.targetTag == currentMission.targetTag }.coerceAtLeast(1)
    }

    @Synchronized
    fun setLightingPreset(preset: com.example.model.LightingPreset) {
        if (config.lightingPreset != preset) {
            config = config.copy(lightingPreset = preset)
            latestSnapshot = createSnapshot()
        }
    }

    @Synchronized
    fun addLookDelta(dx: Float, dy: Float) {
        yaw += dx
        pitch = clamp(pitch + dy, -1.38f, 1.38f)
    }

    fun requestJump() {
        jumpQueued = true
    }

    @Synchronized
    fun cycleCameraMode(): CameraMode {
        cameraMode = when (cameraMode) {
            CameraMode.FIRST_PERSON -> CameraMode.THIRD_PERSON
            CameraMode.THIRD_PERSON -> {
                dronePos = getCameraEye() + Vec3(0f, 3.5f, 0f)
                CameraMode.DRONE
            }
            CameraMode.DRONE -> CameraMode.FIRST_PERSON
        }
        showBanner("Camera: ${cameraMode.label}")
        return cameraMode
    }

    @Synchronized
    fun firePrimaryAction() {
        val eye = getCameraEye()
        val look = getLookDir()

        if (interactionTool == InteractionTool.BUILD_BRUSH) {
            val hit = raycastWorld(eye, look, 210f)
            if (hit != null) {
                ZoneBuilder.spawnBrushStructure(selectedBrush, selectedBuildMaterial, world, terrain, hit, yaw)
                world.checkStabilityAndCollapse(terrain)
                val label = if (selectedBrush == BrushItem.WALL_PANEL) {
                    "${selectedBuildMaterial.label} Wall"
                } else {
                    selectedBrush.label
                }
                showBanner("Placed $label")
            } else {
                showBanner("Aim at terrain to use brush")
            }
            return
        }

        if (fireCooldown > 0f && selectedWeapon != WeaponType.C4) return

        if (gameMode == GameMode.DEMOLITION_OPS) {
            val left = ordnanceAmmo[selectedWeapon] ?: 0
            if (left <= 0) {
                showBanner("Out of ${selectedWeapon.label} ammo!")
                return
            }
            ordnanceAmmo[selectedWeapon] = left - 1
        }

        val spawnOrigin = if (cameraMode == CameraMode.THIRD_PERSON) {
            playerPos + Vec3(0f, 1.55f, 0f) + look * 0.9f
        } else {
            eye + look * 0.8f
        }

        when (selectedWeapon) {
            WeaponType.CARBINE -> {
                fireCooldown = 0.11f
                emitSound(SoundCue.CARBINE_SHOT, 0f, 0.72f)
                firePrecisionBullet(eye, look, spawnOrigin, isHeavyAp = false)
            }
            WeaponType.HEAVY_RIFLE -> {
                fireCooldown = 0.42f
                emitSound(SoundCue.HEAVY_RIFLE_SHOT, 0f, 0.92f)
                shakeIntensity = max(shakeIntensity, 0.08f)
                firePrecisionBullet(eye, look, spawnOrigin, isHeavyAp = true)
            }
            WeaponType.RPG -> {
                fireCooldown = 0.35f
                emitSound(SoundCue.RPG_LAUNCH, 0f, 0.85f)
                projectiles.add(ActiveProjectile(WeaponType.RPG, spawnOrigin, look * 46f))
            }
            WeaponType.C4 -> {
                emitSound(SoundCue.C4_BEEP, 0f, 0.65f)
                projectiles.add(ActiveProjectile(WeaponType.C4, spawnOrigin, look * 26f + Vec3(0f, 3.2f, 0f)))
            }
            WeaponType.ARTILLERY -> {
                val target = raycastWorld(eye, look, 220f)
                if (target != null) {
                    fireCooldown = 0.75f
                    emitSound(SoundCue.RPG_LAUNCH, 0f, 0.95f)
                    repeat(3) { idx ->
                        val spreadX = (rng.nextFloat() - 0.5f) * 6.5f
                        val spreadZ = (rng.nextFloat() - 0.5f) * 6.5f
                        val aimPt = Vec3(target.x + spreadX, target.y, target.z + spreadZ)
                        val skyOrigin = aimPt + Vec3(-8f, 44f, -4f)
                        val vel = (aimPt - skyOrigin).normalized() * 38f
                        projectiles.add(
                            ActiveProjectile(
                                type = WeaponType.ARTILLERY,
                                pos = skyOrigin + Vec3(0f, idx * 14f, 0f),
                                vel = vel
                            )
                        )
                    }
                    showBanner("155mm Barrage Inbound!")
                } else {
                    if (gameMode == GameMode.DEMOLITION_OPS) {
                        ordnanceAmmo[WeaponType.ARTILLERY] = (ordnanceAmmo[WeaponType.ARTILLERY] ?: 0) + 1
                    }
                    showBanner("Aim at terrain or building for Artillery")
                }
            }
            WeaponType.WRECKING_BALL -> {
                fireCooldown = 0.45f
                projectiles.add(
                    ActiveProjectile(
                        type = WeaponType.WRECKING_BALL,
                        pos = spawnOrigin,
                        vel = look * 39f + Vec3(0f, 2.5f, 0f),
                        bouncesLeft = 4
                    )
                )
            }
        }
    }

    private fun firePrecisionBullet(eye: Vec3, look: Vec3, muzzle: Vec3, isHeavyAp: Boolean) {
        val result = world.applyBulletImpact(eye, look, isHeavyAp, terrain, maxDist = 210f)
        val hitPt = result.hitPoint ?: (muzzle + look * 85f)

        // Bright ballistic tracer streaks along path
        val stepCount = if (isHeavyAp) 6 else 4
        val tracerCol = if (isHeavyAp) Vec3(1.0f, 0.78f, 0.25f) else Vec3(1.0f, 0.92f, 0.55f)
        for (i in 1..stepCount) {
            val t = i.toFloat() / (stepCount + 1)
            val p = muzzle + (hitPt - muzzle) * t
            spawnParticle(
                pos = p, vel = look * 2.5f, color = tracerCol,
                alpha = 0.88f, size = if (isHeavyAp) 0.16f else 0.11f, maxAge = 0.09f, gravity = 0f,
                kind = ParticleKind.TRACER, growthRate = 0.1f
            )
        }

        if (result.hitPoint == null) return
        val dist = (result.hitPoint - eye).len()

        if (!result.hitBlock && !result.hitShard) {
            if (result.hitPoint.y <= ChunkedTerrain.WATER_LEVEL + 0.15f) {
                emitSound(SoundCue.WATER_SPLASH, dist, 0.7f)
                spawnWaterSplash(result.hitPoint, 8)
            } else {
                spawnDustBurst(result.hitPoint, 5)
            }
            return
        }

        // Material-specific bullet feedback & audio
        when (result.soundKind) {
            MaterialSoundKind.GLASS -> {
                emitSound(SoundCue.GLASS_SHATTER, dist, 0.9f)
                spawnMaterialChips(result.hitPoint, Vec3(0.75f, 0.92f, 1.0f), if (result.blockBroken) 14 else 6)
            }
            MaterialSoundKind.PLASTIC -> {
                emitSound(SoundCue.PLASTIC_SNAP, dist, 0.85f)
                spawnMaterialChips(result.hitPoint, Vec3(0.92f, 0.52f, 0.22f), if (result.blockBroken) 10 else 5)
            }
            MaterialSoundKind.WOOD -> {
                emitSound(SoundCue.WOOD_SPLINTER, dist, 0.85f)
                spawnMaterialChips(result.hitPoint, Vec3(0.84f, 0.68f, 0.44f), if (result.blockBroken) 12 else 6)
            }
            MaterialSoundKind.METAL -> {
                emitSound(SoundCue.METAL_RICOCHET, dist, if (result.ricochet) 0.95f else 0.82f)
                spawnSparks(result.hitPoint, result.hitNormal, if (isHeavyAp) 12 else 8)
            }
            else -> {
                spawnDustBurst(result.hitPoint, if (result.blockBroken) 11 else 5)
            }
        }

        if (result.hydrantBurstPos != null) {
            geysers.add(ActiveGeyser(result.hydrantBurstPos))
            emitSound(SoundCue.WATER_SPLASH, dist, 0.95f)
            showBanner("Fire hydrant ruptured!")
        }

        for (sb in result.secondaryBlasts) {
            queuedBlasts.add(QueuedBlast(0.06f, sb.pos, sb.radius, sb.craterDepth, 185f, sb.impulse, isFuelFire = true))
        }

        if (result.pointsAwarded > 0) {
            awardDestructionScore(result.pointsAwarded, result.collapsedCascade)
        }
        if (result.collapsedCascade >= 2) {
            spawnDustBurst(result.hitPoint, 10)
        }
        if (result.collapsedCascade >= 3) {
            emitSound(SoundCue.COLLAPSE_RUMBLE, dist, 0.9f)
        }
    }

    @Synchronized
    fun detonateAllC4() {
        if (plantedC4s.isEmpty()) return
        plantedC4s.forEachIndexed { idx, c4 ->
            queuedBlasts.add(
                QueuedBlast(
                    delay = idx * 0.065f,
                    pos = c4.worldPos,
                    radius = 5.4f,
                    craterDepth = 1.45f,
                    damage = 195f,
                    impulse = 22f
                )
            )
        }
        val count = plantedC4s.size
        plantedC4s.clear()
        showBanner("Detonated $count C4 charge${if (count > 1) "s" else ""}!")
    }

    @Synchronized
    fun step(dtRaw: Float): HudUiState {
        val dt = clamp(dtRaw, 0.004f, 0.033f)
        waterTime += dt
        fireCooldown = max(0f, fireCooldown - dt)
        shakeIntensity = max(0f, shakeIntensity - dt * 2.2f)
        if (statusTimer > 0f) {
            statusTimer -= dt
            if (statusTimer <= 0f) statusBanner = null
        }
        if (comboTimer > 0f) {
            comboTimer -= dt
            if (comboTimer <= 0f) comboMultiplier = 1
        }

        if (autoFireActive && interactionTool == InteractionTool.WEAPON && selectedWeapon == WeaponType.CARBINE && fireCooldown <= 0f) {
            firePrimaryAction()
        }

        stepMovement(dt)
        stepProjectiles(dt)
        stepC4Attachments(dt)
        stepQueuedBlasts(dt)
        stepShockwaves(dt)
        stepGeysers(dt)
        stepFireSites(dt)
        stepFlashLights(dt)

        val shardSplashes = world.stepShards(dt, terrain)
        for (sp in shardSplashes) {
            spawnWaterSplash(sp.pos, 10)
            emitSound(SoundCue.WATER_SPLASH, (sp.pos - getCameraEye()).len(), 0.65f)
        }
        val collapseEvents = world.drainCollapseFailureEvents()
        if (collapseEvents.isNotEmpty()) {
            val camEye = getCameraEye()
            var rumblePlayed = false
            val maxDustEvents = min(3, collapseEvents.size)
            for (ci in 0 until maxDustEvents) {
                val ev = collapseEvents[ci]
                spawnDustBurst(ev.pos, if (ev.isClusterBreak) 5 else 4)
                if (!rumblePlayed) {
                    rumblePlayed = true
                    emitSound(SoundCue.COLLAPSE_RUMBLE, (ev.pos - camEye).len(), if (ev.isClusterBreak) 0.62f else 0.78f)
                }
            }
        }

        stepParticles(dt)
        refreshCachesAndSnapshot()

        val missionProgress = if (gameMode == GameMode.DEMOLITION_OPS) {
            evaluateMissionProgress()
        } else null

        val refPos = if (cameraMode == CameraMode.DRONE) dronePos else playerPos
        return HudUiState(
            currentZone = terrain.classifyZone(refPos.x, refPos.z),
            cameraMode = cameraMode,
            interactionTool = interactionTool,
            selectedWeapon = selectedWeapon,
            selectedBrush = selectedBrush,
            selectedBuildMaterial = selectedBuildMaterial,
            gameMode = gameMode,
            activeMission = missionProgress,
            score = score,
            comboMultiplier = comboMultiplier,
            ordnanceAmmo = ordnanceAmmo.toMap(),
            armedC4Count = plantedC4s.size,
            activeShardCount = world.shards.count { !it.sleeping },
            intactBlocksCount = world.blocks.size,
            collapsedCount = world.collapsedCount,
            isSprinting = isSprinting,
            worldConfig = config,
            statusBanner = statusBanner
        )
    }

    private fun evaluateMissionProgress(): MissionProgress {
        val remaining = world.blocks.values.count { it.targetTag == currentMission.targetTag }
        val pct = (((initialTargetBlocks - remaining).toFloat() / initialTargetBlocks.toFloat()) * 100f)
            .toInt()
            .coerceIn(0, 100)
        if (!missionCompleted && pct >= currentMission.requiredDestructionPct) {
            missionCompleted = true
            val stars = when {
                score >= (currentMission.parScore * 1.15f).toInt() -> 3
                score >= (currentMission.parScore * 0.80f).toInt() -> 2
                else -> 1
            }
            missionStarsEarned = stars
            emitSound(SoundCue.OBJECTIVE_CHIME, 0f, 1.0f)
            showBanner("CONTRACT COMPLETE! ${"★".repeat(stars)} ($score PTS)")
            onMissionCompleted?.invoke(currentMission.id, stars)
        }
        return MissionProgress(
            mission = currentMission,
            destructionPct = pct,
            isCompleted = missionCompleted,
            starsEarned = missionStarsEarned
        )
    }

    private fun awardDestructionScore(basePoints: Int, cascadeCount: Int) {
        score += (basePoints + cascadeCount * 12) * comboMultiplier
        comboTimer = 3.2f
        comboMultiplier = (comboMultiplier + 1).coerceAtMost(5)
    }

    private fun stepMovement(dt: Float) {
        val forwardXZ = Vec3(cos(yaw), 0f, sin(yaw)).normalized()
        val rightXZ = Vec3(-sin(yaw), 0f, cos(yaw)).normalized()
        val wishDir = (forwardXZ * moveInputY + rightXZ * moveInputX)
        val wishLen = wishDir.len()
        val normWish = if (wishLen > 1e-3f) wishDir / max(1f, wishLen) else Vec3.ZERO

        if (cameraMode == CameraMode.DRONE) {
            val speed = if (isSprinting) 28f else 17f
            val look3D = getLookDir()
            val droneWish = (look3D * moveInputY + rightXZ * moveInputX + Vec3(0f, droneVertical, 0f))
            dronePos += droneWish * (speed * dt)
            dronePos = Vec3(
                clamp(dronePos.x, ChunkedTerrain.MIN_X + 2f, ChunkedTerrain.MAX_X - 2f),
                clamp(dronePos.y, terrain.groundSurfaceY(dronePos.x, dronePos.z) + 1.2f, 125f),
                clamp(dronePos.z, ChunkedTerrain.MIN_Z + 2f, ChunkedTerrain.MAX_Z - 2f)
            )
            return
        }

        val inWater = playerPos.y < ChunkedTerrain.WATER_LEVEL - 0.15f
        val baseSpeed = if (isSprinting) 13.5f else 8.2f
        val moveSpeed = if (inWater) baseSpeed * 0.62f else baseSpeed

        playerVel = Vec3(
            normWish.x * moveSpeed,
            playerVel.y - 21.0f * dt,
            normWish.z * moveSpeed
        )

        var nextPos = playerPos + playerVel * dt
        nextPos = Vec3(
            clamp(nextPos.x, ChunkedTerrain.MIN_X + 2f, ChunkedTerrain.MAX_X - 2f),
            nextPos.y,
            clamp(nextPos.z, ChunkedTerrain.MIN_Z + 2f, ChunkedTerrain.MAX_Z - 2f)
        )

        val horizSpeed = kotlin.math.hypot(normWish.x * moveSpeed, normWish.z * moveSpeed)

        val (resolvedPos, blockFloorY) = world.resolveCharacterSupport(nextPos, radius = 0.42f, height = 1.75f)
        nextPos = resolvedPos

        val terrainFloorY = terrain.groundSurfaceY(nextPos.x, nextPos.z)
        val floorY = if (blockFloorY != null) max(terrainFloorY, blockFloorY) else terrainFloorY

        val grounded = nextPos.y <= floorY + 0.06f
        isPlayerGrounded = grounded
        if (grounded) {
            nextPos = Vec3(nextPos.x, floorY, nextPos.z)
            playerVel = Vec3(playerVel.x, 0f, playerVel.z)
            if (jumpQueued) {
                playerVel = Vec3(playerVel.x, 8.6f, playerVel.z)
                isPlayerGrounded = false
                jumpQueued = false
            }
        } else {
            jumpQueued = false
        }
        if (horizSpeed > 0.25f && isPlayerGrounded) {
            walkCyclePhase += horizSpeed * dt * 1.35f
            walkCycleAmp = clamp(walkCycleAmp + dt * 8.0f, 0f, clamp(horizSpeed / 8.2f, 0f, 1.25f))
        } else {
            walkCycleAmp = max(0f, walkCycleAmp - dt * 6.5f)
        }
        playerPos = nextPos
    }

    private fun stepProjectiles(dt: Float) {
        val it = projectiles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.age += dt
            if (p.age > 9f) {
                it.remove()
                continue
            }

            when (p.type) {
                WeaponType.RPG -> {
                    p.vel = Vec3(p.vel.x, p.vel.y - 1.2f * dt, p.vel.z)
                    spawnParticle(
                        p.pos,
                        Vec3((rng.nextFloat() - 0.5f) * 1.2f, 0.8f, (rng.nextFloat() - 0.5f) * 1.2f),
                        Vec3(0.75f, 0.75f, 0.78f), alpha = 0.62f, size = 0.36f, maxAge = 0.75f, gravity = 0.55f,
                        kind = ParticleKind.SMOKE, growthRate = 1.25f
                    )
                }
                WeaponType.C4 -> {
                    p.vel = Vec3(p.vel.x, p.vel.y - 17.5f * dt, p.vel.z)
                }
                WeaponType.ARTILLERY -> {
                    p.vel = Vec3(p.vel.x, p.vel.y - 14.0f * dt, p.vel.z)
                    spawnParticle(
                        p.pos, Vec3(0f, 1.5f, 0f),
                        Vec3(1.0f, 0.72f, 0.30f), alpha = 0.86f, size = 0.52f, maxAge = 0.42f, gravity = 0.3f,
                        kind = ParticleKind.FIREBALL, growthRate = 1.1f
                    )
                }
                WeaponType.WRECKING_BALL -> {
                    p.vel = Vec3(p.vel.x, p.vel.y - 17.5f * dt, p.vel.z)
                }
                else -> Unit
            }

            val stepVec = p.vel * dt
            val stepDist = stepVec.len()
            val dir = if (stepDist > 1e-5f) stepVec / stepDist else Vec3(0f, -1f, 0f)

            val blockHit = world.raycastBlocks(p.pos, dir, stepDist + 0.25f)
            val shardHit = world.raycastShards(p.pos, dir, stepDist + 0.25f)
            val nextPos = p.pos + stepVec
            val gy = if (nextPos.x in ChunkedTerrain.MIN_X..ChunkedTerrain.MAX_X &&
                nextPos.z in ChunkedTerrain.MIN_Z..ChunkedTerrain.MAX_Z
            ) terrain.groundSurfaceY(nextPos.x, nextPos.z) else -10f

            if (p.pos.y > ChunkedTerrain.WATER_LEVEL && nextPos.y <= ChunkedTerrain.WATER_LEVEL && gy < ChunkedTerrain.WATER_LEVEL) {
                spawnWaterSplash(Vec3(nextPos.x, ChunkedTerrain.WATER_LEVEL, nextPos.z), 16)
                emitSound(SoundCue.WATER_SPLASH, (nextPos - getCameraEye()).len(), 0.75f)
            }

            if (p.type == WeaponType.WRECKING_BALL) {
                val kinetic = world.applyKineticImpactDetailed(nextPos, p.vel, radius = 0.9f, terrain = terrain)
                for (sb in kinetic.secondaryBlasts) {
                    queuedBlasts.add(QueuedBlast(0.08f, sb.pos, sb.radius, sb.craterDepth, 160f, sb.impulse, isFuelFire = true))
                }
                if (kinetic.bounced) {
                    val wn = kinetic.bounceNormal
                    val vn = p.vel.dot(wn)
                    if (vn < 0f) {
                        p.vel -= wn * (1.72f * vn)
                    }
                    p.vel = p.vel * 0.78f
                    p.pos = kinetic.correctedPos ?: nextPos
                    spawnSparks(p.pos, wn, 10)
                    emitSound(SoundCue.METAL_RICOCHET, (p.pos - getCameraEye()).len(), 0.90f)
                    if (p.bouncesLeft <= 0 || p.vel.len() < 3.5f) it.remove() else p.bouncesLeft--
                    continue
                }
                if (kinetic.hitAny) {
                    p.vel = p.vel * 0.76f
                    spawnDustBurst(nextPos, 12)
                    emitSound(SoundCue.COLLAPSE_RUMBLE, (nextPos - getCameraEye()).len(), 0.75f)
                }
                if (nextPos.y <= gy + 0.55f) {
                    p.pos = Vec3(nextPos.x, gy + 0.55f, nextPos.z)
                    val n = terrain.groundNormal(p.pos.x, p.pos.z)
                    val vn = p.vel.dot(n)
                    if (vn < 0f) p.vel -= n * (1.35f * vn)
                    p.vel = p.vel * 0.80f
                    if (p.bouncesLeft <= 0 || p.vel.len() < 3.5f) it.remove() else p.bouncesLeft--
                    continue
                }
                p.pos = nextPos
                continue
            }

            val bDist = blockHit?.distance ?: Float.MAX_VALUE
            val sDist = shardHit?.distance ?: Float.MAX_VALUE

            if (shardHit != null && sDist < bDist) {
                if (p.type == WeaponType.C4) {
                    val sh = world.shards.firstOrNull { it.id == shardHit.shardId }
                    val localOff = if (sh != null) {
                        sh.orientation.conjugate().rotate(shardHit.hitPoint - sh.center)
                    } else Vec3.ZERO
                    plantedC4s.add(PlantedC4(shardHit.hitPoint, null, localOff, attachedShardId = shardHit.shardId))
                    it.remove()
                    continue
                }
                val (rad, depth, dmg, imp) = explosionParams(p.type)
                triggerExplosion(shardHit.hitPoint, rad, depth, dmg, imp)
                it.remove()
                continue
            }

            if (blockHit != null) {
                if (p.type == WeaponType.C4) {
                    val blk = world.blocks[blockHit.blockId]
                    val localOff = if (blk != null) {
                        blk.orientation.conjugate().rotate(blockHit.hitPoint - blk.center)
                    } else Vec3.ZERO
                    plantedC4s.add(PlantedC4(blockHit.hitPoint, blockHit.blockId, localOff))
                    it.remove()
                    continue
                }
                val (rad, depth, dmg, imp) = explosionParams(p.type)
                triggerExplosion(blockHit.hitPoint, rad, depth, dmg, imp)
                it.remove()
                continue
            }

            if (nextPos.y <= gy) {
                val hitPt = Vec3(nextPos.x, gy, nextPos.z)
                if (p.type == WeaponType.C4) {
                    plantedC4s.add(PlantedC4(hitPt, null, Vec3.ZERO))
                    it.remove()
                    continue
                }
                val (rad, depth, dmg, imp) = explosionParams(p.type)
                triggerExplosion(hitPt, rad, depth, dmg, imp)
                it.remove()
                continue
            }

            p.pos = nextPos
        }
    }

    private fun explosionParams(type: WeaponType): List<Float> = when (type) {
        WeaponType.RPG -> listOf(4.6f, 1.15f, 155f, 18f)
        WeaponType.ARTILLERY -> listOf(6.8f, 1.95f, 240f, 26f)
        else -> listOf(5.4f, 1.45f, 200f, 22f)
    }

    private fun stepC4Attachments(dt: Float) {
        for (c4 in plantedC4s) {
            c4.age += dt
            val blkId = c4.attachedBlockId
            if (blkId != null) {
                val blk = world.blocks[blkId]
                if (blk != null) {
                    c4.worldPos = blk.center + blk.orientation.rotate(c4.localOffset)
                }
            } else {
                val shId = c4.attachedShardId
                if (shId != null) {
                    val sh = world.shards.firstOrNull { it.id == shId }
                    if (sh != null) {
                        c4.worldPos = sh.center + sh.orientation.rotate(c4.localOffset)
                    }
                }
            }
        }
    }

    private fun stepFlashLights(dt: Float) {
        if (flashLights.isEmpty()) return
        val it = flashLights.iterator()
        while (it.hasNext()) {
            val fl = it.next()
            fl.age += dt
            if (fl.age >= fl.duration) it.remove()
        }
    }

    private fun stepQueuedBlasts(dt: Float) {
        if (queuedBlasts.isEmpty()) return
        val ready = ArrayList<QueuedBlast>()
        val it = queuedBlasts.iterator()
        while (it.hasNext()) {
            val b = it.next()
            b.delay -= dt
            if (b.delay <= 0f) {
                ready.add(b)
                it.remove()
            }
        }
        for (b in ready) {
            triggerExplosion(b.pos, b.radius, b.craterDepth, b.damage, b.impulse, isFuelFire = b.isFuelFire)
        }
    }

    private fun triggerExplosion(
        pos: Vec3,
        radius: Float,
        craterDepth: Float,
        damage: Float,
        impulse: Float,
        isFuelFire: Boolean = false
    ) {
        val craterRadius = radius * 0.85f
        terrain.applyCrater(pos.x, pos.z, craterRadius, craterDepth, scorchAmount = 0.92f)
        world.wakeShardsInRadius(pos, craterRadius * 1.75f)
        val outcome = world.applyExplosionDetailed(pos, radius, damage, impulse, terrain)

        val dist = (getCameraEye() - pos).len()
        emitSound(SoundCue.EXPLOSION, dist, 1.0f)
        if (outcome.brokenSounds.contains(MaterialSoundKind.GLASS)) {
            emitSound(SoundCue.GLASS_SHATTER, dist, 0.85f)
        }
        if (outcome.collapsedByCascade >= 2 || outcome.destroyedCount >= 5) {
            emitSound(SoundCue.COLLAPSE_RUMBLE, dist, 0.92f)
            spawnDustBurst(pos + Vec3(0f, 1.2f, 0f), 9)
        }

        if (outcome.pointsAwarded > 0) {
            awardDestructionScore(outcome.pointsAwarded, outcome.collapsedByCascade)
        }

        for (sb in outcome.secondaryBlasts) {
            queuedBlasts.add(
                QueuedBlast(
                    delay = 0.09f + rng.nextFloat() * 0.08f,
                    pos = sb.pos,
                    radius = sb.radius,
                    craterDepth = sb.craterDepth,
                    damage = 190f,
                    impulse = sb.impulse,
                    isFuelFire = true
                )
            )
        }
        for (sp in outcome.splashes) {
            geysers.add(ActiveGeyser(sp.pos))
            emitSound(SoundCue.WATER_SPLASH, dist, 0.85f)
        }

        val shake = clamp(1.0f - dist / 55f, 0f, 1f) * (radius / 5f) * 0.48f
        shakeIntensity = max(shakeIntensity, shake)

        shockwaves.add(ShockwaveRing(pos = pos, maxRadius = radius * 2.15f))
        spawnExplosionParticles(pos, radius)
        if (flashLights.size >= 3) flashLights.removeAt(0)
        flashLights.add(
            ActiveFlashLight(
                pos = pos + Vec3(0f, 0.75f, 0f),
                duration = 0.46f,
                radius = clamp(radius * 3.1f, 11f, 24f),
                color = Vec3(2.6f, 1.48f, 0.56f)
            )
        )

        if (pos.y <= ChunkedTerrain.WATER_LEVEL + 0.8f) {
            spawnWaterSplash(Vec3(pos.x, ChunkedTerrain.WATER_LEVEL, pos.z), 20)
        } else if (isFuelFire || outcome.secondaryBlasts.isNotEmpty() || radius >= 4.2f) {
            val fuel = isFuelFire || outcome.secondaryBlasts.isNotEmpty()
            val targetR = if (fuel) radius * 0.60f else radius * 0.40f
            val targetLife = if (fuel) 13.5f + rng.nextFloat() * 3.5f else 7.0f + rng.nextFloat() * 2.5f
            val nearby = fireSites.firstOrNull { (it.pos - pos).lenSq() < 16f }
            if (nearby != null) {
                nearby.timeLeft = max(nearby.timeLeft, targetLife)
                nearby.radius = min(4.2f, max(nearby.radius, targetR))
                if (fuel) nearby.isFuelFire = true
            } else {
                if (fireSites.size >= 5) fireSites.removeAt(0)
                fireSites.add(
                    ActiveFireSite(
                        pos = pos,
                        radius = targetR,
                        timeLeft = targetLife,
                        isFuelFire = fuel
                    )
                )
            }
        }
    }

    private fun stepShockwaves(dt: Float) {
        val it = shockwaves.iterator()
        while (it.hasNext()) {
            val sw = it.next()
            sw.age += dt
            if (sw.age >= sw.duration) it.remove()
        }
    }

    private fun stepGeysers(dt: Float) {
        val it = geysers.iterator()
        while (it.hasNext()) {
            val g = it.next()
            g.timeLeft -= dt
            g.emitAcc += dt
            if (g.timeLeft <= 0f) {
                it.remove()
                continue
            }
            if (g.emitAcc >= 0.055f) {
                g.emitAcc = 0f
                val dir = Vec3((rng.nextFloat() - 0.5f) * 0.35f, 1.0f, (rng.nextFloat() - 0.5f) * 0.35f).normalized()
                spawnParticle(
                    pos = g.pos + Vec3(0f, 0.25f, 0f),
                    vel = dir * (10.5f + rng.nextFloat() * 3.5f),
                    color = Vec3(0.76f, 0.92f, 1.0f),
                    alpha = 0.82f,
                    size = 0.34f,
                    maxAge = 0.9f,
                    gravity = -16f,
                    kind = ParticleKind.SPLASH,
                    growthRate = 0.5f
                )
            }
        }
    }

    private fun stepFireSites(dt: Float) {
        val it = fireSites.iterator()
        while (it.hasNext()) {
            val fs = it.next()
            fs.timeLeft -= dt
            fs.emitAcc += dt
            if (fs.timeLeft <= 0f) {
                it.remove()
                continue
            }
            val interval = if (fs.isFuelFire) 0.18f else 0.26f
            if (fs.emitAcc >= interval) {
                fs.emitAcc = 0f
                val lifeFade = clamp(fs.timeLeft / 3.0f, 0.25f, 1.0f)
                val ox = (rng.nextFloat() - 0.5f) * fs.radius * 0.55f
                val oz = (rng.nextFloat() - 0.5f) * fs.radius * 0.55f
                val emitPos = Vec3(
                    fs.pos.x + ox,
                    max(fs.pos.y, terrain.groundSurfaceY(fs.pos.x + ox, fs.pos.z + oz)) + 0.18f,
                    fs.pos.z + oz
                )
                // Flickering flame core
                spawnParticle(
                    pos = emitPos,
                    vel = Vec3((rng.nextFloat() - 0.5f) * 0.9f, 2.4f + rng.nextFloat() * 1.6f, (rng.nextFloat() - 0.5f) * 0.9f),
                    color = Vec3(1.0f, 0.46f + rng.nextFloat() * 0.36f, 0.12f),
                    alpha = 0.80f * lifeFade,
                    size = (if (fs.isFuelFire) 0.92f else 0.68f) + rng.nextFloat() * 0.38f,
                    maxAge = 0.42f + rng.nextFloat() * 0.22f,
                    gravity = 2.4f,
                    kind = ParticleKind.FIREBALL,
                    growthRate = 0.65f
                )
                // Convective rising soot plume (tapered life avoids thick screen-blocking blobs)
                val smokeShade = if (fs.isFuelFire) 0.18f + rng.nextFloat() * 0.12f else 0.28f + rng.nextFloat() * 0.14f
                spawnParticle(
                    pos = emitPos + Vec3(0f, 0.72f, 0f),
                    vel = Vec3((rng.nextFloat() - 0.5f) * 1.3f, 3.6f + rng.nextFloat() * 2.0f, (rng.nextFloat() - 0.5f) * 1.3f),
                    color = Vec3(smokeShade, smokeShade * 0.99f, smokeShade * 1.01f),
                    alpha = 0.56f * lifeFade,
                    size = (if (fs.isFuelFire) 1.08f else 0.86f) + rng.nextFloat() * 0.55f,
                    maxAge = 1.65f + rng.nextFloat() * 0.95f,
                    gravity = 1.35f,
                    kind = ParticleKind.SMOKE,
                    growthRate = 1.05f
                )
                if (fs.isFuelFire && rng.nextFloat() < 0.35f) {
                    spawnParticle(
                        pos = emitPos + Vec3(0f, 0.35f, 0f),
                        vel = Vec3((rng.nextFloat() - 0.5f) * 3.2f, 5.2f + rng.nextFloat() * 3.0f, (rng.nextFloat() - 0.5f) * 3.2f),
                        color = Vec3(1.0f, 0.75f, 0.24f),
                        alpha = 0.90f,
                        size = 0.14f,
                        maxAge = 0.48f,
                        gravity = -5.5f,
                        kind = ParticleKind.SPARK,
                        growthRate = 0.08f
                    )
                }
            }
        }
    }

    private fun spawnExplosionParticles(pos: Vec3, radius: Float) {
        // Stage 1: Bright Core Flash & High-Speed Incandescent Sparks
        spawnParticle(
            pos = pos + Vec3(0f, 0.35f, 0f),
            vel = Vec3(0f, 1.2f, 0f),
            color = Vec3(1.0f, 0.92f, 0.65f),
            alpha = 0.95f,
            size = radius * 0.80f,
            maxAge = 0.22f,
            gravity = 0.5f,
            kind = ParticleKind.FIREBALL,
            growthRate = 1.8f
        )
        val sparkCount = (radius * 2.6f).toInt().coerceIn(8, 16)
        repeat(sparkCount) {
            val dir = Vec3(rng.nextFloat() * 2f - 1f, rng.nextFloat() * 1.5f + 0.2f, rng.nextFloat() * 2f - 1f).normalized()
            spawnParticle(
                pos = pos + dir * 0.25f,
                vel = dir * (11f + rng.nextFloat() * radius * 2.6f),
                color = Vec3(1.0f, 0.78f + rng.nextFloat() * 0.2f, 0.25f),
                alpha = 0.96f,
                size = 0.13f + rng.nextFloat() * 0.08f,
                maxAge = 0.32f + rng.nextFloat() * 0.26f,
                gravity = -11f,
                kind = ParticleKind.SPARK,
                growthRate = 0.05f
            )
        }

        // Stage 2: Rising Turbulent Fireball Billows
        val fireCount = (radius * 2.0f).toInt().coerceIn(6, 12)
        repeat(fireCount) {
            val dir = Vec3(rng.nextFloat() * 2f - 1f, rng.nextFloat() * 1.35f + 0.15f, rng.nextFloat() * 2f - 1f).normalized()
            spawnParticle(
                pos = pos + dir * 0.35f,
                vel = dir * (4.2f + rng.nextFloat() * radius * 1.35f),
                color = Vec3(1.0f, 0.42f + rng.nextFloat() * 0.42f, 0.12f),
                alpha = 0.88f,
                size = 0.82f + rng.nextFloat() * 0.68f,
                maxAge = 0.42f + rng.nextFloat() * 0.28f,
                gravity = 3.0f,
                kind = ParticleKind.FIREBALL,
                growthRate = 1.25f
            )
        }

        // Stage 3: High-Contrast Convective Smoke Plume (fewer, crisp puffs -> zero overdraw lag)
        val smokeCount = (radius * 2.4f).toInt().coerceIn(9, 15)
        repeat(smokeCount) {
            val dir = Vec3(rng.nextFloat() * 2f - 1f, rng.nextFloat() * 1.7f + 0.40f, rng.nextFloat() * 2f - 1f).normalized()
            val shade = 0.20f + rng.nextFloat() * 0.24f
            spawnParticle(
                pos = pos + dir * 0.45f,
                vel = dir * (3.2f + rng.nextFloat() * radius * 1.1f),
                color = Vec3(shade * 1.02f, shade, shade * 0.97f),
                alpha = 0.64f,
                size = 1.02f + rng.nextFloat() * 0.72f,
                maxAge = 1.95f + rng.nextFloat() * 1.25f,
                gravity = 1.55f,
                kind = ParticleKind.SMOKE,
                growthRate = 1.12f
            )
        }

        // Stage 4: Lingering Ground Dust Skirt sweeping outward radially
        val dustCount = (radius * 1.8f).toInt().coerceIn(6, 11)
        repeat(dustCount) {
            val ang = rng.nextFloat() * (2f * PI.toFloat())
            val dir = Vec3(cos(ang), 0.16f + rng.nextFloat() * 0.28f, sin(ang)).normalized()
            val tShade = 0.58f + rng.nextFloat() * 0.14f
            spawnParticle(
                pos = pos + dir * (0.45f + rng.nextFloat() * 0.75f),
                vel = dir * (4.8f + rng.nextFloat() * radius * 1.4f),
                color = Vec3(tShade, tShade * 0.93f, tShade * 0.82f),
                alpha = 0.48f,
                size = 0.92f + rng.nextFloat() * 0.65f,
                maxAge = 1.85f + rng.nextFloat() * 1.05f,
                gravity = -0.35f,
                kind = ParticleKind.DUST,
                growthRate = 0.95f
            )
        }
    }

    private fun spawnSparks(pos: Vec3, normal: Vec3, count: Int) {
        repeat(count) {
            val jitter = Vec3(rng.nextFloat() - 0.5f, rng.nextFloat() - 0.2f, rng.nextFloat() - 0.5f)
            val dir = (normal * 0.8f + jitter).normalized()
            spawnParticle(
                pos = pos + normal * 0.08f,
                vel = dir * (7f + rng.nextFloat() * 6f),
                color = Vec3(1.0f, 0.82f, 0.32f),
                alpha = 0.96f,
                size = 0.16f,
                maxAge = 0.32f,
                gravity = -12f,
                kind = ParticleKind.SPARK,
                growthRate = 0.05f
            )
        }
    }

    private fun spawnMaterialChips(pos: Vec3, color: Vec3, count: Int) {
        repeat(count) {
            val dir = Vec3(rng.nextFloat() - 0.5f, rng.nextFloat() * 0.8f + 0.1f, rng.nextFloat() - 0.5f).normalized()
            spawnParticle(
                pos = pos,
                vel = dir * (4.5f + rng.nextFloat() * 5f),
                color = color,
                alpha = 0.86f,
                size = 0.22f,
                maxAge = 0.48f,
                gravity = -14f,
                kind = ParticleKind.DUST,
                growthRate = 0.2f
            )
        }
    }

    private fun spawnDustBurst(pos: Vec3, count: Int) {
        repeat(count) {
            val dir = Vec3(rng.nextFloat() - 0.5f, rng.nextFloat() * 0.7f + 0.2f, rng.nextFloat() - 0.5f).normalized()
            val s = 0.62f + rng.nextFloat() * 0.12f
            spawnParticle(
                pos = pos,
                vel = dir * (3.5f + rng.nextFloat() * 4.5f),
                color = Vec3(s, s * 0.95f, s * 0.86f),
                alpha = 0.58f,
                size = 0.68f + rng.nextFloat() * 0.52f,
                maxAge = 1.6f + rng.nextFloat() * 1.1f,
                gravity = -0.6f,
                kind = ParticleKind.DUST,
                growthRate = 1.15f
            )
        }
    }

    private fun spawnWaterSplash(pos: Vec3, count: Int) {
        repeat(count) {
            val dir = Vec3(
                (rng.nextFloat() - 0.5f) * 1.4f,
                1.2f + rng.nextFloat() * 1.4f,
                (rng.nextFloat() - 0.5f) * 1.4f
            ).normalized()
            spawnParticle(
                pos = pos + Vec3(0f, 0.15f, 0f),
                vel = dir * (6f + rng.nextFloat() * 7f),
                color = Vec3(0.75f, 0.91f, 1.0f),
                alpha = 0.82f,
                size = 0.46f,
                maxAge = 0.85f,
                gravity = -16f,
                kind = ParticleKind.SPLASH,
                growthRate = 0.55f
            )
        }
    }

    private fun spawnParticle(
        pos: Vec3,
        vel: Vec3,
        color: Vec3,
        alpha: Float,
        size: Float,
        maxAge: Float,
        gravity: Float,
        kind: ParticleKind = ParticleKind.SMOKE,
        growthRate: Float = 0.7f
    ) {
        if (particles.size >= 180) {
            val dropIdx = particles.indexOfFirst { it.kind == ParticleKind.SMOKE || it.kind == ParticleKind.DUST }
            particles.removeAt(if (dropIdx >= 0) dropIdx else 0)
        }
        particles.add(
            Particle(
                pos = pos,
                vel = vel,
                color = color,
                alpha = alpha,
                size = size,
                growthRate = growthRate,
                age = 0f,
                maxAge = maxAge,
                gravity = gravity,
                kind = kind,
                rot = rng.nextFloat() * (2f * PI.toFloat()),
                rotSpeed = (rng.nextFloat() - 0.5f) * 1.6f
            )
        )
    }

    private fun stepParticles(dt: Float) {
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.age += dt
            if (p.age >= p.maxAge) {
                it.remove()
                continue
            }
            val drag = if (p.kind == ParticleKind.SMOKE || p.kind == ParticleKind.DUST) 1.85f else 1.15f
            p.vel = Vec3(p.vel.x * (1f - drag * dt), p.vel.y + p.gravity * dt, p.vel.z * (1f - drag * dt))
            p.pos += p.vel * dt
            p.rot += p.rotSpeed * dt
        }
    }

    private fun emitSound(cue: SoundCue, dist: Float, gain: Float) {
        soundListener?.invoke(cue, dist, gain)
    }

    private fun raycastWorld(origin: Vec3, dir: Vec3, maxDist: Float): Vec3? {
        val bHit = world.raycastBlocks(origin, dir, maxDist)
        val sHit = world.raycastShards(origin, dir, maxDist)
        val tHit = terrain.raycast(origin, dir, maxDist)

        var bestDist = maxDist
        var bestPt: Vec3? = null
        if (bHit != null && bHit.distance < bestDist) {
            bestDist = bHit.distance
            bestPt = bHit.hitPoint
        }
        if (sHit != null && sHit.distance < bestDist) {
            bestDist = sHit.distance
            bestPt = sHit.hitPoint
        }
        if (tHit != null) {
            val tDist = (tHit - origin).len()
            if (tDist < bestDist) {
                bestPt = tHit
            }
        }
        return bestPt
    }

    private fun getLookDir(): Vec3 {
        val cp = cos(pitch)
        return Vec3(cos(yaw) * cp, sin(pitch), sin(yaw) * cp).normalized()
    }

    private fun getCameraEye(): Vec3 {
        if (cameraMode == CameraMode.DRONE) return dronePos
        val head = playerPos + Vec3(0f, 1.68f, 0f)
        if (cameraMode == CameraMode.FIRST_PERSON) return head

        val look = getLookDir()
        val right = Vec3(-sin(yaw), 0f, cos(yaw))
        val desired = head - look * 5.6f + right * 0.85f + Vec3(0f, 0.75f, 0f)
        val gy = terrain.groundSurfaceY(desired.x, desired.z) + 0.55f
        return Vec3(desired.x, max(desired.y, gy), desired.z)
    }

    private fun showBanner(text: String) {
        statusBanner = text
        statusTimer = 2.6f
    }

    private fun rebuildStaticStructuresIfNeeded() {
        if (cachedStructVersion == world.structureVersion) return
        cachedStructVersion = world.structureVersion

        var anyDirty = false
        for (s in 0 until sectorCountTotal) {
            val ver = world.sectorVersions[s]
            if (cachedSectorVersions[s] != ver) {
                dirtySectorFlags[s] = true
                sectorOpaqueBuilders[s].reset()
                sectorGlassBuilders[s].reset()
                sectorMinX[s] = Float.POSITIVE_INFINITY
                sectorMinY[s] = Float.POSITIVE_INFINITY
                sectorMinZ[s] = Float.POSITIVE_INFINITY
                sectorMaxX[s] = Float.NEGATIVE_INFINITY
                sectorMaxY[s] = Float.NEGATIVE_INFINITY
                sectorMaxZ[s] = Float.NEGATIVE_INFINITY
                anyDirty = true
            } else {
                dirtySectorFlags[s] = false
            }
        }
        if (!anyDirty) return

        for (block in world.blocks.values) {
            val s = world.sectorIndexFor(block.center.x, block.center.z)
            if (!dirtySectorFlags[s]) continue

            val r = block.poly.boundRadius + 0.3f
            val cx = block.center.x; val cy = block.center.y; val cz = block.center.z
            if (cx - r < sectorMinX[s]) sectorMinX[s] = cx - r
            if (cy - r < sectorMinY[s]) sectorMinY[s] = cy - r
            if (cz - r < sectorMinZ[s]) sectorMinZ[s] = cz - r
            if (cx + r > sectorMaxX[s]) sectorMaxX[s] = cx + r
            if (cy + r > sectorMaxY[s]) sectorMaxY[s] = cy + r
            if (cz + r > sectorMaxZ[s]) sectorMaxZ[s] = cz + r

            val isGlass = block.tileId == MaterialTile.GLASS
            world.emitPolyhedronTriangles(
                poly = block.poly,
                center = block.center,
                orientation = block.orientation,
                uvOrigin = block.uvOrigin,
                tileId = block.tileId,
                tint = block.tint,
                out = if (isGlass) sectorGlassBuilders[s] else sectorOpaqueBuilders[s],
                alpha = if (isGlass) 0.46f else 1.0f,
                uvLocalOffset = block.uvLocalOffset
            )
        }

        for (s in 0 until sectorCountTotal) {
            if (!dirtySectorFlags[s]) continue
            cachedSectorVersions[s] = world.sectorVersions[s]
            val opq = if (sectorOpaqueBuilders[s].size == 0) EMPTY_FLOATS else sectorOpaqueBuilders[s].toFloatArray()
            val gls = if (sectorGlassBuilders[s].size == 0) EMPTY_FLOATS else sectorGlassBuilders[s].toFloatArray()
            val hasGeom = opq.isNotEmpty() || gls.isNotEmpty()
            sectorMeshes[s] = BuildingSectorMeshData(
                sectorIndex = s,
                version = cachedSectorVersions[s],
                opaqueVertices = opq,
                glassVertices = gls,
                minX = if (hasGeom) sectorMinX[s] else 0f,
                minY = if (hasGeom) sectorMinY[s] else 0f,
                minZ = if (hasGeom) sectorMinZ[s] else 0f,
                maxX = if (hasGeom) sectorMaxX[s] else 0f,
                maxY = if (hasGeom) sectorMaxY[s] else 0f,
                maxZ = if (hasGeom) sectorMaxZ[s] else 0f,
                hasGeometry = hasGeom
            )
        }
        cachedSectorMeshesSnapshot = sectorMeshes.copyOf()
    }

    private fun rebuildWaterMesh() {
        val minX = ChunkedTerrain.MIN_X
        val maxX = ChunkedTerrain.MAX_X
        val minZ = ChunkedTerrain.MIN_Z
        val maxZ = ChunkedTerrain.MAX_Z
        val y = ChunkedTerrain.WATER_LEVEL
        val segsX = 160
        val segsZ = 120
        val dx = (maxX - minX) / segsX
        val dz = (maxZ - minZ) / segsZ
        val arr = FloatArray(segsX * segsZ * 6 * 4)
        var p = 0

        fun emitW(x: Float, z: Float) {
            val depth = y - terrain.groundSurfaceY(x, z)
            arr[p++] = x; arr[p++] = y; arr[p++] = z; arr[p++] = depth
        }

        for (iz in 0 until segsZ) {
            val z0 = minZ + iz * dz
            val z1 = z0 + dz
            for (ix in 0 until segsX) {
                val x0 = minX + ix * dx
                val x1 = x0 + dx
                emitW(x0, z0); emitW(x0, z1); emitW(x1, z1)
                emitW(x0, z0); emitW(x1, z1); emitW(x1, z0)
            }
        }
        cachedWaterVerts = arr
    }

    private fun refreshCachesAndSnapshot() {
        var anyChunkChanged = false
        for (idx in chunkMeshes.indices) {
            val ver = terrain.chunkVersions[idx]
            if (chunkMeshes[idx].version != ver) {
                val cx = idx % ChunkedTerrain.CHUNKS_X
                val cz = idx / ChunkedTerrain.CHUNKS_X
                chunkMeshes[idx] = ChunkMeshData(idx, ver, terrain.buildChunkVertices(cx, cz))
                anyChunkChanged = true
            }
        }
        if (anyChunkChanged) {
            terrainVersion++
            cachedChunkMeshesSnapshot = chunkMeshes.copyOf()
            rebuildWaterMesh()
        }
        rebuildStaticStructuresIfNeeded()
        latestSnapshot = createSnapshot()
    }

    private fun rebuildSleepingRubbleIfNeeded() {
        val ver = world.sleepingRubbleVersion
        if (cachedSleepingRubbleVersion == ver) return
        cachedSleepingRubbleVersion = ver
        sleepingRubbleBuilder.reset()
        world.appendBakedDebris(sleepingRubbleBuilder)
        for (i in world.shards.indices) {
            val shard = world.shards[i]
            if (!shard.sleeping) continue
            world.emitShardTriangles(shard, sleepingRubbleBuilder)
        }
        cachedSleepingRubbleVerts = if (sleepingRubbleBuilder.size == 0) EMPTY_FLOATS else sleepingRubbleBuilder.toFloatArray()
    }

    private fun createSnapshot(): RenderSnapshot {
        rebuildSleepingRubbleIfNeeded()
        dynamicBuilder.reset()

        var activeShards = 0
        for (i in world.shards.indices) {
            val shard = world.shards[i]
            if (shard.sleeping) continue
            activeShards++
            world.emitShardTriangles(shard, dynamicBuilder)
        }

        if (cameraMode != CameraMode.FIRST_PERSON) {
            emitArticulatedPlayerAvatar()
        }

        for (p in projectiles) {
            val projPoly = when (p.type) {
                WeaponType.WRECKING_BALL -> wreckingBallPoly
                WeaponType.ARTILLERY -> artilleryPoly
                else -> smallProjPoly
            }
            val color = when (p.type) {
                WeaponType.WRECKING_BALL -> Vec3(0.42f, 0.45f, 0.50f)
                WeaponType.C4 -> Vec3(0.22f, 0.25f, 0.22f)
                else -> Vec3(0.95f, 0.45f, 0.18f)
            }
            val tile = if (p.type == WeaponType.WRECKING_BALL) MaterialTile.STEEL else MaterialTile.CORRUGATED_METAL
            world.emitPolyhedronTriangles(
                projPoly,
                p.pos, Quat.IDENTITY, Vec3.ZERO, tile, color, dynamicBuilder
            )
        }

        for (c4 in plantedC4s) {
            val blink = if (((c4.age * 4.5f).toInt() % 2) == 0) 1.35f else 0.55f
            world.emitPolyhedronTriangles(
                plantedC4Poly,
                c4.worldPos, Quat.IDENTITY, Vec3.ZERO, MaterialTile.STEEL,
                Vec3(blink, 0.18f, 0.15f), dynamicBuilder
            )
        }

        // Translucent expanding shockwave rings
        shockwaveBuilder.reset()
        for (sw in shockwaves) {
            val t = (sw.age / sw.duration).coerceIn(0f, 1f)
            val outerR = 0.55f + sw.maxRadius * t
            val innerR = max(0.1f, outerR - (0.85f + sw.maxRadius * 0.12f))
            val alpha = (1f - t) * (1f - t) * 0.62f
            emitShockwaveRing(sw.pos, innerR, outerR, alpha, shockwaveBuilder)
        }

        val eye = getCameraEye()
        val look = getLookDir()
        val rawCamRight = look.cross(Vec3.UP)
        val camRight = if (rawCamRight.len() < 1e-4f) Vec3(1f, 0f, 0f) else rawCamRight.normalized()
        val camUp = camRight.cross(look).normalized()

        particleBuilder.reset()
        val pCount = particles.size
        if (pCount > 0) {
            val limit = min(pCount, sortedParticleIdx.size)
            for (i in 0 until limit) {
                sortedParticleIdx[i] = i
                sortedParticleDistSq[i] = (particles[i].pos - eye).lenSq()
            }
            // Zero-allocation insertion sort (particles <= 180, nearly sorted) descending by distance
            for (i in 1 until limit) {
                val keyIdx = sortedParticleIdx[i]
                val keyDist = sortedParticleDistSq[i]
                var j = i - 1
                while (j >= 0 && sortedParticleDistSq[j] < keyDist) {
                    sortedParticleIdx[j + 1] = sortedParticleIdx[j]
                    sortedParticleDistSq[j + 1] = sortedParticleDistSq[j]
                    j--
                }
                sortedParticleIdx[j + 1] = keyIdx
                sortedParticleDistSq[j + 1] = keyDist
            }
            for (i in 0 until limit) {
                val pt = particles[sortedParticleIdx[i]]
                val dSq = sortedParticleDistSq[i]
                val isLargeCloud = pt.kind == ParticleKind.SMOKE || pt.kind == ParticleKind.DUST
                if (isLargeCloud && dSq < 1.21f) continue
                val nearFade = if (isLargeCloud && dSq < 6.76f) {
                    val d = kotlin.math.sqrt(dSq)
                    clamp((d - 1.1f) / 1.5f, 0f, 1f)
                } else 1f
                emitParticleQuad(pt, camRight, camUp, nearFade, particleBuilder)
            }
        }

        lineBuilder.reset()
        if (interactionTool == InteractionTool.BUILD_BRUSH) {
            val hit = raycastWorld(eye, look, 210f)
            if (hit != null) {
                emitGroundRing(hit, 2.8f, Vec3(0.22f, 0.88f, 0.98f), lineBuilder)
            }
        } else if (selectedWeapon == WeaponType.ARTILLERY) {
            val hit = raycastWorld(eye, look, 220f)
            if (hit != null) {
                emitGroundRing(hit, 6.2f, Vec3(0.98f, 0.58f, 0.15f), lineBuilder)
            }
        }

        // Collect up to 3 nearest active explosion flash / fire lights for dynamic wall & ground illumination
        val activePointLights = ArrayList<DynamicPointLight>(3)
        for (fl in flashLights) {
            if (activePointLights.size >= 3) break
            val t = (fl.age / fl.duration).coerceIn(0f, 1f)
            val fade = (1f - t) * (1f - t)
            activePointLights.add(
                DynamicPointLight(
                    pos = fl.pos,
                    color = fl.color * fade,
                    radius = fl.radius
                )
            )
        }
        if (activePointLights.size < 3 && fireSites.isNotEmpty()) {
            // Pick up to (3 - activePointLights.size) closest fire sites without allocating sorted list
            val maxPick = 3 - activePointLights.size
            var picked0 = -1; var picked1 = -1; var picked2 = -1
            var d0 = Float.MAX_VALUE; var d1 = Float.MAX_VALUE; var d2 = Float.MAX_VALUE
            for (fi in fireSites.indices) {
                val d = (fireSites[fi].pos - eye).lenSq()
                if (d < d0) {
                    d2 = d1; picked2 = picked1
                    d1 = d0; picked1 = picked0
                    d0 = d; picked0 = fi
                } else if (d < d1) {
                    d2 = d1; picked2 = picked1
                    d1 = d; picked1 = fi
                } else if (d < d2) {
                    d2 = d; picked2 = fi
                }
            }
            val picked = intArrayOf(picked0, picked1, picked2)
            for (k in 0 until maxPick) {
                val fi = picked[k]
                if (fi < 0) break
                val fs = fireSites[fi]
                val lifeFade = clamp(fs.timeLeft / 3.0f, 0.25f, 1f)
                val flicker = 0.82f + 0.18f * sin(waterTime * 19f + fs.pos.x * 1.7f)
                val intensity = (if (fs.isFuelFire) 1.35f else 0.95f) * lifeFade * flicker
                activePointLights.add(
                    DynamicPointLight(
                        pos = fs.pos + Vec3(0f, 0.85f, 0f),
                        color = Vec3(1.15f * intensity, 0.56f * intensity, 0.18f * intensity),
                        radius = clamp(fs.radius * 3.8f, 7.5f, 15f)
                    )
                )
            }
        }

        val dynamicMesh = dynamicRing.publish(dynamicBuilder)
        val shockwaveMesh = shockwaveRing.publish(shockwaveBuilder)
        val particleMesh = particleRing.publish(particleBuilder)
        val lineMesh = lineRing.publish(lineBuilder)

        return RenderSnapshot(
            chunkMeshes = cachedChunkMeshesSnapshot,
            terrainVersion = terrainVersion,
            waterVerts = cachedWaterVerts,
            structureVersion = cachedStructVersion,
            sectorMeshes = cachedSectorMeshesSnapshot,
            dynamicVerts = dynamicMesh.data,
            dynamicFloatCount = dynamicMesh.floatCount,
            shockwaveVerts = shockwaveMesh.data,
            shockwaveFloatCount = shockwaveMesh.floatCount,
            particleVerts = particleMesh.data,
            particleFloatCount = particleMesh.floatCount,
            lineVerts = lineMesh.data,
            lineFloatCount = lineMesh.floatCount,
            cameraPos = eye,
            cameraLookDir = look,
            cameraMode = cameraMode,
            lightingPreset = config.lightingPreset,
            waterTime = waterTime,
            shakeIntensity = shakeIntensity,
            pointLights = activePointLights,
            sleepingRubbleVersion = cachedSleepingRubbleVersion,
            sleepingRubbleVerts = cachedSleepingRubbleVerts,
            snapshotVersion = snapshotVersion++,
            activeShardCount = activeShards
        )
    }

    private fun emitArticulatedPlayerAvatar() {
        val bodyRot = Quat.fromAxisAngle(Vec3.UP, -yaw + (PI.toFloat() * 0.5f))
        val rightAxis = Vec3(-sin(yaw), 0f, cos(yaw))
        val fwdAxis = Vec3(cos(yaw), 0f, sin(yaw))

        val legSwing = if (isPlayerGrounded) sin(walkCyclePhase) * 0.52f * walkCycleAmp else 0.34f
        val armSwing = if (isPlayerGrounded) -sin(walkCyclePhase) * 0.38f * walkCycleAmp else -0.22f
        val bobY = if (isPlayerGrounded) kotlin.math.abs(sin(walkCyclePhase)) * 0.045f * walkCycleAmp else 0f

        val leftHip = playerPos + Vec3(0f, 0.62f + bobY, 0f) - rightAxis * 0.125f
        val rightHip = playerPos + Vec3(0f, 0.62f + bobY, 0f) + rightAxis * 0.125f
        val lLegRot = Quat.fromAxisAngle(rightAxis, legSwing) * bodyRot
        val rLegRot = Quat.fromAxisAngle(rightAxis, -legSwing) * bodyRot
        val lLegCenter = leftHip + lLegRot.rotate(Vec3(0f, -0.30f, 0f))
        val rLegCenter = rightHip + rLegRot.rotate(Vec3(0f, -0.30f, 0f))
        world.emitPolyhedronTriangles(avatarLegPoly, lLegCenter, lLegRot, Vec3.ZERO, MaterialTile.SOIL, Vec3(0.32f, 0.36f, 0.40f), dynamicBuilder)
        world.emitPolyhedronTriangles(avatarLegPoly, rLegCenter, rLegRot, Vec3.ZERO, MaterialTile.SOIL, Vec3(0.32f, 0.36f, 0.40f), dynamicBuilder)

        // Torso & tactical chest rig
        val torsoCenter = playerPos + Vec3(0f, 0.96f + bobY, 0f)
        world.emitPolyhedronTriangles(
            avatarTorsoPoly,
            torsoCenter, bodyRot, Vec3.ZERO, MaterialTile.SANDBAG, Vec3(0.45f, 0.53f, 0.42f), dynamicBuilder
        )

        // Helmeted head tilted subtly with pitch
        val headRot = Quat.fromAxisAngle(rightAxis, clamp(pitch * 0.35f, -0.35f, 0.35f)) * bodyRot
        world.emitPolyhedronTriangles(
            avatarHeadPoly,
            playerPos + Vec3(0f, 1.48f + bobY, 0f),
            headRot, Vec3.ZERO, MaterialTile.STEEL, Vec3(0.38f, 0.44f, 0.36f), dynamicBuilder
        )

        // Left arm (swings naturally while walking) & Right arm (aims equipped weapon/tool)
        val leftShoulder = playerPos + Vec3(0f, 1.24f + bobY, 0f) - rightAxis * 0.30f
        val lArmRot = Quat.fromAxisAngle(rightAxis, armSwing) * bodyRot
        val lArmCenter = leftShoulder + lArmRot.rotate(Vec3(0f, -0.22f, 0f))
        world.emitPolyhedronTriangles(avatarArmPoly, lArmCenter, lArmRot, Vec3.ZERO, MaterialTile.SANDBAG, Vec3(0.42f, 0.50f, 0.39f), dynamicBuilder)

        val aimPitch = clamp(pitch * 0.75f, -0.75f, 0.75f)
        val rightShoulder = playerPos + Vec3(0f, 1.24f + bobY, 0f) + rightAxis * 0.29f
        val rArmRot = Quat.fromAxisAngle(rightAxis, 1.28f + aimPitch) * bodyRot
        val rArmCenter = rightShoulder + rArmRot.rotate(Vec3(0f, -0.18f, 0f))
        world.emitPolyhedronTriangles(avatarArmPoly, rArmCenter, rArmRot, Vec3.ZERO, MaterialTile.SANDBAG, Vec3(0.42f, 0.50f, 0.39f), dynamicBuilder)

        // Active held weapon / construction tool aligned along look pitch & yaw
        val weaponRot = Quat.fromAxisAngle(rightAxis, aimPitch) * bodyRot
        val handAnchor = playerPos + Vec3(0f, 1.15f + bobY, 0f) + rightAxis * 0.25f + fwdAxis * 0.28f
        if (interactionTool == InteractionTool.BUILD_BRUSH) {
            world.emitPolyhedronTriangles(
                brushToolPoly,
                handAnchor + fwdAxis * 0.08f, weaponRot, Vec3.ZERO, MaterialTile.PLASTIC,
                Vec3(0.24f, 0.86f, 0.96f), dynamicBuilder
            )
        } else {
            when (selectedWeapon) {
                WeaponType.CARBINE -> {
                    world.emitPolyhedronTriangles(
                        carbinePoly,
                        handAnchor + fwdAxis * 0.14f, weaponRot, Vec3.ZERO, MaterialTile.STEEL,
                        Vec3(0.22f, 0.24f, 0.26f), dynamicBuilder
                    )
                }
                WeaponType.HEAVY_RIFLE -> {
                    world.emitPolyhedronTriangles(
                        heavyRiflePoly,
                        handAnchor + fwdAxis * 0.24f, weaponRot, Vec3.ZERO, MaterialTile.STEEL,
                        Vec3(0.26f, 0.28f, 0.30f), dynamicBuilder
                    )
                }
                WeaponType.RPG -> {
                    world.emitPolyhedronTriangles(
                        rpgTubePoly,
                        handAnchor + Vec3(0f, 0.08f, 0f) + fwdAxis * 0.10f, weaponRot, Vec3.ZERO,
                        MaterialTile.SANDBAG, Vec3(0.32f, 0.42f, 0.28f), dynamicBuilder
                    )
                    world.emitPolyhedronTriangles(
                        rpgWarheadPoly,
                        handAnchor + Vec3(0f, 0.08f, 0f) + fwdAxis * 0.54f, weaponRot, Vec3.ZERO,
                        MaterialTile.STEEL, Vec3(0.82f, 0.38f, 0.20f), dynamicBuilder
                    )
                }
                WeaponType.C4 -> {
                    world.emitPolyhedronTriangles(
                        c4HeldPoly,
                        handAnchor + fwdAxis * 0.04f, weaponRot, Vec3.ZERO, MaterialTile.STEEL,
                        Vec3(0.88f, 0.24f, 0.18f), dynamicBuilder
                    )
                }
                WeaponType.ARTILLERY -> {
                    world.emitPolyhedronTriangles(
                        artilleryHeldPoly,
                        handAnchor + fwdAxis * 0.08f, weaponRot, Vec3.ZERO, MaterialTile.STEEL,
                        Vec3(0.90f, 0.62f, 0.22f), dynamicBuilder
                    )
                }
                WeaponType.WRECKING_BALL -> {
                    world.emitPolyhedronTriangles(
                        wreckingHeldPoly,
                        handAnchor + fwdAxis * 0.14f, weaponRot, Vec3.ZERO, MaterialTile.STEEL,
                        Vec3(0.46f, 0.50f, 0.56f), dynamicBuilder
                    )
                }
            }
        }
    }

    private fun emitShockwaveRing(center: Vec3, innerR: Float, outerR: Float, alpha: Float, out: FloatArrayBuilder) {
        val segs = 28
        val y = max(center.y + 0.18f, terrain.groundSurfaceY(center.x, center.z) + 0.25f)
        val col = Vec3(1.0f, 0.82f, 0.56f)
        for (i in 0 until segs) {
            val a0 = (2.0 * Math.PI * i / segs).toFloat()
            val a1 = (2.0 * Math.PI * (i + 1) / segs).toFloat()
            val c0 = cos(a0); val s0 = sin(a0)
            val c1 = cos(a1); val s1 = sin(a1)

            val i0 = Vec3(center.x + c0 * innerR, y, center.z + s0 * innerR)
            val o0 = Vec3(center.x + c0 * outerR, y, center.z + s0 * outerR)
            val i1 = Vec3(center.x + c1 * innerR, y, center.z + s1 * innerR)
            val o1 = Vec3(center.x + c1 * outerR, y, center.z + s1 * outerR)

            out.emitVertex(i0, Vec3.UP, col, 0f, 0f, 0f, alpha)
            out.emitVertex(o0, Vec3.UP, col, 0f, 0f, 0f, alpha)
            out.emitVertex(o1, Vec3.UP, col, 0f, 0f, 0f, alpha)

            out.emitVertex(i0, Vec3.UP, col, 0f, 0f, 0f, alpha)
            out.emitVertex(o1, Vec3.UP, col, 0f, 0f, 0f, alpha)
            out.emitVertex(i1, Vec3.UP, col, 0f, 0f, 0f, alpha)
        }
    }

    private fun emitParticleQuad(
        pt: Particle,
        right: Vec3,
        camUp: Vec3,
        nearFade: Float,
        out: FloatArrayBuilder
    ) {
        val life = (pt.age / pt.maxAge).coerceIn(0f, 1f)
        val baseFade = when (pt.kind) {
            ParticleKind.SMOKE, ParticleKind.DUST -> (1f - life * life) * pt.alpha
            ParticleKind.FIREBALL -> (1f - life) * pt.alpha
            else -> (1f - life) * pt.alpha
        }
        val fade = baseFade * nearFade
        if (fade < 0.01f) return

        val r = pt.size * (0.65f + life * pt.growthRate)
        val cr = cos(pt.rot)
        val sr = sin(pt.rot)
        val ax = (right * cr + camUp * sr) * r
        val ay = (camUp * cr - right * sr) * r

        val p0 = pt.pos - ax - ay
        val p1 = pt.pos + ax - ay
        val p2 = pt.pos + ax + ay
        val p3 = pt.pos - ax + ay

        // Map to 2x2 pre-baked particle atlas quadrants:
        // (0,0)=Smoke, (1,0)=Fireball, (0,1)=Dust, (1,1)=Spark/Splash
        val qCol: Float
        val qRow: Float
        val mode: Float
        when (pt.kind) {
            ParticleKind.SMOKE -> { qCol = 0f; qRow = 0f; mode = 0f }
            ParticleKind.FIREBALL -> { qCol = 1f; qRow = 0f; mode = 1f }
            ParticleKind.DUST -> { qCol = 0f; qRow = 1f; mode = 0f }
            ParticleKind.SPARK, ParticleKind.TRACER -> { qCol = 1f; qRow = 1f; mode = 2f }
            ParticleKind.SPLASH -> { qCol = 1f; qRow = 1f; mode = 3f }
        }
        val pad = 0.016f
        val uMin = qCol * 0.5f + pad
        val uMax = (qCol + 1f) * 0.5f - pad
        val vMin = qRow * 0.5f + pad
        val vMax = (qRow + 1f) * 0.5f - pad

        val warmth = if (pt.kind == ParticleKind.SMOKE) ((1f - life * 2.2f).coerceIn(0f, 1f) * 0.65f) else 0f
        val nParam = Vec3(warmth, 1f, 0f)

        out.emitVertex(p0, nParam, pt.color, uMin, vMin, mode, fade)
        out.emitVertex(p1, nParam, pt.color, uMax, vMin, mode, fade)
        out.emitVertex(p2, nParam, pt.color, uMax, vMax, mode, fade)

        out.emitVertex(p0, nParam, pt.color, uMin, vMin, mode, fade)
        out.emitVertex(p2, nParam, pt.color, uMax, vMax, mode, fade)
        out.emitVertex(p3, nParam, pt.color, uMin, vMax, mode, fade)
    }

    private fun emitGroundRing(center: Vec3, radius: Float, color: Vec3, out: FloatArrayBuilder) {
        val segs = 24
        for (i in 0 until segs) {
            val a0 = (2.0 * Math.PI * i / segs).toFloat()
            val a1 = (2.0 * Math.PI * (i + 1) / segs).toFloat()
            val x0 = center.x + cos(a0) * radius
            val z0 = center.z + sin(a0) * radius
            val x1 = center.x + cos(a1) * radius
            val z1 = center.z + sin(a1) * radius
            val y0 = terrain.groundSurfaceY(x0, z0) + 0.15f
            val y1 = terrain.groundSurfaceY(x1, z1) + 0.15f
            out.emitVertex(Vec3(x0, y0, z0), Vec3.UP, color, 0f, 0f, 0f, 1f)
            out.emitVertex(Vec3(x1, y1, z1), Vec3.UP, color, 0f, 0f, 0f, 1f)
        }
    }
}
