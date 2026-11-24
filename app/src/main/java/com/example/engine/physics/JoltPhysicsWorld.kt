package com.example.engine.physics

import android.os.Build
import com.example.engine.terrain.ChunkedTerrain
import com.example.model.MaterialProfiles
import com.example.model.Quat
import com.example.model.Vec3
import com.example.model.clamp
import com.github.stephengold.joltjni.AaBox
import com.github.stephengold.joltjni.Body
import com.github.stephengold.joltjni.BodyCreationSettings
import com.github.stephengold.joltjni.BodyInterface
import com.github.stephengold.joltjni.BoxShapeSettings
import com.github.stephengold.joltjni.BroadPhaseLayerFilter
import com.github.stephengold.joltjni.BroadPhaseLayerInterfaceTable
import com.github.stephengold.joltjni.ContactManifold
import com.github.stephengold.joltjni.ConvexHullShapeSettings
import com.github.stephengold.joltjni.CustomContactListener
import com.github.stephengold.joltjni.HeightFieldShape
import com.github.stephengold.joltjni.HeightFieldShapeSettings
import com.github.stephengold.joltjni.JobSystemThreadPool
import com.github.stephengold.joltjni.Jolt
import com.github.stephengold.joltjni.JoltPhysicsObject
import com.github.stephengold.joltjni.MassProperties
import com.github.stephengold.joltjni.NativeLibraryLoader
import com.github.stephengold.joltjni.ObjectLayerFilter
import com.github.stephengold.joltjni.ObjectLayerPairFilterTable
import com.github.stephengold.joltjni.ObjectVsBroadPhaseLayerFilterTable
import com.github.stephengold.joltjni.PhysicsSettings
import com.github.stephengold.joltjni.PhysicsSystem
import com.github.stephengold.joltjni.ShapeRefC
import com.github.stephengold.joltjni.StaticCompoundShapeSettings
import com.github.stephengold.joltjni.TempAllocator
import com.github.stephengold.joltjni.TempAllocatorImplWithMallocFallback
import com.github.stephengold.joltjni.enumerate.EActivation
import com.github.stephengold.joltjni.enumerate.EMotionQuality
import com.github.stephengold.joltjni.enumerate.EMotionType
import com.github.stephengold.joltjni.enumerate.EOverrideMassProperties
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private typealias JVec3 = com.github.stephengold.joltjni.Vec3
private typealias JRVec3 = com.github.stephengold.joltjni.RVec3
private typealias JQuat = com.github.stephengold.joltjni.Quat

object JoltRuntime {
    val isAvailable: Boolean
    val tempAllocator: TempAllocator?
    val jobSystem: JobSystemThreadPool?

    init {
        var loaded = false
        try {
            loaded = tryLoadNative()
            if (loaded) {
                JoltPhysicsObject.startCleaner()
                Jolt.registerDefaultAllocator()
                Jolt.installDefaultAssertCallback()
                Jolt.installDefaultTraceCallback()
                Jolt.newFactory()
                Jolt.registerTypes()
            }
        } catch (_: Throwable) {
            loaded = false
        }
        isAvailable = loaded
        if (loaded) {
            tempAllocator = TempAllocatorImplWithMallocFallback(40 * 1024 * 1024)
            val threads = min(3, max(1, Runtime.getRuntime().availableProcessors() - 1))
            jobSystem = JobSystemThreadPool(Jolt.cMaxPhysicsJobs, Jolt.cMaxPhysicsBarriers, threads)
        } else {
            tempAllocator = null
            jobSystem = null
        }
    }

    private fun tryLoadNative(): Boolean {
        val isAndroidRuntime = try {
            Build.VERSION.SDK_INT > 0
        } catch (_: Throwable) {
            false
        }
        if (isAndroidRuntime) {
            // jolt-jni-Android binary links LIBC_R (Android 11 / API 30+)
            if (Build.VERSION.SDK_INT < 30) return false
            return try {
                System.loadLibrary("joltjni")
                true
            } catch (_: UnsatisfiedLinkError) {
                false
            }
        }

        // Desktop JVM unit test runner: extract bundled Linux x86-64 shared library from testRuntimeOnly JAR
        val candidates = arrayOf(
            "/linux/x86-64/com/github/stephengold/libjoltjni.so",
            "linux/x86-64/com/github/stephengold/libjoltjni.so"
        )
        for (path in candidates) {
            val stream = JoltRuntime::class.java.getResourceAsStream(path)
                ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(path.trimStart('/'))
            if (stream != null) {
                val tmp = File.createTempFile("libjoltjni-", ".so")
                tmp.deleteOnExit()
                stream.use { input -> tmp.outputStream().use { out -> input.copyTo(out) } }
                if (NativeLibraryLoader.loadLibrary(tmp.absolutePath)) {
                    return true
                }
            }
        }
        return try {
            System.loadLibrary("joltjni")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }
}

data class JoltContactEvent(
    val userData1: Long,
    val userData2: Long,
    val relNormalSpeed: Float,
    val normalX: Float,
    val normalY: Float,
    val normalZ: Float
)

data class JoltStepOutcome(
    val contacts: List<JoltContactEvent>,
    val splashes: List<WaterSplashEvent>
)

private data class JoltBlockHandle(
    val bodyId: Int,
    val minX: Float,
    val minY: Float,
    val minZ: Float,
    val maxX: Float,
    val maxY: Float,
    val maxZ: Float
)

private data class JoltShardHandle(
    val bodyId: Int,
    var lastPos: Vec3,
    var lastOrient: Quat,
    var lastVel: Vec3,
    var lastAngVel: Vec3,
    var wasActive: Boolean
)

class JoltPhysicsWorld(private val gravityY: Float = StructuralWorld.GRAVITY) {

    companion object {
        private const val LAYER_NON_MOVING = 0
        private const val LAYER_MOVING = 1
        private const val NUM_LAYERS = 2
        private const val HF_SAMPLE_COUNT = 516
        private const val HF_BLOCK_SIZE = 4
        private const val INVALID_BODY_ID = -1
    }

    private val objectFilter = ObjectLayerPairFilterTable(NUM_LAYERS).apply {
        enableCollision(LAYER_NON_MOVING, LAYER_MOVING)
        enableCollision(LAYER_MOVING, LAYER_MOVING)
    }
    private val bpInterface = BroadPhaseLayerInterfaceTable(NUM_LAYERS, NUM_LAYERS).apply {
        mapObjectToBroadPhaseLayer(LAYER_NON_MOVING, LAYER_NON_MOVING)
        mapObjectToBroadPhaseLayer(LAYER_MOVING, LAYER_MOVING)
    }
    private val bpFilter = ObjectVsBroadPhaseLayerFilterTable(bpInterface, NUM_LAYERS, objectFilter, NUM_LAYERS)

    private val physicsSystem = PhysicsSystem().apply {
        init(
            24576,
            0,
            49152,
            49152,
            bpInterface,
            bpFilter,
            objectFilter
        )
        setGravity(JVec3(0f, gravityY, 0f))
        val settings = PhysicsSettings(physicsSettings)
        settings.setAllowSleeping(true)
        settings.setPointVelocitySleepThreshold(0.18f)
        settings.setTimeBeforeSleep(0.32f)
        settings.setNumVelocitySteps(8)
        settings.setNumPositionSteps(3)
        settings.setMinVelocityForRestitution(2.5f)
        settings.setBaumgarte(0.22f)
        settings.setPenetrationSlop(0.01f)
        setPhysicsSettings(settings)
    }

    private val bodyInterface: BodyInterface = physicsSystem.bodyInterface
    private val allBpFilter = BroadPhaseLayerFilter()
    private val allObjFilter = ObjectLayerFilter()

    private val blockBodies = HashMap<Int, JoltBlockHandle>(512)
    private val shardBodies = HashMap<Int, JoltShardHandle>(512)

    private var terrainBodyId: Int = INVALID_BODY_ID
    private var terrainShapeRef: ShapeRefC? = null
    private var terrainPatchBuffer: FloatBuffer? = null
    private val syncedChunkVersions = IntArray(ChunkedTerrain.CHUNKS_X * ChunkedTerrain.CHUNKS_Z) { -1 }

    private val contactLock = Any()
    private val pendingContacts = ArrayList<JoltContactEvent>(128)
    private val activeSupportTouches = ArrayList<JoltContactEvent>(256)

    private val contactListener = object : CustomContactListener() {
        override fun onContactAdded(body1Va: Long, body2Va: Long, manifoldVa: Long, settingsVa: Long) {
            recordContact(body1Va, body2Va, manifoldVa, includeImpact = true)
        }

        override fun onContactPersisted(body1Va: Long, body2Va: Long, manifoldVa: Long, settingsVa: Long) {
            recordContact(body1Va, body2Va, manifoldVa, includeImpact = false)
        }
    }

    init {
        physicsSystem.setContactListener(contactListener)
    }

    private fun recordContact(body1Va: Long, body2Va: Long, manifoldVa: Long, includeImpact: Boolean) {
        val b1 = Body(body1Va)
        val b2 = Body(body2Va)
        val u1 = b1.userData
        val u2 = b2.userData
        val m = ContactManifold(manifoldVa)
        val n = m.worldSpaceNormal
        val nx = n.x
        val ny = n.y
        val nz = n.z

        var relVn = 0f
        if (includeImpact) {
            val v1 = b1.linearVelocity
            val v2 = b2.linearVelocity
            val w1 = b1.angularVelocity
            val w2 = b2.angularVelocity
            val linVn = abs((v1.x - v2.x) * nx + (v1.y - v2.y) * ny + (v1.z - v2.z) * nz)
            relVn = linVn + (w1.length() + w2.length()) * 0.25f
        }

        synchronized(contactLock) {
            if (includeImpact && relVn >= 1.2f) {
                pendingContacts.add(JoltContactEvent(u1, u2, relVn, nx, ny, nz))
            }
            if ((u1 < 0L || u2 < 0L) && abs(ny) > 0.50f && activeSupportTouches.size < 4096) {
                activeSupportTouches.add(JoltContactEvent(u1, u2, relVn, nx, ny, nz))
            }
        }
    }

    fun clear() {
        for (handle in shardBodies.values) {
            bodyInterface.removeBody(handle.bodyId)
            bodyInterface.destroyBody(handle.bodyId)
        }
        shardBodies.clear()
        for (handle in blockBodies.values) {
            bodyInterface.removeBody(handle.bodyId)
            bodyInterface.destroyBody(handle.bodyId)
        }
        blockBodies.clear()
        if (terrainBodyId != INVALID_BODY_ID) {
            bodyInterface.removeBody(terrainBodyId)
            bodyInterface.destroyBody(terrainBodyId)
            terrainBodyId = INVALID_BODY_ID
        }
        terrainShapeRef = null
        syncedChunkVersions.fill(-1)
        synchronized(contactLock) {
            pendingContacts.clear()
            activeSupportTouches.clear()
        }
    }

    fun wakeShardIds(ids: List<Int>) {
        if (ids.isEmpty()) return
        for (i in ids.indices) {
            val handle = shardBodies[ids[i]] ?: continue
            bodyInterface.activateBody(handle.bodyId)
            handle.wasActive = true
        }
    }

    fun wakeAroundPoint(pos: Vec3, radius: Float) {
        val r = max(0.5f, radius)
        val box = AaBox(
            JVec3(pos.x - r, pos.y - r, pos.z - r),
            JVec3(pos.x + r, pos.y + r, pos.z + r)
        )
        bodyInterface.activateBodiesInAaBox(box, allBpFilter, allObjFilter)
    }

    fun syncTerrain(terrain: ChunkedTerrain) {
        var dirtyCount = 0
        for (i in syncedChunkVersions.indices) {
            if (syncedChunkVersions[i] != terrain.chunkVersions[i]) dirtyCount++
        }
        if (dirtyCount == 0 && terrainBodyId != INVALID_BODY_ID) return

        if (terrainBodyId == INVALID_BODY_ID || terrainShapeRef == null || dirtyCount > 10) {
            if (terrainBodyId != INVALID_BODY_ID) {
                bodyInterface.removeBody(terrainBodyId)
                bodyInterface.destroyBody(terrainBodyId)
                terrainBodyId = INVALID_BODY_ID
            }
            val fullBuf = Jolt.newDirectFloatBuffer(HF_SAMPLE_COUNT * HF_SAMPLE_COUNT)
            for (iz in 0 until HF_SAMPLE_COUNT) {
                val gz = min(iz, ChunkedTerrain.GRID_VERTS_Z - 1)
                val rowOff = gz * ChunkedTerrain.GRID_VERTS_X
                for (ix in 0 until HF_SAMPLE_COUNT) {
                    val gx = min(ix, ChunkedTerrain.GRID_VERTS_X - 1)
                    fullBuf.put(terrain.heights[rowOff + gx])
                }
            }
            fullBuf.flip()

            val hfSettings = HeightFieldShapeSettings(
                fullBuf,
                JVec3(ChunkedTerrain.MIN_X, 0f, ChunkedTerrain.MIN_Z),
                JVec3(ChunkedTerrain.CELL_SIZE, 1f, ChunkedTerrain.CELL_SIZE),
                HF_SAMPLE_COUNT
            )
            hfSettings.setBlockSize(HF_BLOCK_SIZE)
            val result = hfSettings.create()
            val ref = result.get()
            terrainShapeRef = ref

            val bcs = BodyCreationSettings(
                ref,
                JRVec3(0.0, 0.0, 0.0),
                JQuat.sIdentity(),
                EMotionType.Static,
                LAYER_NON_MOVING
            )
            bcs.setFriction(0.86f)
            bcs.setRestitution(0.06f)
            bcs.setUserData(0L)
            terrainBodyId = bodyInterface.createAndAddBody(bcs, EActivation.DontActivate)
            for (i in syncedChunkVersions.indices) syncedChunkVersions[i] = terrain.chunkVersions[i]
            physicsSystem.optimizeBroadPhase()
            return
        }

        // Incremental crater patch sync into existing Jolt HeightFieldShape (fallback to rebuild if ShapeRefC is not HeightFieldShape)
        val hfShape = terrainShapeRef?.getPtr() as? HeightFieldShape
        val tempAlloc = JoltRuntime.tempAllocator ?: return
        if (hfShape == null) {
            if (terrainBodyId != INVALID_BODY_ID) {
                bodyInterface.removeBody(terrainBodyId)
                bodyInterface.destroyBody(terrainBodyId)
                terrainBodyId = INVALID_BODY_ID
            }
            val fullBuf = Jolt.newDirectFloatBuffer(HF_SAMPLE_COUNT * HF_SAMPLE_COUNT)
            for (iz in 0 until HF_SAMPLE_COUNT) {
                val gz = min(iz, ChunkedTerrain.GRID_VERTS_Z - 1)
                val rowOff = gz * ChunkedTerrain.GRID_VERTS_X
                for (ix in 0 until HF_SAMPLE_COUNT) {
                    val gx = min(ix, ChunkedTerrain.GRID_VERTS_X - 1)
                    fullBuf.put(terrain.heights[rowOff + gx])
                }
            }
            fullBuf.flip()
            val hfSettings = HeightFieldShapeSettings(
                fullBuf,
                JVec3(ChunkedTerrain.MIN_X, 0f, ChunkedTerrain.MIN_Z),
                JVec3(ChunkedTerrain.CELL_SIZE, 1f, ChunkedTerrain.CELL_SIZE),
                HF_SAMPLE_COUNT
            )
            hfSettings.setBlockSize(HF_BLOCK_SIZE)
            val ref = hfSettings.create().get()
            terrainShapeRef = ref
            val bcs = BodyCreationSettings(
                ref,
                JRVec3(0.0, 0.0, 0.0),
                JQuat.sIdentity(),
                EMotionType.Static,
                LAYER_NON_MOVING
            )
            bcs.setFriction(0.86f)
            bcs.setRestitution(0.06f)
            bcs.setUserData(0L)
            terrainBodyId = bodyInterface.createAndAddBody(bcs, EActivation.DontActivate)
            for (cz in 0 until ChunkedTerrain.CHUNKS_Z) {
                for (cx in 0 until ChunkedTerrain.CHUNKS_X) {
                    val cIdx = cz * ChunkedTerrain.CHUNKS_X + cx
                    if (syncedChunkVersions[cIdx] != terrain.chunkVersions[cIdx]) {
                        syncedChunkVersions[cIdx] = terrain.chunkVersions[cIdx]
                        val minWx = ChunkedTerrain.MIN_X + cx * ChunkedTerrain.CHUNK_CELLS * ChunkedTerrain.CELL_SIZE
                        val minWz = ChunkedTerrain.MIN_Z + cz * ChunkedTerrain.CHUNK_CELLS * ChunkedTerrain.CELL_SIZE
                        val wakeBox = AaBox(
                            JVec3(minWx - 1f, -20f, minWz - 1f),
                            JVec3(minWx + ChunkedTerrain.CHUNK_CELLS + 1f, 35f, minWz + ChunkedTerrain.CHUNK_CELLS + 1f)
                        )
                        bodyInterface.activateBodiesInAaBox(wakeBox, allBpFilter, allObjFilter)
                    }
                }
            }
            physicsSystem.optimizeBroadPhase()
            return
        }
        val patchCount = 36
        val buf = terrainPatchBuffer ?: Jolt.newDirectFloatBuffer(patchCount * patchCount).also { terrainPatchBuffer = it }

        for (cz in 0 until ChunkedTerrain.CHUNKS_Z) {
            for (cx in 0 until ChunkedTerrain.CHUNKS_X) {
                val cIdx = cz * ChunkedTerrain.CHUNKS_X + cx
                if (syncedChunkVersions[cIdx] == terrain.chunkVersions[cIdx]) continue
                syncedChunkVersions[cIdx] = terrain.chunkVersions[cIdx]

                val startX = cx * ChunkedTerrain.CHUNK_CELLS
                val startZ = cz * ChunkedTerrain.CHUNK_CELLS
                val countX = min(patchCount, HF_SAMPLE_COUNT - startX)
                val countZ = min(patchCount, HF_SAMPLE_COUNT - startZ)
                buf.clear()
                for (lz in 0 until countZ) {
                    val gz = min(startZ + lz, ChunkedTerrain.GRID_VERTS_Z - 1)
                    val rowOff = gz * ChunkedTerrain.GRID_VERTS_X
                    for (lx in 0 until countX) {
                        val gx = min(startX + lx, ChunkedTerrain.GRID_VERTS_X - 1)
                        buf.put(terrain.heights[rowOff + gx])
                    }
                }
                buf.flip()
                hfShape.setHeights(startX, startZ, countX, countZ, buf, countX, tempAlloc)

                val minWx = ChunkedTerrain.MIN_X + startX * ChunkedTerrain.CELL_SIZE
                val minWz = ChunkedTerrain.MIN_Z + startZ * ChunkedTerrain.CELL_SIZE
                val wakeBox = AaBox(
                    JVec3(minWx - 1f, -20f, minWz - 1f),
                    JVec3(minWx + countX + 1f, 35f, minWz + countZ + 1f)
                )
                bodyInterface.activateBodiesInAaBox(wakeBox, allBpFilter, allObjFilter)
            }
        }
    }

    private fun syncBlocks(blocks: Map<Int, StructuralBlock>) {
        // Remove destroyed/carved blocks and wake sleeping rubble stacked on them
        var anyAddedOrRemoved = false
        val iter = blockBodies.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (!blocks.containsKey(entry.key)) {
                val h = entry.value
                val wakeBox = AaBox(
                    JVec3(h.minX - 0.8f, h.minY - 0.5f, h.minZ - 0.8f),
                    JVec3(h.maxX + 0.8f, h.maxY + 3.2f, h.maxZ + 0.8f)
                )
                bodyInterface.activateBodiesInAaBox(wakeBox, allBpFilter, allObjFilter)
                bodyInterface.removeBody(h.bodyId)
                bodyInterface.destroyBody(h.bodyId)
                iter.remove()
                anyAddedOrRemoved = true
            }
        }

        // Add newly spawned or framed blocks
        for (block in blocks.values) {
            if (blockBodies.containsKey(block.id)) continue
            val shapeRef = buildShapeForPoly(block.poly)
            val bcs = BodyCreationSettings(
                shapeRef,
                JRVec3(block.center.x.toDouble(), block.center.y.toDouble(), block.center.z.toDouble()),
                JQuat(block.orientation.x, block.orientation.y, block.orientation.z, block.orientation.w),
                EMotionType.Static,
                LAYER_NON_MOVING
            )
            bcs.setFriction(0.78f)
            bcs.setRestitution(0.12f)
            bcs.setUserData(block.id.toLong())
            val bodyId = bodyInterface.createAndAddBody(bcs, EActivation.DontActivate)
            val he = block.poly.halfExtents
            val reach = he.len()
            blockBodies[block.id] = JoltBlockHandle(
                bodyId = bodyId,
                minX = block.center.x - reach,
                minY = block.center.y - reach,
                minZ = block.center.z - reach,
                maxX = block.center.x + reach,
                maxY = block.center.y + reach,
                maxZ = block.center.z + reach
            )
            anyAddedOrRemoved = true
        }

        if (anyAddedOrRemoved && blockBodies.size > 32) {
            physicsSystem.optimizeBroadPhase()
        }
    }

    private fun isBoxPoly(poly: ConvexPolyhedron): Boolean {
        if (poly.faces.size != 6) return false
        for (i in poly.faces.indices) {
            val n = poly.faces[i].normal
            val maxAxis = max(abs(n.x), max(abs(n.y), abs(n.z)))
            if (maxAxis < 0.992f) return false
        }
        return true
    }

    private fun buildShapeForPoly(poly: ConvexPolyhedron): ShapeRefC {
        val he = poly.halfExtents
        val hx = max(0.025f, he.x)
        val hy = max(0.025f, he.y)
        val hz = max(0.025f, he.z)
        val minHe = min(hx, min(hy, hz))
        val convexRadius = min(0.015f, minHe * 0.25f).coerceAtLeast(0.002f)

        if (isBoxPoly(poly) || poly.faces.size < 4) {
            return BoxShapeSettings(hx, hy, hz, convexRadius).create().get()
        }

        val pts = ArrayList<JVec3>(24)
        for (fi in poly.faces.indices) {
            val verts = poly.faces[fi].vertices
            for (vi in verts.indices) {
                val v = verts[vi]
                var dup = false
                for (pi in pts.indices) {
                    val p = pts[pi]
                    val dx = p.x - v.x
                    val dy = p.y - v.y
                    val dz = p.z - v.z
                    if (dx * dx + dy * dy + dz * dz < 1e-5f) {
                        dup = true
                        break
                    }
                }
                if (!dup) pts.add(JVec3(v.x, v.y, v.z))
            }
        }
        if (pts.size < 4) {
            return BoxShapeSettings(hx, hy, hz, convexRadius).create().get()
        }

        val hullSettings = ConvexHullShapeSettings(pts, convexRadius)
        hullSettings.setHullTolerance(0.002f)
        val res = hullSettings.create()
        return if (!res.hasError() && res.isValid) {
            res.get()
        } else {
            BoxShapeSettings(hx, hy, hz, convexRadius).create().get()
        }
    }

    private fun buildShapeForShard(shard: DynamicShard): ShapeRefC {
        val pieces = shard.clusterPieces
        if (pieces != null && pieces.size > 1) {
            val compound = StaticCompoundShapeSettings()
            for (i in pieces.indices) {
                val piece = pieces[i]
                val subShape = buildShapeForPoly(piece.poly)
                compound.addShape(
                    JVec3(piece.localOffset.x, piece.localOffset.y, piece.localOffset.z),
                    JQuat.sIdentity(),
                    subShape
                )
            }
            val res = compound.create()
            if (!res.hasError() && res.isValid) return res.get()
        }
        return buildShapeForPoly(shard.poly)
    }

    private fun syncShardsBeforeStep(shards: List<DynamicShard>) {
        val liveIds = HashSet<Int>(shards.size * 2 + 8)
        for (i in shards.indices) liveIds.add(shards[i].id)

        val iter = shardBodies.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (!liveIds.contains(entry.key)) {
                bodyInterface.removeBody(entry.value.bodyId)
                bodyInterface.destroyBody(entry.value.bodyId)
                iter.remove()
            }
        }

        for (i in shards.indices) {
            val shard = shards[i]
            val existing = shardBodies[shard.id]
            if (existing == null) {
                val shapeRef = buildShapeForShard(shard)
                val bcs = BodyCreationSettings(
                    shapeRef,
                    JRVec3(shard.center.x.toDouble(), shard.center.y.toDouble(), shard.center.z.toDouble()),
                    JQuat(shard.orientation.x, shard.orientation.y, shard.orientation.z, shard.orientation.w),
                    EMotionType.Dynamic,
                    LAYER_MOVING
                )
                val massProps = MassProperties()
                massProps.setMass(max(0.25f, shard.massEstimate))
                bcs.setOverrideMassProperties(EOverrideMassProperties.CalculateInertia)
                bcs.setMassPropertiesOverride(massProps)
                bcs.setFriction(0.88f)
                bcs.setRestitution(clamp(shard.restitution * 0.25f, 0.01f, 0.14f))
                bcs.setLinearDamping((0.14f * shard.drag).coerceIn(0.20f, 0.48f))
                bcs.setAngularDamping(0.92f)
                bcs.setAllowSleeping(true)
                bcs.setLinearVelocity(shard.vel.x, shard.vel.y, shard.vel.z)
                bcs.setAngularVelocity(shard.angVel.x, shard.angVel.y, shard.angVel.z)
                bcs.setMotionQuality(
                    if (shard.boundRadius < 0.45f) EMotionQuality.LinearCast else EMotionQuality.Discrete
                )
                bcs.setUserData(-shard.id.toLong())

                val activation = if (shard.sleeping) EActivation.DontActivate else EActivation.Activate
                val bodyId = bodyInterface.createAndAddBody(bcs, activation)
                shardBodies[shard.id] = JoltShardHandle(
                    bodyId = bodyId,
                    lastPos = shard.center,
                    lastOrient = shard.orientation,
                    lastVel = shard.vel,
                    lastAngVel = shard.angVel,
                    wasActive = !shard.sleeping
                )
            } else {
                // Propagate external velocity/position/wake changes (explosions, bullets, wrecking ball, tests)
                val posChanged = (shard.center - existing.lastPos).lenSq() > 1e-6f
                val velChanged = (shard.vel - existing.lastVel).lenSq() > 1e-6f
                val angChanged = (shard.angVel - existing.lastAngVel).lenSq() > 1e-6f
                val wokenExternally = !shard.sleeping && !existing.wasActive

                if (posChanged || velChanged || angChanged || wokenExternally) {
                    if (posChanged) {
                        bodyInterface.setPositionAndRotation(
                            existing.bodyId,
                            JRVec3(shard.center.x.toDouble(), shard.center.y.toDouble(), shard.center.z.toDouble()),
                            JQuat(shard.orientation.x, shard.orientation.y, shard.orientation.z, shard.orientation.w),
                            EActivation.Activate
                        )
                    }
                    if (velChanged || angChanged) {
                        bodyInterface.setLinearAndAngularVelocity(
                            existing.bodyId,
                            shard.vel.x, shard.vel.y, shard.vel.z,
                            shard.angVel.x, shard.angVel.y, shard.angVel.z
                        )
                    }
                    if (wokenExternally || velChanged || angChanged) {
                        bodyInterface.activateBody(existing.bodyId)
                        existing.wasActive = true
                    }
                    existing.lastPos = shard.center
                    existing.lastOrient = shard.orientation
                    existing.lastVel = shard.vel
                    existing.lastAngVel = shard.angVel
                }
            }
        }
    }

    fun step(
        dt: Float,
        terrain: ChunkedTerrain,
        blocks: Map<Int, StructuralBlock>,
        shards: List<DynamicShard>,
        tiltedTipHelper: (DynamicShard, Vec3) -> Vec3?,
        onSleepChanged: () -> Unit
    ): JoltStepOutcome {
        val tempAlloc = JoltRuntime.tempAllocator ?: return JoltStepOutcome(emptyList(), emptyList())
        val jobSys = JoltRuntime.jobSystem ?: return JoltStepOutcome(emptyList(), emptyList())

        syncTerrain(terrain)
        syncBlocks(blocks)
        syncShardsBeforeStep(shards)

        val splashes = ArrayList<WaterSplashEvent>()
        val preAboveWater = HashSet<Int>()

        // Apply water drag, grounded rolling friction, and elongated-pillar anti-balancing torque before Jolt updates
        for (i in shards.indices) {
            val shard = shards[i]
            shard.age += dt
            if (shard.center.y > ChunkedTerrain.WATER_LEVEL) preAboveWater.add(shard.id)
            val handle = shardBodies[shard.id] ?: continue
            if (!handle.wasActive && shard.sleeping) continue

            if (shard.center.y < ChunkedTerrain.WATER_LEVEL) {
                val damp = (1f - 2.4f * dt).coerceIn(0.3f, 0.99f)
                bodyInterface.setLinearAndAngularVelocity(
                    handle.bodyId,
                    shard.vel.x * damp, shard.vel.y * damp, shard.vel.z * damp,
                    shard.angVel.x * damp, shard.angVel.y * damp, shard.angVel.z * damp
                )
            }

            // Gently destabilize tall narrow pillars/beams ONLY when grounded (never in free-fall),
            // and apply unconditionally stable exponential rolling/sliding damping above sleep threshold.
            if (shard.isGroundedContact) {
                val mass = max(0.25f, shard.massEstimate)
                val groundN = terrain.groundNormal(shard.center.x, shard.center.z)
                val tipDir = tiltedTipHelper(shard, groundN)
                if (tipDir != null) {
                    val tipRate = shard.angVel.dot(tipDir)
                    if (shard.vel.lenSq() < 12.0f && abs(tipRate) < 1.9f) {
                        val leverScale = max(0.25f, shard.boundRadius * shard.boundRadius)
                        val torqueMag = mass * leverScale * 7.5f
                        bodyInterface.addTorque(handle.bodyId, tipDir.x * torqueMag, tipDir.y * torqueMag, tipDir.z * torqueMag)
                    }
                    // Exponentially damp upright spin around ground normal so spinning pillars tip cleanly
                    val twist = shard.angVel.dot(groundN)
                    if (abs(twist) > 0.35f) {
                        val spinKeep = kotlin.math.exp(-6.0f * dt)
                        val drop = twist * (1f - spinKeep)
                        bodyInterface.setAngularVelocity(
                            handle.bodyId,
                            shard.angVel.x - groundN.x * drop,
                            shard.angVel.y - groundN.y * drop,
                            shard.angVel.z - groundN.z * drop
                        )
                    }
                } else {
                    val linHorizSq = shard.vel.x * shard.vel.x + shard.vel.z * shard.vel.z
                    val angSq = shard.angVel.lenSq()
                    val onPileOrBlock = shard.supportedByShardId != null || shard.supportedByBlockId != null
                    val thresh = if (onPileOrBlock) 0.08f else 0.05f
                    if ((linHorizSq > thresh || angSq > thresh) && linHorizSq < 16.0f && angSq < 49.0f) {
                        val linRate = if (onPileOrBlock) -2.2f else -5.5f
                        val rollRate = if (onPileOrBlock) -3.5f else -7.5f
                        val linDamp = if (linHorizSq > thresh) kotlin.math.exp(linRate * dt) else 1f
                        val rollDamp = if (angSq > thresh) kotlin.math.exp(rollRate * dt) else 1f
                        bodyInterface.setLinearAndAngularVelocity(
                            handle.bodyId,
                            shard.vel.x * linDamp, shard.vel.y, shard.vel.z * linDamp,
                            shard.angVel.x * rollDamp, shard.angVel.y * rollDamp, shard.angVel.z * rollDamp
                        )
                    }
                }
            }
        }

        synchronized(contactLock) {
            pendingContacts.clear()
            activeSupportTouches.clear()
        }

        val stepDt = dt.coerceIn(1f / 240f, 0.045f)
        val collisionSteps = if (stepDt > 0.015f) 2 else 1
        physicsSystem.update(stepDt, collisionSteps, tempAlloc, jobSys)

        val contactsOut: List<JoltContactEvent>
        val touchesOut: List<JoltContactEvent>
        synchronized(contactLock) {
            contactsOut = ArrayList(pendingContacts)
            touchesOut = ArrayList(activeSupportTouches)
        }

        val shardMap = HashMap<Int, DynamicShard>(shards.size * 2 + 8)
        for (i in shards.indices) shardMap[shards[i].id] = shards[i]

        // Reset dynamic support links for currently active bodies and refresh from Jolt contact touches
        for (i in shards.indices) {
            val shard = shards[i]
            val handle = shardBodies[shard.id] ?: continue
            val isActive = bodyInterface.isActive(handle.bodyId)
            if (isActive) {
                shard.supportedByBlockId = null
                shard.supportedByShardId = null
                shard.isGroundedContact = false
            }
        }

        for (i in touchesOut.indices) {
            val ev = touchesOut[i]
            val u1 = ev.userData1
            val u2 = ev.userData2
            // Jolt normal points from Body 1 -> Body 2
            if (u1 < 0L) {
                val s = shardMap[(-u1).toInt()]
                if (s != null && -ev.normalY > 0.50f) {
                    if (u2 == 0L) {
                        s.isGroundedContact = true
                        s.supportedByBlockId = null
                        s.supportedByShardId = null
                    } else if (u2 > 0L) {
                        s.isGroundedContact = true
                        s.supportedByBlockId = u2.toInt()
                        s.supportedByShardId = null
                    } else if (u2 < 0L) {
                        s.isGroundedContact = true
                        s.supportedByShardId = (-u2).toInt()
                    }
                }
            }
            if (u2 < 0L) {
                val s = shardMap[(-u2).toInt()]
                if (s != null && ev.normalY > 0.50f) {
                    if (u1 == 0L) {
                        s.isGroundedContact = true
                        s.supportedByBlockId = null
                        s.supportedByShardId = null
                    } else if (u1 > 0L) {
                        s.isGroundedContact = true
                        s.supportedByBlockId = u1.toInt()
                        s.supportedByShardId = null
                    } else if (u1 < 0L) {
                        s.isGroundedContact = true
                        s.supportedByShardId = (-u1).toInt()
                    }
                }
            }
        }

        val jPos = JRVec3()
        val jQuat = JQuat()
        val jVel = JVec3()
        val jAng = JVec3()
        var sleepTransitioned = false

        for (i in shards.indices) {
            val shard = shards[i]
            val handle = shardBodies[shard.id] ?: continue
            val isActive = bodyInterface.isActive(handle.bodyId)

            if (isActive || handle.wasActive) {
                bodyInterface.getPositionAndRotation(handle.bodyId, jPos, jQuat)
                bodyInterface.getLinearVelocity(handle.bodyId, jVel)
                bodyInterface.getAngularVelocity(handle.bodyId, jAng)

                val newCenter = Vec3(jPos.x(), jPos.y(), jPos.z())
                val newOrient = Quat(jQuat.x, jQuat.y, jQuat.z, jQuat.w).normalized()
                val newVel = if (isActive) Vec3(jVel.x, jVel.y, jVel.z) else Vec3.ZERO
                val newAng = if (isActive) Vec3(jAng.x, jAng.y, jAng.z) else Vec3.ZERO

                shard.center = newCenter
                shard.orientation = newOrient
                shard.vel = newVel
                shard.angVel = newAng

                handle.lastPos = newCenter
                handle.lastOrient = newOrient
                handle.lastVel = newVel
                handle.lastAngVel = newAng

                if (preAboveWater.contains(shard.id) &&
                    newCenter.y <= ChunkedTerrain.WATER_LEVEL &&
                    newVel.y < -2.2f
                ) {
                    splashes.add(WaterSplashEvent(Vec3(newCenter.x, ChunkedTerrain.WATER_LEVEL, newCenter.z), 1.0f))
                }
            }

            val nowSleeping = !isActive
            if (shard.sleeping != nowSleeping) {
                shard.sleeping = nowSleeping
                if (nowSleeping) {
                    shard.vel = Vec3.ZERO
                    shard.angVel = Vec3.ZERO
                    shard.isGroundedContact = true
                }
                sleepTransitioned = true
            }
            handle.wasActive = isActive
        }

        if (sleepTransitioned) {
            onSleepChanged()
        }

        return JoltStepOutcome(contactsOut, splashes)
    }
}
