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

/** Active-body cap, ground baking, and waking debris that is still in the air.
 */

    /**
     * Recycles oldest settled tiny ground chips first so airborne debris NEVER freezes in mid-air.
     * Sleeping pieces are baked into the static rubble mesh instead of disappearing.
     */
internal fun StructuralWorld.trimShardPoolIfNeeded(incomingSizeHint: Float = 0.5f) {
        val evictedIds = ArrayList<Int>()
        var bakedAny = false
        if (bakedDebris.size > 120_000) bakedDebris.reset()
        while (shards.size >= StructuralWorld.MAX_TOTAL_SHARDS) {
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
        while (activeCount >= StructuralWorld.MAX_ACTIVE_SHARDS) {
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

    /**
     * The active cap used to give up once every piece was large and still moving, so a
     * downtown collapse sat at 375 awake bodies. Sleep whatever has actually landed, and
     * drop the smallest airborne chips until the solver is back inside the budget.
     * Slabs big enough to read as building parts are kept.
     */
internal fun StructuralWorld.shedAwakeOverflow(terrain: ChunkedTerrain) {
        var awake = awakeShardCount()
        if (awake <= StructuralWorld.MAX_ACTIVE_SHARDS) return

        val order = ArrayList<Int>(shards.size)
        for (i in shards.indices) order.add(i)
        order.sortBy { shards[it].boundRadius }

        val sleepIds = ArrayList<Int>()
        for (i in order) {
            if (awake <= StructuralWorld.MAX_ACTIVE_SHARDS) break
            val shard = shards[i]
            if (shard.sleeping || shard.age < 0.12f) continue
            if (shard.vel.lenSq() > 6f || shard.angVel.lenSq() > 8f) continue
            val ground = terrain.groundSurfaceY(shard.center.x, shard.center.z)
            if (shard.center.y - shard.boundRadius > ground + 0.6f) continue
            shard.sleeping = true
            shard.vel = Vec3.ZERO
            shard.angVel = Vec3.ZERO
            shard.isGroundedContact = true
            shard.asleepFor = 0f
            sleepIds.add(shard.id)
            awake--
        }

        val dropped = ArrayList<Int>()
        if (awake > StructuralWorld.MAX_ACTIVE_SHARDS) {
            val remove = ArrayList<DynamicShard>()
            for (i in order) {
                if (awake <= StructuralWorld.MAX_ACTIVE_SHARDS) break
                val shard = shards[i]
                if (shard.sleeping || shard.clusterPieces != null) continue
                if (shard.boundRadius >= 0.55f || shard.age < 0.20f) continue
                remove.add(shard)
                dropped.add(shard.id)
                awake--
            }
            if (remove.isNotEmpty()) shards.removeAll(remove.toSet())
        }

        if (sleepIds.isNotEmpty()) {
            sleepingRubbleVersion++
            joltWorld?.sleepShardIds(sleepIds)
        }
        if (dropped.isNotEmpty()) wakeDependentsOfShards(dropped)
    }

    /** Chips that have been asleep are visual-only, so the live pool stays for pieces still moving. */
internal fun StructuralWorld.retireSettledChips(dt: Float) {
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

internal fun StructuralWorld.canBakeSettled(shard: DynamicShard): Boolean {
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
internal fun StructuralWorld.releaseFloatingSleepers(terrain: ChunkedTerrain) {
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

        val wakeIds = ArrayList<Int>()
        var wokeAny = false
        for (i in shards.indices) {
            val shard = shards[i]
            if (!shard.sleeping) continue
            val ground = terrain.groundSurfaceY(shard.center.x, shard.center.z)
            val tooHigh = shard.center.y > ground + shard.poly.halfExtents.len() + 0.85f
            if (!tooHigh || shardTouchesHeldBlock(shard, heldBlocks)) continue
            shard.sleeping = false
            wokeAny = true
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

internal fun StructuralWorld.shardTouchesHeldBlock(shard: DynamicShard, heldBlocks: Set<Int>): Boolean {
        val nearby = queryNearbyBlocks(shard.center.x, shard.center.z, shard.boundRadius + 2.5f)
        for (i in nearby.indices) {
            val block = nearby[i]
            if (block.id !in heldBlocks) continue
            val he = block.poly.halfExtents
            val dx = abs(block.center.x - shard.center.x) - he.x
            val dz = abs(block.center.z - shard.center.z) - he.z
            if (dx > 0.3f || dz > 0.3f) continue
            val gap = (shard.center.y - min(shard.boundRadius, shard.poly.halfExtents.y)) - (block.center.y + he.y)
            if (gap <= 0.45f && gap >= -0.35f) return true
        }
        return false
    }
