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

class StructuralWorld {

    companion object {
        const val MAX_ACTIVE_SHARDS = 280
        const val MAX_TOTAL_SHARDS = 540
        const val GRAVITY = -18.5f
        const val SECTOR_SIZE = 64f
        const val SECTORS_X = 8
        const val SECTORS_Z = 6
        const val SECTOR_COUNT = SECTORS_X * SECTORS_Z
        private const val BLOCK_CELL_SIZE = 4.0f
        private const val INV_BLOCK_CELL = 1f / BLOCK_CELL_SIZE

        /**
         * Tests set this false before constructing a world to force the custom integrator.
         * Devices on minSdk 30 load Jolt; the custom path remains for a failed native load.
         */
        @Volatile
        var allowJoltPhysics: Boolean = true
    }

    val blocks = LinkedHashMap<Int, StructuralBlock>()
    val shards = ArrayList<DynamicShard>()
    var structureVersion = 1
        private set
    val sectorVersions = IntArray(SECTOR_COUNT) { 1 }
    var sleepingRubbleVersion = 1
        private set
    var collapsedCount = 0
        private set

    private var nextId = 1
    private val rng = Random(918273)

    // Broad-phase 2D spatial hash grid for intact blocks (rebuilt only when structureVersion changes)
    private val blockCellGrid = HashMap<Long, ArrayList<StructuralBlock>>(512)
    private var cachedBlockGridVersion = -1
    private var maxBlockExtent = 2.5f
    private var blockSeenStamp = IntArray(4096)
    private var blockQueryStampGen = 0
    private val nearbyBlocksScratch = ArrayList<StructuralBlock>(24)

    // Zero-allocation scratch buffers for stepShards & computeLowestVertexContact
    private val contactUniqX = FloatArray(64)
    private val contactUniqY = FloatArray(64)
    private val contactUniqZ = FloatArray(64)
    private val shardWorldHeScratch = ArrayList<Vec3>(MAX_TOTAL_SHARDS)
    private val shardById = HashMap<Int, DynamicShard>(512)
    private val phase2Grid = HashMap<Long, ArrayList<Int>>(512)
    private val phase2ActiveKeys = ArrayList<Long>(256)
    private val phase2BucketPool = ArrayList<ArrayList<Int>>(256)
    private val phase2SeenPairs = HashSet<Long>(512)
    private var satNormalX = 0f
    private var satNormalY = 1f
    private var satNormalZ = 0f
    private var satPen = 0f
    private var projMinOut = 0f
    private var projMaxOut = 0f
    private val manifoldScratch = ManifoldScratch()

    // Staggered sequential collapse & load-stress state
    val pendingCollapses = ArrayList<PendingCollapseNode>(64)
    private val pendingBlockIds = HashSet<Int>(64)
    private val collapseFailureEvents = ArrayList<CollapseFailureEvent>(32)
    private var loadBaselineVersion = -1
    private val joltWorld: JoltPhysicsWorld? =
        if (allowJoltPhysics && JoltRuntime.isAvailable) JoltPhysicsWorld() else null
    private val bakedDebris = FloatArrayBuilder(16384)
    private var debrisTerrain: ChunkedTerrain? = null

    val isUsingJoltPhysics: Boolean get() = joltWorld != null

    private fun blockCellKey(cx: Int, cz: Int): Long =
        (cx.toLong() shl 32) xor (cz.toLong() and 0xFFFFFFFFL)

    private fun ensureBlockGrid() {
        if (cachedBlockGridVersion == structureVersion) return
        cachedBlockGridVersion = structureVersion
        for (list in blockCellGrid.values) list.clear()
        var maxExt = 1.0f
        for (block in blocks.values) {
            val bhe = block.poly.halfExtents
            val wHe = if (block.orientation == Quat.IDENTITY) bhe else orientedHalfExtents(bhe, block.orientation)
            val extXZ = max(wHe.x, wHe.z)
            if (extXZ > maxExt) maxExt = extXZ
            val minCx = kotlin.math.floor((block.center.x - wHe.x) * INV_BLOCK_CELL).toInt()
            val maxCx = kotlin.math.floor((block.center.x + wHe.x) * INV_BLOCK_CELL).toInt()
            val minCz = kotlin.math.floor((block.center.z - wHe.z) * INV_BLOCK_CELL).toInt()
            val maxCz = kotlin.math.floor((block.center.z + wHe.z) * INV_BLOCK_CELL).toInt()
            for (cx in minCx..maxCx) {
                for (cz in minCz..maxCz) {
                    blockCellGrid.getOrPut(blockCellKey(cx, cz)) { ArrayList(6) }.add(block)
                }
            }
        }
        maxBlockExtent = maxExt
    }

    private fun queryNearbyBlocks(x: Float, z: Float, radiusXZ: Float): ArrayList<StructuralBlock> {
        ensureBlockGrid()
        nearbyBlocksScratch.clear()
        blockQueryStampGen++
        if (blockQueryStampGen == Int.MAX_VALUE) {
            blockSeenStamp.fill(0)
            blockQueryStampGen = 1
        }
        val minCx = kotlin.math.floor((x - radiusXZ) * INV_BLOCK_CELL).toInt()
        val maxCx = kotlin.math.floor((x + radiusXZ) * INV_BLOCK_CELL).toInt()
        val minCz = kotlin.math.floor((z - radiusXZ) * INV_BLOCK_CELL).toInt()
        val maxCz = kotlin.math.floor((z + radiusXZ) * INV_BLOCK_CELL).toInt()
        for (cx in minCx..maxCx) {
            for (cz in minCz..maxCz) {
                val list = blockCellGrid[blockCellKey(cx, cz)] ?: continue
                for (bi in list.indices) {
                    val block = list[bi]
                    val id = block.id
                    if (id >= blockSeenStamp.size) {
                        blockSeenStamp = blockSeenStamp.copyOf(max(blockSeenStamp.size * 2, id + 256))
                    }
                    if (blockSeenStamp[id] != blockQueryStampGen) {
                        blockSeenStamp[id] = blockQueryStampGen
                        nearbyBlocksScratch.add(block)
                    }
                }
            }
        }
        return nearbyBlocksScratch
    }

    private fun setShardAwake(shard: DynamicShard) {
        if (shard.sleeping) {
            shard.sleeping = false
            sleepingRubbleVersion++
        }
    }

    fun sectorIndexFor(x: Float, z: Float): Int {
        val sx = ((x - ChunkedTerrain.MIN_X) / SECTOR_SIZE).toInt().coerceIn(0, SECTORS_X - 1)
        val sz = ((z - ChunkedTerrain.MIN_Z) / SECTOR_SIZE).toInt().coerceIn(0, SECTORS_Z - 1)
        return sz * SECTORS_X + sx
    }

    private fun markSectorDirty(x: Float, z: Float) {
        val idx = sectorIndexFor(x, z)
        sectorVersions[idx]++
    }

    private fun removeBlockInternal(id: Int): StructuralBlock? {
        val removed = blocks.remove(id) ?: return null
        markSectorDirty(removed.center.x, removed.center.z)
        return removed
    }

    fun clear() {
        blocks.clear()
        shards.clear()
        pendingCollapses.clear()
        pendingBlockIds.clear()
        collapseFailureEvents.clear()
        loadBaselineVersion = -1
        nextId = 1
        collapsedCount = 0
        joltWorld?.clear()
        bakedDebris.reset()
        for (i in sectorVersions.indices) sectorVersions[i]++
        structureVersion++
        sleepingRubbleVersion++
    }

    fun addBlock(
        poly: ConvexPolyhedron,
        center: Vec3,
        orientation: Quat = Quat.IDENTITY,
        tileId: Int,
        tint: Vec3 = Vec3(1f, 1f, 1f),
        hp: Float = 80f,
        groundAnchored: Boolean = false,
        supportIds: MutableList<Int> = mutableListOf(),
        isFuelExplosive: Boolean = false,
        isCoreSupport: Boolean = false,
        targetTag: String? = null,
        pointValue: Int = 15,
        isHydrant: Boolean = false,
        walkable: Boolean = true,
        supportGroups: MutableList<MutableList<Int>> = mutableListOf(),
        checkCantilever: Boolean = false,
        breachDepth: Int = 0
    ): Int {
        val id = nextId++
        blocks[id] = StructuralBlock(
            id = id,
            poly = poly,
            center = center,
            orientation = orientation,
            uvOrigin = center,
            tileId = tileId,
            tint = tint,
            hp = hp,
            maxHp = hp,
            groundAnchored = groundAnchored,
            supportIds = supportIds,
            isFuelExplosive = isFuelExplosive,
            isCoreSupport = isCoreSupport,
            targetTag = targetTag,
            pointValue = pointValue,
            isHydrant = isHydrant,
            walkable = walkable,
            supportGroups = supportGroups,
            checkCantilever = checkCantilever,
            breachDepth = breachDepth
        )
        markSectorDirty(center.x, center.z)
        structureVersion++
        return id
    }

    /**
     * Prunes non-contacting support IDs using 3D OBB/AABB proximity so distant slabs/columns
     * cannot artificially hold up isolated blocks, and enables cantilever gravity checks
     * on multi-support horizontal slabs, roofs, canopies, and decks.
     */
    fun rebuildPhysicalSupports() {
        val extCache = LinkedHashMap<Int, Vec3>(blocks.size)
        for (b in blocks.values) {
            extCache[b.id] = worldHalfExtents(b)
        }

        fun blocksTouch(a: StructuralBlock, b: StructuralBlock, tol: Float = 0.38f): Boolean {
            val ea = extCache[a.id] ?: worldHalfExtents(a)
            val eb = extCache[b.id] ?: worldHalfExtents(b)
            return abs(a.center.x - b.center.x) <= ea.x + eb.x + tol &&
                abs(a.center.y - b.center.y) <= ea.y + eb.y + tol &&
                abs(a.center.z - b.center.z) <= ea.z + eb.z + tol
        }

        for (b in blocks.values) {
            if (!b.groundAnchored && b.supportIds.size > 1) {
                val filtered = b.supportIds.filter { sid ->
                    val s = blocks[sid]
                    s != null && blocksTouch(b, s)
                }
                if (filtered.isNotEmpty()) {
                    b.supportIds.clear()
                    b.supportIds.addAll(filtered)
                } else {
                    val nearest = b.supportIds
                        .mapNotNull { sid -> blocks[sid]?.let { sid to (it.center - b.center).lenSq() } }
                        .minByOrNull { it.second }?.first
                    if (nearest != null) {
                        b.supportIds.clear()
                        b.supportIds.add(nearest)
                    }
                }
            }

            if (b.supportGroups.isNotEmpty()) {
                for (grp in b.supportGroups) {
                    if (grp.size <= 1) continue
                    val filtered = grp.filter { sid ->
                        val s = blocks[sid]
                        s != null && blocksTouch(b, s, tol = 0.45f)
                    }
                    if (filtered.isNotEmpty()) {
                        grp.clear()
                        grp.addAll(filtered)
                    } else {
                        val nearest = grp
                            .mapNotNull { sid -> blocks[sid]?.let { sid to (it.center - b.center).lenSq() } }
                            .minByOrNull { it.second }?.first
                        if (nearest != null) {
                            grp.clear()
                            grp.add(nearest)
                        }
                    }
                }
            }

            // Enable cantilever center-of-mass stability check on multi-support wide horizontal slabs/roofs/decks
            if (!b.groundAnchored && !b.checkCantilever && b.supportIds.size >= 2) {
                val eb = extCache[b.id] ?: worldHalfExtents(b)
                if (min(eb.x, eb.z) >= 0.85f && max(eb.x, eb.z) >= 1.55f && eb.y <= 1.65f && b.tileId != MaterialTile.STAIR_TREAD) {
                    b.checkCantilever = true
                }
            }
        }
        recomputeLoadBaseline()
    }

    private fun worldHalfExtents(b: StructuralBlock): Vec3 {
        val he = b.poly.halfExtents
        val ax = b.orientation.rotate(Vec3(he.x, 0f, 0f))
        val ay = b.orientation.rotate(Vec3(0f, he.y, 0f))
        val az = b.orientation.rotate(Vec3(0f, 0f, he.z))
        return Vec3(
            abs(ax.x) + abs(ay.x) + abs(az.x),
            abs(ax.y) + abs(ay.y) + abs(az.y),
            abs(ax.z) + abs(ay.z) + abs(az.z)
        )
    }

    fun applyBulletImpact(
        origin: Vec3,
        dir: Vec3,
        isHeavyAp: Boolean,
        terrain: ChunkedTerrain,
        maxDist: Float = 165f
    ): BulletImpactResult {
        val bHit = raycastBlocks(origin, dir, maxDist)
        val sHit = raycastShards(origin, dir, maxDist)
        val tHit = terrain.raycast(origin, dir, maxDist)

        val bDist = bHit?.distance ?: Float.MAX_VALUE
        val sDist = sHit?.distance ?: Float.MAX_VALUE
        val tDist = if (tHit != null) (tHit - origin).len() else Float.MAX_VALUE

        if (bHit == null && sHit == null && tHit == null) {
            return BulletImpactResult(
                hitPoint = null, hitNormal = Vec3.UP, hitBlock = false,
                tileId = -1, soundKind = null, ricochet = false,
                blockBroken = false, collapsedCascade = 0, pointsAwarded = 0,
                secondaryBlasts = emptyList(), hydrantBurstPos = null, hitShard = false
            )
        }

        if (sHit != null && sDist < bDist && sDist < tDist) {
            val shard = shards.firstOrNull { it.id == sHit.shardId }
            if (shard != null) {
                val profile = MaterialProfiles.forTile(shard.tileId)
                val ricochet = !isHeavyAp && profile.isCarbineRicochet
                val unitDir = dir.normalized()
                val impulseMag = if (isHeavyAp) 14.5f else 7.2f
                val mass = shard.massEstimate.coerceAtLeast(0.45f)
                setShardAwake(shard)
                shard.isGroundedContact = false
                shard.supportedByBlockId = null
                shard.supportedByShardId = null
                shard.settleTimer = 0f
                shard.vel += (unitDir * (impulseMag / mass)) + Vec3(0f, 0.9f / mass.coerceAtLeast(0.6f), 0f)
                val hitArm = sHit.hitPoint - shard.center
                shard.angVel += hitArm.cross(unitDir) * (3.8f / mass)
                wakeDependentsOfShard(shard.id)
                return BulletImpactResult(
                    hitPoint = sHit.hitPoint,
                    hitNormal = sHit.hitNormal,
                    hitBlock = false,
                    tileId = shard.tileId,
                    soundKind = profile.soundKind,
                    ricochet = ricochet,
                    blockBroken = false,
                    collapsedCascade = 0,
                    pointsAwarded = 0,
                    secondaryBlasts = emptyList(),
                    hydrantBurstPos = null,
                    hitShard = true
                )
            }
        }

        if (tDist < bDist && tHit != null) {
            // Precision bullets kick up dust/water without leaving ground craters
            return BulletImpactResult(
                hitPoint = tHit,
                hitNormal = terrain.groundNormal(tHit.x, tHit.z),
                hitBlock = false,
                tileId = MaterialTile.SOIL,
                soundKind = null,
                ricochet = false,
                blockBroken = false,
                collapsedCascade = 0,
                pointsAwarded = 0,
                secondaryBlasts = emptyList(),
                hydrantBurstPos = null,
                hitShard = false
            )
        }

        val hit = bHit!!
        val blk = blocks[hit.blockId] ?: return BulletImpactResult(
            hitPoint = hit.hitPoint, hitNormal = hit.hitNormal, hitBlock = false,
            tileId = -1, soundKind = null, ricochet = false,
            blockBroken = false, collapsedCascade = 0, pointsAwarded = 0,
            secondaryBlasts = emptyList(), hydrantBurstPos = null
        )

        val profile = MaterialProfiles.forTile(blk.tileId)
        val rawDmg = if (isHeavyAp) 94f * profile.heavyApMult else 26f * profile.carbineMult
        val ricochet = !isHeavyAp && profile.isCarbineRicochet
        blk.hp -= rawDmg

        var broken = false
        var points = 0
        var cascade = 0
        val secondary = ArrayList<SecondaryBlastEvent>()
        var hydrantPos: Vec3? = null

        if (blk.hp <= 0f) {
            broken = true
            val holeHalf = if (isHeavyAp) 0.56f else 0.42f
            val breached = tryCarveLocalBreach(
                blk = blk,
                impactWorld = hit.hitPoint,
                impulseDir = dir.normalized(),
                impulseSpeed = if (isHeavyAp) 13.5f else 8.5f,
                holeHalfU = holeHalf,
                holeHalfV = holeHalf,
                isPrecision = true
            )
            if (breached) {
                collapsedCount++
                points += (blk.pointValue * 0.65f).toInt().coerceAtLeast(6)
            } else {
                removeBlockInternal(blk.id)
                collapsedCount++
                points += blk.pointValue
                if (blk.isFuelExplosive) {
                    secondary.add(SecondaryBlastEvent(blk.center, 5.8f, 1.0f, 19f))
                }
                if (blk.isHydrant) {
                    hydrantPos = blk.center
                }
                val pieces = clampShardPieces(blk, isPrecision = true)
                spawnShatteredShards(
                    block = blk,
                    impactWorld = hit.hitPoint,
                    impulseDir = dir.normalized(),
                    impulseSpeed = if (isHeavyAp) 13.5f else 8.5f,
                    pieces = pieces
                )
            }
            val (cascadeCount, cascadePoints) = checkStabilityAndCollapseWithMetrics(terrain)
            cascade = cascadeCount
            points += cascadePoints
            wakeShardsAroundRemoved(listOf(blk.id), hit.hitPoint, 3.2f)
            structureVersion++
        }

        return BulletImpactResult(
            hitPoint = hit.hitPoint,
            hitNormal = hit.hitNormal,
            hitBlock = true,
            tileId = blk.tileId,
            soundKind = profile.soundKind,
            ricochet = ricochet,
            blockBroken = broken,
            collapsedCascade = cascade,
            pointsAwarded = points,
            secondaryBlasts = secondary,
            hydrantBurstPos = hydrantPos
        )
    }

    fun applyExplosion(
        pos: Vec3,
        radius: Float,
        damage: Float,
        impulseStrength: Float,
        terrain: ChunkedTerrain
    ): Pair<List<SecondaryBlastEvent>, List<WaterSplashEvent>> {
        val outcome = applyExplosionDetailed(pos, radius, damage, impulseStrength, terrain)
        return outcome.secondaryBlasts to outcome.splashes
    }

    fun applyExplosionDetailed(
        pos: Vec3,
        radius: Float,
        damage: Float,
        impulseStrength: Float,
        terrain: ChunkedTerrain
    ): ExplosionOutcome {
        debrisTerrain = terrain
        val secondaryBlasts = ArrayList<SecondaryBlastEvent>()
        val splashes = ArrayList<WaterSplashEvent>()
        val toDestroy = ArrayList<Pair<StructuralBlock, Float>>()
        val sounds = LinkedHashSet<MaterialSoundKind>()
        var points = 0

        for (block in blocks.values) {
            val delta = block.center - pos
            val approxDist = max(0f, delta.len() - block.poly.halfExtents.len() * 0.55f)
            if (approxDist > radius) continue

            val profile = MaterialProfiles.forTile(block.tileId)
            val falloff = 1f - (approxDist / radius)
            val effBlastMult = if (block.isFuelExplosive) max(profile.blastMult, 0.85f) else profile.blastMult
            val dmg = damage * falloff * falloff * effBlastMult
            block.hp -= dmg
            if (block.hp <= 0f) {
                toDestroy.add(block to dmg)
            }
        }

        for ((block, lastDmg) in toDestroy) {
            val impulseSpeed = impulseStrength * clamp(1.1f - (block.center - pos).len() / radius, 0.28f, 1.25f)
            val impulseDir = (block.center - pos).let { if (it.lenSq() > 1e-5f) it.normalized() else Vec3.UP }
            val allowLocalBreach = damage < 420f && lastDmg <= block.maxHp * 1.85f
            val breached = allowLocalBreach && tryCarveLocalBreach(
                blk = block,
                impactWorld = pos,
                impulseDir = impulseDir,
                impulseSpeed = impulseSpeed,
                holeHalfU = clamp(radius * 0.24f, 0.85f, 1.22f),
                holeHalfV = clamp(radius * 0.24f, 0.85f, 1.22f),
                isPrecision = false
            )
            sounds.add(MaterialProfiles.forTile(block.tileId).soundKind)
            if (breached) {
                collapsedCount++
                points += (block.pointValue * 0.7f).toInt().coerceAtLeast(8)
                continue
            }

            removeBlockInternal(block.id)
            collapsedCount++
            points += block.pointValue
            if (block.isFuelExplosive) {
                secondaryBlasts.add(
                    SecondaryBlastEvent(
                        pos = block.center,
                        radius = 6.2f,
                        craterDepth = 1.15f,
                        impulse = 20f
                    )
                )
            }
            if (block.isHydrant) {
                splashes.add(WaterSplashEvent(block.center, 1.5f))
            }
            val pieces = clampShardPieces(block, isPrecision = false)
            spawnShatteredShards(
                block = block,
                impactWorld = pos,
                impulseDir = impulseDir,
                impulseSpeed = impulseSpeed,
                pieces = pieces
            )
        }

        // Push & wake existing dynamic shards near the shockwave
        val wakeRadius = radius * 1.55f
        val wokenIds = ArrayList<Int>()
        for (shard in shards) {
            val delta = shard.center - pos
            val d = delta.len()
            if (d < wakeRadius) {
                if (shard.sleeping) {
                    wokenIds.add(shard.id)
                    setShardAwake(shard)
                }
                shard.isGroundedContact = false
                shard.supportedByBlockId = null
                shard.supportedByShardId = null
                shard.settleTimer = 0f
                if (d < radius * 1.32f) {
                    val factor = (1f - d / (radius * 1.32f)).coerceIn(0.05f, 1f)
                    val pushDir = (delta + Vec3(0f, 1.35f, 0f)).normalized()
                    shard.vel += pushDir * (impulseStrength * 0.78f * factor)
                    shard.angVel += Vec3(
                        (rng.nextFloat() - 0.5f) * 6.0f,
                        (rng.nextFloat() - 0.5f) * 6.0f,
                        (rng.nextFloat() - 0.5f) * 6.0f
                    ) * factor
                    wokenIds.add(shard.id)
                }
            }
        }
        if (wokenIds.isNotEmpty()) {
            wakeDependentsOfShards(wokenIds)
        }

        val (cascadeCount, cascadePoints) = checkStabilityAndCollapseWithMetrics(terrain)
        points += cascadePoints
        if (toDestroy.isNotEmpty() || cascadeCount > 0) {
            wakeShardsAroundRemoved(toDestroy.map { it.first.id }, pos, wakeRadius)
            structureVersion++
        }
        return ExplosionOutcome(
            secondaryBlasts = secondaryBlasts,
            splashes = splashes,
            destroyedCount = toDestroy.size,
            collapsedByCascade = cascadeCount,
            pointsAwarded = points,
            brokenSounds = sounds
        )
    }

    fun applyKineticImpact(
        pos: Vec3,
        vel: Vec3,
        radius: Float,
        terrain: ChunkedTerrain
    ): Pair<Boolean, List<SecondaryBlastEvent>> {
        val detailed = applyKineticImpactDetailed(pos, vel, radius, terrain)
        return detailed.hitAny to detailed.secondaryBlasts
    }

    fun applyKineticImpactDetailed(
        pos: Vec3,
        vel: Vec3,
        radius: Float,
        terrain: ChunkedTerrain
    ): KineticImpactResult {
        val secondary = ArrayList<SecondaryBlastEvent>()
        val speed = vel.len()
        if (speed < 4f) {
            return KineticImpactResult(false, false, Vec3.UP, null, secondary)
        }

        var hitAny = false
        var bounced = false
        var bestBounceNormal = Vec3.UP
        var bestPen = 0f
        val toDestroy = ArrayList<StructuralBlock>()

        for (block in blocks.values) {
            val bhe = block.poly.halfExtents
            val reach = bhe.len() + radius + 0.25f
            if (abs(pos.x - block.center.x) > reach ||
                abs(pos.y - block.center.y) > reach ||
                abs(pos.z - block.center.z) > reach
            ) continue

            val invQ = block.orientation.conjugate()
            val localP = invQ.rotate(pos - block.center)
            val clamped = Vec3(
                localP.x.coerceIn(-bhe.x, bhe.x),
                localP.y.coerceIn(-bhe.y, bhe.y),
                localP.z.coerceIn(-bhe.z, bhe.z)
            )
            val diff = localP - clamped
            val distSq = diff.lenSq()
            if (distSq > radius * radius) continue

            hitAny = true
            val profile = MaterialProfiles.forTile(block.tileId)
            block.hp -= speed * 4.5f * profile.kineticMult
            if (block.hp <= 0f) {
                toDestroy.add(block)
            } else {
                // Surviving wall: compute true oriented OBB face normal for ricochet
                val localN = if (distSq > 1e-5f) {
                    diff.normalized()
                } else {
                    val dx = bhe.x - abs(localP.x)
                    val dy = bhe.y - abs(localP.y)
                    val dz = bhe.z - abs(localP.z)
                    when {
                        dy <= dx && dy <= dz -> Vec3(0f, if (localP.y >= 0f) 1f else -1f, 0f)
                        dx <= dz -> Vec3(if (localP.x >= 0f) 1f else -1f, 0f, 0f)
                        else -> Vec3(0f, 0f, if (localP.z >= 0f) 1f else -1f)
                    }
                }
                val pen = radius - kotlin.math.sqrt(distSq)
                val worldN = block.orientation.rotate(localN).normalized()
                if (pen > bestPen && vel.dot(worldN) < 0f) {
                    bestPen = pen
                    bestBounceNormal = worldN
                    bounced = true
                }
            }
        }

        // Also scatter & bounce off dynamic rubble shards
        val wokenShardIds = ArrayList<Int>()
        for (shard in shards) {
            val sHe = orientedHalfExtents(shard.poly.halfExtents, shard.orientation)
            val reach = sHe.len() + radius + 0.15f
            val delta = shard.center - pos
            if (delta.lenSq() > reach * reach) continue
            val d = delta.len()
            val nOut = if (d > 1e-4f) delta / d else vel.normalized()
            val shardMass = shard.massEstimate.coerceAtLeast(0.5f)
            setShardAwake(shard)
            shard.isGroundedContact = false
            shard.supportedByBlockId = null
            shard.supportedByShardId = null
            shard.settleTimer = 0f
            shard.vel += (nOut * (speed * 0.52f / shardMass.coerceAtLeast(0.8f))) + Vec3(0f, 2.2f, 0f)
            shard.angVel += nOut.cross(vel) * (0.28f / shardMass)
            wokenShardIds.add(shard.id)
            hitAny = true
            if (!bounced && shard.massEstimate >= 2.2f && vel.dot(-nOut) < 0f) {
                bestBounceNormal = -nOut
                bestPen = max(bestPen, 0.12f)
                bounced = true
            }
        }
        if (wokenShardIds.isNotEmpty()) {
            wakeDependentsOfShards(wokenShardIds)
        }

        for (block in toDestroy) {
            removeBlockInternal(block.id)
            collapsedCount++
            if (block.isFuelExplosive) {
                secondary.add(SecondaryBlastEvent(block.center, 5.8f, 1.0f, 18f))
            }
            spawnShatteredShards(
                block = block,
                impactWorld = pos,
                impulseDir = vel.normalized(),
                impulseSpeed = speed * 0.45f,
                pieces = clampShardPieces(block, isPrecision = false)
            )
        }
        if (toDestroy.isNotEmpty()) {
            checkStabilityAndCollapse(terrain)
            wakeShardsAroundRemoved(toDestroy.map { it.id }, pos, radius + 3.5f)
            structureVersion++
        }
        val corrPos = if (bounced) pos + bestBounceNormal * (bestPen + 0.04f) else null
        return KineticImpactResult(hitAny, bounced, bestBounceNormal, corrPos, secondary)
    }

    fun checkStabilityAndCollapse(terrain: ChunkedTerrain) {
        checkStabilityAndCollapseWithMetrics(terrain)
    }

    private fun materialLoadSafetyFactor(tileId: Int): Float = when (tileId) {
        MaterialTile.STEEL -> 3.15f
        MaterialTile.CONCRETE, MaterialTile.RIVER_STONE -> 2.45f
        MaterialTile.BRICK, MaterialTile.STUCCO, MaterialTile.ROOF_SLATE, MaterialTile.CORRUGATED_METAL -> 2.25f
        MaterialTile.TIMBER, MaterialTile.STAIR_TREAD -> 2.05f
        else -> 1.85f
    }

    private fun blockDeadMass(block: StructuralBlock): Float =
        estimateShardMass(block.poly, block.tileId)

    private fun propagateStructuralLoads(activeFilter: ((StructuralBlock) -> Boolean)? = null) {
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

    fun recomputeLoadBaseline() {
        if (blocks.isEmpty()) return
        propagateStructuralLoads()
        for (b in blocks.values) {
            val base = max(blockDeadMass(b), b.sustainedLoad)
            b.baselineLoad = base
            b.loadCapacity = base * materialLoadSafetyFactor(b.tileId)
        }
        loadBaselineVersion = structureVersion
    }

    private fun ensureLoadBaselineForNewBlocks() {
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

    private fun enqueuePendingCollapse(
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

    fun drainCollapseFailureEvents(): List<CollapseFailureEvent> {
        if (collapseFailureEvents.isEmpty()) return emptyList()
        val copy = ArrayList(collapseFailureEvents)
        collapseFailureEvents.clear()
        return copy
    }

    /**
     * Checks foundation undermining from craters, boolean support loss, and Geo-Mod style
     * load-stress redistribution. Small collapses detach immediately; massive multi-story
     * cascades and overloaded load-bearing members stagger sequentially for realistic buckling.
     */
    fun checkStabilityAndCollapseWithMetrics(
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

    private fun stepPendingCollapses(dt: Float, terrain: ChunkedTerrain): Int {
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

    /**
     * Flushes all queued progressive collapses immediately (used by tests needing instant final state).
     */
    fun flushPendingCollapses(terrain: ChunkedTerrain) {
        var guard = 0
        while (pendingCollapses.isNotEmpty() && guard < 360) {
            guard++
            for (i in pendingCollapses.indices) {
                pendingCollapses[i].delay = 0f
            }
            stepPendingCollapses(0.05f, terrain)
        }
    }

    fun wakeShardsInRadius(pos: Vec3, radius: Float) {
        val rSq = radius * radius
        val wokenIds = ArrayList<Int>()
        for (shard in shards) {
            if ((shard.center - pos).lenSq() <= rSq) {
                setShardAwake(shard)
                shard.isGroundedContact = false
                shard.supportedByBlockId = null
                shard.supportedByShardId = null
                shard.settleTimer = 0f
                wokenIds.add(shard.id)
            }
        }
        if (wokenIds.isNotEmpty()) wakeDependentsOfShards(wokenIds)
        joltWorld?.wakeAroundPoint(pos, radius + 0.5f)
    }

    private fun wakeShardsAroundRemoved(removedIds: List<Int>, center: Vec3, radius: Float) {
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

    private fun wakeOrphanedShards() {
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

    fun wakeDependentsOfShard(baseId: Int) {
        wakeDependentsOfShards(listOf(baseId))
    }

    private fun wakeDependentsOfShards(seedIds: List<Int>) {
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

    private fun isCantileverUnsupported(block: StructuralBlock): Boolean {
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

    private fun clampShardPieces(block: StructuralBlock, isPrecision: Boolean): Int {
        val profile = MaterialProfiles.forTile(block.tileId)
        val ext = block.poly.halfExtents.len()
        val basePieces = if (ext > 1.6f) max(profile.shardPieces, 14) else (profile.shardPieces - 1).coerceAtLeast(8)
        return if (isPrecision) {
            (basePieces / 2).coerceIn(6, 10)
        } else {
            basePieces.coerceIn(12, 20)
        }
    }

    private fun estimateShardMass(poly: ConvexPolyhedron, tileId: Int): Float {
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

    private fun computeCompoundInertiaLocal(pieces: List<ClusterPiece>): Vec3 {
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

    private fun computeClusterHullPoly(pieces: List<ClusterPiece>): ConvexPolyhedron {
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

    private fun detachWholeAsFallingShard(block: StructuralBlock, leanOrigin: Vec3? = null) {
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

        // Large slabs, roofs, walls & girders detach as Chaos-style Clustered Compound Shards
        // holding 2-3 pre-fractured sub-meter Voronoi pieces bonded by breakable strain thresholds.
        if (maxDim > 0.85f || he.len() > 1.15f) {
            val stressPieces = when {
                maxDim > 2.4f -> 7
                maxDim > 1.5f -> 5
                else -> 4
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
    private fun tryCarveLocalBreach(
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

    private fun spawnShatteredShards(
        block: StructuralBlock,
        impactWorld: Vec3,
        impulseDir: Vec3,
        impulseSpeed: Float,
        pieces: Int
    ) {
        val profile = MaterialProfiles.forTile(block.tileId)
        val localImpact = block.orientation.conjugate().rotate(impactWorld - block.center)
        val split = VoronoiPolyhedron.shatter(
            poly = block.poly,
            localImpact = localImpact,
            maxPieces = pieces,
            rng = rng,
            fractureStyle = profile.fractureStyle
        )
        for ((offset, piecePoly) in split) {
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
    }

    fun appendBakedDebris(out: FloatArrayBuilder) {
        out.appendFrom(bakedDebris)
    }

    /**
     * Recycles oldest settled tiny ground chips first so airborne debris NEVER freezes in mid-air.
     * Sleeping pieces are baked into the static rubble mesh instead of disappearing.
     */
    private fun trimShardPoolIfNeeded(incomingSizeHint: Float = 0.5f) {
        val evictedIds = ArrayList<Int>()
        var bakedAny = false
        if (bakedDebris.size > 120_000) bakedDebris.reset()
        while (shards.size >= MAX_TOTAL_SHARDS) {
            val tinySleepIdx = shards.indexOfFirst { it.sleeping && it.boundRadius < 0.45f }
            val sleepIdx = if (tinySleepIdx >= 0) tinySleepIdx else shards.indexOfFirst { it.sleeping }
            val rmIdx = if (sleepIdx >= 0) {
                sleepIdx
            } else {
                val smallIdx = shards.indexOfFirst { it.boundRadius < max(0.85f, incomingSizeHint) }
                if (smallIdx >= 0) smallIdx else 0
            }
            val removed = shards.removeAt(rmIdx)
            if (removed.sleeping && canBakeSettled(removed)) {
                emitShardTriangles(removed, bakedDebris)
                bakedAny = true
            }
            evictedIds.add(removed.id)
        }
        var activeCount = shards.count { !it.sleeping }
        while (activeCount >= MAX_ACTIVE_SHARDS) {
            val tinySleepIdx = shards.indexOfFirst { it.sleeping && it.boundRadius < 0.45f }
            val settledIdx = if (tinySleepIdx >= 0) tinySleepIdx else shards.indexOfFirst { it.sleeping }
            if (settledIdx >= 0) {
                val removed = shards.removeAt(settledIdx)
                if (canBakeSettled(removed)) {
                    emitShardTriangles(removed, bakedDebris)
                    bakedAny = true
                }
                evictedIds.add(removed.id)
                continue
            }
            val smallActiveIdx = shards.indexOfFirst { !it.sleeping && it.age > 0.40f && it.boundRadius < 0.90f }
            if (smallActiveIdx >= 0) {
                evictedIds.add(shards.removeAt(smallActiveIdx).id)
                activeCount--
            } else {
                break
            }
        }
        if (bakedAny) sleepingRubbleVersion++
        if (evictedIds.isNotEmpty()) {
            wakeDependentsOfShards(evictedIds)
        }
    }

    /** Chips that have been asleep are visual-only, so the live pool stays for pieces still moving. */
    private fun retireSettledChips(dt: Float) {
        var retiring: ArrayList<DynamicShard>? = null
        for (i in shards.indices) {
            val shard = shards[i]
            if (!shard.sleeping) {
                shard.asleepFor = 0f
                continue
            }
            shard.asleepFor += dt
            if (shard.asleepFor < 0.9f) continue
            if (shard.boundRadius >= 0.40f) continue
            if (shard.clusterPieces != null) continue
            if (!canBakeSettled(shard)) continue
            if (retiring == null) retiring = ArrayList()
            retiring.add(shard)
        }
        val batch = retiring ?: return
        if (bakedDebris.size > 120_000) bakedDebris.reset()
        val evictedIds = ArrayList<Int>(batch.size)
        for (shard in batch) {
            emitShardTriangles(shard, bakedDebris)
            evictedIds.add(shard.id)
        }
        shards.removeAll(batch.toSet())
        sleepingRubbleVersion++
        wakeDependentsOfShards(evictedIds)
    }

    private fun canBakeSettled(shard: DynamicShard): Boolean {
        val terrain = debrisTerrain ?: return false
        if (!shard.sleeping) return false
        val ground = terrain.groundSurfaceY(shard.center.x, shard.center.z)
        return shard.center.y - shard.boundRadius <= ground + 0.6f
    }

    /**
     * Jolt (and the custom integrator) will mark a whole contact island asleep even when that
     * island is still in the air. Those shards then get baked into the rubble mesh and hang
     * forever. Only a support path down to the terrain, or to a block that still stands, may stay asleep.
     */
    private fun releaseFloatingSleepers(terrain: ChunkedTerrain) {
        if (shards.isEmpty()) return

        val heldBlocks = HashSet<Int>()
        val blockQueue = ArrayDeque<Int>()
        val dependents = HashMap<Int, ArrayList<Int>>()
        for (block in blocks.values) {
            if (block.groundAnchored) {
                if (heldBlocks.add(block.id)) blockQueue.add(block.id)
            }
            for (sid in block.supportIds) {
                val list = dependents[sid]
                if (list == null) dependents[sid] = ArrayList<Int>().apply { add(block.id) }
                else list.add(block.id)
            }
        }
        while (blockQueue.isNotEmpty()) {
            val id = blockQueue.removeFirst()
            val deps = dependents[id] ?: continue
            for (dep in deps) {
                if (heldBlocks.add(dep)) blockQueue.add(dep)
            }
        }

        val grounded = BooleanArray(shards.size)
        val queue = ArrayDeque<Int>()
        for (i in shards.indices) {
            val shard = shards[i]
            val ground = terrain.groundSurfaceY(shard.center.x, shard.center.z)
            if (shard.center.y - shard.boundRadius <= ground + 0.6f) {
                grounded[i] = true
                queue.add(i)
            }
        }
        for (i in shards.indices) {
            if (grounded[i]) continue
            if (shardTouchesHeldBlock(shards[i], heldBlocks)) {
                grounded[i] = true
                queue.add(i)
            }
        }

        val cell = 4f
        val grid = HashMap<Long, ArrayList<Int>>()
        for (i in shards.indices) {
            val shard = shards[i]
            val key = shardCellKey(shard.center.x, shard.center.z, cell)
            val list = grid[key]
            if (list == null) grid[key] = ArrayList<Int>().apply { add(i) }
            else list.add(i)
        }

        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val base = shards[i]
            val cx = kotlin.math.floor(base.center.x / cell).toInt()
            val cz = kotlin.math.floor(base.center.z / cell).toInt()
            for (dx in -1..1) {
                for (dz in -1..1) {
                    val list = grid[shardCellKey((cx + dx) * cell, (cz + dz) * cell, cell)] ?: continue
                    for (j in list) {
                        if (grounded[j]) continue
                        val other = shards[j]
                        if (other.center.y + 0.2f < base.center.y) continue
                        val horiz = hypot(other.center.x - base.center.x, other.center.z - base.center.z)
                        val reach = base.boundRadius + other.boundRadius + 0.35f
                        if (horiz > reach) continue
                        val gap = (other.center.y - other.boundRadius) - (base.center.y + base.boundRadius)
                        if (gap > 0.4f) continue
                        grounded[j] = true
                        queue.add(j)
                    }
                }
            }
        }

        val wakeIds = ArrayList<Int>()
        var wokeAny = false
        for (i in shards.indices) {
            val shard = shards[i]
            if (!shard.sleeping || grounded[i]) continue
            if (shard.sleeping) {
                shard.sleeping = false
                wokeAny = true
            }
            shard.isGroundedContact = false
            shard.supportedByBlockId = null
            shard.supportedByShardId = null
            shard.settleTimer = 0f
            shard.asleepFor = 0f
            if (shard.vel.y > -0.35f) {
                shard.vel = Vec3(shard.vel.x, -1.4f, shard.vel.z)
            }
            wakeIds.add(shard.id)
        }
        if (wokeAny) sleepingRubbleVersion++
        if (wakeIds.isNotEmpty()) joltWorld?.wakeShardIds(wakeIds)
    }

    private fun shardCellKey(x: Float, z: Float, cell: Float): Long {
        val cx = kotlin.math.floor(x / cell).toInt()
        val cz = kotlin.math.floor(z / cell).toInt()
        return (cx.toLong() shl 32) xor (cz.toLong() and 0xffffffffL)
    }

    private fun shardTouchesHeldBlock(shard: DynamicShard, heldBlocks: Set<Int>): Boolean {
        val nearby = queryNearbyBlocks(shard.center.x, shard.center.z, shard.boundRadius + 2.5f)
        for (i in nearby.indices) {
            val block = nearby[i]
            if (block.id !in heldBlocks) continue
            val he = block.poly.halfExtents
            val dx = abs(block.center.x - shard.center.x) - he.x - shard.boundRadius
            val dz = abs(block.center.z - shard.center.z) - he.z - shard.boundRadius
            if (dx > 0.35f || dz > 0.35f) continue
            val gap = (shard.center.y - shard.boundRadius) - (block.center.y + he.y)
            if (gap <= 0.4f && shard.center.y + 0.15f >= block.center.y - he.y) return true
        }
        return false
    }

    private fun orientedHalfExtents(he: Vec3, orientation: Quat): Vec3 {
        val ax = orientation.rotate(Vec3(he.x, 0f, 0f))
        val ay = orientation.rotate(Vec3(0f, he.y, 0f))
        val az = orientation.rotate(Vec3(0f, 0f, he.z))
        return Vec3(
            abs(ax.x) + abs(ay.x) + abs(az.x),
            abs(ax.y) + abs(ay.y) + abs(az.y),
            abs(ax.z) + abs(ay.z) + abs(az.z)
        )
    }

    private data class LowestVertexContact(
        val minY: Float,
        val lowestX: Float,
        val lowestZ: Float,
        val arm: Vec3,
        val uniqueSupportCount: Int,
        val nextEdgeGap: Float = 0f
    )

    private fun flatGapTolerance(shard: DynamicShard): Float =
        max(0.042f, shard.poly.halfExtents.len() * 0.055f)

    /**
     * Detects any elongated shard (beam, plank, column, slab strip) tilted steeper than ~10.4 degrees
     * from the resting surface plane and returns the unit torque axis that pulls its raised end
     * straight down onto a flat resting face.
     */
    private fun tiltedElongatedTorqueDir(shard: DynamicShard, surfaceNormal: Vec3 = Vec3.UP): Vec3? {
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

    private fun applyInvInertiaWorld(invInertiaLocal: Vec3, orientation: Quat, worldVec: Vec3): Vec3 {
        val local = orientation.conjugate().rotate(worldVec)
        val scaled = Vec3(local.x * invInertiaLocal.x, local.y * invInertiaLocal.y, local.z * invInertiaLocal.z)
        return orientation.rotate(scaled)
    }

    private fun isCrushableMaterial(tileId: Int): Boolean = when (tileId) {
        MaterialTile.GLASS,
        MaterialTile.PLASTIC,
        MaterialTile.TIMBER,
        MaterialTile.AWNING_TRIM,
        MaterialTile.PINE_NEEDLE,
        MaterialTile.BRICK -> true
        else -> false
    }

    private fun computeTipTorque(
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

    private fun releaseClusterPieces(
        shard: DynamicShard,
        splitIds: HashSet<Int>,
        toRemove: ArrayList<DynamicShard>,
        secondarySplits: ArrayList<DynamicShard>
    ): Boolean {
        val pieces = shard.clusterPieces ?: return false
        if (!splitIds.add(shard.id)) return false
        toRemove.add(shard)
        val profile = MaterialProfiles.forTile(shard.tileId)
        collapseFailureEvents.add(CollapseFailureEvent(shard.center, shard.tileId, isClusterBreak = true))
        for (i in pieces.indices) {
            val piece = pieces[i]
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
                    invInertiaLocal = computeInvInertiaLocal(piece.poly.halfExtents, piece.mass)
                )
            )
        }
        return true
    }

    private fun queueSecondarySplit(
        shard: DynamicShard,
        localImpact: Vec3,
        pieces: Int,
        splitIds: HashSet<Int>,
        toRemove: ArrayList<DynamicShard>,
        secondarySplits: ArrayList<DynamicShard>
    ): Boolean {
        if (shard.clusterPieces != null) {
            return releaseClusterPieces(shard, splitIds, toRemove, secondarySplits)
        }
        if (!shard.canSecondarySplit || shard.splitDepth >= 2 || !splitIds.add(shard.id)) return false
        toRemove.add(shard)
        val nextDepth = shard.splitDepth + 1
        val style = MaterialProfiles.forTile(shard.tileId).fractureStyle
        val fractured = VoronoiPolyhedron.shatter(shard.poly, localImpact, pieces, rng, style)
        for ((off, subPoly) in fractured) {
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
                    canSecondarySplit = nextDepth < 2 && subPoly.boundRadius > 0.75f,
                    restitution = shard.restitution,
                    drag = shard.drag,
                    massEstimate = subMass,
                    uvLocalOffset = shard.uvLocalOffset + off,
                    splitDepth = nextDepth,
                    boundRadius = subPoly.boundRadius,
                    invInertiaLocal = computeInvInertiaLocal(subPoly.halfExtents, subMass)
                )
            )
        }
        return true
    }

    /**
     * Finds the true lowest world support contact across the rotated polyhedron (or compound cluster)
     * vertices, measuring flatness and support polygon along [surfaceNormal].
     */
    private fun computeLowestVertexContact(shard: DynamicShard, surfaceNormal: Vec3 = Vec3.UP): LowestVertexContact {
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

    private fun projectPolyRangeInto(
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
    private fun findPolySatContact(a: DynamicShard, b: DynamicShard): Boolean {
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

    fun stepShards(dt: Float, terrain: ChunkedTerrain): List<WaterSplashEvent> {
        debrisTerrain = terrain
        stepPendingCollapses(dt, terrain)
        val splashes = if (shards.isEmpty()) {
            emptyList()
        } else {
            val jolt = joltWorld
            if (jolt != null) {
                stepShardsWithJolt(jolt, dt, terrain)
            } else {
                val substeps = if (dt > 0.011f) 2 else 1
                val subDt = dt / substeps.toFloat()
                var combinedSplashes: ArrayList<WaterSplashEvent>? = null
                repeat(substeps) {
                    val stepSplashes = stepShardsSubstep(subDt, terrain)
                    if (stepSplashes.isNotEmpty()) {
                        var list = combinedSplashes
                        if (list == null) {
                            list = ArrayList(stepSplashes.size)
                            combinedSplashes = list
                        }
                        list.addAll(stepSplashes)
                    }
                }
                combinedSplashes ?: emptyList()
            }
        }
        releaseFloatingSleepers(terrain)
        retireSettledChips(dt)
        return splashes
    }

    private fun stepShardsWithJolt(
        jolt: JoltPhysicsWorld,
        dt: Float,
        terrain: ChunkedTerrain
    ): List<WaterSplashEvent> {
        val outcome = jolt.step(
            dt = dt,
            terrain = terrain,
            blocks = blocks,
            shards = shards,
            tiltedTipHelper = ::tiltedElongatedTorqueDir,
            onSleepChanged = { sleepingRubbleVersion++ }
        )

        val secondarySplits = ArrayList<DynamicShard>()
        val splitIds = HashSet<Int>()
        val toRemove = ArrayList<DynamicShard>()
        val crushedBlocks = ArrayList<StructuralBlock>()
        val shardMap = HashMap<Int, DynamicShard>(shards.size * 2 + 8)

        for (i in shards.indices) {
            val s = shards[i]
            shardMap[s.id] = s
            if (abs(s.center.x) > ChunkedTerrain.MAX_X - 1f || abs(s.center.z) > ChunkedTerrain.MAX_Z - 1f) {
                if (splitIds.add(s.id)) toRemove.add(s)
            }
        }

        for (ci in outcome.contacts.indices) {
            val ev = outcome.contacts[ci]
            val u1 = ev.userData1
            val u2 = ev.userData2

            if ((u1 < 0L && u2 == 0L) || (u2 < 0L && u1 == 0L)) {
                val sId = if (u1 < 0L) (-u1).toInt() else (-u2).toInt()
                val shard = shardMap[sId] ?: continue
                if (splitIds.contains(shard.id)) continue
                if (shard.clusterPieces != null && shard.age > 0.04f && ev.relNormalSpeed > 3.2f && secondarySplits.size < 28) {
                    releaseClusterPieces(shard, splitIds, toRemove, secondarySplits)
                } else if (shard.canSecondarySplit && shard.age > 0.05f && shard.boundRadius > 0.58f &&
                    ev.relNormalSpeed > 7.5f && secondarySplits.size < 28
                ) {
                    queueSecondarySplit(shard, Vec3(0f, -shard.poly.halfExtents.y, 0f), 5, splitIds, toRemove, secondarySplits)
                }
            } else if ((u1 < 0L && u2 > 0L) || (u2 < 0L && u1 > 0L)) {
                val sId = if (u1 < 0L) (-u1).toInt() else (-u2).toInt()
                val bId = if (u1 > 0L) u1.toInt() else u2.toInt()
                val shard = shardMap[sId] ?: continue
                val block = blocks[bId] ?: continue
                // Jolt normal points from Body 1 -> Body 2; normalOnShard points into shard
                val nyOnShard = if (u1 > 0L) ev.normalY else -ev.normalY

                val crushWeight = max(shard.massEstimate, shard.poly.volume * 0.55f)
                if (shard.age > 0.08f && nyOnShard > 0.45f && ev.relNormalSpeed > 3.0f &&
                    (shard.boundRadius > 0.65f || shard.massEstimate > 1.8f)
                ) {
                    val profile = MaterialProfiles.forTile(block.tileId)
                    block.hp -= max(1.2f, crushWeight) * ev.relNormalSpeed * 4.2f * profile.kineticMult
                    if (block.hp <= 0f && !crushedBlocks.contains(block)) {
                        crushedBlocks.add(block)
                    }
                }

                if (!splitIds.contains(shard.id)) {
                    if (shard.clusterPieces != null && shard.age >= 0.01f && ev.relNormalSpeed > 1.25f && secondarySplits.size < 28) {
                        releaseClusterPieces(shard, splitIds, toRemove, secondarySplits)
                    } else if (shard.canSecondarySplit && shard.age > 0.06f && shard.boundRadius > 0.58f &&
                        ev.relNormalSpeed > 7.0f && secondarySplits.size < 28
                    ) {
                        val localHit = shard.orientation.conjugate().rotate(block.center - shard.center)
                        queueSecondarySplit(shard, localHit, 4, splitIds, toRemove, secondarySplits)
                    }
                }
            } else if (u1 < 0L && u2 < 0L) {
                val a = shardMap[(-u1).toInt()] ?: continue
                val b = shardMap[(-u2).toInt()] ?: continue
                if (splitIds.contains(a.id) || splitIds.contains(b.id)) continue

                // Heavy-on-light shard crushing
                if (abs(ev.normalY) > 0.35f && ev.relNormalSpeed > 2.6f) {
                    val aCrushesB = a.massEstimate > b.massEstimate * 2.5f && isCrushableMaterial(b.tileId)
                    val bCrushesA = b.massEstimate > a.massEstimate * 2.5f && isCrushableMaterial(a.tileId)
                    if (aCrushesB) {
                        setShardAwake(b)
                        wakeDependentsOfShard(b.id)
                        if (b.canSecondarySplit && b.boundRadius > 0.42f && secondarySplits.size < 28) {
                            val localHit = b.orientation.conjugate().rotate(a.center - b.center)
                            if (queueSecondarySplit(b, localHit, 4, splitIds, toRemove, secondarySplits)) continue
                        } else if (b.boundRadius <= 0.42f && splitIds.add(b.id)) {
                            toRemove.add(b)
                            continue
                        }
                    } else if (bCrushesA) {
                        setShardAwake(a)
                        wakeDependentsOfShard(a.id)
                        if (a.canSecondarySplit && a.boundRadius > 0.42f && secondarySplits.size < 28) {
                            val localHit = a.orientation.conjugate().rotate(b.center - a.center)
                            if (queueSecondarySplit(a, localHit, 4, splitIds, toRemove, secondarySplits)) continue
                        } else if (a.boundRadius <= 0.42f && splitIds.add(a.id)) {
                            toRemove.add(a)
                            continue
                        }
                    }
                }

                // Compound cluster strain break or secondary Voronoi shatter on high-speed shard-on-shard impact
                if (secondarySplits.size < 28) {
                    if (a.clusterPieces != null && a.age > 0.05f && ev.relNormalSpeed > 3.6f) {
                        if (releaseClusterPieces(a, splitIds, toRemove, secondarySplits)) continue
                    } else if (b.clusterPieces != null && b.age > 0.05f && ev.relNormalSpeed > 3.6f) {
                        setShardAwake(b)
                        if (releaseClusterPieces(b, splitIds, toRemove, secondarySplits)) continue
                    } else if (ev.relNormalSpeed > 4.6f) {
                        if (a.canSecondarySplit && a.age > 0.06f && a.boundRadius > 0.58f) {
                            val localHit = a.orientation.conjugate().rotate(b.center - a.center)
                            if (queueSecondarySplit(a, localHit, 4, splitIds, toRemove, secondarySplits)) continue
                        } else if (b.canSecondarySplit && b.age > 0.06f && b.boundRadius > 0.58f) {
                            setShardAwake(b)
                            val localHit = b.orientation.conjugate().rotate(a.center - b.center)
                            if (queueSecondarySplit(b, localHit, 4, splitIds, toRemove, secondarySplits)) continue
                        }
                    }
                }
            }
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

        return outcome.splashes
    }

    private fun stepShardsSubstep(dt: Float, terrain: ChunkedTerrain): List<WaterSplashEvent> {
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
            if (!s.sleeping) activeShardCount++
            shardWorldHeScratch.add(orientedHalfExtents(s.poly.halfExtents, s.orientation))
        }

        // Phase 1: Integrate gravity, orientation, intact 6-face OBB collision, and oriented terrain/crater contact
        for (i in shards.indices) {
            val shard = shards[i]
            shard.age += dt
            if (shard.sleeping) continue

            shard.isGroundedContact = false
            val wasAboveWater = shard.center.y > ChunkedTerrain.WATER_LEVEL
            val airDrag = (1f - shard.drag * 0.07f * dt).coerceIn(0.86f, 1f)
            shard.vel = Vec3(
                shard.vel.x * airDrag,
                shard.vel.y + GRAVITY * dt,
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
                        if ((a.sleeping && b.sleeping) || splitIds.contains(a.id) || splitIds.contains(b.id)) continue

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
            if (shard.sleeping || splitIds.contains(shard.id)) continue
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
        return splashes
    }

    fun raycastBlocks(origin: Vec3, dir: Vec3, maxDist: Float = 165f): BlockRayHit? {
        var best: BlockRayHit? = null
        var bestT = maxDist

        for (block in blocks.values) {
            val invQ = block.orientation.conjugate()
            val localOrig = invQ.rotate(origin - block.center)
            val localDir = invQ.rotate(dir)
            val he = block.poly.halfExtents

            val hit = intersectRayBox(localOrig, localDir, he, bestT)
            if (hit != null) {
                val (t, localN) = hit
                if (t < bestT) {
                    bestT = t
                    val worldPt = origin + dir * t
                    val worldN = block.orientation.rotate(localN).normalized()
                    best = BlockRayHit(block.id, worldPt, worldN, t)
                }
            }
        }
        return best
    }

    fun raycastShards(origin: Vec3, dir: Vec3, maxDist: Float = 165f): ShardRayHit? {
        var best: ShardRayHit? = null
        var bestT = maxDist

        for (shard in shards) {
            val sHe = orientedHalfExtents(shard.poly.halfExtents, shard.orientation)
            val boundR = sHe.len() + 0.1f
            val toCenter = shard.center - origin
            val proj = toCenter.dot(dir)
            if (proj < -boundR || proj > bestT + boundR) continue

            val invQ = shard.orientation.conjugate()
            val localOrig = invQ.rotate(origin - shard.center)
            val localDir = invQ.rotate(dir)
            val hit = intersectRayBox(localOrig, localDir, shard.poly.halfExtents, bestT)
            if (hit != null) {
                val (t, localN) = hit
                if (t < bestT) {
                    bestT = t
                    val worldPt = origin + dir * t
                    val worldN = shard.orientation.rotate(localN).normalized()
                    best = ShardRayHit(shard.id, worldPt, worldN, t)
                }
            }
        }
        return best
    }

    /**
     * Resolves multi-story floors, sloped staircases/ramps (using rotated OBB surface projection),
     * ceiling head clearance, and wall push-outs so every building floor & rooftop is climbable.
     */
    fun resolveCharacterSupport(pos: Vec3, radius: Float, height: Float): Pair<Vec3, Float?> {
        var p = pos
        var standY: Float? = null
        val stepUpLimit = 0.76f
        val downRayOriginOffset = stepUpLimit
        val downDir = Vec3(0f, -1f, 0f)

        val nearbyBlocks = queryNearbyBlocks(p.x, p.z, radius + maxBlockExtent + 0.65f)

        // Pass 1: Find highest walkable surface beneath or within step-up reach of player's feet
        for (bi in nearbyBlocks.indices) {
            val block = nearbyBlocks[bi]
            if (!block.walkable) continue
            val he = block.poly.halfExtents
            val maxExt = he.len() + radius + 0.5f
            if (abs(p.x - block.center.x) > maxExt || abs(p.z - block.center.z) > maxExt) continue

            val invQ = block.orientation.conjugate()
            val rayStart = Vec3(p.x, p.y + downRayOriginOffset, p.z)
            val localOrig = invQ.rotate(rayStart - block.center)
            val localDir = invQ.rotate(downDir)

            // Expand X/Z slightly for comfortable footing along stair edges
            val footHe = Vec3(he.x + radius * 0.45f, he.y, he.z + radius * 0.45f)
            val hit = intersectRayBox(localOrig, localDir, footHe, maxDist = 2.0f, minT = 0.0f)
            if (hit != null) {
                val (t, localN) = hit
                val worldN = block.orientation.rotate(localN).normalized()
                if (worldN.y > 0.42f) {
                    val surfaceY = rayStart.y - t
                    if (surfaceY <= p.y + stepUpLimit && surfaceY >= p.y - 1.25f) {
                        standY = if (standY == null) surfaceY else max(standY, surfaceY)
                    }
                }
            }
        }

        // Pass 2: Wall horizontal push-out and overhead ceiling head stop
        val bodyMidOffset = 0.92f
        for (bi in nearbyBlocks.indices) {
            val block = nearbyBlocks[bi]
            val he = block.poly.halfExtents
            val maxExt = he.len() + radius + 0.35f
            if (abs(p.x - block.center.x) > maxExt || abs(p.z - block.center.z) > maxExt) continue

            val invQ = block.orientation.conjugate()
            val upAxis = block.orientation.rotate(Vec3.UP)
            val isTiltedRamp = abs(upAxis.y) in 0.40f..0.96f
            if (isTiltedRamp) continue // Never push sideways out of stair ramps

            val footProbe = invQ.rotate(Vec3(p.x, p.y + 0.25f, p.z) - block.center)
            val headProbe = invQ.rotate(Vec3(p.x, p.y + height - 0.12f, p.z) - block.center)
            val midProbe = invQ.rotate(Vec3(p.x, p.y + bodyMidOffset, p.z) - block.center)

            val topY = block.center.y + he.y
            val botY = block.center.y - he.y

            // If surface is low enough to step onto (curb, plinth, low sill), skip horizontal wall push
            if (topY <= p.y + stepUpLimit && abs(upAxis.y) > 0.95f) continue

            // Overhead ceiling check (prevent jumping head straight through 2nd-story slab)
            if (abs(headProbe.x) < he.x + radius * 0.5f && abs(headProbe.z) < he.z + radius * 0.5f) {
                if (p.y + height > botY + 0.02f && p.y < botY - 0.35f && topY > p.y + height * 0.65f) {
                    p = Vec3(p.x, botY - height - 0.02f, p.z)
                }
            }

            // Horizontal side-wall push-out when overlapping vertical range of wall/column
            val inVertRange = (p.y + height > botY + 0.22f) && (p.y < topY - 0.22f)
            if (!inVertRange) continue

            val overlapX = (he.x + radius) - abs(midProbe.x)
            val overlapZ = (he.z + radius) - abs(midProbe.z)
            if (overlapX > 0f && overlapZ > 0f &&
                abs(footProbe.y) <= he.y + height * 0.5f
            ) {
                val localPush = if (overlapX < overlapZ) {
                    Vec3(if (midProbe.x >= 0f) overlapX else -overlapX, 0f, 0f)
                } else {
                    Vec3(0f, 0f, if (midProbe.z >= 0f) overlapZ else -overlapZ)
                }
                val worldPush = block.orientation.rotate(localPush)
                p = Vec3(p.x + worldPush.x, p.y, p.z + worldPush.z)
            }
        }

        return p to standY
    }

    private fun intersectRayBox(
        orig: Vec3,
        dir: Vec3,
        he: Vec3,
        maxDist: Float,
        minT: Float = 0.05f
    ): Pair<Float, Vec3>? {
        var tMin = minT
        var tMax = maxDist
        var hitNormal = Vec3(0f, 1f, 0f)

        // X slab
        if (abs(dir.x) < 1e-6f) {
            if (orig.x < -he.x || orig.x > he.x) return null
        } else {
            val invD = 1f / dir.x
            var t1 = (-he.x - orig.x) * invD
            var t2 = (he.x - orig.x) * invD
            var n = Vec3(-1f, 0f, 0f)
            if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp; n = Vec3(1f, 0f, 0f) }
            if (t1 > tMin) { tMin = t1; hitNormal = n }
            tMax = min(tMax, t2)
            if (tMin > tMax) return null
        }

        // Y slab
        if (abs(dir.y) < 1e-6f) {
            if (orig.y < -he.y || orig.y > he.y) return null
        } else {
            val invD = 1f / dir.y
            var t1 = (-he.y - orig.y) * invD
            var t2 = (he.y - orig.y) * invD
            var n = Vec3(0f, -1f, 0f)
            if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp; n = Vec3(0f, 1f, 0f) }
            if (t1 > tMin) { tMin = t1; hitNormal = n }
            tMax = min(tMax, t2)
            if (tMin > tMax) return null
        }

        // Z slab
        if (abs(dir.z) < 1e-6f) {
            if (orig.z < -he.z || orig.z > he.z) return null
        } else {
            val invD = 1f / dir.z
            var t1 = (-he.z - orig.z) * invD
            var t2 = (he.z - orig.z) * invD
            var n = Vec3(0f, 0f, -1f)
            if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp; n = Vec3(0f, 0f, 1f) }
            if (t1 > tMin) { tMin = t1; hitNormal = n }
            tMax = min(tMax, t2)
            if (tMin > tMax) return null
        }

        return tMin to hitNormal
    }

    fun emitShardTriangles(
        shard: DynamicShard,
        out: FloatArrayBuilder,
        alpha: Float = 1f
    ) {
        val pieces = shard.clusterPieces
        if (pieces == null) {
            emitPolyhedronTriangles(
                poly = shard.poly,
                center = shard.center,
                orientation = shard.orientation,
                uvOrigin = shard.uvOrigin,
                tileId = shard.tileId,
                tint = shard.tint,
                out = out,
                alpha = alpha,
                uvLocalOffset = shard.uvLocalOffset
            )
            return
        }
        for (i in pieces.indices) {
            val piece = pieces[i]
            val worldPieceCenter = shard.center + shard.orientation.rotate(piece.localOffset)
            emitPolyhedronTriangles(
                poly = piece.poly,
                center = worldPieceCenter,
                orientation = shard.orientation,
                uvOrigin = shard.uvOrigin,
                tileId = shard.tileId,
                tint = shard.tint,
                out = out,
                alpha = alpha,
                uvLocalOffset = piece.uvLocalOffset
            )
        }
    }

    /**
     * Appends triangulated lit vertex data (13 floats/vert) for a polyhedron using object-local
     * triplanar UV projection anchored by [uvLocalOffset] so textures stay locked onto moving,
     * rotating, and fractured objects with zero heap allocations.
     */
    fun emitPolyhedronTriangles(
        poly: ConvexPolyhedron,
        center: Vec3,
        orientation: Quat,
        uvOrigin: Vec3,
        tileId: Int,
        tint: Vec3,
        out: FloatArrayBuilder,
        alpha: Float = 1f,
        uvLocalOffset: Vec3 = Vec3.ZERO
    ) {
        val tileF = tileId.toFloat()
        val scale = 0.42f
        val ox = uvLocalOffset.x
        val oy = uvLocalOffset.y
        val oz = uvLocalOffset.z
        val faces = poly.faces
        for (fi in faces.indices) {
            val face = faces[fi]
            val verts = face.vertices
            if (verts.size < 3) continue
            val ln = face.normal
            val ax = abs(ln.x)
            val ay = abs(ln.y)
            val az = abs(ln.z)
            val planeMode = when {
                ay >= ax && ay >= az -> 0
                ax >= az -> 1
                else -> 2
            }

            val wn = orientation.rotate(ln).normalized()
            val vNorms = face.vertexNormals
            val hasSmooth = vNorms != null && vNorms.size == verts.size
            val wn0 = if (hasSmooth) orientation.rotate(vNorms!![0]).normalized() else wn
            val lv0 = verts[0]
            val v0 = center + orientation.rotate(lv0)
            val lx0 = ox + lv0.x
            val ly0 = oy + lv0.y
            val lz0 = oz + lv0.z
            val u0: Float
            val tv0: Float
            when (planeMode) {
                0 -> { u0 = lx0 * scale; tv0 = lz0 * scale }
                1 -> { u0 = lz0 * scale; tv0 = -ly0 * scale }
                else -> { u0 = lx0 * scale; tv0 = -ly0 * scale }
            }

            for (i in 1 until verts.size - 1) {
                val lv1 = verts[i]
                val lv2 = verts[i + 1]
                val v1 = center + orientation.rotate(lv1)
                val v2 = center + orientation.rotate(lv2)
                val wn1 = if (hasSmooth) orientation.rotate(vNorms!![i]).normalized() else wn
                val wn2 = if (hasSmooth) orientation.rotate(vNorms!![i + 1]).normalized() else wn

                val lx1 = ox + lv1.x; val ly1 = oy + lv1.y; val lz1 = oz + lv1.z
                val lx2 = ox + lv2.x; val ly2 = oy + lv2.y; val lz2 = oz + lv2.z
                val u1: Float; val tv1: Float
                val u2: Float; val tv2: Float
                when (planeMode) {
                    0 -> {
                        u1 = lx1 * scale; tv1 = lz1 * scale
                        u2 = lx2 * scale; tv2 = lz2 * scale
                    }
                    1 -> {
                        u1 = lz1 * scale; tv1 = -ly1 * scale
                        u2 = lz2 * scale; tv2 = -ly2 * scale
                    }
                    else -> {
                        u1 = lx1 * scale; tv1 = -ly1 * scale
                        u2 = lx2 * scale; tv2 = -ly2 * scale
                    }
                }

                out.emitVertex(v0, wn0, tint, u0, tv0, tileF, alpha)
                out.emitVertex(v1, wn1, tint, u1, tv1, tileF, alpha)
                out.emitVertex(v2, wn2, tint, u2, tv2, tileF, alpha)
            }
        }
    }
}

