package com.example.engine.physics

import com.example.engine.terrain.ChunkedTerrain
import com.example.model.BuildMaterial
import com.example.model.MaterialTile
import com.example.model.Quat
import com.example.model.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object ArchitecturalPrimitives {

    /**
     * Anchors a terrain-graded foundation plinth deep enough to sit flush on slopes,
     * plus a gently sloped entry ramp on the doorway approach so the player can walk inside smoothly.
     */
    fun buildGradedFoundation(
        world: StructuralWorld,
        terrain: ChunkedTerrain,
        cx: Float,
        cz: Float,
        halfW: Float,
        halfD: Float,
        entryOffset: Vec3,
        entryHalfW: Float = 1.2f,
        tileId: Int = MaterialTile.CONCRETE,
        tint: Vec3 = Vec3(0.78f, 0.78f, 0.76f),
        targetTag: String? = null
    ): Pair<List<Int>, Float> {
        val corners = listOf(
            terrain.groundSurfaceY(cx - halfW, cz - halfD),
            terrain.groundSurfaceY(cx + halfW, cz - halfD),
            terrain.groundSurfaceY(cx - halfW, cz + halfD),
            terrain.groundSurfaceY(cx + halfW, cz + halfD),
            terrain.groundSurfaceY(cx, cz)
        )
        val minGy = corners.minOrNull() ?: terrain.groundSurfaceY(cx, cz)
        val maxGy = corners.maxOrNull() ?: minGy
        val floorTopY = maxGy + 0.34f

        // 2x2 foundation quadrants so craters/blasts undermine and collapse individual corners cleanly
        val padHalfW = (halfW + 0.3f) * 0.5f + 0.04f
        val padHalfD = (halfD + 0.3f) * 0.5f + 0.04f
        val foundIds = ArrayList<Int>(4)
        for (sx in listOf(-1f, 1f)) {
            for (sz in listOf(-1f, 1f)) {
                val px = cx + sx * (halfW + 0.3f) * 0.5f
                val pz = cz + sz * (halfD + 0.3f) * 0.5f
                val localMin = min(
                    terrain.groundSurfaceY(px, pz),
                    min(
                        terrain.groundSurfaceY(px - padHalfW * 0.65f, pz - padHalfD * 0.65f),
                        terrain.groundSurfaceY(px + padHalfW * 0.65f, pz + padHalfD * 0.65f)
                    )
                )
                val bottomY = min(localMin, minGy) - 0.18f
                val padH = max(0.54f, floorTopY - bottomY)
                foundIds.add(
                    world.addBlock(
                        poly = VoronoiPolyhedron.createBox(padHalfW, padH * 0.5f, padHalfD),
                        center = Vec3(px, floorTopY - padH * 0.5f, pz),
                        tileId = tileId,
                        tint = tint,
                        hp = 540f,
                        groundAnchored = true,
                        isCoreSupport = true,
                        targetTag = targetTag,
                        pointValue = 20
                    )
                )
            }
        }

        // Entry ramp outside front door down to surrounding terrain
        val entryWorld = Vec3(cx + entryOffset.x, floorTopY, cz + entryOffset.z)
        val outDir = Vec3(entryOffset.x, 0f, entryOffset.z).normalized()
        val endPt = entryWorld + outDir * 1.45f
        val endGy = terrain.groundSurfaceY(endPt.x, endPt.z) - 0.05f
        if (floorTopY - endGy > 0.10f) {
            buildStairFlight(
                world = world,
                start = Vec3(endPt.x, endGy, endPt.z),
                end = Vec3(entryWorld.x, floorTopY, entryWorld.z),
                halfWidth = entryHalfW,
                tileId = MaterialTile.CONCRETE,
                tint = Vec3(0.82f, 0.82f, 0.80f),
                hp = 120f,
                groundAnchored = true,
                topSupports = foundIds,
                targetTag = targetTag,
                withRails = false
            )
        }
        return foundIds to floorTopY
    }

    /**
     * Places high-HP Structural Steel / Iron corner & intermediate columns supporting upper slabs.
     */
    fun buildSteelCoreColumns(
        world: StructuralWorld,
        colPositions: List<Pair<Float, Float>>,
        baseY: Float,
        height: Float,
        halfThick: Float = 0.22f,
        hp: Float = 640f,
        groundAnchored: Boolean,
        supportIds: List<Int>,
        targetTag: String? = null
    ): List<Int> {
        val ids = ArrayList<Int>(colPositions.size)
        for ((x, z) in colPositions) {
            ids.add(
                world.addBlock(
                    poly = VoronoiPolyhedron.createBox(halfThick, height * 0.5f, halfThick),
                    center = Vec3(x, baseY + height * 0.5f, z),
                    tileId = MaterialTile.STEEL,
                    tint = Vec3(0.50f, 0.54f, 0.60f),
                    hp = hp,
                    groundAnchored = groundAnchored,
                    supportIds = supportIds.toMutableList(),
                    isCoreSupport = true,
                    targetTag = targetTag,
                    pointValue = 35
                )
            )
        }
        return ids
    }

    /**
     * Builds a walkable multi-story floor slab split around an open rectangular stairwell cutout
     * `(holeMinX..holeMaxX, holeMinZ..holeMaxZ)` so climbing stairs leads unobstructed onto the upper floor.
     */
    fun buildSegmentedFloorWithCutout(
        world: StructuralWorld,
        cx: Float,
        cz: Float,
        centerY: Float,
        halfW: Float,
        halfD: Float,
        halfThick: Float,
        holeMinX: Float,
        holeMaxX: Float,
        holeMinZ: Float,
        holeMaxZ: Float,
        tileId: Int,
        tint: Vec3,
        hp: Float,
        supports: List<Int>,
        targetTag: String? = null
    ): List<Int> {
        val minX = cx - halfW
        val maxX = cx + halfW
        val minZ = cz - halfD
        val maxZ = cz + halfD

        val clampedHoleMinX = holeMinX.coerceIn(minX + 0.4f, maxX - 0.8f)
        val clampedHoleMaxX = holeMaxX.coerceIn(clampedHoleMinX + 0.6f, maxX - 0.25f)
        val clampedHoleMinZ = holeMinZ.coerceIn(minZ + 0.4f, maxZ - 0.8f)
        val clampedHoleMaxZ = holeMaxZ.coerceIn(clampedHoleMinZ + 0.6f, maxZ - 0.25f)

        // Identify X positions of discrete interior support columns so wide bays only split over real supports
        val splitCandidatesX = supports.mapNotNull { sid ->
            val b = world.blocks[sid] ?: return@mapNotNull null
            if (b.poly.halfExtents.x <= 0.65f) b.center.x else null
        }.distinct().sorted()

        fun splitAlongX(rx0: Float, rx1: Float): List<Pair<Float, Float>> {
            val spanX = rx1 - rx0
            if (spanX <= 4.4f) return listOf(rx0 to rx1)
            val mid = (rx0 + rx1) * 0.5f
            val splitX = splitCandidatesX
                .filter { it > rx0 + 1.25f && it < rx1 - 1.25f }
                .minByOrNull { abs(it - mid) }
                ?: return listOf(rx0 to rx1)
            return listOf(rx0 to splitX, splitX to rx1)
        }

        val slabIds = ArrayList<Int>(8)
        val leftBayIds = ArrayList<Int>(2)
        val rightBayIds = ArrayList<Int>(2)

        // 1. Left strip across full depth (never split across Z where no center columns exist)
        if (clampedHoleMinX - minX > 0.25f) {
            for ((bx0, bx1) in splitAlongX(minX, clampedHoleMinX)) {
                val hx = (bx1 - bx0) * 0.5f
                val hz = (maxZ - minZ) * 0.5f
                val id = world.addBlock(
                    poly = VoronoiPolyhedron.createBox(hx, halfThick, hz),
                    center = Vec3((bx0 + bx1) * 0.5f, centerY, (minZ + maxZ) * 0.5f),
                    tileId = tileId,
                    tint = tint,
                    hp = hp,
                    supportIds = supports.toMutableList(),
                    targetTag = targetTag,
                    pointValue = 22,
                    checkCantilever = true
                )
                slabIds.add(id)
                leftBayIds.add(id)
            }
        }

        // 2. Right strip across full depth
        if (maxX - clampedHoleMaxX > 0.25f) {
            for ((bx0, bx1) in splitAlongX(clampedHoleMaxX, maxX)) {
                val hx = (bx1 - bx0) * 0.5f
                val hz = (maxZ - minZ) * 0.5f
                val id = world.addBlock(
                    poly = VoronoiPolyhedron.createBox(hx, halfThick, hz),
                    center = Vec3((bx0 + bx1) * 0.5f, centerY, (minZ + maxZ) * 0.5f),
                    tileId = tileId,
                    tint = tint,
                    hp = hp,
                    supportIds = supports.toMutableList(),
                    targetTag = targetTag,
                    pointValue = 22,
                    checkCantilever = true
                )
                slabIds.add(id)
                rightBayIds.add(id)
            }
        }

        // 3. Stairwell header strips (North/South of cutout) bridged across Left & Right main bays + columns
        val headerRects = listOf(
            floatArrayOf(clampedHoleMinX, clampedHoleMaxX, minZ, clampedHoleMinZ),
            floatArrayOf(clampedHoleMinX, clampedHoleMaxX, clampedHoleMaxZ, maxZ)
        )
        for (r in headerRects) {
            val rx0 = r[0]; val rx1 = r[1]; val rz0 = r[2]; val rz1 = r[3]
            val spanX = rx1 - rx0
            val spanZ = rz1 - rz0
            if (spanX < 0.25f || spanZ < 0.25f) continue
            val headerCenterX = (rx0 + rx1) * 0.5f
            val headerCenterZ = (rz0 + rz1) * 0.5f
            val hasDirectCol = supports.any { sid ->
                val sb = world.blocks[sid] ?: return@any false
                abs(sb.center.x - headerCenterX) < spanX * 0.35f && abs(sb.center.z - headerCenterZ) <= spanZ * 0.5f + 0.55f
            }
            val headerGroups = if (!hasDirectCol && leftBayIds.isNotEmpty() && rightBayIds.isNotEmpty()) {
                mutableListOf(leftBayIds.toMutableList(), rightBayIds.toMutableList())
            } else mutableListOf()

            val headerSupports = (supports + leftBayIds + rightBayIds).distinct().toMutableList()
            slabIds.add(
                world.addBlock(
                    poly = VoronoiPolyhedron.createBox(spanX * 0.5f, halfThick, spanZ * 0.5f),
                    center = Vec3(headerCenterX, centerY, headerCenterZ),
                    tileId = tileId,
                    tint = tint,
                    hp = hp,
                    supportIds = headerSupports,
                    targetTag = targetTag,
                    pointValue = 22,
                    supportGroups = headerGroups,
                    checkCantilever = true
                )
            )
        }
        return slabIds
    }

    /**
     * Builds a smooth walkable sloped stair ramp from `start` (bottom landing) to `end` (top landing).
     * Registers dual-end support groups so staircases never float off a single missing floor/landing.
     */
    fun buildStairFlight(
        world: StructuralWorld,
        start: Vec3,
        end: Vec3,
        halfWidth: Float = 0.85f,
        tileId: Int = MaterialTile.STAIR_TREAD,
        tint: Vec3 = Vec3(0.92f, 0.88f, 0.82f),
        hp: Float = 95f,
        groundAnchored: Boolean = false,
        supportIds: List<Int> = emptyList(),
        bottomSupports: List<Int> = emptyList(),
        topSupports: List<Int> = emptyList(),
        targetTag: String? = null,
        withRails: Boolean = true
    ): Int {
        val dx = end.x - start.x
        val dz = end.z - start.z
        val rise = end.y - start.y
        val run = max(0.3f, hypot(dx, dz))
        val len = hypot(run, rise)

        val yawAngle = atan2(dx, dz)
        val tiltAngle = atan2(rise, run)
        val orient = Quat.fromAxisAngle(Vec3.UP, yawAngle) *
            Quat.fromAxisAngle(Vec3(1f, 0f, 0f), -tiltAngle)

        val center = Vec3(
            (start.x + end.x) * 0.5f,
            (start.y + end.y) * 0.5f - 0.08f,
            (start.z + end.z) * 0.5f
        )

        val combinedSupports = (bottomSupports + topSupports + supportIds).distinct().toMutableList()
        val groups = mutableListOf<MutableList<Int>>()
        if (bottomSupports.isNotEmpty() && topSupports.isNotEmpty()) {
            groups.add(bottomSupports.toMutableList())
            groups.add(topSupports.toMutableList())
        } else if (groundAnchored && topSupports.isNotEmpty()) {
            groups.add(topSupports.toMutableList())
        } else if (!groundAnchored && supportIds.isNotEmpty()) {
            // Partition supportIds by Y relative to midpoint if caller passed flat supportIds
            val midY = (start.y + end.y) * 0.5f
            val low = supportIds.filter { (world.blocks[it]?.center?.y ?: midY) <= midY }
            val high = supportIds.filter { (world.blocks[it]?.center?.y ?: midY) > midY }
            if (low.isNotEmpty() && high.isNotEmpty()) {
                groups.add(low.toMutableList())
                groups.add(high.toMutableList())
            }
        }

        val rampId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(halfWidth, 0.10f, len * 0.5f + 0.12f),
            center = center,
            orientation = orient,
            tileId = tileId,
            tint = tint,
            hp = hp,
            groundAnchored = groundAnchored,
            supportIds = combinedSupports,
            targetTag = targetTag,
            pointValue = 18,
            supportGroups = groups
        )

        if (withRails) {
            val rightDir = orient.rotate(Vec3(1f, 0f, 0f)).normalized()
            val upDir = orient.rotate(Vec3(0f, 1f, 0f)).normalized()
            for (side in listOf(-1f, 1f)) {
                world.addBlock(
                    poly = VoronoiPolyhedron.createBox(0.05f, 0.36f, len * 0.48f),
                    center = center + rightDir * (side * (halfWidth - 0.06f)) + upDir * 0.44f,
                    orientation = orient,
                    tileId = MaterialTile.STEEL,
                    tint = Vec3(0.48f, 0.52f, 0.56f),
                    hp = 70f,
                    supportIds = mutableListOf(rampId),
                    targetTag = targetTag,
                    pointValue = 8,
                    walkable = false
                )
            }
        }
        return rampId
    }

    fun buildWindowedWall(
        world: StructuralWorld,
        center: Vec3,
        halfLength: Float,
        height: Float,
        halfThick: Float,
        alongX: Boolean,
        wallTile: Int,
        tint: Vec3,
        groundAnchored: Boolean,
        supportIds: List<Int>,
        windowHalfW: Float = 0.85f,
        targetTag: String? = null,
        wallHp: Float? = null
    ): List<Int> {
        val sillH = 0.85f
        val lintelH = 0.45f
        val winH = max(0.75f, height - sillH - lintelH)
        val pierLen = max(0.35f, (halfLength - windowHalfW) * 0.5f)
        val pierOffset = windowHalfW + pierLen
        val baseHp = wallHp ?: defaultWallHp(wallTile)

        val botY = center.y - height * 0.5f
        val supList = supportIds.toMutableList()
        val topSupports = ArrayList<Int>()

        fun box(lx: Float, ly: Float, lz: Float) = if (alongX) {
            VoronoiPolyhedron.createBox(lx, ly, lz)
        } else {
            VoronoiPolyhedron.createBox(lz, ly, lx)
        }

        fun pos(along: Float, y: Float, out: Float = 0f) = if (alongX) {
            Vec3(center.x + along, y, center.z + out)
        } else {
            Vec3(center.x + out, y, center.z + along)
        }

        // 1. Sill base wall
        val sillId = world.addBlock(
            poly = box(halfLength, sillH * 0.5f, halfThick),
            center = pos(0f, botY + sillH * 0.5f),
            tileId = wallTile,
            tint = tint,
            hp = baseHp,
            groundAnchored = groundAnchored,
            supportIds = supList,
            targetTag = targetTag,
            pointValue = 16
        )

        // 2. Projecting concrete window sill trim + flanking vertical casing trimmers
        world.addBlock(
            poly = box(windowHalfW + 0.14f, 0.08f, halfThick + 0.10f),
            center = pos(0f, botY + sillH + 0.05f),
            tileId = MaterialTile.CONCRETE,
            tint = Vec3(0.86f, 0.85f, 0.82f),
            hp = 45f,
            supportIds = mutableListOf(sillId),
            targetTag = targetTag,
            pointValue = 8,
            walkable = false
        )

        // 3. Left & Right flanking piers + decorative vertical window jamb trimmers
        val jambTile = if (wallTile == MaterialTile.TIMBER) MaterialTile.TIMBER else MaterialTile.CONCRETE
        val jambTint = if (jambTile == MaterialTile.TIMBER) Vec3(0.82f, 0.72f, 0.60f) else Vec3(0.88f, 0.87f, 0.84f)
        for (side in listOf(-1f, 1f)) {
            val pierId = world.addBlock(
                poly = box(pierLen, winH * 0.5f, halfThick),
                center = pos(side * pierOffset, botY + sillH + winH * 0.5f),
                tileId = wallTile,
                tint = tint,
                hp = baseHp * 0.92f,
                supportIds = mutableListOf(sillId),
                targetTag = targetTag,
                pointValue = 15
            )
            topSupports.add(pierId)
            world.addBlock(
                poly = box(0.08f, winH * 0.48f, halfThick + 0.06f),
                center = pos(side * (windowHalfW + 0.05f), botY + sillH + winH * 0.5f),
                tileId = jambTile,
                tint = jambTint,
                hp = 42f,
                supportIds = mutableListOf(pierId, sillId),
                targetTag = targetTag,
                pointValue = 6,
                walkable = false
            )
        }

        // 4. Breakable translucent glass pane + slim steel mullion
        world.addBlock(
            poly = box(windowHalfW - 0.04f, winH * 0.46f, 0.05f),
            center = pos(0f, botY + sillH + winH * 0.5f),
            tileId = MaterialTile.GLASS,
            tint = Vec3(0.82f, 0.92f, 1.0f),
            hp = 12f,
            supportIds = mutableListOf(sillId),
            targetTag = targetTag,
            pointValue = 10,
            walkable = false
        )
        world.addBlock(
            poly = box(0.05f, winH * 0.48f, 0.08f),
            center = pos(0f, botY + sillH + winH * 0.5f),
            tileId = MaterialTile.STEEL,
            tint = Vec3(0.32f, 0.34f, 0.38f),
            hp = 48f,
            supportIds = mutableListOf(sillId),
            targetTag = targetTag,
            pointValue = 8,
            walkable = false
        )

        // 5. Overhead lintel beam + projecting cornice trimmer cap (remains standing as long as >= 1 side pier holds)
        val lintelId = world.addBlock(
            poly = box(halfLength, lintelH * 0.5f, halfThick + 0.04f),
            center = pos(0f, botY + sillH + winH + lintelH * 0.5f),
            tileId = MaterialTile.CONCRETE,
            tint = Vec3(0.82f, 0.81f, 0.78f),
            hp = 185f,
            supportIds = topSupports.toMutableList(),
            targetTag = targetTag,
            pointValue = 16
        )
        world.addBlock(
            poly = box(windowHalfW + 0.22f, 0.07f, halfThick + 0.11f),
            center = pos(0f, botY + sillH + winH + 0.08f),
            tileId = MaterialTile.CONCRETE,
            tint = Vec3(0.88f, 0.87f, 0.84f),
            hp = 45f,
            supportIds = mutableListOf(lintelId),
            targetTag = targetTag,
            pointValue = 6,
            walkable = false
        )
        topSupports.add(lintelId)
        return topSupports
    }

    fun buildDoorwayWall(
        world: StructuralWorld,
        center: Vec3,
        halfLength: Float,
        height: Float,
        halfThick: Float,
        alongX: Boolean,
        wallTile: Int,
        tint: Vec3,
        groundAnchored: Boolean,
        supportIds: List<Int>,
        doorHalfW: Float = 0.88f,
        targetTag: String? = null,
        wallHp: Float? = null
    ): List<Int> {
        val doorH = minOf(2.35f, height - 0.38f)
        val lintelH = max(0.38f, height - doorH)
        val sideLen = max(0.35f, (halfLength - doorHalfW) * 0.5f)
        val sideOff = doorHalfW + sideLen
        val botY = center.y - height * 0.5f
        val baseHp = wallHp ?: defaultWallHp(wallTile)

        fun box(lx: Float, ly: Float, lz: Float) = if (alongX) {
            VoronoiPolyhedron.createBox(lx, ly, lz)
        } else {
            VoronoiPolyhedron.createBox(lz, ly, lx)
        }

        fun pos(along: Float, y: Float) = if (alongX) {
            Vec3(center.x + along, y, center.z)
        } else {
            Vec3(center.x, y, center.z + along)
        }

        val sideIds = ArrayList<Int>()
        for (side in listOf(-1f, 1f)) {
            val sid = world.addBlock(
                poly = box(sideLen, doorH * 0.5f, halfThick),
                center = pos(side * sideOff, botY + doorH * 0.5f),
                tileId = wallTile,
                tint = tint,
                hp = baseHp,
                groundAnchored = groundAnchored,
                supportIds = supportIds.toMutableList(),
                targetTag = targetTag,
                pointValue = 16
            )
            sideIds.add(sid)
            // Vertical doorway casing trimmer
            world.addBlock(
                poly = box(0.08f, doorH * 0.49f, halfThick + 0.06f),
                center = pos(side * (doorHalfW + 0.04f), botY + doorH * 0.5f),
                tileId = MaterialTile.CONCRETE,
                tint = Vec3(0.86f, 0.85f, 0.82f),
                hp = 46f,
                supportIds = mutableListOf(sid),
                targetTag = targetTag,
                pointValue = 6,
                walkable = false
            )
        }

        val lintelId = world.addBlock(
            poly = box(halfLength, lintelH * 0.5f, halfThick + 0.04f),
            center = pos(0f, botY + doorH + lintelH * 0.5f),
            tileId = MaterialTile.CONCRETE,
            tint = Vec3(0.80f, 0.80f, 0.78f),
            hp = 185f,
            supportIds = sideIds.toMutableList(),
            targetTag = targetTag,
            pointValue = 16
        )
        sideIds.add(lintelId)
        return sideIds
    }

    /**
     * Places a multi-piece modular wall panel in any BuildMaterial (Wood, Sheet Metal, Steel, Plastic, Glass, Brick).
     */
    fun buildMaterialWallPanel(
        world: StructuralWorld,
        terrain: ChunkedTerrain,
        hit: Vec3,
        yaw: Float,
        material: BuildMaterial
    ) {
        val baseY = terrain.groundSurfaceY(hit.x, hit.z)
        val orient = Quat.fromAxisAngle(Vec3.UP, -yaw + (Math.PI.toFloat() * 0.5f))
        val wallH = 2.6f
        val halfW = 1.65f
        val halfT = material.thickness * 0.5f

        val baseId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(halfW + 0.14f, 0.14f, halfT + 0.14f),
            center = Vec3(hit.x, baseY + 0.12f, hit.z),
            orientation = orient,
            tileId = MaterialTile.CONCRETE,
            tint = Vec3(0.78f, 0.78f, 0.76f),
            hp = 180f,
            groundAnchored = true
        )

        // Two flanking frame posts in concrete or steel
        val postTile = if (material == BuildMaterial.GLASS || material == BuildMaterial.PLASTIC) {
            MaterialTile.STEEL
        } else {
            material.tileId
        }
        val postTint = if (postTile == MaterialTile.STEEL) Vec3(0.45f, 0.48f, 0.52f) else material.defaultTint
        val postHp = if (postTile == MaterialTile.STEEL) 220f else material.defaultHp * 1.15f
        val posts = ArrayList<Int>()
        for (side in listOf(-1f, 1f)) {
            val worldOff = orient.rotate(Vec3(side * (halfW - 0.16f), 0.26f + wallH * 0.5f, 0f))
            posts.add(
                world.addBlock(
                    poly = VoronoiPolyhedron.createBox(0.16f, wallH * 0.5f, halfT + 0.03f),
                    center = Vec3(hit.x, baseY, hit.z) + worldOff,
                    orientation = orient,
                    tileId = postTile,
                    tint = postTint,
                    hp = postHp,
                    groundAnchored = true,
                    supportIds = mutableListOf(baseId),
                    isCoreSupport = (postTile == MaterialTile.STEEL)
                )
            )
        }

        // Segmented infill wall panels (lower & upper) in the selected material
        val panelHalfW = halfW - 0.34f
        val segH = wallH * 0.46f
        for (row in 0..1) {
            val yOff = 0.26f + segH * 0.5f + row * (segH + 0.04f)
            val worldOff = orient.rotate(Vec3(0f, yOff, 0f))
            world.addBlock(
                poly = VoronoiPolyhedron.createBox(panelHalfW, segH * 0.5f, halfT),
                center = Vec3(hit.x, baseY, hit.z) + worldOff,
                orientation = orient,
                tileId = material.tileId,
                tint = material.defaultTint,
                hp = material.defaultHp,
                supportIds = posts.toMutableList()
            )
        }
    }

    fun buildDetailedRoof(
        world: StructuralWorld,
        cx: Float,
        cz: Float,
        eaveY: Float,
        halfW: Float,
        halfD: Float,
        roofHy: Float,
        roofTile: Int,
        supports: List<Int>,
        withChimney: Boolean = true,
        targetTag: String? = null
    ) {
        val corniceId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(halfW + 0.35f, 0.11f, halfD + 0.35f),
            center = Vec3(cx, eaveY + 0.11f, cz),
            tileId = MaterialTile.TIMBER,
            tint = Vec3(0.88f, 0.86f, 0.82f),
            hp = 165f,
            supportIds = supports.toMutableList(),
            targetTag = targetTag,
            pointValue = 16,
            checkCantilever = true
        )
        val roofId = world.addBlock(
            poly = VoronoiPolyhedron.createPitchedRoof(halfW + 0.42f, roofHy, halfD + 0.45f),
            center = Vec3(cx, eaveY + 0.22f + roofHy, cz),
            tileId = roofTile,
            tint = Vec3(0.95f, 0.93f, 0.90f),
            hp = 175f,
            supportIds = mutableListOf(corniceId),
            targetTag = targetTag,
            pointValue = 25,
            checkCantilever = true
        )
        if (withChimney) {
            world.addBlock(
                poly = VoronoiPolyhedron.createBox(0.36f, 0.78f, 0.36f),
                center = Vec3(cx + halfW * 0.48f, eaveY + 0.22f + roofHy * 1.45f, cz - halfD * 0.35f),
                tileId = MaterialTile.BRICK,
                tint = Vec3(0.96f, 0.92f, 0.90f),
                hp = 48f,
                supportIds = mutableListOf(roofId),
                targetTag = targetTag,
                pointValue = 12
            )
        }
    }

    /**
     * Builds an exterior cantilevered balcony slab projecting cleanly (overhang <= 0.6m from supporting wall/slab edge)
     * complete with steel corner posts and horizontal safety railings.
     */
    fun buildBalconyWithRailings(
        world: StructuralWorld,
        deckCenter: Vec3,
        halfW: Float,
        halfD: Float,
        outwardNormal: Vec3,
        slabTile: Int = MaterialTile.CONCRETE,
        railTile: Int = MaterialTile.STEEL,
        supportIds: List<Int>,
        targetTag: String? = null
    ): Int {
        val deckThick = 0.14f
        val deckId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(halfW, deckThick, halfD),
            center = deckCenter,
            tileId = slabTile,
            tint = Vec3(0.84f, 0.84f, 0.81f),
            hp = 88f,
            supportIds = supportIds.toMutableList(),
            targetTag = targetTag,
            pointValue = 18,
            checkCantilever = true
        )

        val topY = deckCenter.y + deckThick
        val railH = 0.94f
        val postOffX = halfW - 0.10f
        val postOffZ = halfD - 0.10f

        // Corner & midpoint railing posts along outer edge
        val postSpecs = when {
            outwardNormal.z > 0.5f -> listOf(
                -postOffX to postOffZ, 0f to postOffZ, postOffX to postOffZ,
                -postOffX to -postOffZ * 0.4f, postOffX to -postOffZ * 0.4f
            )
            outwardNormal.z < -0.5f -> listOf(
                -postOffX to -postOffZ, 0f to -postOffZ, postOffX to -postOffZ,
                -postOffX to postOffZ * 0.4f, postOffX to postOffZ * 0.4f
            )
            outwardNormal.x > 0.5f -> listOf(
                postOffX to -postOffZ, postOffX to 0f, postOffX to postOffZ,
                -postOffX * 0.4f to -postOffZ, -postOffX * 0.4f to postOffZ
            )
            else -> listOf(
                -postOffX to -postOffZ, -postOffX to 0f, -postOffX to postOffZ,
                postOffX * 0.4f to -postOffZ, postOffX * 0.4f to postOffZ
            )
        }
        val postIds = ArrayList<Int>(postSpecs.size)
        for ((px, pz) in postSpecs) {
            postIds.add(
                world.addBlock(
                    poly = VoronoiPolyhedron.createBox(0.05f, railH * 0.5f, 0.05f),
                    center = Vec3(deckCenter.x + px, topY + railH * 0.5f, deckCenter.z + pz),
                    tileId = railTile,
                    tint = Vec3(0.42f, 0.45f, 0.50f),
                    hp = 48f,
                    supportIds = mutableListOf(deckId),
                    targetTag = targetTag,
                    pointValue = 6,
                    walkable = false
                )
            )
        }

        // Top horizontal safety handrail bars enclosing the outer 3 balcony edges
        val railY = topY + railH
        if (abs(outwardNormal.z) > 0.5f) {
            val frontZ = deckCenter.z + outwardNormal.z * postOffZ
            world.addBlock(
                poly = VoronoiPolyhedron.createBox(halfW, 0.05f, 0.06f),
                center = Vec3(deckCenter.x, railY, frontZ),
                tileId = railTile, tint = Vec3(0.46f, 0.50f, 0.55f),
                hp = 45f, supportIds = postIds.take(3).toMutableList(), targetTag = targetTag, walkable = false
            )
            for (sx in listOf(-1f, 1f)) {
                world.addBlock(
                    poly = VoronoiPolyhedron.createBox(0.06f, 0.05f, halfD),
                    center = Vec3(deckCenter.x + sx * postOffX, railY, deckCenter.z),
                    tileId = railTile, tint = Vec3(0.46f, 0.50f, 0.55f),
                    hp = 45f, supportIds = listOf(deckId).toMutableList(), targetTag = targetTag, walkable = false
                )
            }
        } else {
            val frontX = deckCenter.x + outwardNormal.x * postOffX
            world.addBlock(
                poly = VoronoiPolyhedron.createBox(0.06f, 0.05f, halfD),
                center = Vec3(frontX, railY, deckCenter.z),
                tileId = railTile, tint = Vec3(0.46f, 0.50f, 0.55f),
                hp = 45f, supportIds = postIds.take(3).toMutableList(), targetTag = targetTag, walkable = false
            )
            for (sz in listOf(-1f, 1f)) {
                world.addBlock(
                    poly = VoronoiPolyhedron.createBox(halfW, 0.05f, 0.06f),
                    center = Vec3(deckCenter.x, railY, deckCenter.z + sz * postOffZ),
                    tileId = railTile, tint = Vec3(0.46f, 0.50f, 0.55f),
                    hp = 45f, supportIds = listOf(deckId).toMutableList(), targetTag = targetTag, walkable = false
                )
            }
        }
        return deckId
    }

    /**
     * Multi-tier metal/timber shelving rack stocked with polymer totes and crates.
     */
    fun buildWarehouseRack(
        world: StructuralWorld,
        cx: Float,
        floorY: Float,
        cz: Float,
        alongX: Boolean,
        supportIds: List<Int>,
        targetTag: String? = null
    ) {
        val halfSpan = 1.35f
        val halfDepth = 0.42f
        val rackH = 2.15f
        fun box(lx: Float, ly: Float, lz: Float) = if (alongX) {
            VoronoiPolyhedron.createBox(lx, ly, lz)
        } else {
            VoronoiPolyhedron.createBox(lz, ly, lx)
        }
        fun pt(along: Float, y: Float, off: Float = 0f) = if (alongX) {
            Vec3(cx + along, y, cz + off)
        } else {
            Vec3(cx + off, y, cz + along)
        }

        val posts = ArrayList<Int>(4)
        for (sa in listOf(-halfSpan + 0.08f, halfSpan - 0.08f)) {
            for (sd in listOf(-halfDepth + 0.08f, halfDepth - 0.08f)) {
                posts.add(
                    world.addBlock(
                        poly = VoronoiPolyhedron.createBox(0.06f, rackH * 0.5f, 0.06f),
                        center = pt(sa, floorY + rackH * 0.5f, sd),
                        tileId = MaterialTile.STEEL,
                        tint = Vec3(0.42f, 0.46f, 0.52f),
                        hp = 68f,
                        supportIds = supportIds.toMutableList(),
                        targetTag = targetTag,
                        pointValue = 8,
                        walkable = false
                    )
                )
            }
        }

        for ((idx, shelfY) in listOf(floorY + 0.85f, floorY + 1.65f).withIndex()) {
            val shelfId = world.addBlock(
                poly = box(halfSpan, 0.06f, halfDepth),
                center = pt(0f, shelfY, 0f),
                tileId = MaterialTile.TIMBER,
                tint = Vec3(0.86f, 0.74f, 0.56f),
                hp = 34f,
                supportIds = posts.toMutableList(),
                targetTag = targetTag,
                pointValue = 8
            )
            // Bins/crates sitting atop each shelf
            for (binOff in listOf(-0.68f, 0.62f)) {
                val isPlastic = ((idx + if (binOff > 0f) 1 else 0) % 2 == 0)
                world.addBlock(
                    poly = box(0.32f, 0.24f, 0.30f),
                    center = pt(binOff, shelfY + 0.30f, 0f),
                    tileId = if (isPlastic) MaterialTile.PLASTIC else MaterialTile.TIMBER,
                    tint = if (isPlastic) Vec3(0.24f, 0.58f, 0.90f) else Vec3(0.92f, 0.86f, 0.74f),
                    hp = 20f,
                    supportIds = mutableListOf(shelfId),
                    targetTag = targetTag,
                    pointValue = 6,
                    walkable = false
                )
            }
        }
    }

    /**
     * Furnished interior room suite: desk/counter, seating stool, bookcase shelf, and storage tote.
     */
    fun buildOfficeSuiteProps(
        world: StructuralWorld,
        cx: Float,
        floorY: Float,
        cz: Float,
        supportIds: List<Int>,
        targetTag: String? = null
    ) {
        val sups = supportIds.toMutableList()
        // Desk tabletop + legs + terminal/prop
        val deskId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(1.05f, 0.42f, 0.38f),
            center = Vec3(cx - 0.4f, floorY + 0.42f, cz - 0.6f),
            tileId = MaterialTile.TIMBER,
            tint = Vec3(0.88f, 0.76f, 0.60f),
            hp = 28f, supportIds = sups, targetTag = targetTag, pointValue = 10
        )
        world.addBlock(
            poly = VoronoiPolyhedron.createBox(0.30f, 0.24f, 0.24f),
            center = Vec3(cx - 0.5f, floorY + 1.08f, cz - 0.6f),
            tileId = MaterialTile.PLASTIC,
            tint = Vec3(0.22f, 0.26f, 0.32f),
            hp = 18f, supportIds = mutableListOf(deskId), targetTag = targetTag, pointValue = 8, walkable = false
        )
        // Stool/chair
        world.addBlock(
            poly = VoronoiPolyhedron.createBox(0.26f, 0.26f, 0.26f),
            center = Vec3(cx - 0.4f, floorY + 0.26f, cz + 0.25f),
            tileId = MaterialTile.TIMBER,
            tint = Vec3(0.76f, 0.56f, 0.38f),
            hp = 18f, supportIds = sups.toMutableList(), targetTag = targetTag, pointValue = 6, walkable = false
        )
        // Compact storage shelving
        buildWarehouseRack(world, cx + 1.65f, floorY, cz + 0.3f, alongX = false, supportIds = sups, targetTag = targetTag)
    }

    fun buildInteriorRoomProps(
        world: StructuralWorld,
        cx: Float,
        floorY: Float,
        cz: Float,
        supportIds: List<Int>,
        targetTag: String? = null
    ) {
        // Wooden counter/desk + polymer storage tote + crate + stool
        val deskId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(1.05f, 0.44f, 0.36f),
            center = Vec3(cx - 0.8f, floorY + 0.44f, cz - 1.1f),
            tileId = MaterialTile.TIMBER,
            tint = Vec3(0.92f, 0.84f, 0.74f),
            hp = 28f,
            supportIds = supportIds.toMutableList(),
            targetTag = targetTag,
            pointValue = 10
        )
        world.addBlock(
            poly = VoronoiPolyhedron.createBox(0.36f, 0.28f, 0.36f),
            center = Vec3(cx - 0.8f, floorY + 1.16f, cz - 1.1f),
            tileId = MaterialTile.PLASTIC,
            tint = Vec3(0.24f, 0.60f, 0.92f),
            hp = 24f,
            supportIds = mutableListOf(deskId),
            targetTag = targetTag,
            pointValue = 10
        )
        world.addBlock(
            poly = VoronoiPolyhedron.createBox(0.42f, 0.42f, 0.42f),
            center = Vec3(cx + 1.2f, floorY + 0.42f, cz + 0.7f),
            tileId = MaterialTile.TIMBER,
            tint = Vec3(0.95f, 0.90f, 0.80f),
            hp = 24f,
            supportIds = supportIds.toMutableList(),
            targetTag = targetTag,
            pointValue = 10
        )
        world.addBlock(
            poly = VoronoiPolyhedron.createBox(0.28f, 0.24f, 0.28f),
            center = Vec3(cx - 0.7f, floorY + 0.24f, cz - 0.25f),
            tileId = MaterialTile.TIMBER,
            tint = Vec3(0.78f, 0.62f, 0.46f),
            hp = 18f,
            supportIds = supportIds.toMutableList(),
            targetTag = targetTag,
            pointValue = 6,
            walkable = false
        )
    }

    // --- Street Props & Destructible Urban Furniture ---

    fun buildStreetLamp(world: StructuralWorld, terrain: ChunkedTerrain, x: Float, z: Float) {
        val gy = terrain.groundSurfaceY(x, z)
        val poleId = world.addBlock(
            poly = VoronoiPolyhedron.createCylinderApprox(0.11f, 1.85f, 6),
            center = Vec3(x, gy + 1.85f, z),
            tileId = MaterialTile.STEEL,
            tint = Vec3(0.32f, 0.35f, 0.38f),
            hp = 68f,
            groundAnchored = true,
            pointValue = 12,
            walkable = false
        )
        val armId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(0.45f, 0.08f, 0.08f),
            center = Vec3(x + 0.35f, gy + 3.68f, z),
            tileId = MaterialTile.STEEL,
            tint = Vec3(0.34f, 0.36f, 0.40f),
            hp = 45f,
            supportIds = mutableListOf(poleId),
            walkable = false
        )
        world.addBlock(
            poly = VoronoiPolyhedron.createBox(0.22f, 0.14f, 0.18f),
            center = Vec3(x + 0.72f, gy + 3.55f, z),
            tileId = MaterialTile.GLASS,
            tint = Vec3(1.1f, 0.95f, 0.70f),
            hp = 10f,
            supportIds = mutableListOf(armId),
            pointValue = 10,
            walkable = false
        )
    }

    fun buildFireHydrant(world: StructuralWorld, terrain: ChunkedTerrain, x: Float, z: Float) {
        val gy = terrain.groundSurfaceY(x, z)
        val bodyId = world.addBlock(
            poly = VoronoiPolyhedron.createCylinderApprox(0.18f, 0.42f, 8),
            center = Vec3(x, gy + 0.42f, z),
            tileId = MaterialTile.STEEL,
            tint = Vec3(0.92f, 0.22f, 0.16f),
            hp = 34f,
            groundAnchored = true,
            isHydrant = true,
            pointValue = 20,
            walkable = false
        )
        world.addBlock(
            poly = VoronoiPolyhedron.createBox(0.26f, 0.08f, 0.12f),
            center = Vec3(x, gy + 0.65f, z),
            tileId = MaterialTile.STEEL,
            tint = Vec3(0.95f, 0.26f, 0.20f),
            hp = 24f,
            supportIds = mutableListOf(bodyId),
            isHydrant = true,
            walkable = false
        )
    }

    fun buildParkBench(world: StructuralWorld, terrain: ChunkedTerrain, x: Float, z: Float, alongX: Boolean = true) {
        val gy = terrain.groundSurfaceY(x, z)
        val legs = ArrayList<Int>()
        for (side in listOf(-0.65f, 0.65f)) {
            val lx = if (alongX) x + side else x
            val lz = if (alongX) z else z + side
            val legGy = min(gy, terrain.groundSurfaceY(lx, lz)) - 0.08f
            val legH = max(0.40f, (gy + 0.48f) - legGy)
            legs.add(
                world.addBlock(
                    poly = VoronoiPolyhedron.createBox(0.08f, legH * 0.5f, 0.22f),
                    center = Vec3(lx, legGy + legH * 0.5f, lz),
                    tileId = MaterialTile.STEEL,
                    tint = Vec3(0.28f, 0.30f, 0.32f),
                    hp = 55f,
                    groundAnchored = true,
                    walkable = false
                )
            )
        }
        val seatPoly = if (alongX) VoronoiPolyhedron.createBox(0.85f, 0.07f, 0.28f) else VoronoiPolyhedron.createBox(0.28f, 0.07f, 0.85f)
        world.addBlock(
            poly = seatPoly,
            center = Vec3(x, gy + 0.50f, z),
            tileId = MaterialTile.TIMBER,
            tint = Vec3(0.86f, 0.66f, 0.44f),
            hp = 24f,
            supportIds = legs,
            pointValue = 10
        )
    }

    fun buildPlasticBarrier(world: StructuralWorld, terrain: ChunkedTerrain, x: Float, z: Float, alongX: Boolean = true) {
        val gy = terrain.groundSurfaceY(x, z)
        val poly = if (alongX) {
            VoronoiPolyhedron.createBox(0.95f, 0.46f, 0.24f)
        } else {
            VoronoiPolyhedron.createBox(0.24f, 0.46f, 0.95f)
        }
        world.addBlock(
            poly = poly,
            center = Vec3(x, gy + 0.46f, z),
            tileId = MaterialTile.PLASTIC,
            tint = Vec3(0.98f, 0.46f, 0.14f),
            hp = 32f,
            groundAnchored = true,
            pointValue = 12
        )
    }

    fun buildSupplyCrate(world: StructuralWorld, terrain: ChunkedTerrain, x: Float, z: Float, plastic: Boolean = false) {
        val gy = terrain.groundSurfaceY(x, z)
        world.addBlock(
            poly = VoronoiPolyhedron.createBox(0.45f, 0.42f, 0.45f),
            center = Vec3(x, gy + 0.42f, z),
            tileId = if (plastic) MaterialTile.PLASTIC else MaterialTile.TIMBER,
            tint = if (plastic) Vec3(0.22f, 0.56f, 0.85f) else Vec3(0.94f, 0.88f, 0.78f),
            hp = if (plastic) 30f else 38f,
            groundAnchored = true,
            pointValue = 10
        )
    }

    fun buildSteelTrussBridge(world: StructuralWorld, terrain: ChunkedTerrain, bridgeZ: Float = 0f) {
        val rc = terrain.riverCenterX(bridgeZ)
        val halfSpan = max(12.2f, terrain.riverHalfWidth() + 5.8f)
        val deckY = 3.45f
        val deckThick = 0.42f
        val bridgeHalfW = 3.35f
        val tag = "BRIDGE"

        val pierXs = listOf(rc - halfSpan * 0.64f, rc, rc + halfSpan * 0.64f)
        val pierIds = ArrayList<Int>()

        for (px in pierXs) {
            val gy = terrain.groundSurfaceY(px, bridgeZ)
            val bottomY = gy - 0.65f
            val topY = deckY - deckThick
            val h = max(1.2f, topY - bottomY)
            val basePier = world.addBlock(
                poly = VoronoiPolyhedron.createBox(0.88f, h * 0.5f, bridgeHalfW * 0.95f),
                center = Vec3(px, bottomY + h * 0.5f, bridgeZ),
                tileId = MaterialTile.RIVER_STONE,
                tint = Vec3(0.86f, 0.86f, 0.84f),
                hp = 520f,
                groundAnchored = true,
                isCoreSupport = true,
                targetTag = tag,
                pointValue = 40
            )
            val capId = world.addBlock(
                poly = VoronoiPolyhedron.createBox(1.05f, 0.22f, bridgeHalfW * 1.02f),
                center = Vec3(px, topY - 0.1f, bridgeZ),
                tileId = MaterialTile.CONCRETE,
                tint = Vec3(0.84f, 0.84f, 0.82f),
                hp = 420f,
                supportIds = mutableListOf(basePier),
                isCoreSupport = true,
                targetTag = tag,
                pointValue = 30
            )
            pierIds.add(capId)
        }

        val westAbutX = rc - halfSpan
        val eastAbutX = rc + halfSpan
        val westAbutId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(1.25f, 0.95f, bridgeHalfW + 0.3f),
            center = Vec3(westAbutX, max(terrain.groundSurfaceY(westAbutX, bridgeZ), deckY - 0.95f), bridgeZ),
            tileId = MaterialTile.CONCRETE,
            tint = Vec3(0.80f, 0.80f, 0.78f),
            hp = 540f,
            groundAnchored = true,
            isCoreSupport = true,
            targetTag = tag,
            pointValue = 35
        )
        val eastAbutId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(1.25f, 0.95f, bridgeHalfW + 0.3f),
            center = Vec3(eastAbutX, max(terrain.groundSurfaceY(eastAbutX, bridgeZ), deckY - 0.95f), bridgeZ),
            tileId = MaterialTile.CONCRETE,
            tint = Vec3(0.80f, 0.80f, 0.78f),
            hp = 540f,
            groundAnchored = true,
            isCoreSupport = true,
            targetTag = tag,
            pointValue = 35
        )

        val segCenters = listOf(
            (westAbutX + pierXs[1]) * 0.5f to listOf(westAbutId, pierIds[0], pierIds[1]),
            (pierXs[1] + eastAbutX) * 0.5f to listOf(pierIds[1], pierIds[2], eastAbutId)
        )
        val segHalfLen = (eastAbutX - westAbutX) * 0.25f + 0.15f
        val trussH = 2.65f

        for ((sx, supports) in segCenters) {
            val deckId = world.addBlock(
                poly = VoronoiPolyhedron.createBox(segHalfLen, deckThick * 0.5f, bridgeHalfW),
                center = Vec3(sx, deckY - deckThick * 0.5f, bridgeZ),
                tileId = MaterialTile.ASPHALT,
                tint = Vec3(0.96f, 0.96f, 0.97f),
                hp = 440f,
                supportIds = supports.toMutableList(),
                targetTag = tag,
                pointValue = 45,
                checkCantilever = true
            )

            val postXs = listOf(sx - segHalfLen * 0.75f, sx, sx + segHalfLen * 0.75f)
            for (side in listOf(-1f, 1f)) {
                val zSide = bridgeZ + side * (bridgeHalfW - 0.22f)
                world.addBlock(
                    poly = VoronoiPolyhedron.createBox(segHalfLen * 0.96f, 0.26f, 0.28f),
                    center = Vec3(sx, deckY + 0.26f, zSide),
                    tileId = MaterialTile.CONCRETE,
                    tint = Vec3(0.86f, 0.86f, 0.84f),
                    hp = 210f,
                    supportIds = mutableListOf(deckId),
                    targetTag = tag,
                    pointValue = 18
                )

                val postIds = ArrayList<Int>()
                for (px in postXs) {
                    postIds.add(
                        world.addBlock(
                            poly = VoronoiPolyhedron.createBox(0.16f, trussH * 0.5f, 0.16f),
                            center = Vec3(px, deckY + trussH * 0.5f, zSide),
                            tileId = MaterialTile.STEEL,
                            tint = Vec3(0.55f, 0.62f, 0.68f),
                            hp = 520f,
                            supportIds = mutableListOf(deckId),
                            isCoreSupport = true,
                            targetTag = tag,
                            pointValue = 25
                        )
                    )
                }

                for (i in 0 until postXs.size - 1) {
                    val xMid = (postXs[i] + postXs[i + 1]) * 0.5f
                    val dx = postXs[i + 1] - postXs[i]
                    val diagLen = sqrt(dx * dx + trussH * trussH) * 0.5f
                    val angle = atan2(trussH, dx) * (if (i % 2 == 0) 1f else -1f)
                    world.addBlock(
                        poly = VoronoiPolyhedron.createBox(diagLen, 0.11f, 0.11f),
                        center = Vec3(xMid, deckY + trussH * 0.5f, zSide),
                        orientation = Quat.fromAxisAngle(Vec3(0f, 0f, 1f), angle),
                        tileId = MaterialTile.STEEL,
                        tint = Vec3(0.52f, 0.58f, 0.64f),
                        hp = 440f,
                        supportIds = mutableListOf(postIds[i], postIds[i + 1]),
                        isCoreSupport = true,
                        targetTag = tag,
                        pointValue = 20,
                        walkable = false
                    )
                }

                world.addBlock(
                    poly = VoronoiPolyhedron.createBox(segHalfLen * 0.88f, 0.16f, 0.18f),
                    center = Vec3(sx, deckY + trussH + 0.16f, zSide),
                    tileId = MaterialTile.STEEL,
                    tint = Vec3(0.50f, 0.56f, 0.62f),
                    hp = 480f,
                    supportIds = postIds,
                    isCoreSupport = true,
                    targetTag = tag,
                    pointValue = 25
                )
            }

            world.addBlock(
                poly = VoronoiPolyhedron.createBox(0.22f, 0.16f, bridgeHalfW - 0.22f),
                center = Vec3(sx, deckY + trussH + 0.16f, bridgeZ),
                tileId = MaterialTile.STEEL,
                tint = Vec3(0.50f, 0.56f, 0.62f),
                hp = 440f,
                supportIds = mutableListOf(deckId),
                isCoreSupport = true,
                targetTag = tag,
                pointValue = 20
            )
        }
    }

    private fun defaultWallHp(tileId: Int): Float = when (tileId) {
        MaterialTile.GLASS -> 14f
        MaterialTile.PLASTIC -> 42f
        MaterialTile.TIMBER -> 115f
        MaterialTile.CORRUGATED_METAL -> 220f
        MaterialTile.STEEL -> 560f
        MaterialTile.BRICK -> 220f
        MaterialTile.STUCCO -> 195f
        else -> 260f
    }
}
