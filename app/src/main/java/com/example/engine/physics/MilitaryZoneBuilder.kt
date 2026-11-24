package com.example.engine.physics

import com.example.engine.terrain.ChunkedTerrain
import com.example.model.MaterialTile
import com.example.model.Quat
import com.example.model.Vec3
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

object MilitaryZoneBuilder {

    fun buildArmyBase(
        world: StructuralWorld,
        terrain: ChunkedTerrain,
        fortification: Float,
        rng: Random
    ) {
        // West Perimeter Gatehouse & Concrete Blast Walls along x = 25.5 (clear of z = -56 & z = 0 boulevards)
        for (wz in listOf(-46f, -38f, -30f, -22f, -14f, -70f, -78f, -86f, -94f)) {
            val gy = terrain.groundSurfaceY(25.5f, wz)
            world.addBlock(
                poly = VoronoiPolyhedron.createBox(0.55f, 1.55f, 3.4f),
                center = Vec3(25.5f, gy + 1.55f, wz),
                tileId = MaterialTile.CONCRETE, tint = Vec3(0.80f, 0.82f, 0.78f),
                hp = 260f, groundAnchored = true, pointValue = 20
            )
        }
        // Entrance Chicane Barriers flanking Central Blvd (z=0) & North Crossing Blvd (z=-56)
        for (bzRoad in listOf(0f, -56f)) {
            for (side in listOf(-1f, 1f)) {
                val bx = 23.2f
                val bz = bzRoad + side * 4.5f
                val gy = terrain.groundSurfaceY(bx, bz)
                world.addBlock(
                    poly = VoronoiPolyhedron.createBox(0.95f, 0.50f, 0.38f),
                    center = Vec3(bx, gy + 0.50f, bz),
                    tileId = MaterialTile.CONCRETE, tint = Vec3(0.85f, 0.85f, 0.80f),
                    hp = 165f, groundAnchored = true, pointValue = 15
                )
                ArchitecturalPrimitives.buildPlasticBarrier(world, terrain, 21.2f, bzRoad + side * 5.5f, alongX = true)
            }
        }

        // North & South compound perimeter blast walls (clear of East Base N-S avenues x = 56, 108)
        for (wx in listOf(30f, 38f, 46f, 66f, 74f, 82f, 90f, 98f, 118f, 126f, 134f, 142f, 150f)) {
            val wz = -108.5f
            val gy = terrain.groundSurfaceY(wx, wz)
            world.addBlock(
                poly = VoronoiPolyhedron.createBox(3.55f, 1.50f, 0.48f),
                center = Vec3(wx, gy + 1.50f, wz),
                tileId = MaterialTile.CONCRETE, tint = Vec3(0.78f, 0.80f, 0.76f),
                hp = 240f, groundAnchored = true, pointValue = 20
            )
        }
        // East rear perimeter blast wall (x = 156.5, clear of z = -56)
        for (wz in listOf(-98f, -90f, -82f, -74f, -66f, -44f, -36f, -28f, -20f, -12f)) {
            val gy = terrain.groundSurfaceY(156.5f, wz)
            world.addBlock(
                poly = VoronoiPolyhedron.createBox(0.48f, 1.50f, 3.55f),
                center = Vec3(156.5f, gy + 1.50f, wz),
                tileId = MaterialTile.CONCRETE, tint = Vec3(0.76f, 0.78f, 0.74f),
                hp = 240f, groundAnchored = true, pointValue = 20
            )
        }

        // Tall 3-Tier Climbable Guard Watchtowers
        buildWatchtower(world, terrain, 31.5f, -16.5f)
        if (fortification >= 0.75f) {
            buildWatchtower(world, terrain, 31.5f, -40.5f)
            buildWatchtower(world, terrain, 120.0f, -72.0f)
        }

        // Reinforced 5-Story + Rooftop Radar Command HQ in central Base block (x in 62..102, z in -50..-8)
        buildCommandHQ(world, terrain, 82.0f, -28.0f)

        // Elevated Cylindrical Fuel Silos & Explosive Tank Farm (tagged "SILO" for Ops Contract)
        buildFuelSilo(world, terrain, 42.0f, -20.5f, rng)
        if (fortification >= 0.9f) {
            buildFuelSilo(world, terrain, 42.0f, -31.5f, rng)
            buildFuelSilo(world, terrain, 136.0f, -78.0f, rng)
        }
        buildFuelDepot(world, terrain, 38.5f, -43.0f)
        buildFuelDepot(world, terrain, 136.0f, -92.0f)

        // Giant 2-Tier Catwalk Steel Hangars in North & East compound blocks
        buildHangar(world, terrain, 82.0f, -84.0f, halfW = 9.4f, halfD = 6.6f)
        buildHangar(world, terrain, 135.0f, -22.0f, halfW = 9.0f, halfD = 6.2f)
        if (fortification >= 1.15f) {
            buildHangar(world, terrain, 38.0f, -84.0f, halfW = 7.4f, halfD = 5.6f)
        }

        // Forward Sandbag Bunkers & Street lamps along compound avenues
        buildSandbagBunker(world, terrain, 126.0f, -41.5f)
        buildSandbagBunker(world, terrain, 144.0f, -41.5f)
        for (lx in listOf(30f, 44f, 68f, 84f, 98f, 122f, 142f)) {
            ArchitecturalPrimitives.buildStreetLamp(world, terrain, lx, -50.8f)
            ArchitecturalPrimitives.buildStreetLamp(world, terrain, lx + 4.0f, -61.2f)
        }
        ArchitecturalPrimitives.buildStreetLamp(world, terrain, 50.8f, -22f)
        ArchitecturalPrimitives.buildStreetLamp(world, terrain, 61.2f, -34f)
    }

    /**
     * Monumental 5-Story + Rooftop Radar Command HQ built around a reinforced Steel Core skeleton.
     * Every story (1F Ops Lobby -> 5F High Command -> Rooftop Radar Deck) is spacious, furnished, climbable,
     * and equipped with exterior balconies and safety railings.
     */
    fun buildCommandHQ(world: StructuralWorld, terrain: ChunkedTerrain, cx: Float, cz: Float) {
        val tag = "HQ"
        val halfW = 9.0f
        val halfD = 7.0f
        val hFloor = 3.45f
        val slabT = 0.34f

        val (foundIds, floor1Y) = ArchitecturalPrimitives.buildGradedFoundation(
            world = world, terrain = terrain, cx = cx, cz = cz,
            halfW = halfW, halfD = halfD,
            entryOffset = Vec3(-halfW - 0.28f, 0f, 0f), // Entrance faces West (-X) toward East Base Ave
            entryHalfW = 1.75f,
            tint = Vec3(0.74f, 0.75f, 0.72f),
            targetTag = tag
        )

        // Protective blast chicane outside front door (-X) grounded into local terrain slope
        val chicaneX = cx - halfW - 2.35f
        val chicaneBot = minOf(
            floor1Y,
            terrain.groundSurfaceY(chicaneX, cz),
            terrain.groundSurfaceY(chicaneX, cz - 1.8f),
            terrain.groundSurfaceY(chicaneX, cz + 1.8f)
        ) - 0.12f
        val chicaneTop = floor1Y + 2.95f
        val chicaneH = max(1.2f, chicaneTop - chicaneBot)
        world.addBlock(
            poly = VoronoiPolyhedron.createBox(0.48f, chicaneH * 0.5f, 2.35f),
            center = Vec3(chicaneX, chicaneBot + chicaneH * 0.5f, cz),
            tileId = MaterialTile.CONCRETE, tint = Vec3(0.78f, 0.80f, 0.76f),
            hp = 240f, groundAnchored = true, targetTag = tag
        )

        val colCoords = listOf(
            (cx - halfW + 0.34f) to (cz - halfD + 0.34f),
            (cx - 3.1f) to (cz - halfD + 0.34f),
            (cx + 3.1f) to (cz - halfD + 0.34f),
            (cx + halfW - 0.34f) to (cz - halfD + 0.34f),
            (cx - halfW + 0.34f) to (cz + halfD - 0.34f),
            (cx - 3.1f) to (cz + halfD - 0.34f),
            (cx + 3.1f) to (cz + halfD - 0.34f),
            (cx + halfW - 0.34f) to (cz + halfD - 0.34f)
        )

        var prevFloorIds = foundIds
        var curFloorY = floor1Y

        for (story in 1..5) {
            val steelCols = ArchitecturalPrimitives.buildSteelCoreColumns(
                world = world, colPositions = colCoords, baseY = curFloorY, height = hFloor,
                halfThick = 0.26f, hp = 560f, groundAnchored = false, supportIds = prevFloorIds, targetTag = tag
            )

            // West Facade (-X): Front door on 1F, exterior balconies on 3F & 4F, armored windows on 2F & 5F
            if (story == 1 || story == 3 || story == 4) {
                ArchitecturalPrimitives.buildDoorwayWall(
                    world, Vec3(cx - halfW + 0.26f, curFloorY + hFloor * 0.5f, cz),
                    halfLength = halfD - 0.62f, height = hFloor, halfThick = 0.25f, alongX = false,
                    wallTile = MaterialTile.CONCRETE, tint = Vec3(0.80f, 0.82f, 0.78f),
                    groundAnchored = false, supportIds = prevFloorIds, doorHalfW = 1.55f, targetTag = tag
                )
                if (story >= 3) {
                    ArchitecturalPrimitives.buildBalconyWithRailings(
                        world = world,
                        deckCenter = Vec3(cx - halfW - 0.52f, curFloorY - 0.10f, cz),
                        halfW = 0.86f, halfD = 3.4f, outwardNormal = Vec3(-1f, 0f, 0f),
                        supportIds = prevFloorIds, targetTag = tag
                    )
                }
            } else {
                ArchitecturalPrimitives.buildWindowedWall(
                    world, Vec3(cx - halfW + 0.26f, curFloorY + hFloor * 0.5f, cz),
                    halfLength = halfD - 0.62f, height = hFloor, halfThick = 0.25f, alongX = false,
                    wallTile = MaterialTile.CONCRETE, tint = Vec3(0.80f, 0.82f, 0.78f),
                    groundAnchored = false, supportIds = prevFloorIds, windowHalfW = 2.0f, targetTag = tag
                )
            }

            ArchitecturalPrimitives.buildWindowedWall(
                world, Vec3(cx + halfW - 0.26f, curFloorY + hFloor * 0.5f, cz),
                halfLength = halfD - 0.62f, height = hFloor, halfThick = 0.25f, alongX = false,
                wallTile = MaterialTile.CONCRETE, tint = Vec3(0.76f, 0.78f, 0.75f),
                groundAnchored = false, supportIds = prevFloorIds, windowHalfW = 2.0f, targetTag = tag
            )
            for (side in listOf(-1f, 1f)) {
                ArchitecturalPrimitives.buildWindowedWall(
                    world, Vec3(cx, curFloorY + hFloor * 0.5f, cz + side * (halfD - 0.26f)),
                    halfLength = halfW - 0.62f, height = hFloor, halfThick = 0.25f, alongX = true,
                    wallTile = MaterialTile.CONCRETE, tint = Vec3(0.80f, 0.82f, 0.78f),
                    groundAnchored = false, supportIds = prevFloorIds, windowHalfW = 2.4f, targetTag = tag
                )
            }

            if (story == 1) {
                ArchitecturalPrimitives.buildInteriorRoomProps(world, cx + 1.0f, curFloorY, cz - 0.8f, prevFloorIds, targetTag = tag)
            } else {
                ArchitecturalPrimitives.buildOfficeSuiteProps(world, cx - 0.2f, curFloorY, cz - 0.5f, prevFloorIds, targetTag = tag)
                world.addBlock(
                    poly = VoronoiPolyhedron.createBox(1.35f, 0.56f, 0.40f),
                    center = Vec3(cx + 0.2f, curFloorY + 0.56f, cz + 1.9f),
                    tileId = MaterialTile.PLASTIC, tint = Vec3(0.24f, 0.34f, 0.40f),
                    hp = 54f, supportIds = prevFloorIds.toMutableList(), targetTag = tag
                )
            }

            val nextSlabY = curFloorY + hFloor + slabT * 0.5f
            val nextFloorY = nextSlabY + slabT * 0.5f
            val isOdd = (story % 2 == 1)
            val hMinX = if (isOdd) cx - 6.4f else cx + 3.4f
            val hMaxX = if (isOdd) cx - 3.4f else cx + 6.4f
            val hMinZ = cz - 2.8f
            val hMaxZ = cz + 2.8f
            val stStart = if (isOdd) Vec3(cx - 4.9f, curFloorY, cz + 2.6f) else Vec3(cx + 4.9f, curFloorY, cz - 2.6f)
            val stEnd = if (isOdd) Vec3(cx - 4.9f, nextFloorY, cz - 2.9f) else Vec3(cx + 4.9f, nextFloorY, cz + 2.9f)

            val nextSlabIds = ArchitecturalPrimitives.buildSegmentedFloorWithCutout(
                world = world, cx = cx, cz = cz, centerY = nextSlabY,
                halfW = halfW + 0.12f, halfD = halfD + 0.12f, halfThick = slabT * 0.5f,
                holeMinX = hMinX, holeMaxX = hMaxX,
                holeMinZ = hMinZ, holeMaxZ = hMaxZ,
                tileId = MaterialTile.CONCRETE, tint = Vec3(0.82f, 0.82f, 0.80f),
                hp = 240f, supports = steelCols, targetTag = tag
            )
            ArchitecturalPrimitives.buildStairFlight(
                world = world,
                start = stStart,
                end = stEnd,
                halfWidth = 1.02f, tileId = MaterialTile.STAIR_TREAD,
                hp = 150f, groundAnchored = false, bottomSupports = prevFloorIds, topSupports = nextSlabIds, targetTag = tag
            )
            prevFloorIds = nextSlabIds
            curFloorY = nextFloorY
        }

        val roofTopY = curFloorY
        for (side in listOf(-1f, 1f)) {
            world.addBlock(
                poly = VoronoiPolyhedron.createBox(halfW - 0.15f, 0.52f, 0.20f),
                center = Vec3(cx, roofTopY + 0.52f, cz + side * (halfD - 0.24f)),
                tileId = MaterialTile.CONCRETE, tint = Vec3(0.74f, 0.76f, 0.73f),
                hp = 140f, supportIds = prevFloorIds.toMutableList(), targetTag = tag
            )
            world.addBlock(
                poly = VoronoiPolyhedron.createBox(0.20f, 0.52f, halfD - 0.36f),
                center = Vec3(cx + side * (halfW - 0.24f), roofTopY + 0.52f, cz),
                tileId = MaterialTile.CONCRETE, tint = Vec3(0.74f, 0.76f, 0.73f),
                hp = 140f, supportIds = prevFloorIds.toMutableList(), targetTag = tag
            )
        }
        world.addBlock(
            poly = VoronoiPolyhedron.createBox(1.15f, 0.55f, 0.90f),
            center = Vec3(cx - 1.2f, roofTopY + 0.55f, cz - 3.1f),
            tileId = MaterialTile.CORRUGATED_METAL, tint = Vec3(0.75f, 0.78f, 0.80f),
            hp = 110f, supportIds = prevFloorIds.toMutableList(), targetTag = tag
        )
        val mastId = world.addBlock(
            poly = VoronoiPolyhedron.createCylinderApprox(0.26f, 2.5f, 10),
            center = Vec3(cx + 1.0f, roofTopY + 2.5f, cz + 2.8f),
            tileId = MaterialTile.STEEL, tint = Vec3(0.88f, 0.90f, 0.92f),
            hp = 165f, supportIds = prevFloorIds.toMutableList(), targetTag = tag, walkable = false
        )
        world.addBlock(
            poly = VoronoiPolyhedron.createBox(1.55f, 0.98f, 0.22f),
            center = Vec3(cx + 1.0f, roofTopY + 5.15f, cz + 2.8f),
            orientation = Quat.fromAxisAngle(Vec3(1f, 0f, 0f), 0.35f),
            tileId = MaterialTile.STEEL, tint = Vec3(0.95f, 0.97f, 1.0f),
            hp = 95f, supportIds = mutableListOf(mastId), targetTag = tag, walkable = false
        )
    }

    fun buildFuelSilo(world: StructuralWorld, terrain: ChunkedTerrain, cx: Float, cz: Float, rng: Random) {
        val tag = "SILO"
        val baseY = terrain.groundSurfaceY(cx, cz)
        val legH = 2.1f
        val legOff = 1.45f
        val legs = ArrayList<Int>()
        for (sx in listOf(-1f, 1f)) {
            for (sz in listOf(-1f, 1f)) {
                val lx = cx + sx * legOff
                val lz = cz + sz * legOff
                val lBot = min(baseY, terrain.groundSurfaceY(lx, lz)) - 0.12f
                val lTop = baseY + legH
                val lH = max(0.8f, lTop - lBot)
                legs.add(
                    world.addBlock(
                        poly = VoronoiPolyhedron.createBox(0.24f, lH * 0.5f, 0.24f),
                        center = Vec3(lx, lBot + lH * 0.5f, lz),
                        tileId = MaterialTile.STEEL, tint = Vec3(0.68f, 0.72f, 0.75f),
                        hp = 180f, groundAnchored = true, isCoreSupport = true, targetTag = tag, pointValue = 25
                    )
                )
            }
        }
        val tankId = world.addBlock(
            poly = VoronoiPolyhedron.createCylinderApprox(2.05f, 3.05f, 12),
            center = Vec3(cx, baseY + legH + 3.05f, cz),
            tileId = MaterialTile.CORRUGATED_METAL, tint = Vec3(0.86f, 0.84f, 0.80f),
            hp = 72f, supportIds = legs, isFuelExplosive = true, targetTag = tag, pointValue = 55, checkCantilever = true
        )
        world.addBlock(
            poly = VoronoiPolyhedron.createTaperedCanopy(2.05f, 0.65f, 0.70f, 12),
            center = Vec3(cx, baseY + legH + 6.80f, cz),
            tileId = MaterialTile.STEEL, tint = Vec3(0.92f, 0.34f, 0.20f),
            hp = 58f, supportIds = mutableListOf(tankId), targetTag = tag, pointValue = 20
        )
        val drumGy = terrain.groundSurfaceY(cx + 2.7f, cz + 0.9f)
        world.addBlock(
            poly = VoronoiPolyhedron.createCylinderApprox(0.42f, 0.56f, 8),
            center = Vec3(cx + 2.7f, drumGy + 0.56f, cz + 0.9f),
            tileId = MaterialTile.STEEL, tint = Vec3(0.95f, 0.24f, 0.18f),
            hp = 24f, groundAnchored = true, isFuelExplosive = true, targetTag = tag, pointValue = 25
        )
    }

    /**
     * Tall 3-Tier Climbable Steel Watchtower with switchback landings leading up to a sandbagged observation cab.
     */
    fun buildWatchtower(world: StructuralWorld, terrain: ChunkedTerrain, cx: Float, cz: Float) {
        val baseY = terrain.groundSurfaceY(cx, cz)
        val legH = 7.2f
        val legOff = 1.85f
        val legs = ArrayList<Int>()
        for (sx in listOf(-1f, 1f)) {
            for (sz in listOf(-1f, 1f)) {
                val lx = cx + sx * legOff
                val lz = cz + sz * legOff
                val lBot = min(baseY, terrain.groundSurfaceY(lx, lz)) - 0.12f
                val lTop = baseY + legH
                val lH = max(1.2f, lTop - lBot)
                legs.add(
                    world.addBlock(
                        poly = VoronoiPolyhedron.createBox(0.24f, lH * 0.5f, 0.24f),
                        center = Vec3(lx, lBot + lH * 0.5f, lz),
                        tileId = MaterialTile.STEEL, tint = Vec3(0.66f, 0.70f, 0.65f),
                        hp = 240f, groundAnchored = true, isCoreSupport = true, pointValue = 25
                    )
                )
            }
        }

        // Tier 1 Mid Landing (South/North safe side) -> Tier 2 Mid Landing -> Tier 3 Top Guard Deck
        val tier1Y = baseY + 2.4f
        val tier1Id = world.addBlock(
            poly = VoronoiPolyhedron.createBox(1.85f, 0.12f, 0.95f),
            center = Vec3(cx, tier1Y, cz - legOff - 0.75f),
            tileId = MaterialTile.STEEL, tint = Vec3(0.60f, 0.64f, 0.66f),
            hp = 125f, supportIds = listOf(legs[0], legs[2]).toMutableList(), checkCantilever = true
        )
        ArchitecturalPrimitives.buildStairFlight(
            world = world,
            start = Vec3(cx - 2.8f, terrain.groundSurfaceY(cx - 2.8f, cz - legOff - 0.75f), cz - legOff - 0.75f),
            end = Vec3(cx - 0.5f, tier1Y + 0.12f, cz - legOff - 0.75f),
            halfWidth = 0.66f, tileId = MaterialTile.STAIR_TREAD,
            hp = 85f, groundAnchored = true, topSupports = listOf(tier1Id)
        )

        val tier2Y = baseY + 4.8f
        val tier2WestBridgeId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(0.68f, 0.12f, 1.15f),
            center = Vec3(cx - 1.38f, tier2Y, cz - 0.90f),
            tileId = MaterialTile.STEEL, tint = Vec3(0.58f, 0.62f, 0.64f),
            hp = 125f, supportIds = listOf(legs[0]).toMutableList(), checkCantilever = true
        )
        val tier2Id = world.addBlock(
            poly = VoronoiPolyhedron.createBox(2.08f, 0.12f, 1.24f),
            center = Vec3(cx, tier2Y, cz + 0.85f),
            tileId = MaterialTile.STEEL, tint = Vec3(0.58f, 0.62f, 0.64f),
            hp = 125f, supportIds = listOf(legs[1], legs[3], tier2WestBridgeId).toMutableList(), checkCantilever = true
        )
        ArchitecturalPrimitives.buildStairFlight(
            world = world,
            start = Vec3(cx + 0.6f, tier1Y + 0.12f, cz - legOff - 0.75f),
            end = Vec3(cx + 0.6f, tier2Y + 0.12f, cz - 0.35f),
            halfWidth = 0.66f, tileId = MaterialTile.STAIR_TREAD,
            hp = 85f, groundAnchored = false, bottomSupports = listOf(tier1Id), topSupports = listOf(tier2Id)
        )

        val platY = baseY + legH + 0.18f
        val platFloorY = platY + 0.18f
        val platId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(2.35f, 0.18f, 2.35f),
            center = Vec3(cx, platY, cz),
            tileId = MaterialTile.TIMBER, tint = Vec3(0.90f, 0.85f, 0.78f),
            hp = 105f, supportIds = legs, checkCantilever = true
        )
        ArchitecturalPrimitives.buildStairFlight(
            world = world,
            start = Vec3(cx - 0.6f, tier2Y + 0.12f, cz),
            end = Vec3(cx - 0.6f, platFloorY, cz + 1.55f),
            halfWidth = 0.68f, tileId = MaterialTile.STAIR_TREAD,
            hp = 85f, groundAnchored = false, bottomSupports = listOf(tier2Id), topSupports = listOf(platId)
        )

        // Sandbagged parapet walls (leaving entry gap on +Z stair arrival side!)
        world.addBlock(
            poly = VoronoiPolyhedron.createBox(2.15f, 0.48f, 0.22f),
            center = Vec3(cx, platFloorY + 0.48f, cz - 2.10f),
            tileId = MaterialTile.SANDBAG, tint = Vec3(0.98f, 0.94f, 0.86f),
            hp = 72f, supportIds = mutableListOf(platId)
        )
        for (side in listOf(-1f, 1f)) {
            world.addBlock(
                poly = VoronoiPolyhedron.createBox(0.22f, 0.48f, 1.85f),
                center = Vec3(cx + side * 2.10f, platFloorY + 0.48f, cz),
                tileId = MaterialTile.SANDBAG, tint = Vec3(0.98f, 0.94f, 0.86f),
                hp = 72f, supportIds = mutableListOf(platId)
            )
        }

        val cabPosts = ArrayList<Int>()
        for (sx in listOf(-1f, 1f)) {
            for (sz in listOf(-1f, 1f)) {
                cabPosts.add(
                    world.addBlock(
                        poly = VoronoiPolyhedron.createBox(0.16f, 1.30f, 0.16f),
                        center = Vec3(cx + sx * 2.02f, platFloorY + 1.30f, cz + sz * 2.02f),
                        tileId = MaterialTile.STEEL, tint = Vec3(0.75f, 0.78f, 0.76f),
                        hp = 150f, supportIds = mutableListOf(platId)
                    )
                )
            }
        }
        world.addBlock(
            poly = VoronoiPolyhedron.createPitchedRoof(2.55f, 0.85f, 2.55f),
            center = Vec3(cx, platFloorY + 3.45f, cz),
            tileId = MaterialTile.CORRUGATED_METAL, tint = Vec3(0.74f, 0.78f, 0.72f),
            hp = 82f, supportIds = cabPosts
        )
    }

    /**
     * Giant 2-Tier Drive-In Steel Hangar with stacked interior steel catwalks, shelving racks & stairways.
     */
    fun buildHangar(
        world: StructuralWorld,
        terrain: ChunkedTerrain,
        cx: Float,
        cz: Float,
        halfW: Float,
        halfD: Float
    ) {
        val baseY = terrain.groundSurfaceY(cx, cz)
        val wallH = 6.2f
        val wallTopY = baseY + wallH
        val wallSupports = ArrayList<Int>()

        for (sx in listOf(-1f, 0f, 1f)) {
            for (sz in listOf(-1f, 1f)) {
                val lx = cx + sx * (halfW - 0.34f)
                val lz = cz + sz * (halfD - 0.34f)
                val cBot = min(baseY, terrain.groundSurfaceY(lx, lz)) - 0.12f
                val cH = max(1.5f, wallTopY - cBot)
                wallSupports.add(
                    world.addBlock(
                        poly = VoronoiPolyhedron.createBox(0.32f, cH * 0.5f, 0.32f),
                        center = Vec3(lx, cBot + cH * 0.5f, lz),
                        tileId = MaterialTile.STEEL, tint = Vec3(0.65f, 0.68f, 0.72f),
                        hp = 325f, groundAnchored = true, isCoreSupport = true, pointValue = 30
                    )
                )
            }
        }
        for (side in listOf(-1f, 1f)) {
            val wz = cz + side * (halfD - 0.24f)
            val wBot = minOf(
                baseY,
                terrain.groundSurfaceY(cx, wz),
                terrain.groundSurfaceY(cx - halfW * 0.7f, wz),
                terrain.groundSurfaceY(cx + halfW * 0.7f, wz)
            ) - 0.12f
            val wH = max(1.5f, wallTopY - wBot)
            wallSupports.add(
                world.addBlock(
                    poly = VoronoiPolyhedron.createBox(halfW - 0.6f, wH * 0.5f, 0.24f),
                    center = Vec3(cx, wBot + wH * 0.5f, wz),
                    tileId = MaterialTile.CORRUGATED_METAL, tint = Vec3(0.80f, 0.84f, 0.78f),
                    hp = 128f, groundAnchored = true, pointValue = 22
                )
            )
        }

        // Mid & Upper Walkable Steel Catwalks along North interior wall with switchback steel stairs!
        val cat1Y = baseY + 2.45f
        val cat1Id = world.addBlock(
            poly = VoronoiPolyhedron.createBox(halfW - 0.8f, 0.14f, 1.15f),
            center = Vec3(cx, cat1Y, cz - halfD + 1.55f),
            tileId = MaterialTile.STEEL, tint = Vec3(0.54f, 0.58f, 0.62f),
            hp = 185f, supportIds = wallSupports.toMutableList(), checkCantilever = true
        )
        ArchitecturalPrimitives.buildStairFlight(
            world = world,
            start = Vec3(cx - halfW + 1.1f, baseY, cz - halfD + 1.55f),
            end = Vec3(cx - 1.4f, cat1Y + 0.14f, cz - halfD + 1.55f),
            halfWidth = 0.78f, tileId = MaterialTile.STAIR_TREAD,
            hp = 105f, groundAnchored = true, topSupports = listOf(cat1Id)
        )

        val cat2Y = baseY + 4.55f
        val cat2Id = world.addBlock(
            poly = VoronoiPolyhedron.createBox(halfW - 0.8f, 0.14f, 1.15f),
            center = Vec3(cx, cat2Y, cz + halfD - 1.55f),
            tileId = MaterialTile.STEEL, tint = Vec3(0.54f, 0.58f, 0.62f),
            hp = 185f, supportIds = wallSupports.toMutableList(), checkCantilever = true
        )
        ArchitecturalPrimitives.buildStairFlight(
            world = world,
            start = Vec3(cx + 1.2f, cat1Y + 0.14f, cz - halfD + 1.55f),
            end = Vec3(cx + 1.2f, cat2Y + 0.14f, cz + halfD - 1.55f),
            halfWidth = 0.78f, tileId = MaterialTile.STAIR_TREAD,
            hp = 105f, groundAnchored = false, bottomSupports = listOf(cat1Id), topSupports = listOf(cat2Id)
        )

        // Warehouse shelving rack & Military Supply Crates inside Hangar bay
        val bayGy = terrain.groundSurfaceY(cx - 2.0f, cz + 0.6f)
        val bayAnchorId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(1.6f, 0.08f, 0.65f),
            center = Vec3(cx - 1.8f, bayGy + 0.06f, cz + 0.4f),
            tileId = MaterialTile.CONCRETE, tint = Vec3(0.75f, 0.76f, 0.74f),
            hp = 120f, groundAnchored = true
        )
        ArchitecturalPrimitives.buildWarehouseRack(world, cx - 1.8f, bayGy + 0.14f, cz + 0.4f, alongX = true, listOf(bayAnchorId))
        ArchitecturalPrimitives.buildSupplyCrate(world, terrain, cx + 2.2f, cz + 0.5f, plastic = true)
        ArchitecturalPrimitives.buildSupplyCrate(world, terrain, cx + 3.2f, cz - 0.4f, plastic = false)

        // High-pitched corrugated roof
        world.addBlock(
            poly = VoronoiPolyhedron.createPitchedRoof(halfW + 0.45f, 1.85f, halfD + 0.45f),
            center = Vec3(cx, baseY + wallH + 1.85f, cz),
            tileId = MaterialTile.CORRUGATED_METAL, tint = Vec3(0.76f, 0.80f, 0.76f),
            hp = 145f, supportIds = wallSupports, pointValue = 35, checkCantilever = true
        )
    }

    fun buildSandbagBunker(world: StructuralWorld, terrain: ChunkedTerrain, cx: Float, cz: Float) {
        val baseY = terrain.groundSurfaceY(cx, cz)
        val wallTopY = baseY + 2.30f
        val wallIds = ArrayList<Int>()
        for (side in listOf(-1f, 1f)) {
            val wz = cz + side * 2.1f
            val wBot = minOf(
                baseY,
                terrain.groundSurfaceY(cx, wz),
                terrain.groundSurfaceY(cx - 1.8f, wz),
                terrain.groundSurfaceY(cx + 1.8f, wz)
            ) - 0.12f
            val wH = max(1.0f, wallTopY - wBot)
            wallIds.add(
                world.addBlock(
                    poly = VoronoiPolyhedron.createBox(2.4f, wH * 0.5f, 0.46f),
                    center = Vec3(cx, wBot + wH * 0.5f, wz),
                    tileId = MaterialTile.SANDBAG, tint = Vec3(0.98f, 0.95f, 0.88f),
                    hp = 135f, groundAnchored = true
                )
            )
        }
        val sideBot = minOf(
            baseY,
            terrain.groundSurfaceY(cx - 1.95f, cz),
            terrain.groundSurfaceY(cx - 1.95f, cz - 1.2f),
            terrain.groundSurfaceY(cx - 1.95f, cz + 1.2f)
        ) - 0.12f
        val sideH = max(1.0f, wallTopY - sideBot)
        wallIds.add(
            world.addBlock(
                poly = VoronoiPolyhedron.createBox(0.46f, sideH * 0.5f, 1.65f),
                center = Vec3(cx - 1.95f, sideBot + sideH * 0.5f, cz),
                tileId = MaterialTile.SANDBAG, tint = Vec3(0.98f, 0.95f, 0.88f),
                hp = 135f, groundAnchored = true
            )
        )
        val roofId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(2.65f, 0.25f, 2.65f),
            center = Vec3(cx, baseY + 2.55f, cz),
            tileId = MaterialTile.CONCRETE, tint = Vec3(0.82f, 0.83f, 0.80f),
            hp = 130f, supportIds = wallIds, checkCantilever = true
        )
        // Ramp onto bunker firing roof
        ArchitecturalPrimitives.buildStairFlight(
            world = world,
            start = Vec3(cx + 3.4f, terrain.groundSurfaceY(cx + 3.4f, cz + 3.2f), cz + 3.2f),
            end = Vec3(cx + 1.2f, baseY + 2.80f, cz + 2.2f),
            halfWidth = 0.72f, tileId = MaterialTile.STAIR_TREAD,
            hp = 85f, groundAnchored = true, topSupports = listOf(roofId)
        )
    }

    fun buildFuelDepot(world: StructuralWorld, terrain: ChunkedTerrain, cx: Float, cz: Float) {
        val offsets = listOf(
            Vec3(-0.7f, 0f, -0.5f),
            Vec3(0.6f, 0f, -0.4f),
            Vec3(0f, 0f, 0.7f),
            Vec3(-0.1f, 1.1f, -0.1f)
        )
        val baseDrumIds = ArrayList<Int>(3)
        for ((idx, off) in offsets.withIndex()) {
            val gy = terrain.groundSurfaceY(cx + off.x, cz + off.z)
            val id = world.addBlock(
                poly = VoronoiPolyhedron.createCylinderApprox(0.42f, 0.56f, 8),
                center = Vec3(cx + off.x, gy + 0.54f + off.y, cz + off.z),
                tileId = MaterialTile.STEEL, tint = Vec3(0.95f, 0.24f, 0.18f),
                hp = 24f,
                groundAnchored = (idx < 3),
                supportIds = if (idx < 3) mutableListOf() else baseDrumIds.toMutableList(),
                isFuelExplosive = true,
                targetTag = "SILO"
            )
            if (idx < 3) baseDrumIds.add(id)
        }
    }
}
