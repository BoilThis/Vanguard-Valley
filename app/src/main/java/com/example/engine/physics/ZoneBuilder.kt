package com.example.engine.physics

import com.example.engine.terrain.ChunkedTerrain
import com.example.model.BrushItem
import com.example.model.BuildMaterial
import com.example.model.MaterialTile
import com.example.model.Quat
import com.example.model.Vec3
import com.example.model.WorldConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

object ZoneBuilder {

    fun populateValley(world: StructuralWorld, terrain: ChunkedTerrain, config: WorldConfig) {
        val rng = Random(config.seed)

        // 1. Triple Steel Truss & Stone Pier River Bridges (z = -56, z = 0, z = +56)
        ArchitecturalPrimitives.buildSteelTrussBridge(world, terrain, bridgeZ = -56f)
        ArchitecturalPrimitives.buildSteelTrussBridge(world, terrain, bridgeZ = 0f)
        ArchitecturalPrimitives.buildSteelTrussBridge(world, terrain, bridgeZ = 56f)

        // 2. Expanded West Bank Metropolis (Downtown 11-Tower Core, Civic Plaza, Central Park, University, Stadium)
        buildSmallTown(world, terrain, config)

        // 3. West Bank Timber Riverwalk Boardwalk along all 4 riverfront spans
        CityParkAndStreetlifeBuilder.buildRiverwalk(world, terrain)

        // 4. Army Base (East Bank North Plateau)
        MilitaryZoneBuilder.buildArmyBase(world, terrain, config.baseFortification, rng)

        // 5. East Bank Tech Plaza, Heavy Industrial, Warehouse Row & Freight Rail Yard
        buildIndustrialDistrict(world, terrain, rng)

        // 6. Multi-tier Pine Forest & Valley Rim Hills (512m x 384m perimeter)
        buildPineForest(world, terrain, config, rng)

        // 7. Prune non-contacting support links and finalize structural physics graph
        world.rebuildPhysicalSupports()
    }

    private fun buildSmallTown(world: StructuralWorld, terrain: ChunkedTerrain, config: WorldConfig) {
        ValleyLayout.placeWestBank(world, terrain, config)
    }

    private fun buildIndustrialDistrict(world: StructuralWorld, terrain: ChunkedTerrain, rng: Random) {
        ValleyLayout.placeEastBank(world, terrain, rng)
    }


    /**
     * Monumental 6-Story + Rooftop Terrace Brick Hotel built around an Iron/Steel core skeleton.
     * Every floor (1F Lobby -> 6F Suites -> Rooftop Terrace) is spacious, furnished, enterable, and climbable,
     * with walkable exterior balconies and railings on upper floors.
     */
    fun buildBrickHotel3F(world: StructuralWorld, terrain: ChunkedTerrain, cx: Float, cz: Float) {
        val tag = "HOTEL"
        val halfW = 9.6f
        val halfD = 7.2f
        val hFloor = 3.45f
        val slabT = 0.34f

        val (foundIds, floor1Y) = ArchitecturalPrimitives.buildGradedFoundation(
            world = world, terrain = terrain, cx = cx, cz = cz,
            halfW = halfW, halfD = halfD,
            entryOffset = Vec3(0f, 0f, halfD + 0.28f),
            entryHalfW = 1.9f,
            targetTag = tag
        )

        // 8 heavy Steel/Iron Core Columns rising all 6 stories
        val colCoords = listOf(
            (cx - halfW + 0.34f) to (cz - halfD + 0.34f),
            (cx - 3.2f) to (cz - halfD + 0.34f),
            (cx + 3.2f) to (cz - halfD + 0.34f),
            (cx + halfW - 0.34f) to (cz - halfD + 0.34f),
            (cx - halfW + 0.34f) to (cz + halfD - 0.34f),
            (cx - 3.2f) to (cz + halfD - 0.34f),
            (cx + 3.2f) to (cz + halfD - 0.34f),
            (cx + halfW - 0.34f) to (cz + halfD - 0.34f)
        )

        var prevFloorIdList = foundIds
        var curFloorY = floor1Y

        for (story in 1..6) {
            val wallTile = when {
                story <= 3 -> MaterialTile.BRICK
                story <= 5 -> MaterialTile.STUCCO
                else -> MaterialTile.BRICK
            }
            val wallTint = when {
                story <= 3 -> Vec3(0.95f, 0.90f, 0.87f)
                story <= 5 -> Vec3(0.97f, 0.95f, 0.90f)
                else -> Vec3(0.90f, 0.86f, 0.83f)
            }

            val steelCols = ArchitecturalPrimitives.buildSteelCoreColumns(
                world = world, colPositions = colCoords, baseY = curFloorY, height = hFloor,
                halfThick = 0.26f, hp = 540f, groundAnchored = false,
                supportIds = prevFloorIdList, targetTag = tag
            )

            // Front facade: wide doorways on Floor 1 (Lobby) & Balcony floors (2, 4, 5)
            val hasBalcony = (story == 2 || story == 4 || story == 5)
            if (story == 1 || hasBalcony) {
                val frontIds = ArchitecturalPrimitives.buildDoorwayWall(
                    world, Vec3(cx, curFloorY + hFloor * 0.5f, cz + halfD - 0.26f),
                    halfLength = halfW - 0.62f, height = hFloor, halfThick = 0.24f, alongX = true,
                    wallTile = wallTile, tint = wallTint, groundAnchored = false,
                    supportIds = prevFloorIdList, doorHalfW = 1.60f, targetTag = tag
                )
                if (story == 1) {
                    world.addBlock(
                        poly = VoronoiPolyhedron.createBox(3.8f, 0.14f, 1.15f),
                        center = Vec3(cx, curFloorY + 2.85f, cz + halfD + 0.88f),
                        orientation = Quat.fromAxisAngle(Vec3(1f, 0f, 0f), 0.20f),
                        tileId = MaterialTile.AWNING_TRIM, tint = Vec3(1f, 0.96f, 0.92f),
                        hp = 68f, supportIds = (frontIds + steelCols).toMutableList(), targetTag = tag, walkable = false
                    )
                } else {
                    ArchitecturalPrimitives.buildBalconyWithRailings(
                        world = world,
                        deckCenter = Vec3(cx, curFloorY - 0.10f, cz + halfD + 0.56f),
                        halfW = 3.8f,
                        halfD = 0.88f,
                        outwardNormal = Vec3(0f, 0f, 1f),
                        supportIds = prevFloorIdList,
                        targetTag = tag
                    )
                }
            } else {
                ArchitecturalPrimitives.buildWindowedWall(
                    world, Vec3(cx, curFloorY + hFloor * 0.5f, cz + halfD - 0.26f),
                    halfLength = halfW - 0.62f, height = hFloor, halfThick = 0.24f, alongX = true,
                    wallTile = wallTile, tint = wallTint, groundAnchored = false,
                    supportIds = prevFloorIdList, windowHalfW = 2.8f, targetTag = tag
                )
            }

            ArchitecturalPrimitives.buildWindowedWall(
                world, Vec3(cx, curFloorY + hFloor * 0.5f, cz - halfD + 0.26f),
                halfLength = halfW - 0.62f, height = hFloor, halfThick = 0.24f, alongX = true,
                wallTile = wallTile, tint = wallTint, groundAnchored = false,
                supportIds = prevFloorIdList, windowHalfW = 2.8f, targetTag = tag
            )
            for (side in listOf(-1f, 1f)) {
                ArchitecturalPrimitives.buildWindowedWall(
                    world, Vec3(cx + side * (halfW - 0.26f), curFloorY + hFloor * 0.5f, cz),
                    halfLength = halfD - 0.62f, height = hFloor, halfThick = 0.24f, alongX = false,
                    wallTile = wallTile, tint = wallTint, groundAnchored = false,
                    supportIds = prevFloorIdList, windowHalfW = 2.1f, targetTag = tag
                )
            }

            if (story == 3) {
                for (side in listOf(-1f, 1f)) {
                    world.addBlock(
                        poly = VoronoiPolyhedron.createBox(halfW + 0.14f, 0.10f, 0.16f),
                        center = Vec3(cx, curFloorY + hFloor + 0.08f, cz + side * (halfD + 0.04f)),
                        tileId = MaterialTile.CONCRETE, tint = Vec3(0.88f, 0.87f, 0.84f),
                        hp = 95f, supportIds = prevFloorIdList.toMutableList(), targetTag = tag, walkable = false
                    )
                }
            }

            if (story == 1) {
                ArchitecturalPrimitives.buildInteriorRoomProps(world, cx + 0.6f, curFloorY, cz - 0.8f, prevFloorIdList, targetTag = tag)
            } else {
                ArchitecturalPrimitives.buildOfficeSuiteProps(world, cx - 0.4f, curFloorY, cz - 0.6f, prevFloorIdList, targetTag = tag)
                if (story % 2 == 0) {
                    world.addBlock(
                        poly = VoronoiPolyhedron.createBox(0.10f, hFloor * 0.43f, 1.85f),
                        center = Vec3(cx - 2.4f, curFloorY + hFloor * 0.43f, cz + 1.1f),
                        tileId = MaterialTile.TIMBER, tint = Vec3(0.90f, 0.82f, 0.70f),
                        hp = 72f, supportIds = prevFloorIdList.toMutableList(), targetTag = tag
                    )
                } else {
                    world.addBlock(
                        poly = VoronoiPolyhedron.createBox(0.10f, hFloor * 0.43f, 1.85f),
                        center = Vec3(cx + 2.4f, curFloorY + hFloor * 0.43f, cz + 1.1f),
                        tileId = MaterialTile.PLASTIC, tint = Vec3(0.86f, 0.90f, 0.94f),
                        hp = 48f, supportIds = prevFloorIdList.toMutableList(), targetTag = tag
                    )
                }
            }

            val nextSlabCenterY = curFloorY + hFloor + slabT * 0.5f
            val nextFloorY = nextSlabCenterY + slabT * 0.5f

            val plan = if (story % 2 == 1) {
                StairPlan(
                    cx - 7.1f, cx - 3.7f, cz - 2.9f, cz + 2.7f,
                    Vec3(cx - 5.4f, curFloorY, cz + 2.5f),
                    Vec3(cx - 5.4f, nextFloorY, cz - 3.1f)
                )
            } else {
                StairPlan(
                    cx + 3.7f, cx + 7.1f, cz - 2.7f, cz + 2.9f,
                    Vec3(cx + 5.4f, curFloorY, cz - 2.5f),
                    Vec3(cx + 5.4f, nextFloorY, cz + 3.1f)
                )
            }

            val nextSlabIds = ArchitecturalPrimitives.buildSegmentedFloorWithCutout(
                world = world, cx = cx, cz = cz, centerY = nextSlabCenterY,
                halfW = halfW - 0.1f, halfD = halfD - 0.1f, halfThick = slabT * 0.5f,
                holeMinX = plan.holeMinX, holeMaxX = plan.holeMaxX,
                holeMinZ = plan.holeMinZ, holeMaxZ = plan.holeMaxZ,
                tileId = MaterialTile.CONCRETE, tint = Vec3(0.84f, 0.84f, 0.82f),
                hp = 235f, supports = steelCols, targetTag = tag
            )

            ArchitecturalPrimitives.buildStairFlight(
                world = world,
                start = plan.start,
                end = plan.end,
                halfWidth = 1.05f,
                tileId = MaterialTile.STAIR_TREAD,
                hp = 150f,
                groundAnchored = false,
                bottomSupports = prevFloorIdList,
                topSupports = nextSlabIds,
                targetTag = tag
            )

            prevFloorIdList = nextSlabIds
            curFloorY = nextFloorY
        }

        val roofY = curFloorY
        for (side in listOf(-1f, 1f)) {
            world.addBlock(
                poly = VoronoiPolyhedron.createBox(halfW - 0.14f, 0.50f, 0.20f),
                center = Vec3(cx, roofY + 0.50f, cz + side * (halfD - 0.26f)),
                tileId = MaterialTile.BRICK, tint = Vec3(0.92f, 0.88f, 0.84f),
                hp = 125f, supportIds = prevFloorIdList.toMutableList(), targetTag = tag
            )
            world.addBlock(
                poly = VoronoiPolyhedron.createBox(0.20f, 0.50f, halfD - 0.38f),
                center = Vec3(cx + side * (halfW - 0.26f), roofY + 0.50f, cz),
                tileId = MaterialTile.BRICK, tint = Vec3(0.92f, 0.88f, 0.84f),
                hp = 125f, supportIds = prevFloorIdList.toMutableList(), targetTag = tag
            )
        }
        world.addBlock(
            poly = VoronoiPolyhedron.createBox(1.3f, 0.60f, 0.95f),
            center = Vec3(cx + 1.0f, roofY + 0.60f, cz - 3.2f),
            tileId = MaterialTile.CORRUGATED_METAL, tint = Vec3(0.76f, 0.80f, 0.84f),
            hp = 105f, supportIds = prevFloorIdList.toMutableList(), targetTag = tag
        )
        world.addBlock(
            poly = VoronoiPolyhedron.createCylinderApprox(0.92f, 0.98f, 10),
            center = Vec3(cx - 0.8f, roofY + 0.98f, cz + 3.0f),
            tileId = MaterialTile.PLASTIC, tint = Vec3(0.20f, 0.48f, 0.74f),
            hp = 64f, supportIds = prevFloorIdList.toMutableList(), targetTag = tag
        )
    }

    /**
     * 4-Story Hardware & Supply Megastore stocked with warehouse shelving racks, polymer crates,
     * exterior balconies, and wide interior stairs climbing every floor to the roof terrace.
     */
    fun buildHardwareStore(world: StructuralWorld, terrain: ChunkedTerrain, cx: Float, cz: Float) {
        val tag = "HARDWARE"
        val halfW = 9.2f
        val halfD = 7.0f
        val hFloor = 3.45f
        val slabT = 0.34f

        val (foundIds, floor1Y) = ArchitecturalPrimitives.buildGradedFoundation(
            world = world, terrain = terrain, cx = cx, cz = cz,
            halfW = halfW, halfD = halfD,
            entryOffset = Vec3(0f, 0f, -halfD - 0.28f),
            entryHalfW = 1.9f,
            targetTag = tag
        )

        val hwCols = listOf(
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

        for (story in 1..4) {
            val steelCols = ArchitecturalPrimitives.buildSteelCoreColumns(
                world = world, colPositions = hwCols, baseY = curFloorY, height = hFloor,
                halfThick = 0.24f, hp = 510f, groundAnchored = false, supportIds = prevFloorIds, targetTag = tag
            )

            val frontTile = when (story) {
                1 -> MaterialTile.TIMBER
                2 -> MaterialTile.CORRUGATED_METAL
                3 -> MaterialTile.PLASTIC
                else -> MaterialTile.TIMBER
            }
            val frontTint = when (story) {
                1 -> Vec3(0.95f, 0.90f, 0.82f)
                2 -> Vec3(0.84f, 0.88f, 0.86f)
                3 -> Vec3(0.94f, 0.96f, 0.98f)
                else -> Vec3(0.92f, 0.86f, 0.76f)
            }

            if (story == 1 || story == 3) {
                val frontIds = ArchitecturalPrimitives.buildDoorwayWall(
                    world, Vec3(cx, curFloorY + hFloor * 0.5f, cz - halfD + 0.26f),
                    halfLength = halfW - 0.62f, height = hFloor, halfThick = 0.24f, alongX = true,
                    wallTile = frontTile, tint = frontTint, groundAnchored = false,
                    supportIds = prevFloorIds, doorHalfW = 1.65f, targetTag = tag
                )
                if (story == 1) {
                    world.addBlock(
                        poly = VoronoiPolyhedron.createBox(5.2f, 0.52f, 0.18f),
                        center = Vec3(cx, curFloorY + hFloor - 0.16f, cz - halfD - 0.10f),
                        tileId = MaterialTile.PLASTIC, tint = Vec3(0.96f, 0.62f, 0.14f),
                        hp = 58f, supportIds = (frontIds + steelCols).toMutableList(), targetTag = tag, walkable = false
                    )
                } else {
                    ArchitecturalPrimitives.buildBalconyWithRailings(
                        world = world,
                        deckCenter = Vec3(cx, curFloorY - 0.10f, cz - halfD - 0.54f),
                        halfW = 3.8f, halfD = 0.84f, outwardNormal = Vec3(0f, 0f, -1f),
                        supportIds = prevFloorIds, targetTag = tag
                    )
                }
            } else {
                ArchitecturalPrimitives.buildWindowedWall(
                    world, Vec3(cx, curFloorY + hFloor * 0.5f, cz - halfD + 0.26f),
                    halfLength = halfW - 0.62f, height = hFloor, halfThick = 0.24f, alongX = true,
                    wallTile = frontTile, tint = frontTint, groundAnchored = false,
                    supportIds = prevFloorIds, windowHalfW = 2.5f, targetTag = tag
                )
            }

            ArchitecturalPrimitives.buildWindowedWall(
                world, Vec3(cx, curFloorY + hFloor * 0.5f, cz + halfD - 0.26f),
                halfLength = halfW - 0.62f, height = hFloor, halfThick = 0.24f, alongX = true,
                wallTile = MaterialTile.CORRUGATED_METAL, tint = Vec3(0.84f, 0.88f, 0.86f),
                groundAnchored = false, supportIds = prevFloorIds, windowHalfW = 2.4f, targetTag = tag
            )
            for (side in listOf(-1f, 1f)) {
                ArchitecturalPrimitives.buildWindowedWall(
                    world, Vec3(cx + side * (halfW - 0.26f), curFloorY + hFloor * 0.5f, cz),
                    halfLength = halfD - 0.62f, height = hFloor, halfThick = 0.24f, alongX = false,
                    wallTile = if (story % 2 == 1) MaterialTile.TIMBER else MaterialTile.CORRUGATED_METAL,
                    tint = Vec3(0.92f, 0.88f, 0.80f),
                    groundAnchored = false, supportIds = prevFloorIds, windowHalfW = 1.9f, targetTag = tag
                )
            }

            ArchitecturalPrimitives.buildWarehouseRack(world, cx - 0.4f, curFloorY, cz - 1.8f, alongX = true, prevFloorIds, tag)
            ArchitecturalPrimitives.buildWarehouseRack(world, cx + 0.4f, curFloorY, cz + 1.8f, alongX = true, prevFloorIds, tag)
            if (story == 1) {
                ArchitecturalPrimitives.buildInteriorRoomProps(world, cx + 1.8f, curFloorY, cz, prevFloorIds, tag)
            }

            val nextSlabY = curFloorY + hFloor + slabT * 0.5f
            val nextFloorY = nextSlabY + slabT * 0.5f
            val plan = if (story % 2 == 1) {
                StairPlan(
                    cx - 7.0f, cx - 3.6f, cz - 2.8f, cz + 2.8f,
                    Vec3(cx - 5.3f, curFloorY, cz - 2.5f),
                    Vec3(cx - 5.3f, nextFloorY, cz + 2.9f)
                )
            } else {
                StairPlan(
                    cx + 3.6f, cx + 7.0f, cz - 2.8f, cz + 2.8f,
                    Vec3(cx + 5.3f, curFloorY, cz + 2.5f),
                    Vec3(cx + 5.3f, nextFloorY, cz - 2.9f)
                )
            }

            val nextSlabIds = ArchitecturalPrimitives.buildSegmentedFloorWithCutout(
                world = world, cx = cx, cz = cz, centerY = nextSlabY,
                halfW = halfW - 0.08f, halfD = halfD - 0.08f, halfThick = slabT * 0.5f,
                holeMinX = plan.holeMinX, holeMaxX = plan.holeMaxX,
                holeMinZ = plan.holeMinZ, holeMaxZ = plan.holeMaxZ,
                tileId = if (story == 4) MaterialTile.CORRUGATED_METAL else MaterialTile.TIMBER,
                tint = if (story == 4) Vec3(0.76f, 0.80f, 0.82f) else Vec3(0.90f, 0.82f, 0.70f),
                hp = 215f, supports = steelCols, targetTag = tag
            )
            ArchitecturalPrimitives.buildStairFlight(
                world = world, start = plan.start, end = plan.end,
                halfWidth = 1.02f, tileId = MaterialTile.STAIR_TREAD,
                hp = 140f, groundAnchored = false, bottomSupports = prevFloorIds, topSupports = nextSlabIds, targetTag = tag
            )

            prevFloorIds = nextSlabIds
            curFloorY = nextFloorY
        }

        for (side in listOf(-1f, 1f)) {
            world.addBlock(
                poly = VoronoiPolyhedron.createBox(halfW - 0.12f, 0.38f, 0.16f),
                center = Vec3(cx, curFloorY + 0.38f, cz + side * (halfD - 0.20f)),
                tileId = MaterialTile.CORRUGATED_METAL, tint = Vec3(0.74f, 0.78f, 0.80f),
                hp = 115f, supportIds = prevFloorIds.toMutableList(), targetTag = tag
            )
        }
    }

    /**
     * 4-Story Corner Shop, Café & Furnished Apartments with striped awning, exterior balconies, and interior stairs.
     */
    fun buildCornerShop(world: StructuralWorld, terrain: ChunkedTerrain, cx: Float, cz: Float) {
        val halfW = 7.2f
        val halfD = 5.8f
        val hFloor = 3.35f
        val slabT = 0.32f

        val (foundIds, floor1Y) = ArchitecturalPrimitives.buildGradedFoundation(
            world = world, terrain = terrain, cx = cx, cz = cz,
            halfW = halfW, halfD = halfD,
            entryOffset = Vec3(0f, 0f, halfD + 0.28f),
            entryHalfW = 1.65f
        )

        val shopCols = listOf(
            (cx - halfW + 0.32f) to (cz - halfD + 0.32f),
            (cx - 2.3f) to (cz - halfD + 0.32f),
            (cx + 2.3f) to (cz - halfD + 0.32f),
            (cx + halfW - 0.32f) to (cz - halfD + 0.32f),
            (cx - halfW + 0.32f) to (cz + halfD - 0.32f),
            (cx - 2.3f) to (cz + halfD - 0.32f),
            (cx + 2.3f) to (cz + halfD - 0.32f),
            (cx + halfW - 0.32f) to (cz + halfD - 0.32f)
        )

        var prevFloorIds = foundIds
        var curFloorY = floor1Y
        var topSupports: List<Int> = foundIds

        for (story in 1..4) {
            val wallTile = if (story <= 2) MaterialTile.BRICK else MaterialTile.STUCCO
            val wallTint = if (story <= 2) Vec3(0.95f, 0.92f, 0.90f) else Vec3(0.98f, 0.96f, 0.91f)
            val steelCols = ArchitecturalPrimitives.buildSteelCoreColumns(
                world = world, colPositions = shopCols, baseY = curFloorY, height = hFloor,
                halfThick = 0.22f, hp = 460f, groundAnchored = false, supportIds = prevFloorIds
            )
            val storyTop = ArrayList<Int>(steelCols)

            if (story == 1 || story == 2 || story == 3) {
                val frontIds = ArchitecturalPrimitives.buildDoorwayWall(
                    world, Vec3(cx, curFloorY + hFloor * 0.5f, cz + halfD - 0.25f),
                    halfLength = halfW - 0.54f, height = hFloor, halfThick = 0.22f, alongX = true,
                    wallTile = wallTile, tint = wallTint, groundAnchored = false,
                    supportIds = prevFloorIds, doorHalfW = 1.35f
                )
                storyTop.addAll(frontIds)
                if (story == 1) {
                    world.addBlock(
                        poly = VoronoiPolyhedron.createBox(halfW * 0.78f, 0.13f, 1.15f),
                        center = Vec3(cx, curFloorY + 2.85f, cz + halfD + 0.85f),
                        orientation = Quat.fromAxisAngle(Vec3(1f, 0f, 0f), 0.22f),
                        tileId = MaterialTile.AWNING_TRIM, tint = Vec3(1f, 0.98f, 0.96f),
                        hp = 62f, supportIds = (frontIds + steelCols).toMutableList(), walkable = false
                    )
                } else {
                    ArchitecturalPrimitives.buildBalconyWithRailings(
                        world = world,
                        deckCenter = Vec3(cx, curFloorY - 0.10f, cz + halfD + 0.54f),
                        halfW = 3.1f, halfD = 0.82f, outwardNormal = Vec3(0f, 0f, 1f),
                        supportIds = prevFloorIds
                    )
                }
            } else {
                storyTop.addAll(
                    ArchitecturalPrimitives.buildWindowedWall(
                        world, Vec3(cx, curFloorY + hFloor * 0.5f, cz + halfD - 0.25f),
                        halfLength = halfW - 0.54f, height = hFloor, halfThick = 0.22f, alongX = true,
                        wallTile = wallTile, tint = wallTint, groundAnchored = false,
                        supportIds = prevFloorIds, windowHalfW = 1.9f
                    )
                )
            }

            storyTop.addAll(
                ArchitecturalPrimitives.buildWindowedWall(
                    world, Vec3(cx, curFloorY + hFloor * 0.5f, cz - halfD + 0.25f),
                    halfLength = halfW - 0.54f, height = hFloor, halfThick = 0.22f, alongX = true,
                    wallTile = wallTile, tint = wallTint, groundAnchored = false,
                    supportIds = prevFloorIds, windowHalfW = 1.9f
                )
            )
            for (side in listOf(-1f, 1f)) {
                storyTop.addAll(
                    ArchitecturalPrimitives.buildWindowedWall(
                        world, Vec3(cx + side * (halfW - 0.25f), curFloorY + hFloor * 0.5f, cz),
                        halfLength = halfD - 0.56f, height = hFloor, halfThick = 0.22f, alongX = false,
                        wallTile = wallTile, tint = wallTint, groundAnchored = false,
                        supportIds = prevFloorIds, windowHalfW = 1.5f
                    )
                )
            }

            if (story == 1) {
                ArchitecturalPrimitives.buildInteriorRoomProps(world, cx + 1.0f, curFloorY, cz - 0.7f, prevFloorIds)
            } else {
                ArchitecturalPrimitives.buildOfficeSuiteProps(world, cx, curFloorY, cz - 0.5f, prevFloorIds)
            }

            if (story < 4) {
                val nextSlabY = curFloorY + hFloor + slabT * 0.5f
                val nextFloorY = nextSlabY + slabT * 0.5f
                val plan = if (story % 2 == 1) {
                    StairPlan(
                        cx - 5.6f, cx - 2.6f, cz - 2.5f, cz + 2.5f,
                        Vec3(cx - 4.1f, curFloorY, cz + 2.2f),
                        Vec3(cx - 4.1f, nextFloorY, cz - 2.7f)
                    )
                } else {
                    StairPlan(
                        cx + 2.6f, cx + 5.6f, cz - 2.5f, cz + 2.5f,
                        Vec3(cx + 4.1f, curFloorY, cz - 2.2f),
                        Vec3(cx + 4.1f, nextFloorY, cz + 2.7f)
                    )
                }

                val nextSlabIds = ArchitecturalPrimitives.buildSegmentedFloorWithCutout(
                    world = world, cx = cx, cz = cz, centerY = nextSlabY,
                    halfW = halfW + 0.08f, halfD = halfD + 0.08f, halfThick = slabT * 0.5f,
                    holeMinX = plan.holeMinX, holeMaxX = plan.holeMaxX,
                    holeMinZ = plan.holeMinZ, holeMaxZ = plan.holeMaxZ,
                    tileId = MaterialTile.CONCRETE, tint = Vec3(0.84f, 0.84f, 0.82f),
                    hp = 210f, supports = steelCols
                )
                ArchitecturalPrimitives.buildStairFlight(
                    world = world, start = plan.start, end = plan.end,
                    halfWidth = 0.96f, tileId = MaterialTile.STAIR_TREAD,
                    hp = 135f, groundAnchored = false, bottomSupports = prevFloorIds, topSupports = nextSlabIds
                )
                prevFloorIds = nextSlabIds
                curFloorY = nextFloorY
            } else {
                topSupports = storyTop
            }
        }

        ArchitecturalPrimitives.buildDetailedRoof(
            world, cx, cz, eaveY = curFloorY + hFloor, halfW = halfW, halfD = halfD,
            roofHy = 1.50f, roofTile = MaterialTile.ROOF_SLATE, supports = topSupports, withChimney = true
        )
    }

    /**
     * 5-Tier Civic Hall & Climbable Clock Tower:
     * 3 Civic Hall Stories -> Tier 4 Tower Observatory -> Tier 5 High Belfry & Spire.
     */
    fun buildClockTowerHall(world: StructuralWorld, terrain: ChunkedTerrain, cx: Float, cz: Float) {
        val tag = "TOWN_HALL"
        val halfW = 9.0f
        val halfD = 7.2f
        val hallH = 3.45f
        val slabT = 0.34f

        val (foundIds, floor1Y) = ArchitecturalPrimitives.buildGradedFoundation(
            world = world, terrain = terrain, cx = cx, cz = cz,
            halfW = halfW, halfD = halfD,
            entryOffset = Vec3(0f, 0f, halfD + 0.28f),
            entryHalfW = 1.9f,
            tileId = MaterialTile.RIVER_STONE,
            tint = Vec3(0.86f, 0.85f, 0.82f),
            targetTag = tag
        )

        val hallCols = listOf(
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
        var tier3Supports: List<Int> = foundIds

        for (story in 1..3) {
            val steelCols = ArchitecturalPrimitives.buildSteelCoreColumns(
                world = world, colPositions = hallCols, baseY = curFloorY, height = hallH,
                halfThick = 0.26f, hp = 540f, groundAnchored = false, supportIds = prevFloorIds, targetTag = tag
            )
            val storyTop = ArrayList<Int>(steelCols)

            if (story == 1 || story == 2) {
                storyTop.addAll(
                    ArchitecturalPrimitives.buildDoorwayWall(
                        world, Vec3(cx, curFloorY + hallH * 0.5f, cz + halfD - 0.28f),
                        halfLength = halfW - 0.62f, height = hallH, halfThick = 0.24f, alongX = true,
                        wallTile = MaterialTile.BRICK, tint = Vec3(0.95f, 0.92f, 0.90f),
                        groundAnchored = false, supportIds = prevFloorIds, doorHalfW = 1.65f, targetTag = tag
                    )
                )
                if (story == 1) {
                    // Grand Portico Stone Columns grounded firmly into terrain
                    for (sx in listOf(-2.8f, 2.8f)) {
                        val px = cx + sx
                        val pz = cz + halfD + 0.95f
                        val pBot = min(curFloorY, terrain.groundSurfaceY(px, pz)) - 0.14f
                        val pTop = curFloorY + hallH
                        val pH = max(1.0f, pTop - pBot)
                        world.addBlock(
                            poly = VoronoiPolyhedron.createCylinderApprox(0.32f, pH * 0.5f, 10),
                            center = Vec3(px, pBot + pH * 0.5f, pz),
                            tileId = MaterialTile.CONCRETE, tint = Vec3(0.92f, 0.90f, 0.86f),
                            hp = 195f, groundAnchored = true, targetTag = tag
                        )
                    }
                } else {
                    ArchitecturalPrimitives.buildBalconyWithRailings(
                        world = world,
                        deckCenter = Vec3(cx, curFloorY - 0.10f, cz + halfD + 0.56f),
                        halfW = 3.6f, halfD = 0.88f, outwardNormal = Vec3(0f, 0f, 1f),
                        supportIds = prevFloorIds, targetTag = tag
                    )
                }
            } else {
                storyTop.addAll(
                    ArchitecturalPrimitives.buildWindowedWall(
                        world, Vec3(cx, curFloorY + hallH * 0.5f, cz + halfD - 0.28f),
                        halfLength = halfW - 0.62f, height = hallH, halfThick = 0.24f, alongX = true,
                        wallTile = MaterialTile.BRICK, tint = Vec3(0.95f, 0.92f, 0.90f),
                        groundAnchored = false, supportIds = prevFloorIds, windowHalfW = 2.4f, targetTag = tag
                    )
                )
            }

            storyTop.addAll(
                ArchitecturalPrimitives.buildWindowedWall(
                    world, Vec3(cx, curFloorY + hallH * 0.5f, cz - halfD + 0.28f),
                    halfLength = halfW - 0.62f, height = hallH, halfThick = 0.24f, alongX = true,
                    wallTile = MaterialTile.BRICK, tint = Vec3(0.95f, 0.92f, 0.90f),
                    groundAnchored = false, supportIds = prevFloorIds, windowHalfW = 2.4f, targetTag = tag
                )
            )
            for (side in listOf(-1f, 1f)) {
                storyTop.addAll(
                    ArchitecturalPrimitives.buildWindowedWall(
                        world, Vec3(cx + side * (halfW - 0.28f), curFloorY + hallH * 0.5f, cz),
                        halfLength = halfD - 0.62f, height = hallH, halfThick = 0.24f, alongX = false,
                        wallTile = MaterialTile.BRICK, tint = Vec3(0.95f, 0.92f, 0.90f),
                        groundAnchored = false, supportIds = prevFloorIds, windowHalfW = 1.9f, targetTag = tag
                    )
                )
            }

            ArchitecturalPrimitives.buildOfficeSuiteProps(world, cx + 0.3f, curFloorY, cz - 0.6f, prevFloorIds, targetTag = tag)

            val nextSlabY = curFloorY + hallH + slabT * 0.5f
            val nextFloorY = nextSlabY + slabT * 0.5f
            val plan = if (story % 2 == 1) {
                StairPlan(
                    cx - 7.8f, cx - 4.0f, cz - 2.6f, cz + 2.6f,
                    Vec3(cx - 5.9f, curFloorY, cz + 2.4f),
                    Vec3(cx - 5.9f, nextFloorY, cz - 2.8f)
                )
            } else {
                StairPlan(
                    cx + 4.0f, cx + 7.8f, cz - 2.6f, cz + 2.6f,
                    Vec3(cx + 5.9f, curFloorY, cz - 2.4f),
                    Vec3(cx + 5.9f, nextFloorY, cz + 2.8f)
                )
            }

            val nextSlabIds = ArchitecturalPrimitives.buildSegmentedFloorWithCutout(
                world = world, cx = cx, cz = cz, centerY = nextSlabY,
                halfW = halfW + 0.1f, halfD = halfD + 0.1f, halfThick = slabT * 0.5f,
                holeMinX = plan.holeMinX, holeMaxX = plan.holeMaxX,
                holeMinZ = plan.holeMinZ, holeMaxZ = plan.holeMaxZ,
                tileId = MaterialTile.CONCRETE, tint = Vec3(0.86f, 0.85f, 0.82f),
                hp = 235f, supports = if (story == 3) storyTop else steelCols, targetTag = tag
            )
            ArchitecturalPrimitives.buildStairFlight(
                world = world, start = plan.start, end = plan.end,
                halfWidth = 1.02f, tileId = MaterialTile.STAIR_TREAD,
                hp = 165f, groundAnchored = false, bottomSupports = prevFloorIds, topSupports = nextSlabIds, targetTag = tag
            )
            prevFloorIds = nextSlabIds
            curFloorY = nextFloorY
            tier3Supports = nextSlabIds
        }

        // Tier 4 & Tier 5: Climbable Double-Stage Clock Tower & Belfry Overlook
        val tZ = cz + 0.2f
        val towerHalf = 2.40f
        val shaftH = 3.5f
        val shaftCols = ArchitecturalPrimitives.buildSteelCoreColumns(
            world = world,
            colPositions = listOf(
                (cx - towerHalf) to (tZ - towerHalf),
                (cx + towerHalf) to (tZ - towerHalf),
                (cx - towerHalf) to (tZ + towerHalf),
                (cx + towerHalf) to (tZ + towerHalf)
            ),
            baseY = curFloorY, height = shaftH, halfThick = 0.28f, hp = 440f,
            groundAnchored = false, supportIds = tier3Supports, targetTag = tag
        )
        val belfryY = curFloorY + shaftH + 0.18f
        val belfryFloorY = belfryY + 0.18f
        val belfrySlabId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(towerHalf + 0.30f, 0.18f, towerHalf + 0.30f),
            center = Vec3(cx, belfryY, tZ),
            tileId = MaterialTile.STUCCO, tint = Vec3(0.98f, 0.96f, 0.90f),
            hp = 175f, supportIds = shaftCols.toMutableList(), targetTag = tag, checkCantilever = true
        )
        ArchitecturalPrimitives.buildStairFlight(
            world = world,
            start = Vec3(cx - 5.8f, curFloorY, tZ + 2.35f),
            end = Vec3(cx - 1.7f, belfryFloorY, tZ + 2.35f),
            halfWidth = 0.85f, tileId = MaterialTile.STAIR_TREAD,
            hp = 140f, groundAnchored = false, bottomSupports = tier3Supports, topSupports = listOf(belfrySlabId), targetTag = tag
        )

        // Tier 5 Belfry Observation Deck & Spire
        val belfryPosts = ArchitecturalPrimitives.buildSteelCoreColumns(
            world = world,
            colPositions = listOf(
                (cx - towerHalf + 0.22f) to (tZ - towerHalf + 0.22f),
                (cx + towerHalf - 0.22f) to (tZ - towerHalf + 0.22f),
                (cx - towerHalf + 0.22f) to (tZ + towerHalf - 0.22f),
                (cx + towerHalf - 0.22f) to (tZ + towerHalf - 0.22f)
            ),
            baseY = belfryFloorY, height = 2.85f, halfThick = 0.22f, hp = 320f,
            groundAnchored = false, supportIds = listOf(belfrySlabId), targetTag = tag
        )
        for (side in listOf(-1f, 1f)) {
            world.addBlock(
                poly = VoronoiPolyhedron.createBox(1.05f, 1.05f, 0.14f),
                center = Vec3(cx, belfryFloorY + 1.42f, tZ + side * (towerHalf + 0.14f)),
                tileId = MaterialTile.CLOCK_FACE, tint = Vec3(1.05f, 1.03f, 0.98f),
                hp = 68f, supportIds = belfryPosts.toMutableList(), targetTag = tag, walkable = false
            )
        }
        world.addBlock(
            poly = VoronoiPolyhedron.createTaperedCanopy(3.15f, 0.18f, 2.55f, 4),
            center = Vec3(cx, belfryFloorY + 5.3f, tZ),
            orientation = Quat.fromAxisAngle(Vec3.UP, 0.785f),
            tileId = MaterialTile.ROOF_SLATE, tint = Vec3(0.82f, 0.85f, 0.90f),
            hp = 125f, supportIds = belfryPosts.toMutableList(), targetTag = tag
        )
    }

    /**
     * Expanded Gas Station & 2-Story Service Garage with climbable roof overlook, 3 explosive fuel pumps, and 4 canopy columns.
     */
    fun buildGasStation(world: StructuralWorld, terrain: ChunkedTerrain, cx: Float, cz: Float) {
        val canopyX = cx
        val canopyZ = cz - 4.2f
        val baseY = terrain.groundSurfaceY(canopyX, canopyZ)
        val colH = 3.8f
        val cols = ArrayList<Int>()
        for (sx in listOf(-4.2f, 4.2f)) {
            for (sz in listOf(-2.0f, 2.0f)) {
                val px = canopyX + sx
                val pz = canopyZ + sz
                val cBot = min(baseY, terrain.groundSurfaceY(px, pz)) - 0.14f
                val cTop = baseY + colH
                val cH = max(1.0f, cTop - cBot)
                cols.add(
                    world.addBlock(
                        poly = VoronoiPolyhedron.createBox(0.26f, cH * 0.5f, 0.26f),
                        center = Vec3(px, cBot + cH * 0.5f, pz),
                        tileId = MaterialTile.STEEL, tint = Vec3(0.88f, 0.90f, 0.92f),
                        hp = 420f, groundAnchored = true, isCoreSupport = true
                    )
                )
            }
        }
        val canopyId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(5.4f, 0.36f, 3.0f),
            center = Vec3(canopyX, baseY + colH + 0.36f, canopyZ),
            tileId = MaterialTile.PLASTIC, tint = Vec3(0.95f, 0.36f, 0.20f),
            hp = 120f, supportIds = cols, checkCantilever = true
        )
        for (px in listOf(-2.4f, 0f, 2.4f)) {
            val gy = terrain.groundSurfaceY(canopyX + px, canopyZ)
            world.addBlock(
                poly = VoronoiPolyhedron.createBox(0.42f, 0.92f, 0.34f),
                center = Vec3(canopyX + px, gy + 0.92f, canopyZ),
                tileId = MaterialTile.STEEL, tint = Vec3(0.95f, 0.30f, 0.22f),
                hp = 38f, groundAnchored = true, isFuelExplosive = true
            )
        }

        // Enlarged 2-Story Service Garage (+Z side)
        val garZ = cz + 3.8f
        val halfW = 6.2f
        val halfD = 3.8f
        val h1 = 3.40f
        val h2 = 3.15f
        val (garFounds, garFloorY) = ArchitecturalPrimitives.buildGradedFoundation(
            world, terrain, cx, garZ, halfW = halfW, halfD = halfD,
            entryOffset = Vec3(0f, 0f, -halfD - 0.1f), entryHalfW = 1.65f
        )
        val garCols1 = ArchitecturalPrimitives.buildSteelCoreColumns(
            world,
            listOf(
                (cx - halfW + 0.32f) to (garZ - halfD + 0.32f), (cx - 2.1f) to (garZ - halfD + 0.32f),
                (cx + 2.1f) to (garZ - halfD + 0.32f), (cx + halfW - 0.32f) to (garZ - halfD + 0.32f),
                (cx - halfW + 0.32f) to (garZ + halfD - 0.32f), (cx - 2.1f) to (garZ + halfD - 0.32f),
                (cx + 2.1f) to (garZ + halfD - 0.32f), (cx + halfW - 0.32f) to (garZ + halfD - 0.32f)
            ),
            garFloorY, h1, hp = 440f, groundAnchored = false, supportIds = garFounds
        )
        ArchitecturalPrimitives.buildDoorwayWall(
            world, Vec3(cx, garFloorY + h1 * 0.5f, garZ - halfD + 0.26f),
            halfLength = halfW - 0.55f, height = h1, halfThick = 0.22f, alongX = true,
            wallTile = MaterialTile.CORRUGATED_METAL, tint = Vec3(0.92f, 0.92f, 0.88f),
            groundAnchored = false, supportIds = garFounds, doorHalfW = 1.65f
        )
        ArchitecturalPrimitives.buildWindowedWall(
            world, Vec3(cx, garFloorY + h1 * 0.5f, garZ + halfD - 0.26f),
            halfLength = halfW - 0.55f, height = h1, halfThick = 0.22f, alongX = true,
            wallTile = MaterialTile.STUCCO, tint = Vec3(0.92f, 0.90f, 0.86f),
            groundAnchored = false, supportIds = garFounds, windowHalfW = 1.75f
        )
        for (side in listOf(-1f, 1f)) {
            ArchitecturalPrimitives.buildWindowedWall(
                world, Vec3(cx + side * (halfW - 0.26f), garFloorY + h1 * 0.5f, garZ),
                halfLength = halfD - 0.55f, height = h1, halfThick = 0.22f, alongX = false,
                wallTile = MaterialTile.STUCCO, tint = Vec3(0.92f, 0.90f, 0.86f),
                groundAnchored = false, supportIds = garFounds, windowHalfW = 1.25f
            )
        }
        ArchitecturalPrimitives.buildWarehouseRack(world, cx + 4.1f, garFloorY, garZ + 0.6f, alongX = false, garFounds)
        ArchitecturalPrimitives.buildInteriorRoomProps(world, cx + 0.3f, garFloorY, garZ + 0.3f, garFounds)

        // Garage Floor 2 Slab + Wide Stairs
        val floor2SlabY = garFloorY + h1 + 0.16f
        val floor2Y = floor2SlabY + 0.16f
        val floor2Ids = ArchitecturalPrimitives.buildSegmentedFloorWithCutout(
            world = world, cx = cx, cz = garZ, centerY = floor2SlabY,
            halfW = halfW + 0.06f, halfD = halfD + 0.06f, halfThick = 0.16f,
            holeMinX = cx - 5.1f, holeMaxX = cx - 2.4f, holeMinZ = garZ - 2.0f, holeMaxZ = garZ + 2.0f,
            tileId = MaterialTile.CONCRETE, tint = Vec3(0.80f, 0.80f, 0.78f),
            hp = 210f, supports = garCols1
        )
        ArchitecturalPrimitives.buildStairFlight(
            world = world,
            start = Vec3(cx - 3.75f, garFloorY, garZ + 1.85f),
            end = Vec3(cx - 3.75f, floor2Y, garZ - 2.15f),
            halfWidth = 0.92f, tileId = MaterialTile.STAIR_TREAD,
            hp = 155f, groundAnchored = false, bottomSupports = garFounds, topSupports = floor2Ids
        )

        // 2nd Floor Office Overlook + Skybridge to Canopy + Exterior Stair
        val garCols2 = ArchitecturalPrimitives.buildSteelCoreColumns(
            world,
            listOf(
                (cx - halfW + 0.32f) to (garZ - halfD + 0.32f), (cx - 2.1f) to (garZ - halfD + 0.32f),
                (cx + 2.1f) to (garZ - halfD + 0.32f), (cx + halfW - 0.32f) to (garZ - halfD + 0.32f),
                (cx - halfW + 0.32f) to (garZ + halfD - 0.32f), (cx - 2.1f) to (garZ + halfD - 0.32f),
                (cx + 2.1f) to (garZ + halfD - 0.32f), (cx + halfW - 0.32f) to (garZ + halfD - 0.32f)
            ),
            floor2Y, h2, hp = 380f, groundAnchored = false, supportIds = floor2Ids
        )
        ArchitecturalPrimitives.buildWindowedWall(
            world, Vec3(cx, floor2Y + h2 * 0.5f, garZ + halfD - 0.25f),
            halfLength = halfW - 0.55f, height = h2, halfThick = 0.22f, alongX = true,
            wallTile = MaterialTile.STUCCO, tint = Vec3(0.95f, 0.93f, 0.88f),
            groundAnchored = false, supportIds = floor2Ids, windowHalfW = 1.75f
        )
        ArchitecturalPrimitives.buildWindowedWall(
            world, Vec3(cx, floor2Y + h2 * 0.5f, garZ - halfD + 0.25f),
            halfLength = halfW - 0.55f, height = h2, halfThick = 0.22f, alongX = true,
            wallTile = MaterialTile.STUCCO, tint = Vec3(0.95f, 0.93f, 0.88f),
            groundAnchored = false, supportIds = floor2Ids, windowHalfW = 1.75f
        )
        ArchitecturalPrimitives.buildOfficeSuiteProps(world, cx + 1.5f, floor2Y, garZ, floor2Ids)
        val garRoofY = floor2Y + h2 + 0.16f
        val garRoofId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(halfW + 0.1f, 0.16f, halfD + 0.1f),
            center = Vec3(cx, garRoofY, garZ),
            tileId = MaterialTile.CONCRETE, tint = Vec3(0.80f, 0.80f, 0.78f),
            hp = 195f, supportIds = garCols2.toMutableList(), checkCantilever = true
        )
        ArchitecturalPrimitives.buildStairFlight(
            world = world,
            start = Vec3(cx + 7.1f, floor2Y, garZ + 2.5f),
            end = Vec3(cx + 7.1f, garRoofY + 0.16f, garZ - 2.4f),
            halfWidth = 0.85f, tileId = MaterialTile.STAIR_TREAD,
            hp = 145f, groundAnchored = false, bottomSupports = floor2Ids, topSupports = listOf(garRoofId)
        )
        // Skybridge connecting Floor 2 Garage Slab to Gas Canopy
        world.addBlock(
            poly = VoronoiPolyhedron.createBox(0.75f, 0.14f, 0.82f),
            center = Vec3(cx, baseY + colH + 0.36f, cz - 0.2f),
            tileId = MaterialTile.STEEL, tint = Vec3(0.72f, 0.75f, 0.78f),
            hp = 110f, supportIds = (listOf(canopyId) + floor2Ids).toMutableList(),
            supportGroups = mutableListOf(mutableListOf(canopyId), floor2Ids.toMutableList())
        )
    }

    /**
     * Spacious Porch Cottage with furnished 1F interior, porch rails, and wooden stair ramp up to an enterable attic loft!
     */
    fun buildPorchCottage(
        world: StructuralWorld,
        terrain: ChunkedTerrain,
        cx: Float,
        cz: Float,
        wallTile: Int = MaterialTile.STUCCO
    ) {
        val halfW = 5.2f
        val halfD = 4.2f
        val wallH = 3.20f

        val (foundIds, floor1Y) = ArchitecturalPrimitives.buildGradedFoundation(
            world, terrain, cx, cz, halfW, halfD,
            entryOffset = Vec3(0f, 0f, halfD + 0.32f), entryHalfW = 1.35f
        )

        val sups = ArrayList<Int>(
            ArchitecturalPrimitives.buildSteelCoreColumns(
                world,
                listOf(
                    (cx - halfW + 0.26f) to (cz - halfD + 0.26f),
                    (cx - 1.7f) to (cz - halfD + 0.26f),
                    (cx + 1.7f) to (cz - halfD + 0.26f),
                    (cx + halfW - 0.26f) to (cz - halfD + 0.26f),
                    (cx - halfW + 0.26f) to (cz + halfD - 0.26f),
                    (cx - 1.7f) to (cz + halfD - 0.26f),
                    (cx + 1.7f) to (cz + halfD - 0.26f),
                    (cx + halfW - 0.26f) to (cz + halfD - 0.26f)
                ),
                floor1Y, wallH, halfThick = 0.18f, hp = 360f, groundAnchored = false, supportIds = foundIds
            )
        )
        val porchPosts = ArrayList<Int>()
        for (px in listOf(-3.8f, 3.8f)) {
            val wx = cx + px
            val wz = cz + halfD + 0.95f
            val pBot = min(floor1Y, terrain.groundSurfaceY(wx, wz)) - 0.12f
            val pTop = floor1Y + wallH
            val pH = max(1.0f, pTop - pBot)
            porchPosts.add(
                world.addBlock(
                    poly = VoronoiPolyhedron.createBox(0.16f, pH * 0.5f, 0.16f),
                    center = Vec3(wx, pBot + pH * 0.5f, wz),
                    tileId = MaterialTile.TIMBER, tint = Vec3(0.94f, 0.90f, 0.84f),
                    hp = 82f, groundAnchored = true
                )
            )
        }
        val frontDoorIds = ArchitecturalPrimitives.buildDoorwayWall(
            world, Vec3(cx, floor1Y + wallH * 0.5f, cz + halfD - 0.25f),
            halfLength = halfW - 0.48f, height = wallH, halfThick = 0.20f, alongX = true,
            wallTile = wallTile, tint = Vec3(0.96f, 0.94f, 0.90f),
            groundAnchored = false, supportIds = foundIds, doorHalfW = 1.25f
        )
        sups.addAll(frontDoorIds)
        sups.addAll(
            ArchitecturalPrimitives.buildWindowedWall(
                world, Vec3(cx, floor1Y + wallH * 0.5f, cz - halfD + 0.25f),
                halfLength = halfW - 0.48f, height = wallH, halfThick = 0.20f, alongX = true,
                wallTile = wallTile, tint = Vec3(0.96f, 0.94f, 0.90f),
                groundAnchored = false, supportIds = foundIds, windowHalfW = 1.35f
            )
        )
        for (side in listOf(-1f, 1f)) {
            sups.addAll(
                ArchitecturalPrimitives.buildWindowedWall(
                    world, Vec3(cx + side * (halfW - 0.25f), floor1Y + wallH * 0.5f, cz),
                    halfLength = halfD - 0.52f, height = wallH, halfThick = 0.20f, alongX = false,
                    wallTile = wallTile, tint = Vec3(0.95f, 0.93f, 0.89f),
                    groundAnchored = false, supportIds = foundIds, windowHalfW = 1.15f
                )
            )
        }
        ArchitecturalPrimitives.buildInteriorRoomProps(world, cx + 1.2f, floor1Y, cz - 0.6f, foundIds)

        val loftY = floor1Y + wallH + 0.14f
        val loftIds = ArchitecturalPrimitives.buildSegmentedFloorWithCutout(
            world = world, cx = cx, cz = cz, centerY = loftY,
            halfW = halfW - 0.08f, halfD = halfD - 0.08f, halfThick = 0.14f,
            holeMinX = cx - 4.4f, holeMaxX = cx - 2.0f, holeMinZ = cz - 1.8f, holeMaxZ = cz + 2.2f,
            tileId = MaterialTile.TIMBER, tint = Vec3(0.92f, 0.84f, 0.74f),
            hp = 165f, supports = sups
        )
        ArchitecturalPrimitives.buildStairFlight(
            world = world,
            start = Vec3(cx - 3.2f, floor1Y, cz + 2.0f),
            end = Vec3(cx - 3.2f, loftY + 0.14f, cz - 1.95f),
            halfWidth = 0.90f, tileId = MaterialTile.STAIR_TREAD,
            hp = 125f, groundAnchored = false, bottomSupports = foundIds, topSupports = loftIds
        )

        world.addBlock(
            poly = VoronoiPolyhedron.createBox(halfW + 0.1f, 0.14f, 0.82f),
            center = Vec3(cx, floor1Y + wallH - 0.12f, cz + halfD + 0.62f),
            orientation = Quat.fromAxisAngle(Vec3(1f, 0f, 0f), 0.16f),
            tileId = MaterialTile.ROOF_SLATE, tint = Vec3(0.90f, 0.88f, 0.85f),
            hp = 82f, supportIds = (porchPosts + frontDoorIds).toMutableList()
        )
        val loftFloorY = loftY + 0.14f
        ArchitecturalPrimitives.buildOfficeSuiteProps(world, cx + 1.1f, loftFloorY, cz - 0.4f, loftIds)
        val kneeIds = ArrayList<Int>(loftIds)
        for (side in listOf(-1f, 1f)) {
            kneeIds.add(
                world.addBlock(
                    poly = VoronoiPolyhedron.createBox(halfW - 0.2f, 0.88f, 0.18f),
                    center = Vec3(cx, loftFloorY + 0.88f, cz + side * (halfD - 0.20f)),
                    tileId = wallTile, tint = Vec3(0.96f, 0.94f, 0.90f),
                    hp = 96f, supportIds = loftIds.toMutableList()
                )
            )
        }
        ArchitecturalPrimitives.buildDetailedRoof(
            world, cx, cz, eaveY = loftFloorY + 1.76f, halfW = halfW, halfD = halfD,
            roofHy = 1.45f, roofTile = MaterialTile.ROOF_SLATE, supports = kneeIds, withChimney = true
        )
    }

    private fun buildPineForest(
        world: StructuralWorld,
        terrain: ChunkedTerrain,
        config: WorldConfig,
        rng: Random
    ) {
        buildPorchCottage(world, terrain, cx = -32.0f, cz = -168.0f, wallTile = MaterialTile.TIMBER)

        val treeCount = (205 * config.forestDensity).toInt().coerceIn(60, 260)
        var placed = 0
        var attempts = 0
        while (placed < treeCount && attempts < 2400) {
            attempts++
            val x = rng.nextFloat() * 492f - 246f
            val z = rng.nextFloat() * 364f - 182f

            val distRiver = abs(x - terrain.riverCenterX(z))
            if (distRiver < terrain.riverHalfWidth() + 4.8f) continue
            if (terrain.isMainRoad(x, z) || terrain.isSidewalk(x, z)) continue
            if (abs(x + 32.0f) < 8.5f && abs(z + 168.0f) < 7.5f) continue
            // Keep clear of urban West grid, Army Base north plateau, and East Tech / Industrial / Rail Yard
            if (x in -216f..-16f && z in -148f..158f) continue
            if (x in 18f..164f && z in -124f..-4f) continue
            if (x in 18f..204f && z in 4f..160f) continue

            buildPineTree(world, terrain, x, z, 0.92f + rng.nextFloat() * 0.58f)
            placed++
        }

        repeat(36) {
            val bx = rng.nextFloat() * 460f - 230f
            val bz = if (rng.nextBoolean()) (-154f - rng.nextFloat() * 28f) else (154f + rng.nextFloat() * 28f)
            if (abs(bx - terrain.riverCenterX(bz)) > terrain.riverHalfWidth() + 4.0f &&
                (abs(bx + 32.0f) > 8.5f || abs(bz + 168.0f) > 8.5f) &&
                !terrain.isMainRoad(bx, bz)
            ) {
                val gy = terrain.groundSurfaceY(bx, bz)
                val r = 0.85f + rng.nextFloat() * 0.85f
                world.addBlock(
                    poly = VoronoiPolyhedron.createCylinderApprox(r, r * 0.65f, 9),
                    center = Vec3(bx, gy + r * 0.45f, bz),
                    orientation = Quat.fromAxisAngle(Vec3(0.3f, 1f, 0.2f), rng.nextFloat() * 3f),
                    tileId = MaterialTile.RIVER_STONE, tint = Vec3(0.75f, 0.78f, 0.72f),
                    hp = 160f, groundAnchored = true
                )
            }
        }
    }

    fun buildPineTree(
        world: StructuralWorld,
        terrain: ChunkedTerrain,
        x: Float,
        z: Float,
        scale: Float = 1.0f
    ) {
        val gy = terrain.groundSurfaceY(x, z)
        val trunkH1 = 1.75f * scale
        val trunkH2 = 1.65f * scale

        val t1 = world.addBlock(
            poly = VoronoiPolyhedron.createCylinderApprox(0.30f * scale, trunkH1 * 0.5f, 10),
            center = Vec3(x, gy + trunkH1 * 0.5f, z),
            tileId = MaterialTile.PINE_BARK, tint = Vec3(0.95f, 0.90f, 0.85f),
            hp = 54f, groundAnchored = true, walkable = false
        )
        val t2 = world.addBlock(
            poly = VoronoiPolyhedron.createCylinderApprox(0.24f * scale, trunkH2 * 0.5f, 10),
            center = Vec3(x, gy + trunkH1 + trunkH2 * 0.5f, z),
            tileId = MaterialTile.PINE_BARK, tint = Vec3(0.95f, 0.90f, 0.85f),
            hp = 46f, supportIds = mutableListOf(t1), walkable = false
        )
        val baseCanopyY = gy + trunkH1 + trunkH2 * 0.55f
        val c1 = world.addBlock(
            poly = VoronoiPolyhedron.createTaperedCanopy(2.05f * scale, 0.78f * scale, 1.15f * scale, 9),
            center = Vec3(x, baseCanopyY + 0.95f * scale, z),
            tileId = MaterialTile.PINE_NEEDLE, tint = Vec3(0.88f, 0.97f, 0.86f),
            hp = 38f, supportIds = mutableListOf(t2), walkable = false
        )
        val c2 = world.addBlock(
            poly = VoronoiPolyhedron.createTaperedCanopy(1.55f * scale, 0.50f * scale, 1.05f * scale, 9),
            center = Vec3(x, baseCanopyY + 2.45f * scale, z),
            orientation = Quat.fromAxisAngle(Vec3.UP, 0.35f),
            tileId = MaterialTile.PINE_NEEDLE, tint = Vec3(0.93f, 1.00f, 0.90f),
            hp = 32f, supportIds = mutableListOf(c1), walkable = false
        )
        world.addBlock(
            poly = VoronoiPolyhedron.createTaperedCanopy(1.05f * scale, 0.10f * scale, 1.05f * scale, 9),
            center = Vec3(x, baseCanopyY + 3.85f * scale, z),
            orientation = Quat.fromAxisAngle(Vec3.UP, 0.70f),
            tileId = MaterialTile.PINE_NEEDLE, tint = Vec3(0.98f, 1.04f, 0.95f),
            hp = 26f, supportIds = mutableListOf(c2), walkable = false
        )
    }

    fun spawnBrushStructure(
        item: BrushItem,
        buildMaterial: BuildMaterial,
        world: StructuralWorld,
        terrain: ChunkedTerrain,
        hit: Vec3,
        yaw: Float
    ) {
        when (item) {
            BrushItem.WALL_PANEL -> ArchitecturalPrimitives.buildMaterialWallPanel(world, terrain, hit, yaw, buildMaterial)
            BrushItem.SKYSCRAPER -> SkyscraperAndComplexBuilder.buildSkyscraper(world, terrain, hit.x, hit.z, stories = 16, style = SkyscraperStyle.GLASS_CURTAIN, targetTag = "BRUSH_TOWER")
            BrushItem.HOTEL_3F -> buildBrickHotel3F(world, terrain, hit.x, hit.z)
            BrushItem.APARTMENT_BLOCK -> UrbanExpansionBuilder.buildApartmentBuilding(world, terrain, hit.x, hit.z, stories = 5)
            BrushItem.HOSPITAL -> SkyscraperAndComplexBuilder.buildMetroHospital(world, terrain, hit.x, hit.z)
            BrushItem.BANK -> SkyscraperAndComplexBuilder.buildCentralVaultBank(world, terrain, hit.x, hit.z)
            BrushItem.FACTORY -> SkyscraperAndComplexBuilder.buildHeavyFactory(world, terrain, hit.x, hit.z)
            BrushItem.MALL -> SkyscraperAndComplexBuilder.buildShoppingMall(world, terrain, hit.x, hit.z)
            BrushItem.HARDWARE_STORE -> buildHardwareStore(world, terrain, hit.x, hit.z)
            BrushItem.CORNER_SHOP -> buildCornerShop(world, terrain, hit.x, hit.z)
            BrushItem.CLOCK_TOWER -> buildClockTowerHall(world, terrain, hit.x, hit.z)
            BrushItem.WAREHOUSE -> UrbanExpansionBuilder.buildWarehouseDepot(world, terrain, hit.x, hit.z)
            BrushItem.RAIL_CAR -> UrbanExpansionBuilder.buildFreightCar(world, terrain, hit.x, hit.z, isTanker = true)
            BrushItem.GAS_STATION -> buildGasStation(world, terrain, hit.x, hit.z)
            BrushItem.PARK_GAZEBO -> CityParkAndStreetlifeBuilder.buildParkGazebo(world, terrain, hit.x, hit.z)
            BrushItem.PARKED_CAR -> CityParkAndStreetlifeBuilder.buildParkedVehicle(world, terrain, hit.x, hit.z, alongX = abs(kotlin.math.sin(yaw)) < 0.707f, kind = ParkedVehicleKind.SEDAN)
            BrushItem.COMMAND_HQ -> MilitaryZoneBuilder.buildCommandHQ(world, terrain, hit.x, hit.z)
            BrushItem.FUEL_SILO -> MilitaryZoneBuilder.buildFuelSilo(world, terrain, hit.x, hit.z, Random(System.nanoTime()))
            BrushItem.WATCHTOWER -> MilitaryZoneBuilder.buildWatchtower(world, terrain, hit.x, hit.z)
            BrushItem.BUNKER -> MilitaryZoneBuilder.buildSandbagBunker(world, terrain, hit.x, hit.z)
            BrushItem.FUEL_DEPOT -> MilitaryZoneBuilder.buildFuelDepot(world, terrain, hit.x, hit.z)
            BrushItem.PINE_TREE -> buildPineTree(world, terrain, hit.x, hit.z, 1.05f)
            BrushItem.RAISE_TERRAIN -> terrain.sculptBrush(hit.x, hit.z, radius = 4.2f, deltaY = 0.85f)
            BrushItem.LOWER_TERRAIN -> {
                terrain.sculptBrush(hit.x, hit.z, radius = 4.2f, deltaY = -0.85f)
                world.checkStabilityAndCollapse(terrain)
            }
        }
        world.rebuildPhysicalSupports()
    }
}

private data class StairPlan(
    val holeMinX: Float,
    val holeMaxX: Float,
    val holeMinZ: Float,
    val holeMaxZ: Float,
    val start: Vec3,
    val end: Vec3
)
