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
        const val MAX_TOTAL_SHARDS = 720
        /** Full rigid bodies. Extra pieces past this are ballistic so a wall can break into chunks. */
        const val SHATTER_CEILING = 140
        const val MAX_BALLISTIC = 420
        const val GRAVITY = -18.5f
        const val SECTOR_SIZE = 64f
        const val SECTORS_X = 8
        const val SECTORS_Z = 6
        const val SECTOR_COUNT = SECTORS_X * SECTORS_Z
        internal const val BLOCK_CELL_SIZE = 4.0f
        internal const val INV_BLOCK_CELL = 1f / BLOCK_CELL_SIZE

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
        internal set
    val sectorVersions = IntArray(SECTOR_COUNT) { 1 }
    var sleepingRubbleVersion = 1
        internal set
    var collapsedCount = 0
        internal set

    internal var nextId = 1
    internal val rng = Random(918273)

    // Broad-phase 2D spatial hash grid for intact blocks (rebuilt only when structureVersion changes)
    internal val blockCellGrid = HashMap<Long, ArrayList<StructuralBlock>>(512)
    internal var cachedBlockGridVersion = -1
    internal var maxBlockExtent = 2.5f
    internal var blockSeenStamp = IntArray(4096)
    internal var blockQueryStampGen = 0
    internal val nearbyBlocksScratch = ArrayList<StructuralBlock>(24)

    // Zero-allocation scratch buffers for stepShards & computeLowestVertexContact
    internal val contactUniqX = FloatArray(64)
    internal val contactUniqY = FloatArray(64)
    internal val contactUniqZ = FloatArray(64)
    internal val shardWorldHeScratch = ArrayList<Vec3>(MAX_TOTAL_SHARDS)
    internal val shardById = HashMap<Int, DynamicShard>(512)
    internal val phase2Grid = HashMap<Long, ArrayList<Int>>(512)
    internal val phase2ActiveKeys = ArrayList<Long>(256)
    internal val phase2BucketPool = ArrayList<ArrayList<Int>>(256)
    internal val phase2SeenPairs = HashSet<Long>(512)
    internal var satNormalX = 0f
    internal var satNormalY = 1f
    internal var satNormalZ = 0f
    internal var satPen = 0f
    internal var projMinOut = 0f
    internal var projMaxOut = 0f
    internal val manifoldScratch = ManifoldScratch()

    // Staggered sequential collapse & load-stress state
    val pendingCollapses = ArrayList<PendingCollapseNode>(64)
    internal val pendingBlockIds = HashSet<Int>(64)
    internal val collapseFailureEvents = ArrayList<CollapseFailureEvent>(32)
    internal var loadBaselineVersion = -1
    internal val joltWorld: JoltPhysicsWorld? =
        if (allowJoltPhysics && JoltRuntime.isAvailable) JoltPhysicsWorld() else null
    internal val bakedDebris = FloatArrayBuilder(16384)
    internal var debrisTerrain: ChunkedTerrain? = null
    internal var reservedFractures = 0

    val isUsingJoltPhysics: Boolean get() = joltWorld != null

    internal fun blockCellKey(cx: Int, cz: Int): Long =
        (cx.toLong() shl 32) xor (cz.toLong() and 0xFFFFFFFFL)

    internal fun ensureBlockGrid() {
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

    internal fun queryNearbyBlocks(x: Float, z: Float, radiusXZ: Float): ArrayList<StructuralBlock> {
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

    internal fun setShardAwake(shard: DynamicShard) {
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

    internal fun markSectorDirty(x: Float, z: Float) {
        val idx = sectorIndexFor(x, z)
        sectorVersions[idx]++
    }

    internal fun removeBlockInternal(id: Int): StructuralBlock? {
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

    internal fun worldHalfExtents(b: StructuralBlock): Vec3 {
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
        shedAwakeOverflow(terrain)
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
        shedAwakeOverflow(terrain)
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


    fun drainCollapseFailureEvents(): List<CollapseFailureEvent> {
        if (collapseFailureEvents.isEmpty()) return emptyList()
        val copy = ArrayList(collapseFailureEvents)
        collapseFailureEvents.clear()
        return copy
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


    fun wakeDependentsOfShard(baseId: Int) {
        wakeDependentsOfShards(listOf(baseId))
    }


    fun appendBakedDebris(out: FloatArrayBuilder) {
        out.appendFrom(bakedDebris)
    }


    fun stepShards(dt: Float, terrain: ChunkedTerrain): List<WaterSplashEvent> {
        debrisTerrain = terrain
        reservedFractures = 0
        stepPendingCollapses(dt, terrain)
        releaseMatureClusters()
        shedAwakeOverflow(terrain)
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
        stepBallisticShards(dt, terrain)
        shedAwakeOverflow(terrain)
        releaseFloatingSleepers(terrain)
        retireSettledChips(dt)
        return splashes
    }

    internal fun stepShardsWithJolt(
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
        reservedFractures = 0

        return outcome.splashes
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

    internal fun intersectRayBox(
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

