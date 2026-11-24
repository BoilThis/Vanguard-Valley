package com.example.engine.physics

import com.example.engine.terrain.ChunkedTerrain
import com.example.model.MaterialTile
import com.example.model.Quat
import com.example.model.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object UrbanExpansionBuilder {

    /**
     * 4-5 Story Residential Apartment Block with spacious furnished suites, wide stairwell,
     * exterior balconies on upper levels, and a rooftop HVAC/skylight terrace.
     */
    fun buildApartmentBuilding(
        world: StructuralWorld,
        terrain: ChunkedTerrain,
        cx: Float,
        cz: Float,
        stories: Int = 5,
        facadeTile: Int = MaterialTile.BRICK,
        facadeTint: Vec3 = Vec3(0.92f, 0.72f, 0.62f),
        targetTag: String = "APARTMENTS"
    ) {
        val storyCount = stories.coerceIn(3, 6)
        val halfW = 8.8f
        val halfD = 6.6f
        val hFloor = 3.40f
        val slabT = 0.34f

        val (foundIds, floor1Y) = ArchitecturalPrimitives.buildGradedFoundation(
            world = world,
            terrain = terrain,
            cx = cx,
            cz = cz,
            halfW = halfW,
            halfD = halfD,
            entryOffset = Vec3(0f, 0f, halfD + 0.28f),
            entryHalfW = 1.75f,
            tileId = MaterialTile.CONCRETE,
            tint = Vec3(0.84f, 0.83f, 0.80f),
            targetTag = targetTag
        )

        val colXs = listOf(-halfW + 0.32f, -3.1f, 3.1f, halfW - 0.32f)
        val colCoords = ArrayList<Pair<Float, Float>>(colXs.size * 2)
        for (px in colXs) {
            colCoords.add((cx + px) to (cz - halfD + 0.32f))
            colCoords.add((cx + px) to (cz + halfD - 0.32f))
        }

        var prevFloorIds = foundIds
        var curFloorY = floor1Y
        var topSupports = foundIds

        for (story in 1..storyCount) {
            val cols = ArchitecturalPrimitives.buildSteelCoreColumns(
                world = world,
                colPositions = colCoords,
                baseY = curFloorY,
                height = hFloor,
                halfThick = 0.24f,
                hp = 480f,
                groundAnchored = false,
                supportIds = prevFloorIds,
                targetTag = targetTag
            )
            val wallTile = if (story == 1) MaterialTile.CONCRETE else facadeTile
            val wallTint = if (story == 1) Vec3(0.88f, 0.86f, 0.82f) else facadeTint

            if (story == 1 || story % 2 == 0) {
                val frontIds = ArchitecturalPrimitives.buildDoorwayWall(
                    world, Vec3(cx, curFloorY + hFloor * 0.5f, cz + halfD - 0.25f),
                    halfLength = halfW - 0.58f, height = hFloor, halfThick = 0.22f, alongX = true,
                    wallTile = wallTile, tint = wallTint, groundAnchored = false,
                    supportIds = prevFloorIds, doorHalfW = 1.45f, targetTag = targetTag
                )
                if (story == 1) {
                    world.addBlock(
                        poly = VoronoiPolyhedron.createBox(2.6f, 0.12f, 0.86f),
                        center = Vec3(cx, curFloorY + 2.75f, cz + halfD + 0.65f),
                        tileId = MaterialTile.AWNING_TRIM,
                        tint = Vec3(0.24f, 0.44f, 0.64f),
                        hp = 58f,
                        supportIds = (frontIds + cols).toMutableList(),
                        targetTag = targetTag,
                        walkable = false
                    )
                } else {
                    ArchitecturalPrimitives.buildBalconyWithRailings(
                        world = world,
                        deckCenter = Vec3(cx, curFloorY - 0.10f, cz + halfD + 0.52f),
                        halfW = 3.4f, halfD = 0.82f, outwardNormal = Vec3(0f, 0f, 1f),
                        supportIds = prevFloorIds, targetTag = targetTag
                    )
                }
            } else {
                ArchitecturalPrimitives.buildWindowedWall(
                    world, Vec3(cx, curFloorY + hFloor * 0.5f, cz + halfD - 0.25f),
                    halfLength = halfW - 0.58f, height = hFloor, halfThick = 0.22f, alongX = true,
                    wallTile = wallTile, tint = wallTint, groundAnchored = false,
                    supportIds = prevFloorIds, windowHalfW = 2.6f, targetTag = targetTag
                )
            }

            ArchitecturalPrimitives.buildWindowedWall(
                world, Vec3(cx, curFloorY + hFloor * 0.5f, cz - halfD + 0.25f),
                halfLength = halfW - 0.58f, height = hFloor, halfThick = 0.22f, alongX = true,
                wallTile = wallTile, tint = wallTint, groundAnchored = false,
                supportIds = prevFloorIds, windowHalfW = 2.6f, targetTag = targetTag
            )
            for (side in listOf(-1f, 1f)) {
                ArchitecturalPrimitives.buildWindowedWall(
                    world, Vec3(cx + side * (halfW - 0.25f), curFloorY + hFloor * 0.5f, cz),
                    halfLength = halfD - 0.58f, height = hFloor, halfThick = 0.22f, alongX = false,
                    wallTile = wallTile, tint = wallTint, groundAnchored = false,
                    supportIds = prevFloorIds, windowHalfW = 1.9f, targetTag = targetTag
                )
            }

            // Spacious residential furnishings away from the central stairwell bay
            if (story == 1) {
                ArchitecturalPrimitives.buildInteriorRoomProps(world, cx + 1.2f, curFloorY, cz - 0.6f, prevFloorIds, targetTag)
            } else {
                ArchitecturalPrimitives.buildOfficeSuiteProps(world, cx - 0.4f, curFloorY, cz - 0.4f, prevFloorIds, targetTag)
            }

            val nextSlabY = curFloorY + hFloor + slabT * 0.5f
            val nextFloorY = nextSlabY + slabT * 0.5f
            val isOdd = (story % 2 == 1)
            val holeMinX = if (isOdd) cx - 6.1f else cx + 3.2f
            val holeMaxX = if (isOdd) cx - 3.2f else cx + 6.1f
            val holeMinZ = cz - 2.6f
            val holeMaxZ = cz + 2.6f
            val stStart = if (isOdd) Vec3(cx - 4.65f, curFloorY, cz + 2.4f) else Vec3(cx + 4.65f, curFloorY, cz - 2.4f)
            val stEnd = if (isOdd) Vec3(cx - 4.65f, nextFloorY, cz - 2.8f) else Vec3(cx + 4.65f, nextFloorY, cz + 2.8f)

            val nextSlabs = ArchitecturalPrimitives.buildSegmentedFloorWithCutout(
                world = world, cx = cx, cz = cz, centerY = nextSlabY,
                halfW = halfW - 0.08f, halfD = halfD - 0.08f, halfThick = slabT * 0.5f,
                holeMinX = holeMinX, holeMaxX = holeMaxX, holeMinZ = holeMinZ, holeMaxZ = holeMaxZ,
                tileId = MaterialTile.CONCRETE, tint = Vec3(0.84f, 0.84f, 0.82f),
                hp = 230f, supports = cols, targetTag = targetTag
            )
            ArchitecturalPrimitives.buildStairFlight(
                world = world, start = stStart, end = stEnd,
                halfWidth = 1.05f, tileId = MaterialTile.STAIR_TREAD, hp = 140f,
                groundAnchored = false, bottomSupports = prevFloorIds, topSupports = nextSlabs, targetTag = targetTag
            )
            prevFloorIds = nextSlabs
            topSupports = nextSlabs
            curFloorY = nextFloorY
        }

        // Rooftop parapet & HVAC penthouse
        val roofY = curFloorY
        for (side in listOf(-1f, 1f)) {
            world.addBlock(
                poly = VoronoiPolyhedron.createBox(halfW - 0.12f, 0.44f, 0.18f),
                center = Vec3(cx, roofY + 0.44f, cz + side * (halfD - 0.22f)),
                tileId = facadeTile, tint = facadeTint, hp = 115f,
                supportIds = topSupports.toMutableList(), targetTag = targetTag
            )
            world.addBlock(
                poly = VoronoiPolyhedron.createBox(0.18f, 0.44f, halfD - 0.34f),
                center = Vec3(cx + side * (halfW - 0.22f), roofY + 0.44f, cz),
                tileId = facadeTile, tint = facadeTint, hp = 115f,
                supportIds = topSupports.toMutableList(), targetTag = targetTag
            )
        }
        CityParkAndStreetlifeBuilder.buildRooftopHvac(world, cx + 2.2f, roofY, cz - 1.8f, topSupports, targetTag)
    }

    /**
     * Spacious Logistics Warehouse Depot (20m x 13.2m) with wide rolling bay door, steel frame,
     * high clerestory windows, interior pallet shelving racks, and catwalk loft.
     */
    fun buildWarehouseDepot(
        world: StructuralWorld,
        terrain: ChunkedTerrain,
        cx: Float,
        cz: Float,
        targetTag: String = "WAREHOUSE"
    ) {
        val halfW = 10.0f
        val halfD = 6.6f
        val wallH = 5.2f

        val (foundIds, floor1Y) = ArchitecturalPrimitives.buildGradedFoundation(
            world = world, terrain = terrain, cx = cx, cz = cz,
            halfW = halfW, halfD = halfD,
            entryOffset = Vec3(-halfW - 0.28f, 0f, 0f),
            entryHalfW = 2.2f,
            tileId = MaterialTile.CONCRETE,
            tint = Vec3(0.74f, 0.75f, 0.74f),
            targetTag = targetTag
        )

        val colCoords = ArrayList<Pair<Float, Float>>(8)
        for (px in listOf(-halfW + 0.34f, -3.3f, 3.3f, halfW - 0.34f)) {
            colCoords.add((cx + px) to (cz - halfD + 0.34f))
            colCoords.add((cx + px) to (cz + halfD - 0.34f))
        }
        val cols = ArchitecturalPrimitives.buildSteelCoreColumns(
            world = world, colPositions = colCoords, baseY = floor1Y, height = wallH,
            halfThick = 0.26f, hp = 560f, groundAnchored = false, supportIds = foundIds, targetTag = targetTag
        )

        // Wide West (-X) loading doorway + East/North/South corrugated bays
        ArchitecturalPrimitives.buildDoorwayWall(
            world, Vec3(cx - halfW + 0.25f, floor1Y + wallH * 0.5f, cz),
            halfLength = halfD - 0.58f, height = wallH, halfThick = 0.22f, alongX = false,
            wallTile = MaterialTile.CORRUGATED_METAL, tint = Vec3(0.70f, 0.74f, 0.78f),
            groundAnchored = false, supportIds = foundIds, doorHalfW = 2.2f, targetTag = targetTag
        )
        ArchitecturalPrimitives.buildWindowedWall(
            world, Vec3(cx + halfW - 0.25f, floor1Y + wallH * 0.5f, cz),
            halfLength = halfD - 0.58f, height = wallH, halfThick = 0.22f, alongX = false,
            wallTile = MaterialTile.CORRUGATED_METAL, tint = Vec3(0.68f, 0.72f, 0.76f),
            groundAnchored = false, supportIds = foundIds, windowHalfW = 2.0f, targetTag = targetTag
        )
        for (side in listOf(-1f, 1f)) {
            ArchitecturalPrimitives.buildWindowedWall(
                world, Vec3(cx, floor1Y + wallH * 0.5f, cz + side * (halfD - 0.25f)),
                halfLength = halfW - 0.58f, height = wallH, halfThick = 0.22f, alongX = true,
                wallTile = MaterialTile.CORRUGATED_METAL, tint = Vec3(0.72f, 0.76f, 0.80f),
                groundAnchored = false, supportIds = foundIds, windowHalfW = 3.2f, targetTag = targetTag
            )
        }

        // Interior pallet storage racks + office mezzanine loft along East end
        ArchitecturalPrimitives.buildWarehouseRack(world, cx - 2.6f, floor1Y, cz - 2.6f, alongX = true, foundIds, targetTag)
        ArchitecturalPrimitives.buildWarehouseRack(world, cx - 2.6f, floor1Y, cz + 2.6f, alongX = true, foundIds, targetTag)
        ArchitecturalPrimitives.buildWarehouseRack(world, cx + 2.8f, floor1Y, cz - 2.6f, alongX = true, foundIds, targetTag)

        val loftY = floor1Y + 2.85f
        val loftId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(2.9f, 0.14f, halfD - 0.5f),
            center = Vec3(cx + 6.4f, loftY, cz),
            tileId = MaterialTile.STEEL, tint = Vec3(0.56f, 0.60f, 0.65f),
            hp = 240f, supportIds = cols.toMutableList(), targetTag = targetTag, checkCantilever = true
        )
        ArchitecturalPrimitives.buildStairFlight(
            world = world,
            start = Vec3(cx + 1.2f, floor1Y, cz + 3.2f),
            end = Vec3(cx + 3.7f, loftY + 0.14f, cz + 3.2f),
            halfWidth = 0.88f, tileId = MaterialTile.STAIR_TREAD, hp = 125f,
            groundAnchored = false, bottomSupports = foundIds, topSupports = listOf(loftId), targetTag = targetTag
        )

        // 3 Segmented roof bays centered squarely over frame column spans
        val roofY = floor1Y + wallH
        for (bx in listOf(cx - 6.6f, cx, cx + 6.6f)) {
            world.addBlock(
                poly = VoronoiPolyhedron.createPitchedRoof(3.4f, 1.35f, halfD + 0.22f),
                center = Vec3(bx, roofY + 1.35f, cz),
                tileId = MaterialTile.CORRUGATED_METAL, tint = Vec3(0.60f, 0.64f, 0.68f),
                hp = 195f, supportIds = cols.toMutableList(), targetTag = targetTag, checkCantilever = true
            )
        }
    }

    /**
     * Grand 3-Story Neoclassical Brick & Limestone University Hall (22.4m x 14.8m)
     * with columned portico, spacious 3.6m lecture halls, wide grand staircase, and rooftop clock cupola.
     */
    fun buildUniversityHall(
        world: StructuralWorld,
        terrain: ChunkedTerrain,
        cx: Float,
        cz: Float,
        targetTag: String = "UNIVERSITY"
    ) {
        val halfW = 11.2f
        val halfD = 7.4f
        val hFloor = 3.60f
        val slabT = 0.34f

        val (foundIds, floor1Y) = ArchitecturalPrimitives.buildGradedFoundation(
            world = world, terrain = terrain, cx = cx, cz = cz,
            halfW = halfW, halfD = halfD,
            entryOffset = Vec3(0f, 0f, -halfD - 0.30f),
            entryHalfW = 2.1f,
            tileId = MaterialTile.CONCRETE,
            tint = Vec3(0.86f, 0.84f, 0.78f),
            targetTag = targetTag
        )

        // Limestone classical portico on North (-Z) campus entrance
        val porticoZ = cz - halfD - 1.65f
        val portGy = min(floor1Y, terrain.groundSurfaceY(cx, porticoZ)) - 0.14f
        val apronH = max(0.50f, floor1Y - portGy)
        val apronId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(5.6f, apronH * 0.5f, 1.15f),
            center = Vec3(cx, floor1Y - apronH * 0.5f, porticoZ + 0.35f),
            tileId = MaterialTile.CONCRETE, tint = Vec3(0.92f, 0.90f, 0.84f),
            hp = 360f, groundAnchored = true, isCoreSupport = true, targetTag = targetTag
        )
        val portCols = ArrayList<Int>(4)
        for (px in listOf(-4.4f, -1.8f, 1.8f, 4.4f)) {
            portCols.add(
                world.addBlock(
                    poly = VoronoiPolyhedron.createCylinderApprox(0.32f, hFloor * 0.5f, 10),
                    center = Vec3(cx + px, floor1Y + hFloor * 0.5f, porticoZ),
                    tileId = MaterialTile.CONCRETE, tint = Vec3(0.96f, 0.94f, 0.88f),
                    hp = 320f, supportIds = mutableListOf(apronId), isCoreSupport = true, targetTag = targetTag
                )
            )
        }
        world.addBlock(
            poly = VoronoiPolyhedron.createBox(5.5f, 0.42f, 1.25f),
            center = Vec3(cx, floor1Y + hFloor + 0.42f, cz - halfD - 0.85f),
            tileId = MaterialTile.CONCRETE, tint = Vec3(0.92f, 0.90f, 0.84f),
            hp = 260f, supportIds = (portCols + foundIds).toMutableList(), targetTag = targetTag, checkCantilever = true
        )

        val colXs = listOf(-halfW + 0.34f, -4.4f, 4.4f, halfW - 0.34f)
        val colCoords = ArrayList<Pair<Float, Float>>(8)
        for (px in colXs) {
            colCoords.add((cx + px) to (cz - halfD + 0.34f))
            colCoords.add((cx + px) to (cz + halfD - 0.34f))
        }

        var prevFloorIds = foundIds
        var curFloorY = floor1Y
        var topSupports = foundIds

        for (story in 1..3) {
            val cols = ArchitecturalPrimitives.buildSteelCoreColumns(
                world = world, colPositions = colCoords, baseY = curFloorY, height = hFloor,
                halfThick = 0.25f, hp = 540f, groundAnchored = false, supportIds = prevFloorIds, targetTag = targetTag
            )
            val wallTint = Vec3(0.84f, 0.46f, 0.38f)
            if (story == 1) {
                ArchitecturalPrimitives.buildDoorwayWall(
                    world, Vec3(cx, curFloorY + hFloor * 0.5f, cz - halfD + 0.26f),
                    halfLength = halfW - 0.62f, height = hFloor, halfThick = 0.24f, alongX = true,
                    wallTile = MaterialTile.BRICK, tint = wallTint, groundAnchored = false,
                    supportIds = prevFloorIds, doorHalfW = 1.95f, targetTag = targetTag
                )
            } else {
                ArchitecturalPrimitives.buildWindowedWall(
                    world, Vec3(cx, curFloorY + hFloor * 0.5f, cz - halfD + 0.26f),
                    halfLength = halfW - 0.62f, height = hFloor, halfThick = 0.24f, alongX = true,
                    wallTile = MaterialTile.BRICK, tint = wallTint, groundAnchored = false,
                    supportIds = prevFloorIds, windowHalfW = 3.2f, targetTag = targetTag
                )
            }
            ArchitecturalPrimitives.buildWindowedWall(
                world, Vec3(cx, curFloorY + hFloor * 0.5f, cz + halfD - 0.26f),
                halfLength = halfW - 0.62f, height = hFloor, halfThick = 0.24f, alongX = true,
                wallTile = MaterialTile.BRICK, tint = wallTint, groundAnchored = false,
                supportIds = prevFloorIds, windowHalfW = 3.2f, targetTag = targetTag
            )
            for (side in listOf(-1f, 1f)) {
                ArchitecturalPrimitives.buildWindowedWall(
                    world, Vec3(cx + side * (halfW - 0.26f), curFloorY + hFloor * 0.5f, cz),
                    halfLength = halfD - 0.62f, height = hFloor, halfThick = 0.24f, alongX = false,
                    wallTile = MaterialTile.BRICK, tint = wallTint, groundAnchored = false,
                    supportIds = prevFloorIds, windowHalfW = 2.2f, targetTag = targetTag
                )
            }

            ArchitecturalPrimitives.buildInteriorRoomProps(world, cx - 5.2f, curFloorY, cz - 0.8f, prevFloorIds, targetTag)
            ArchitecturalPrimitives.buildOfficeSuiteProps(world, cx + 5.0f, curFloorY, cz + 0.6f, prevFloorIds, targetTag)

            val nextSlabY = curFloorY + hFloor + slabT * 0.5f
            val nextFloorY = nextSlabY + slabT * 0.5f
            val nextSlabs = ArchitecturalPrimitives.buildSegmentedFloorWithCutout(
                world = world, cx = cx, cz = cz, centerY = nextSlabY,
                halfW = halfW - 0.08f, halfD = halfD - 0.08f, halfThick = slabT * 0.5f,
                holeMinX = cx - 1.5f, holeMaxX = cx + 1.5f, holeMinZ = cz - 2.8f, holeMaxZ = cz + 2.8f,
                tileId = MaterialTile.CONCRETE, tint = Vec3(0.88f, 0.86f, 0.82f),
                hp = 240f, supports = cols, targetTag = targetTag
            )
            val stairDir = if (story % 2 == 1) 1f else -1f
            ArchitecturalPrimitives.buildStairFlight(
                world = world,
                start = Vec3(cx, curFloorY, cz + stairDir * 2.5f),
                end = Vec3(cx, nextFloorY, cz - stairDir * 2.9f),
                halfWidth = 1.15f, tileId = MaterialTile.STAIR_TREAD, hp = 150f,
                groundAnchored = false, bottomSupports = prevFloorIds, topSupports = nextSlabs, targetTag = targetTag
            )
            prevFloorIds = nextSlabs
            topSupports = nextSlabs
            curFloorY = nextFloorY
        }

        // Sloped Slate Academic Roof + Central Bell Cupola
        ArchitecturalPrimitives.buildDetailedRoof(
            world = world, cx = cx, cz = cz, eaveY = curFloorY,
            halfW = halfW - 0.1f, halfD = halfD - 0.1f, roofHy = 1.85f,
            roofTile = MaterialTile.ROOF_SLATE, supports = topSupports, withChimney = false, targetTag = targetTag
        )
        val cupolaBaseY = curFloorY + 3.4f
        val cupId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(1.85f, 1.35f, 1.85f),
            center = Vec3(cx, cupolaBaseY + 1.35f, cz),
            tileId = MaterialTile.STUCCO, tint = Vec3(0.96f, 0.94f, 0.88f),
            hp = 160f, supportIds = topSupports.toMutableList(), targetTag = targetTag
        )
        world.addBlock(
            poly = VoronoiPolyhedron.createTaperedCanopy(2.15f, 0.14f, 1.85f, 8),
            center = Vec3(cx, cupolaBaseY + 2.70f + 0.92f, cz),
            tileId = MaterialTile.STEEL, tint = Vec3(0.28f, 0.62f, 0.54f),
            hp = 120f, supportIds = mutableListOf(cupId), targetTag = targetTag
        )
    }

    /**
     * Grand Municipal Stadium wrapping the 36m x 28m striped turf pitch (-122..-86 x 122..150):
     * 4-sided terraced concrete grandstands, walkable upper concourses + access stairs,
     * overhanging steel roof canopies along North & South stands, and 4 corner floodlight towers.
     */
    fun buildMunicipalStadium(
        world: StructuralWorld,
        terrain: ChunkedTerrain,
        cx: Float = -104f,
        cz: Float = 136f
    ) {
        val tag = "STADIUM"
        val pitchHalfW = 19.2f
        val pitchHalfD = 14.8f

        // North & South Main Grandstands (segmented along X so damage collapses bays locally)
        for (sideZ in listOf(-1f, 1f)) {
            val bayXs = listOf(-14.4f, -4.8f, 4.8f, 14.4f)
            val standCols = ArrayList<Int>(16)
            for (bx in bayXs) {
                val wx = cx + bx
                val innerZ = cz + sideZ * (pitchHalfD + 1.15f)
                val outerZ = cz + sideZ * (pitchHalfD + 4.35f)
                val gyIn = terrain.groundSurfaceY(wx, innerZ)
                val gyOut = terrain.groundSurfaceY(wx, outerZ)

                // Front retaining wall & lower tier
                val wallId = world.addBlock(
                    poly = VoronoiPolyhedron.createBox(4.72f, 1.05f, 0.72f),
                    center = Vec3(wx, gyIn + 1.00f, innerZ),
                    tileId = MaterialTile.CONCRETE, tint = Vec3(0.86f, 0.86f, 0.84f),
                    hp = 380f, groundAnchored = true, isCoreSupport = true, targetTag = tag
                )
                // Mid seating terrace
                val midId = world.addBlock(
                    poly = VoronoiPolyhedron.createBox(4.72f, 1.55f, 1.15f),
                    center = Vec3(wx, gyIn + 1.50f, cz + sideZ * (pitchHalfD + 2.65f)),
                    tileId = MaterialTile.CONCRETE, tint = Vec3(0.82f, 0.84f, 0.86f),
                    hp = 340f, groundAnchored = true, supportIds = mutableListOf(wallId), targetTag = tag
                )
                // Upper seating concourse + outer concrete wall
                val upperId = world.addBlock(
                    poly = VoronoiPolyhedron.createBox(4.72f, 2.15f, 1.15f),
                    center = Vec3(wx, gyOut + 2.10f, outerZ),
                    tileId = MaterialTile.CONCRETE, tint = Vec3(0.78f, 0.80f, 0.84f),
                    hp = 380f, groundAnchored = true, isCoreSupport = true, targetTag = tag
                )
                // Colored stadium seat row fascia strips
                world.addBlock(
                    poly = VoronoiPolyhedron.createBox(4.55f, 0.22f, 0.85f),
                    center = Vec3(wx, gyIn + 3.18f, cz + sideZ * (pitchHalfD + 2.65f)),
                    tileId = MaterialTile.PLASTIC, tint = Vec3(0.18f, 0.46f, 0.84f),
                    hp = 55f, supportIds = mutableListOf(midId), targetTag = tag
                )

                // Steel canopy support mast & roof truss over each stand bay
                val mastZ = cz + sideZ * (pitchHalfD + 4.95f)
                val mastGy = terrain.groundSurfaceY(wx, mastZ)
                val mastId = world.addBlock(
                    poly = VoronoiPolyhedron.createBox(0.28f, 3.85f, 0.28f),
                    center = Vec3(wx, mastGy + 3.85f, mastZ),
                    tileId = MaterialTile.STEEL, tint = Vec3(0.46f, 0.50f, 0.56f),
                    hp = 520f, groundAnchored = true, isCoreSupport = true, targetTag = tag
                )
                standCols.add(mastId)
                world.addBlock(
                    poly = VoronoiPolyhedron.createBox(4.72f, 0.16f, 2.35f),
                    center = Vec3(wx, mastGy + 7.82f, cz + sideZ * (pitchHalfD + 3.25f)),
                    orientation = Quat.fromAxisAngle(Vec3(1f, 0f, 0f), sideZ * -0.10f),
                    tileId = MaterialTile.CORRUGATED_METAL, tint = Vec3(0.90f, 0.92f, 0.95f),
                    hp = 210f, supportIds = mutableListOf(mastId, upperId), targetTag = tag, checkCantilever = true
                )
            }
            // Central entry stair onto North & South grandstands
            val stZOut = cz + sideZ * (pitchHalfD + 6.4f)
            val stZIn = cz + sideZ * (pitchHalfD + 3.4f)
            val stGy = terrain.groundSurfaceY(cx, stZOut)
            ArchitecturalPrimitives.buildStairFlight(
                world = world,
                start = Vec3(cx, stGy, stZOut),
                end = Vec3(cx, stGy + 4.25f, stZIn),
                halfWidth = 1.45f, tileId = MaterialTile.STAIR_TREAD, hp = 145f,
                groundAnchored = true, targetTag = tag
            )
        }

        // West & East Endzone Terraces (leaving central tunnel entrance open!)
        for (sideX in listOf(-1f, 1f)) {
            for (bz in listOf(-7.8f, 7.8f)) {
                val wx = cx + sideX * (pitchHalfW + 1.65f)
                val wz = cz + bz
                val gy = terrain.groundSurfaceY(wx, wz)
                val lowId = world.addBlock(
                    poly = VoronoiPolyhedron.createBox(0.95f, 1.05f, 4.6f),
                    center = Vec3(wx, gy + 1.00f, wz),
                    tileId = MaterialTile.CONCRETE, tint = Vec3(0.84f, 0.84f, 0.82f),
                    hp = 340f, groundAnchored = true, targetTag = tag
                )
                world.addBlock(
                    poly = VoronoiPolyhedron.createBox(1.15f, 1.85f, 4.6f),
                    center = Vec3(cx + sideX * (pitchHalfW + 3.45f), gy + 1.85f, wz),
                    tileId = MaterialTile.CONCRETE, tint = Vec3(0.78f, 0.80f, 0.82f),
                    hp = 360f, groundAnchored = true, supportIds = mutableListOf(lowId), targetTag = tag
                )
            }
        }

        // 4 Tall Steel Corner Floodlight Towers
        for (sx in listOf(-1f, 1f)) {
            for (sz in listOf(-1f, 1f)) {
                val tx = cx + sx * (pitchHalfW + 4.4f)
                val tz = cz + sz * (pitchHalfD + 4.4f)
                val gy = terrain.groundSurfaceY(tx, tz) - 0.1f
                val mast1 = world.addBlock(
                    poly = VoronoiPolyhedron.createCylinderApprox(0.32f, 5.2f, 8),
                    center = Vec3(tx, gy + 5.2f, tz),
                    tileId = MaterialTile.STEEL, tint = Vec3(0.52f, 0.56f, 0.62f),
                    hp = 420f, groundAnchored = true, isCoreSupport = true, targetTag = tag
                )
                val mast2 = world.addBlock(
                    poly = VoronoiPolyhedron.createCylinderApprox(0.26f, 4.4f, 8),
                    center = Vec3(tx, gy + 10.4f + 4.4f, tz),
                    tileId = MaterialTile.STEEL, tint = Vec3(0.56f, 0.60f, 0.66f),
                    hp = 320f, supportIds = mutableListOf(mast1), targetTag = tag
                )
                val rackId = world.addBlock(
                    poly = VoronoiPolyhedron.createBox(1.55f, 0.85f, 0.24f),
                    center = Vec3(tx, gy + 19.8f, tz),
                    orientation = Quat.fromAxisAngle(Vec3.UP, if (sx * sz > 0f) -0.78f else 0.78f),
                    tileId = MaterialTile.STEEL, tint = Vec3(0.34f, 0.38f, 0.44f),
                    hp = 140f, supportIds = mutableListOf(mast2), targetTag = tag, walkable = false
                )
                world.addBlock(
                    poly = VoronoiPolyhedron.createBox(1.35f, 0.62f, 0.12f),
                    center = Vec3(tx, gy + 19.8f, tz),
                    orientation = Quat.fromAxisAngle(Vec3.UP, if (sx * sz > 0f) -0.78f else 0.78f),
                    tileId = MaterialTile.GLASS, tint = Vec3(1.1f, 1.05f, 0.88f),
                    hp = 24f, supportIds = mutableListOf(rackId), targetTag = tag, walkable = false
                )
            }
        }
    }

    /**
     * Freight Rail Yard (x in +28..+196, z in +120..+152):
     * Dual parallel steel rail lines with sleepers, freight boxcars & explosive pressurized tanker cars,
     * overhead industrial gantry crane, and a brick Freight Dispatch Station.
     */
    fun buildRailYard(
        world: StructuralWorld,
        terrain: ChunkedTerrain,
        cx: Float = 108f,
        cz: Float = 136f
    ) {
        val tag = "RAIL_YARD"
        val trackZs = listOf(cz - 7.5f, cz + 7.5f)
        for (tz in trackZs) {
            var rx = 34f
            while (rx <= 186f) {
                val gy = terrain.groundSurfaceY(rx, tz)
                // Steel track segment pair embedded on gravel bed
                for (railOff in listOf(-0.95f, 0.95f)) {
                    world.addBlock(
                        poly = VoronoiPolyhedron.createBox(5.8f, 0.08f, 0.09f),
                        center = Vec3(rx, gy + 0.08f, tz + railOff),
                        tileId = MaterialTile.STEEL, tint = Vec3(0.42f, 0.45f, 0.50f),
                        hp = 260f, groundAnchored = true, targetTag = tag, walkable = false
                    )
                }
                rx += 12.0f
            }
        }

        // Freight Boxcars & Pressurized Fuel Tanker Cars parked along North & South tracks
        val carSpecs = listOf(
            Triple(44f, trackZs[0], false),
            Triple(60f, trackZs[0], true),
            Triple(76f, trackZs[0], false),
            Triple(122f, trackZs[0], true),
            Triple(140f, trackZs[0], false),
            Triple(48f, trackZs[1], true),
            Triple(66f, trackZs[1], false),
            Triple(118f, trackZs[1], false),
            Triple(136f, trackZs[1], true),
            Triple(154f, trackZs[1], false)
        )
        for ((idx, spec) in carSpecs.withIndex()) {
            val (carX, carZ, isTanker) = spec
            val tint = when (idx % 4) {
                0 -> Vec3(0.76f, 0.30f, 0.24f)
                1 -> Vec3(0.24f, 0.46f, 0.72f)
                2 -> Vec3(0.84f, 0.62f, 0.20f)
                else -> Vec3(0.32f, 0.54f, 0.40f)
            }
            buildFreightCar(world, terrain, carX, carZ, isTanker = isTanker, bodyTint = tint, targetTag = tag)
        }

        // Overhead Steel Rail Gantry Crane spanning both tracks around x = 96
        val craneX = 96f
        val craneGy = terrain.groundSurfaceY(craneX, cz)
        val legs = ArrayList<Int>(4)
        for (sx in listOf(-3.2f, 3.2f)) {
            for (sz in listOf(-10.8f, 10.8f)) {
                val lx = craneX + sx
                val lz = cz + sz
                val lGy = terrain.groundSurfaceY(lx, lz) - 0.1f
                legs.add(
                    world.addBlock(
                        poly = VoronoiPolyhedron.createBox(0.34f, 4.6f, 0.34f),
                        center = Vec3(lx, lGy + 4.6f, lz),
                        tileId = MaterialTile.STEEL, tint = Vec3(0.92f, 0.66f, 0.14f),
                        hp = 480f, groundAnchored = true, isCoreSupport = true, targetTag = tag
                    )
                )
            }
        }
        val bridgeId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(3.55f, 0.42f, 11.4f),
            center = Vec3(craneX, craneGy + 9.45f, cz),
            tileId = MaterialTile.STEEL, tint = Vec3(0.95f, 0.72f, 0.16f),
            hp = 420f, supportIds = legs, targetTag = tag, checkCantilever = true
        )
        // Crane trolley cab + suspended cargo container
        val trolleyId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(1.45f, 0.65f, 1.55f),
            center = Vec3(craneX, craneGy + 10.35f, cz - 1.5f),
            tileId = MaterialTile.CORRUGATED_METAL, tint = Vec3(0.30f, 0.34f, 0.38f),
            hp = 160f, supportIds = mutableListOf(bridgeId), targetTag = tag
        )
        world.addBlock(
            poly = VoronoiPolyhedron.createBox(3.2f, 1.25f, 1.25f),
            center = Vec3(craneX, craneGy + 6.45f, cz - 1.5f),
            tileId = MaterialTile.CORRUGATED_METAL, tint = Vec3(0.22f, 0.56f, 0.74f),
            hp = 140f, supportIds = mutableListOf(trolleyId), targetTag = tag
        )

        // Freight Yard Dispatch Depot on East Yard (+174, 136)
        buildWarehouseDepot(world, terrain, cx = 175f, cz = cz, targetTag = tag)
    }

    /**
     * Reusable Freight Boxcar or Pressurized Tanker Car for Rail Yard and Creative Brush.
     */
    fun buildFreightCar(
        world: StructuralWorld,
        terrain: ChunkedTerrain,
        cx: Float,
        cz: Float,
        isTanker: Boolean = false,
        bodyTint: Vec3 = Vec3(0.76f, 0.32f, 0.24f),
        targetTag: String = "RAIL_YARD"
    ) {
        val gy = terrain.groundSurfaceY(cx, cz)
        val bogieGys = ArrayList<Triple<Float, Float, Float>>(4)
        var maxBogieGy = gy
        for (ax in listOf(-4.2f, 4.2f)) {
            for (sz in listOf(-0.92f, 0.92f)) {
                val bGy = terrain.groundSurfaceY(cx + ax, cz + sz)
                if (bGy > maxBogieGy) maxBogieGy = bGy
                bogieGys.add(Triple(ax, sz, bGy))
            }
        }
        val chassisY = maxBogieGy + 0.94f
        val chassisBottom = chassisY - 0.16f
        val bogies = ArrayList<Int>(4)
        for ((ax, sz, bGy) in bogieGys) {
            val baseGy = bGy - 0.04f
            val bogieHy = max(0.38f, (chassisBottom - baseGy) * 0.5f)
            bogies.add(
                world.addBlock(
                    poly = VoronoiPolyhedron.createBox(0.85f, bogieHy, 0.24f),
                    center = Vec3(cx + ax, baseGy + bogieHy, cz + sz),
                    tileId = MaterialTile.STEEL, tint = Vec3(0.22f, 0.24f, 0.26f),
                    hp = 165f, groundAnchored = true, isCoreSupport = true, targetTag = targetTag
                )
            )
        }
        val chassisId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(6.2f, 0.20f, 1.48f),
            center = Vec3(cx, chassisY, cz),
            tileId = MaterialTile.STEEL, tint = Vec3(0.28f, 0.30f, 0.34f),
            hp = 240f, supportIds = bogies, targetTag = targetTag, checkCantilever = true
        )
        if (isTanker) {
            world.addBlock(
                poly = VoronoiPolyhedron.createCylinderApprox(1.32f, 5.6f, 12),
                center = Vec3(cx, chassisY + 1.48f, cz),
                orientation = Quat.fromAxisAngle(Vec3(0f, 0f, 1f), (PI * 0.5).toFloat()),
                tileId = MaterialTile.CORRUGATED_METAL,
                tint = Vec3(0.90f, 0.34f, 0.22f),
                hp = 96f,
                supportIds = mutableListOf(chassisId),
                isFuelExplosive = true,
                targetTag = targetTag,
                pointValue = 55
            )
        } else {
            world.addBlock(
                poly = VoronoiPolyhedron.createBox(5.95f, 1.48f, 1.42f),
                center = Vec3(cx, chassisY + 1.68f, cz),
                tileId = MaterialTile.CORRUGATED_METAL,
                tint = bodyTint,
                hp = 190f,
                supportIds = mutableListOf(chassisId),
                targetTag = targetTag,
                pointValue = 40
            )
        }
    }

    /**
     * Attached Row-Shop Brownstones / Urban Storefronts (2 adjacent colorful 3-story shopfronts).
     */
    fun buildRowShopBlock(
        world: StructuralWorld,
        terrain: ChunkedTerrain,
        cx: Float,
        cz: Float,
        targetTag: String = "ROW_SHOPS"
    ) {
        val shopHalfW = 4.6f
        val shopHalfD = 4.8f
        val hFloor = 3.25f
        val slabT = 0.28f

        val specs = listOf(
            Triple(-shopHalfW - 0.15f, MaterialTile.BRICK, Vec3(0.84f, 0.44f, 0.36f)),
            Triple(shopHalfW + 0.15f, MaterialTile.STUCCO, Vec3(0.94f, 0.82f, 0.58f))
        )
        for ((idx, spec) in specs.withIndex()) {
            val (offX, tile, tint) = spec
            val sx = cx + offX
            val (foundIds, floor1Y) = ArchitecturalPrimitives.buildGradedFoundation(
                world, terrain, sx, cz, shopHalfW, shopHalfD,
                entryOffset = Vec3(0f, 0f, shopHalfD + 0.24f), entryHalfW = 1.25f, targetTag = targetTag
            )
            val colsCoords = listOf(
                (sx - shopHalfW + 0.28f) to (cz - shopHalfD + 0.28f),
                (sx - 0.65f) to (cz - shopHalfD + 0.28f),
                (sx + shopHalfW - 0.28f) to (cz - shopHalfD + 0.28f),
                (sx - shopHalfW + 0.28f) to (cz + shopHalfD - 0.28f),
                (sx - 0.65f) to (cz + shopHalfD - 0.28f),
                (sx + shopHalfW - 0.28f) to (cz + shopHalfD - 0.28f)
            )
            var prevIds = foundIds
            var curY = floor1Y
            var topIds = foundIds
            for (story in 1..3) {
                val cols = ArchitecturalPrimitives.buildSteelCoreColumns(
                    world, colsCoords, curY, hFloor, halfThick = 0.20f, hp = 360f,
                    groundAnchored = false, supportIds = prevIds, targetTag = targetTag
                )
                if (story == 1) {
                    val frontIds = ArchitecturalPrimitives.buildDoorwayWall(
                        world, Vec3(sx, curY + hFloor * 0.5f, cz + shopHalfD - 0.22f),
                        halfLength = shopHalfW - 0.48f, height = hFloor, halfThick = 0.20f, alongX = true,
                        wallTile = tile, tint = tint, groundAnchored = false,
                        supportIds = prevIds, doorHalfW = 1.2f, targetTag = targetTag
                    )
                    world.addBlock(
                        poly = VoronoiPolyhedron.createBox(shopHalfW * 0.72f, 0.11f, 0.86f),
                        center = Vec3(sx, curY + 2.65f, cz + shopHalfD + 0.62f),
                        orientation = Quat.fromAxisAngle(Vec3(1f, 0f, 0f), 0.20f),
                        tileId = MaterialTile.AWNING_TRIM,
                        tint = if (idx == 0) Vec3(0.18f, 0.56f, 0.36f) else Vec3(0.84f, 0.26f, 0.22f),
                        hp = 42f, supportIds = (frontIds + cols).toMutableList(), targetTag = targetTag, walkable = false
                    )
                } else {
                    ArchitecturalPrimitives.buildWindowedWall(
                        world, Vec3(sx, curY + hFloor * 0.5f, cz + shopHalfD - 0.22f),
                        halfLength = shopHalfW - 0.48f, height = hFloor, halfThick = 0.20f, alongX = true,
                        wallTile = tile, tint = tint, groundAnchored = false,
                        supportIds = prevIds, windowHalfW = 1.35f, targetTag = targetTag
                    )
                }
                ArchitecturalPrimitives.buildWindowedWall(
                    world, Vec3(sx, curY + hFloor * 0.5f, cz - shopHalfD + 0.22f),
                    halfLength = shopHalfW - 0.48f, height = hFloor, halfThick = 0.20f, alongX = true,
                    wallTile = tile, tint = tint, groundAnchored = false,
                    supportIds = prevIds, windowHalfW = 1.35f, targetTag = targetTag
                )
                for (side in listOf(-1f, 1f)) {
                    ArchitecturalPrimitives.buildWindowedWall(
                        world, Vec3(sx + side * (shopHalfW - 0.22f), curY + hFloor * 0.5f, cz),
                        halfLength = shopHalfD - 0.50f, height = hFloor, halfThick = 0.20f, alongX = false,
                        wallTile = tile, tint = tint, groundAnchored = false,
                        supportIds = prevIds, windowHalfW = 1.1f, targetTag = targetTag
                    )
                }
                ArchitecturalPrimitives.buildInteriorRoomProps(world, sx + 0.4f, curY, cz - 0.4f, prevIds, targetTag)
                val nextSlabY = curY + hFloor + slabT * 0.5f
                val nextFloorY = nextSlabY + slabT * 0.5f
                val nextSlabs = ArchitecturalPrimitives.buildSegmentedFloorWithCutout(
                    world, sx, cz, nextSlabY, shopHalfW - 0.06f, shopHalfD - 0.06f, slabT * 0.5f,
                    sx - 3.6f, sx - 1.5f, cz - 1.9f, cz + 1.9f,
                    MaterialTile.CONCRETE, Vec3(0.84f, 0.84f, 0.82f), 195f, cols, targetTag
                )
                ArchitecturalPrimitives.buildStairFlight(
                    world, Vec3(sx - 2.55f, curY, cz + 1.8f), Vec3(sx - 2.55f, nextFloorY, cz - 2.0f),
                    halfWidth = 0.82f, tileId = MaterialTile.STAIR_TREAD, hp = 120f,
                    groundAnchored = false, bottomSupports = prevIds, topSupports = nextSlabs, targetTag = targetTag
                )
                prevIds = nextSlabs
                topIds = nextSlabs
                curY = nextFloorY
            }
            ArchitecturalPrimitives.buildDetailedRoof(
                world, sx, cz, eaveY = curY, halfW = shopHalfW, halfD = shopHalfD,
                roofHy = 1.15f, roofTile = MaterialTile.ROOF_SLATE, supports = topIds, withChimney = false, targetTag = targetTag
            )
        }
    }

    /**
     * Monumental Civic Plaza obelisk & landscaped plaza lamps/benches beside Clock Tower Hall.
     */
    fun buildCivicPlazaMonuments(world: StructuralWorld, terrain: ChunkedTerrain, cx: Float, cz: Float) {
        val gy = terrain.groundSurfaceY(cx, cz)
        val plinth1 = world.addBlock(
            poly = VoronoiPolyhedron.createBox(2.4f, 0.35f, 2.4f),
            center = Vec3(cx, gy + 0.32f, cz),
            tileId = MaterialTile.RIVER_STONE, tint = Vec3(0.88f, 0.86f, 0.82f),
            hp = 420f, groundAnchored = true, isCoreSupport = true, targetTag = "CIVIC_PLAZA"
        )
        val plinth2 = world.addBlock(
            poly = VoronoiPolyhedron.createBox(1.25f, 0.85f, 1.25f),
            center = Vec3(cx, gy + 1.52f, cz),
            tileId = MaterialTile.CONCRETE, tint = Vec3(0.92f, 0.90f, 0.86f),
            hp = 340f, supportIds = mutableListOf(plinth1), targetTag = "CIVIC_PLAZA"
        )
        world.addBlock(
            poly = VoronoiPolyhedron.createTaperedCanopy(0.75f, 0.14f, 6.2f, 4),
            center = Vec3(cx, gy + 5.47f, cz),
            orientation = Quat.fromAxisAngle(Vec3.UP, (PI * 0.25).toFloat()),
            tileId = MaterialTile.CONCRETE, tint = Vec3(0.95f, 0.93f, 0.88f),
            hp = 260f, supportIds = mutableListOf(plinth2), targetTag = "CIVIC_PLAZA"
        )
        for ((bx, bz, alongX) in listOf(
            Triple(cx - 4.2f, cz, false),
            Triple(cx + 4.2f, cz, false),
            Triple(cx, cz - 4.2f, true),
            Triple(cx, cz + 4.2f, true)
        )) {
            ArchitecturalPrimitives.buildParkBench(world, terrain, bx, bz, alongX = alongX)
        }
    }
}
