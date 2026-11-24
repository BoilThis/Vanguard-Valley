package com.example.engine.physics

import com.example.engine.terrain.ChunkedTerrain
import com.example.model.MaterialTile
import com.example.model.Quat
import com.example.model.Vec3
import kotlin.math.max
import kotlin.math.min

enum class SkyscraperStyle {
    LIMESTONE_SETBACK,
    GLASS_CURTAIN,
    BRICK_TERRACED
}

object SkyscraperAndComplexBuilder {

    /**
     * 12-22 Story Skyscraper built around a continuous high-HP steel elevator/stair core and
     * perimeter structural columns, with spacious lobby/floors, setback transitions, glass/spandrel facades,
     * wide climbable interior staircase flights, and rooftop props.
     */
    fun buildSkyscraper(
        world: StructuralWorld,
        terrain: ChunkedTerrain,
        cx: Float,
        cz: Float,
        stories: Int = 17,
        style: SkyscraperStyle = SkyscraperStyle.LIMESTONE_SETBACK,
        targetTag: String = "TOWER_A"
    ) {
        val storyCount = stories.coerceIn(12, 22)
        val baseHalfW = when (style) {
            SkyscraperStyle.LIMESTONE_SETBACK -> 8.4f
            SkyscraperStyle.GLASS_CURTAIN -> 7.8f
            SkyscraperStyle.BRICK_TERRACED -> 7.8f
        }
        val baseHalfD = when (style) {
            SkyscraperStyle.LIMESTONE_SETBACK -> 8.4f
            SkyscraperStyle.GLASS_CURTAIN -> 7.8f
            SkyscraperStyle.BRICK_TERRACED -> 7.0f
        }
        val lobbyH = 4.40f
        val upperH = 3.45f
        val slabT = 0.34f

        val (foundIds, floor1Y) = ArchitecturalPrimitives.buildGradedFoundation(
            world = world,
            terrain = terrain,
            cx = cx,
            cz = cz,
            halfW = baseHalfW,
            halfD = baseHalfD,
            entryOffset = Vec3(0f, 0f, baseHalfD + 0.28f),
            entryHalfW = 2.0f,
            tileId = MaterialTile.CONCRETE,
            tint = Vec3(0.76f, 0.76f, 0.75f),
            targetTag = targetTag
        )

        // Central continuous steel shaft columns (rise unbroken from 1F to roof)
        val coreCols = listOf(
            (cx - 2.2f) to (cz - 1.9f),
            (cx + 2.2f) to (cz - 1.9f),
            (cx - 2.2f) to (cz + 1.9f),
            (cx + 2.2f) to (cz + 1.9f)
        )

        var prevSlabSupports = foundIds
        var prevCoreSupports = foundIds
        var curFloorY = floor1Y
        var lastHalfW = baseHalfW
        var lastHalfD = baseHalfD

        for (story in 1..storyCount) {
            val hFloor = if (story == 1) lobbyH else upperH
            val (curHalfW, curHalfD) = when (style) {
                SkyscraperStyle.LIMESTONE_SETBACK -> when {
                    story <= 7 -> baseHalfW to baseHalfD
                    story <= 14 -> 7.1f to 7.1f
                    else -> 5.8f to 5.8f
                }
                SkyscraperStyle.GLASS_CURTAIN -> when {
                    story <= 16 -> baseHalfW to baseHalfD
                    else -> 6.5f to 6.5f
                }
                SkyscraperStyle.BRICK_TERRACED -> when {
                    story <= 10 -> baseHalfW to baseHalfD
                    else -> 6.4f to 5.8f
                }
            }

            val perimSupports = prevSlabSupports

            val coreIds = ArchitecturalPrimitives.buildSteelCoreColumns(
                world = world,
                colPositions = coreCols,
                baseY = curFloorY,
                height = hFloor,
                halfThick = 0.28f,
                hp = 880f,
                groundAnchored = false,
                supportIds = prevCoreSupports,
                targetTag = targetTag
            )

            val midXOff = (curHalfW - 0.34f) * 0.42f
            val perimCols = listOf(
                (cx - curHalfW + 0.34f) to (cz - curHalfD + 0.34f),
                (cx - midXOff) to (cz - curHalfD + 0.34f),
                (cx + midXOff) to (cz - curHalfD + 0.34f),
                (cx + curHalfW - 0.34f) to (cz - curHalfD + 0.34f),
                (cx - curHalfW + 0.34f) to (cz + curHalfD - 0.34f),
                (cx - midXOff) to (cz + curHalfD - 0.34f),
                (cx + midXOff) to (cz + curHalfD - 0.34f),
                (cx + curHalfW - 0.34f) to (cz + curHalfD - 0.34f)
            )
            val perimIds = ArchitecturalPrimitives.buildSteelCoreColumns(
                world = world,
                colPositions = perimCols,
                baseY = curFloorY,
                height = hFloor,
                halfThick = 0.25f,
                hp = 680f,
                groundAnchored = false,
                supportIds = perimSupports,
                targetTag = targetTag
            )
            val allColIds = coreIds + perimIds

            val spandrelTile = when (style) {
                SkyscraperStyle.LIMESTONE_SETBACK -> MaterialTile.CONCRETE
                SkyscraperStyle.GLASS_CURTAIN -> MaterialTile.STEEL
                SkyscraperStyle.BRICK_TERRACED -> MaterialTile.BRICK
            }
            val spandrelTint = when (style) {
                SkyscraperStyle.LIMESTONE_SETBACK -> Vec3(0.92f, 0.89f, 0.82f)
                SkyscraperStyle.GLASS_CURTAIN -> Vec3(0.30f, 0.36f, 0.44f)
                SkyscraperStyle.BRICK_TERRACED -> if (story <= 10) Vec3(0.90f, 0.62f, 0.50f) else Vec3(0.95f, 0.90f, 0.82f)
            }
            val glassTint = when (style) {
                SkyscraperStyle.LIMESTONE_SETBACK -> Vec3(0.86f, 0.93f, 0.98f)
                SkyscraperStyle.GLASS_CURTAIN -> Vec3(0.68f, 0.86f, 1.0f)
                SkyscraperStyle.BRICK_TERRACED -> Vec3(0.84f, 0.92f, 0.95f)
            }

            if (story == 1) {
                // Detailed walkable double-height lobby entrance + canopy on street front (+Z)
                val frontIds = ArchitecturalPrimitives.buildDoorwayWall(
                    world = world,
                    center = Vec3(cx, curFloorY + hFloor * 0.5f, cz + curHalfD - 0.26f),
                    halfLength = curHalfW - 0.62f,
                    height = hFloor,
                    halfThick = 0.24f,
                    alongX = true,
                    wallTile = spandrelTile,
                    tint = spandrelTint,
                    groundAnchored = false,
                    supportIds = perimSupports,
                    doorHalfW = 1.80f,
                    targetTag = targetTag
                )
                world.addBlock(
                    poly = VoronoiPolyhedron.createBox(3.1f, 0.14f, 1.05f),
                    center = Vec3(cx, curFloorY + 3.25f, cz + curHalfD + 0.85f),
                    tileId = MaterialTile.STEEL,
                    tint = Vec3(0.36f, 0.40f, 0.46f),
                    hp = 185f,
                    supportIds = (frontIds + perimIds).toMutableList(),
                    targetTag = targetTag,
                    walkable = false
                )
                ArchitecturalPrimitives.buildWindowedWall(
                    world, Vec3(cx, curFloorY + hFloor * 0.5f, cz - curHalfD + 0.26f),
                    halfLength = curHalfW - 0.62f, height = hFloor, halfThick = 0.24f, alongX = true,
                    wallTile = spandrelTile, tint = spandrelTint, groundAnchored = false,
                    supportIds = perimSupports, windowHalfW = 2.6f, targetTag = targetTag
                )
                for (side in listOf(-1f, 1f)) {
                    ArchitecturalPrimitives.buildWindowedWall(
                        world, Vec3(cx + side * (curHalfW - 0.26f), curFloorY + hFloor * 0.5f, cz),
                        halfLength = curHalfD - 0.62f, height = hFloor, halfThick = 0.24f, alongX = false,
                        wallTile = spandrelTile, tint = spandrelTint, groundAnchored = false,
                        supportIds = perimSupports, windowHalfW = 2.4f, targetTag = targetTag
                    )
                }
                ArchitecturalPrimitives.buildInteriorRoomProps(world, cx + 3.6f, curFloorY, cz - 2.4f, prevSlabSupports, targetTag)
            } else {
                // Compound upper story facade bays (spandrel + full-width glass curtain strip per face)
                val spandrelH = 0.92f
                val curtainH = hFloor - spandrelH
                for (side in listOf(-1f, 1f)) {
                    val zFace = cz + side * (curHalfD - 0.24f)
                    val sIdZ = world.addBlock(
                        poly = VoronoiPolyhedron.createBox(curHalfW - 0.58f, spandrelH * 0.5f, 0.20f),
                        center = Vec3(cx, curFloorY + spandrelH * 0.5f, zFace),
                        tileId = spandrelTile,
                        tint = spandrelTint,
                        hp = 210f,
                        supportIds = perimSupports.toMutableList(),
                        targetTag = targetTag,
                        pointValue = 16
                    )
                    world.addBlock(
                        poly = VoronoiPolyhedron.createBox(curHalfW - 0.60f, curtainH * 0.48f, 0.06f),
                        center = Vec3(cx, curFloorY + spandrelH + curtainH * 0.5f, zFace),
                        tileId = MaterialTile.GLASS,
                        tint = glassTint,
                        hp = 16f,
                        supportIds = mutableListOf(sIdZ),
                        targetTag = targetTag,
                        pointValue = 12,
                        walkable = false
                    )
                    val xFace = cx + side * (curHalfW - 0.24f)
                    val sIdX = world.addBlock(
                        poly = VoronoiPolyhedron.createBox(0.20f, spandrelH * 0.5f, curHalfD - 0.58f),
                        center = Vec3(xFace, curFloorY + spandrelH * 0.5f, cz),
                        tileId = spandrelTile,
                        tint = spandrelTint,
                        hp = 210f,
                        supportIds = perimSupports.toMutableList(),
                        targetTag = targetTag,
                        pointValue = 16
                    )
                    world.addBlock(
                        poly = VoronoiPolyhedron.createBox(0.06f, curtainH * 0.48f, curHalfD - 0.60f),
                        center = Vec3(xFace, curFloorY + spandrelH + curtainH * 0.5f, cz),
                        tileId = MaterialTile.GLASS,
                        tint = glassTint,
                        hp = 16f,
                        supportIds = mutableListOf(sIdX),
                        targetTag = targetTag,
                        pointValue = 12,
                        walkable = false
                    )
                }
            }

            val nextSlabCenterY = curFloorY + hFloor + slabT * 0.5f
            val nextFloorY = nextSlabCenterY + slabT * 0.5f

            val stairSide = if (story % 2 == 1) -1f else 1f
            val holeMinX = cx - 1.35f
            val holeMaxX = cx + 1.35f
            val holeMinZ = cz - 1.65f
            val holeMaxZ = cz + 1.65f

            val nextSlabIds = ArchitecturalPrimitives.buildSegmentedFloorWithCutout(
                world = world,
                cx = cx,
                cz = cz,
                centerY = nextSlabCenterY,
                halfW = curHalfW - 0.08f,
                halfD = curHalfD - 0.08f,
                halfThick = slabT * 0.5f,
                holeMinX = holeMinX,
                holeMaxX = holeMaxX,
                holeMinZ = holeMinZ,
                holeMaxZ = holeMaxZ,
                tileId = MaterialTile.CONCRETE,
                tint = Vec3(0.84f, 0.84f, 0.82f),
                hp = 235f,
                supports = allColIds,
                targetTag = targetTag
            )

            ArchitecturalPrimitives.buildStairFlight(
                world = world,
                start = Vec3(cx, curFloorY, cz + stairSide * 1.60f),
                end = Vec3(cx, nextFloorY, cz - stairSide * 1.65f),
                halfWidth = 1.05f,
                tileId = MaterialTile.STAIR_TREAD,
                hp = 155f,
                groundAnchored = false,
                bottomSupports = prevCoreSupports,
                topSupports = nextSlabIds,
                targetTag = targetTag,
                withRails = false
            )

            prevSlabSupports = nextSlabIds
            prevCoreSupports = nextSlabIds
            curFloorY = nextFloorY
            lastHalfW = curHalfW
            lastHalfD = curHalfD
        }

        // Rooftop Crown Parapet & Style-Specific Roof Props
        val roofY = curFloorY
        for (side in listOf(-1f, 1f)) {
            world.addBlock(
                poly = VoronoiPolyhedron.createBox(lastHalfW - 0.14f, 0.56f, 0.18f),
                center = Vec3(cx, roofY + 0.56f, cz + side * (lastHalfD - 0.24f)),
                tileId = MaterialTile.CONCRETE,
                tint = Vec3(0.86f, 0.85f, 0.82f),
                hp = 135f,
                supportIds = prevSlabSupports.toMutableList(),
                targetTag = targetTag
            )
            world.addBlock(
                poly = VoronoiPolyhedron.createBox(0.18f, 0.56f, lastHalfD - 0.36f),
                center = Vec3(cx + side * (lastHalfW - 0.24f), roofY + 0.56f, cz),
                tileId = MaterialTile.CONCRETE,
                tint = Vec3(0.86f, 0.85f, 0.82f),
                hp = 135f,
                supportIds = prevSlabSupports.toMutableList(),
                targetTag = targetTag
            )
        }

        when (style) {
            SkyscraperStyle.LIMESTONE_SETBACK -> {
                val pentId = world.addBlock(
                    poly = VoronoiPolyhedron.createBox(2.05f, 1.25f, 2.05f),
                    center = Vec3(cx, roofY + 1.25f, cz),
                    tileId = MaterialTile.CONCRETE,
                    tint = Vec3(0.88f, 0.86f, 0.80f),
                    hp = 185f,
                    supportIds = prevSlabSupports.toMutableList(),
                    targetTag = targetTag
                )
                world.addBlock(
                    poly = VoronoiPolyhedron.createCylinderApprox(0.22f, 3.4f, 10),
                    center = Vec3(cx, roofY + 2.50f + 3.4f, cz),
                    tileId = MaterialTile.STEEL,
                    tint = Vec3(0.70f, 0.74f, 0.80f),
                    hp = 160f,
                    supportIds = mutableListOf(pentId),
                    targetTag = targetTag,
                    walkable = false
                )
                CityParkAndStreetlifeBuilder.buildRooftopHvac(world, cx + 2.6f, roofY, cz - 2.6f, prevSlabSupports, targetTag)
            }
            SkyscraperStyle.GLASS_CURTAIN -> {
                val padId = world.addBlock(
                    poly = VoronoiPolyhedron.createBox(3.1f, 0.18f, 3.1f),
                    center = Vec3(cx, roofY + 0.18f, cz),
                    tileId = MaterialTile.CONCRETE,
                    tint = Vec3(0.32f, 0.36f, 0.40f),
                    hp = 185f,
                    supportIds = prevSlabSupports.toMutableList(),
                    targetTag = targetTag
                )
                for (sx in listOf(-0.85f, 0.85f)) {
                    world.addBlock(
                        poly = VoronoiPolyhedron.createBox(0.20f, 0.04f, 1.35f),
                        center = Vec3(cx + sx, roofY + 0.40f, cz),
                        tileId = MaterialTile.AWNING_TRIM,
                        tint = Vec3(1.0f, 0.88f, 0.25f),
                        hp = 55f,
                        supportIds = mutableListOf(padId),
                        targetTag = targetTag,
                        walkable = false
                    )
                }
                world.addBlock(
                    poly = VoronoiPolyhedron.createBox(0.85f, 0.04f, 0.20f),
                    center = Vec3(cx, roofY + 0.40f, cz),
                    tileId = MaterialTile.AWNING_TRIM,
                    tint = Vec3(1.0f, 0.88f, 0.25f),
                    hp = 55f,
                    supportIds = mutableListOf(padId),
                    targetTag = targetTag,
                    walkable = false
                )
            }
            SkyscraperStyle.BRICK_TERRACED -> {
                CityParkAndStreetlifeBuilder.buildRooftopWaterTower(world, cx - 2.4f, roofY, cz + 2.2f, prevSlabSupports, targetTag)
                CityParkAndStreetlifeBuilder.buildRooftopBillboard(world, cx + 1.1f, roofY, cz - 2.8f, alongX = true, supports = prevSlabSupports, targetTag = targetTag)
            }
        }
    }

    /**
     * Metro Hospital: multi-wing clinical building with ground ER ambulance canopy,
     * 4 spacious clinical stories, red medical roof cross emblem, and rooftop HVAC.
     */
    fun buildMetroHospital(
        world: StructuralWorld,
        terrain: ChunkedTerrain,
        cx: Float,
        cz: Float
    ) {
        val tag = "HOSPITAL"
        val halfW = 11.4f
        val halfD = 7.8f
        val hFloor = 3.45f
        val slabT = 0.34f

        val (foundIds, floor1Y) = ArchitecturalPrimitives.buildGradedFoundation(
            world = world,
            terrain = terrain,
            cx = cx,
            cz = cz,
            halfW = halfW,
            halfD = halfD,
            entryOffset = Vec3(0f, 0f, halfD + 0.28f),
            entryHalfW = 2.1f,
            tileId = MaterialTile.CONCRETE,
            tint = Vec3(0.84f, 0.86f, 0.88f),
            targetTag = tag
        )

        val colCoords = listOf(
            (cx - halfW + 0.34f) to (cz - halfD + 0.34f),
            (cx - 5.6f) to (cz - halfD + 0.34f),
            (cx - 1.85f) to (cz - halfD + 0.34f),
            (cx + 1.85f) to (cz - halfD + 0.34f),
            (cx + 5.6f) to (cz - halfD + 0.34f),
            (cx + halfW - 0.34f) to (cz - halfD + 0.34f),
            (cx - halfW + 0.34f) to (cz + halfD - 0.34f),
            (cx - 5.6f) to (cz + halfD - 0.34f),
            (cx - 1.85f) to (cz + halfD - 0.34f),
            (cx + 1.85f) to (cz + halfD - 0.34f),
            (cx + 5.6f) to (cz + halfD - 0.34f),
            (cx + halfW - 0.34f) to (cz + halfD - 0.34f)
        )

        val canopyPosts = ArrayList<Int>(2)
        for (px in listOf(-3.1f, 3.1f)) {
            val postX = cx + px
            val postZ = cz + halfD + 2.65f
            val pBot = min(floor1Y, terrain.groundSurfaceY(postX, postZ)) - 0.10f
            val pH = max(1.2f, floor1Y + 3.30f - pBot)
            canopyPosts.add(
                world.addBlock(
                    poly = VoronoiPolyhedron.createBox(0.22f, pH * 0.5f, 0.22f),
                    center = Vec3(postX, pBot + pH * 0.5f, postZ),
                    tileId = MaterialTile.STEEL,
                    tint = Vec3(0.88f, 0.90f, 0.92f),
                    hp = 280f,
                    groundAnchored = true,
                    isCoreSupport = true,
                    targetTag = tag
                )
            )
        }
        val erCanopyId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(3.8f, 0.20f, 1.65f),
            center = Vec3(cx, floor1Y + 3.45f, cz + halfD + 1.50f),
            tileId = MaterialTile.CONCRETE,
            tint = Vec3(0.95f, 0.97f, 0.99f),
            hp = 220f,
            supportIds = (canopyPosts + foundIds).toMutableList(),
            targetTag = tag,
            checkCantilever = true
        )
        world.addBlock(
            poly = VoronoiPolyhedron.createBox(3.82f, 0.16f, 0.10f),
            center = Vec3(cx, floor1Y + 3.48f, cz + halfD + 3.05f),
            tileId = MaterialTile.AWNING_TRIM,
            tint = Vec3(0.92f, 0.18f, 0.18f),
            hp = 68f,
            supportIds = mutableListOf(erCanopyId),
            targetTag = tag,
            walkable = false
        )

        var prevFloorIds = foundIds
        var curFloorY = floor1Y
        val wallTint = Vec3(0.97f, 0.98f, 0.99f)

        for (story in 1..4) {
            val cols = ArchitecturalPrimitives.buildSteelCoreColumns(
                world = world,
                colPositions = colCoords,
                baseY = curFloorY,
                height = hFloor,
                halfThick = 0.25f,
                hp = 560f,
                groundAnchored = false,
                supportIds = prevFloorIds,
                targetTag = tag
            )
            if (story == 1) {
                ArchitecturalPrimitives.buildDoorwayWall(
                    world, Vec3(cx, curFloorY + hFloor * 0.5f, cz + halfD - 0.26f),
                    halfLength = halfW - 0.62f, height = hFloor, halfThick = 0.24f, alongX = true,
                    wallTile = MaterialTile.STUCCO, tint = wallTint, groundAnchored = false,
                    supportIds = prevFloorIds, doorHalfW = 1.85f, targetTag = tag
                )
            } else {
                ArchitecturalPrimitives.buildWindowedWall(
                    world, Vec3(cx, curFloorY + hFloor * 0.5f, cz + halfD - 0.26f),
                    halfLength = halfW - 0.62f, height = hFloor, halfThick = 0.24f, alongX = true,
                    wallTile = MaterialTile.STUCCO, tint = wallTint, groundAnchored = false,
                    supportIds = prevFloorIds, windowHalfW = 3.2f, targetTag = tag
                )
            }
            ArchitecturalPrimitives.buildWindowedWall(
                world, Vec3(cx, curFloorY + hFloor * 0.5f, cz - halfD + 0.26f),
                halfLength = halfW - 0.62f, height = hFloor, halfThick = 0.24f, alongX = true,
                wallTile = MaterialTile.STUCCO, tint = wallTint, groundAnchored = false,
                supportIds = prevFloorIds, windowHalfW = 3.2f, targetTag = tag
            )
            for (side in listOf(-1f, 1f)) {
                ArchitecturalPrimitives.buildWindowedWall(
                    world, Vec3(cx + side * (halfW - 0.26f), curFloorY + hFloor * 0.5f, cz),
                    halfLength = halfD - 0.62f, height = hFloor, halfThick = 0.24f, alongX = false,
                    wallTile = MaterialTile.STUCCO, tint = wallTint, groundAnchored = false,
                    supportIds = prevFloorIds, windowHalfW = 2.4f, targetTag = tag
                )
            }
            ArchitecturalPrimitives.buildInteriorRoomProps(world, cx + 3.4f, curFloorY, cz - 1.2f, prevFloorIds, tag)

            val nextSlabCenterY = curFloorY + hFloor + slabT * 0.5f
            val nextFloorY = nextSlabCenterY + slabT * 0.5f
            val stairSide = if (story % 2 == 1) 1f else -1f
            val hMinX = cx - 1.45f
            val hMaxX = cx + 1.45f

            val nextSlabs = ArchitecturalPrimitives.buildSegmentedFloorWithCutout(
                world = world, cx = cx, cz = cz, centerY = nextSlabCenterY,
                halfW = halfW - 0.1f, halfD = halfD - 0.1f, halfThick = slabT * 0.5f,
                holeMinX = hMinX, holeMaxX = hMaxX, holeMinZ = cz - 2.6f, holeMaxZ = cz + 2.6f,
                tileId = MaterialTile.CONCRETE, tint = Vec3(0.86f, 0.88f, 0.90f),
                hp = 235f, supports = cols, targetTag = tag
            )
            ArchitecturalPrimitives.buildStairFlight(
                world = world,
                start = Vec3(cx, curFloorY, cz + stairSide * 2.4f),
                end = Vec3(cx, nextFloorY, cz - stairSide * 2.65f),
                halfWidth = 1.05f, tileId = MaterialTile.STAIR_TREAD, hp = 150f,
                groundAnchored = false, bottomSupports = prevFloorIds, topSupports = nextSlabs, targetTag = tag
            )
            prevFloorIds = nextSlabs
            curFloorY = nextFloorY
        }

        val roofY = curFloorY
        for (side in listOf(-1f, 1f)) {
            world.addBlock(
                poly = VoronoiPolyhedron.createBox(halfW - 0.14f, 0.50f, 0.18f),
                center = Vec3(cx, roofY + 0.50f, cz + side * (halfD - 0.24f)),
                tileId = MaterialTile.CONCRETE, tint = wallTint, hp = 130f,
                supportIds = prevFloorIds.toMutableList(), targetTag = tag
            )
            world.addBlock(
                poly = VoronoiPolyhedron.createBox(0.18f, 0.50f, halfD - 0.36f),
                center = Vec3(cx + side * (halfW - 0.24f), roofY + 0.50f, cz),
                tileId = MaterialTile.CONCRETE, tint = wallTint, hp = 130f,
                supportIds = prevFloorIds.toMutableList(), targetTag = tag
            )
        }
        world.addBlock(
            poly = VoronoiPolyhedron.createBox(1.85f, 0.08f, 0.56f),
            center = Vec3(cx + 3.4f, roofY + 0.08f, cz),
            tileId = MaterialTile.AWNING_TRIM, tint = Vec3(0.95f, 0.16f, 0.16f),
            hp = 65f, supportIds = prevFloorIds.toMutableList(), targetTag = tag, walkable = false
        )
        world.addBlock(
            poly = VoronoiPolyhedron.createBox(0.56f, 0.08f, 1.85f),
            center = Vec3(cx + 3.4f, roofY + 0.09f, cz),
            tileId = MaterialTile.AWNING_TRIM, tint = Vec3(0.95f, 0.16f, 0.16f),
            hp = 65f, supportIds = prevFloorIds.toMutableList(), targetTag = tag, walkable = false
        )
        CityParkAndStreetlifeBuilder.buildRooftopHvac(world, cx - 5.4f, roofY, cz + 2.2f, prevFloorIds, tag)
    }

    /**
     * Central Vault Bank: Neoclassical limestone portico columns, grand entrance facing West (-X),
     * spacious 4.65m tall banking hall, and a heavy steel vault core filled with reserve crates.
     */
    fun buildCentralVaultBank(
        world: StructuralWorld,
        terrain: ChunkedTerrain,
        cx: Float,
        cz: Float
    ) {
        val tag = "BANK"
        val halfW = 8.4f
        val halfD = 9.8f
        val wallH = 4.65f
        val slabT = 0.36f

        val (foundIds, floor1Y) = ArchitecturalPrimitives.buildGradedFoundation(
            world = world,
            terrain = terrain,
            cx = cx,
            cz = cz,
            halfW = halfW,
            halfD = halfD,
            entryOffset = Vec3(-halfW - 0.3f, 0f, 0f),
            entryHalfW = 2.0f,
            tileId = MaterialTile.CONCRETE,
            tint = Vec3(0.86f, 0.84f, 0.78f),
            targetTag = tag
        )

        val stoneTint = Vec3(0.93f, 0.90f, 0.82f)

        // Portico foundation apron + 4 neoclassical columns on West (-X) face
        val porticoX = cx - halfW - 1.75f
        val portGround = min(floor1Y, terrain.groundSurfaceY(porticoX, cz)) - 0.12f
        val portApronH = max(0.45f, floor1Y - portGround)
        val apronId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(1.25f, portApronH * 0.5f, 6.2f),
            center = Vec3(porticoX + 0.35f, floor1Y - portApronH * 0.5f, cz),
            tileId = MaterialTile.CONCRETE, tint = stoneTint,
            hp = 380f, groundAnchored = true, isCoreSupport = true, targetTag = tag
        )
        val porticoCols = ArrayList<Int>(4)
        for (pz in listOf(-5.2f, -2.0f, 2.0f, 5.2f)) {
            porticoCols.add(
                world.addBlock(
                    poly = VoronoiPolyhedron.createCylinderApprox(0.36f, wallH * 0.5f, 10),
                    center = Vec3(porticoX, floor1Y + wallH * 0.5f, cz + pz),
                    tileId = MaterialTile.CONCRETE, tint = Vec3(0.96f, 0.94f, 0.88f),
                    hp = 460f, groundAnchored = false, supportIds = mutableListOf(apronId),
                    isCoreSupport = true, targetTag = tag
                )
            )
        }
        world.addBlock(
            poly = VoronoiPolyhedron.createBox(1.35f, 0.46f, 6.0f),
            center = Vec3(cx - halfW - 0.92f, floor1Y + wallH + 0.46f, cz),
            tileId = MaterialTile.CONCRETE, tint = stoneTint,
            hp = 290f, supportIds = (porticoCols + foundIds).toMutableList(), targetTag = tag,
            checkCantilever = true
        )

        val bankCols = ArchitecturalPrimitives.buildSteelCoreColumns(
            world = world,
            colPositions = listOf(
                (cx - halfW + 0.34f) to (cz - halfD + 0.34f),
                (cx - 2.6f) to (cz - halfD + 0.34f),
                (cx + 2.6f) to (cz - halfD + 0.34f),
                (cx + halfW - 0.34f) to (cz - halfD + 0.34f),
                (cx - halfW + 0.34f) to (cz + halfD - 0.34f),
                (cx - 2.6f) to (cz + halfD - 0.34f),
                (cx + 2.6f) to (cz + halfD - 0.34f),
                (cx + halfW - 0.34f) to (cz + halfD - 0.34f),
                (cx + 1.2f) to (cz - 3.2f),
                (cx + 1.2f) to (cz + 3.2f)
            ),
            baseY = floor1Y,
            height = wallH,
            halfThick = 0.28f,
            hp = 580f,
            groundAnchored = false,
            supportIds = foundIds,
            targetTag = tag
        )

        ArchitecturalPrimitives.buildDoorwayWall(
            world, Vec3(cx - halfW + 0.26f, floor1Y + wallH * 0.5f, cz),
            halfLength = halfD - 0.64f, height = wallH, halfThick = 0.26f, alongX = false,
            wallTile = MaterialTile.CONCRETE, tint = stoneTint, groundAnchored = false,
            supportIds = foundIds, doorHalfW = 1.75f, targetTag = tag
        )
        ArchitecturalPrimitives.buildWindowedWall(
            world, Vec3(cx + halfW - 0.26f, floor1Y + wallH * 0.5f, cz),
            halfLength = halfD - 0.64f, height = wallH, halfThick = 0.26f, alongX = false,
            wallTile = MaterialTile.CONCRETE, tint = stoneTint, groundAnchored = false,
            supportIds = foundIds, windowHalfW = 2.4f, targetTag = tag
        )
        for (side in listOf(-1f, 1f)) {
            ArchitecturalPrimitives.buildWindowedWall(
                world, Vec3(cx, floor1Y + wallH * 0.5f, cz + side * (halfD - 0.26f)),
                halfLength = halfW - 0.64f, height = wallH, halfThick = 0.26f, alongX = true,
                wallTile = MaterialTile.CONCRETE, tint = stoneTint, groundAnchored = false,
                supportIds = foundIds, windowHalfW = 2.5f, targetTag = tag
            )
        }

        // Spacious Reinforced Steel Vault Core at back-east of hall
        val vaultX = cx + 3.8f
        val vaultSideSupports = foundIds.toMutableList()
        for (sz in listOf(-1f, 1f)) {
            world.addBlock(
                poly = VoronoiPolyhedron.createBox(2.45f, 1.75f, 0.26f),
                center = Vec3(vaultX, floor1Y + 1.75f, cz + sz * 2.45f),
                tileId = MaterialTile.STEEL, tint = Vec3(0.38f, 0.42f, 0.48f),
                hp = 720f, supportIds = vaultSideSupports, isCoreSupport = true, targetTag = tag
            )
        }
        world.addBlock(
            poly = VoronoiPolyhedron.createBox(0.26f, 1.75f, 2.25f),
            center = Vec3(vaultX + 2.35f, floor1Y + 1.75f, cz),
            tileId = MaterialTile.STEEL, tint = Vec3(0.38f, 0.42f, 0.48f),
            hp = 720f, supportIds = vaultSideSupports, isCoreSupport = true, targetTag = tag
        )
        world.addBlock(
            poly = VoronoiPolyhedron.createCylinderApprox(1.15f, 0.24f, 12),
            center = Vec3(vaultX - 2.25f, floor1Y + 1.50f, cz),
            orientation = Quat.fromAxisAngle(Vec3(0f, 0f, 1f), (Math.PI * 0.5).toFloat()),
            tileId = MaterialTile.STEEL, tint = Vec3(0.72f, 0.76f, 0.80f),
            hp = 640f, supportIds = vaultSideSupports, isCoreSupport = true, targetTag = tag
        )
        for (gz in listOf(-0.90f, 0.90f)) {
            world.addBlock(
                poly = VoronoiPolyhedron.createBox(0.54f, 0.46f, 0.54f),
                center = Vec3(vaultX + 0.45f, floor1Y + 0.46f, cz + gz),
                tileId = MaterialTile.PLASTIC, tint = Vec3(0.98f, 0.80f, 0.20f),
                hp = 75f, supportIds = vaultSideSupports, targetTag = tag, pointValue = 40
            )
        }

        val roofCenterY = floor1Y + wallH + slabT * 0.5f
        val roofFloorY = roofCenterY + slabT * 0.5f
        val roofSlabs = ArchitecturalPrimitives.buildSegmentedFloorWithCutout(
            world = world, cx = cx, cz = cz, centerY = roofCenterY,
            halfW = halfW - 0.08f, halfD = halfD - 0.08f, halfThick = slabT * 0.5f,
            holeMinX = cx - 4.8f, holeMaxX = cx - 2.4f, holeMinZ = cz + 4.4f, holeMaxZ = cz + 7.2f,
            tileId = MaterialTile.CONCRETE, tint = stoneTint, hp = 260f, supports = bankCols, targetTag = tag
        )
        ArchitecturalPrimitives.buildStairFlight(
            world = world,
            start = Vec3(cx - 3.6f, floor1Y, cz + 3.4f),
            end = Vec3(cx - 3.6f, roofFloorY, cz + 7.4f),
            halfWidth = 1.02f, tileId = MaterialTile.STAIR_TREAD, hp = 155f,
            groundAnchored = false, bottomSupports = foundIds, topSupports = roofSlabs, targetTag = tag
        )
        ArchitecturalPrimitives.buildDetailedRoof(
            world = world, cx = cx, cz = cz, eaveY = roofFloorY,
            halfW = halfW - 0.1f, halfD = halfD - 0.1f, roofHy = 1.75f,
            roofTile = MaterialTile.ROOF_SLATE, supports = roofSlabs, withChimney = false, targetTag = tag
        )
    }

    /**
     * Heavy Manufacturing Factory: wide 26.8m x 18.8m steel/corrugated hall with sawtooth skylight bays,
     * interior overhead crane beam, twin brick/steel smokestacks, and explosive boiler tanks.
     */
    fun buildHeavyFactory(
        world: StructuralWorld,
        terrain: ChunkedTerrain,
        cx: Float,
        cz: Float
    ) {
        val tag = "FACTORY"
        val halfW = 13.4f
        val halfD = 9.4f
        val wallH = 6.2f

        val (foundIds, floor1Y) = ArchitecturalPrimitives.buildGradedFoundation(
            world = world,
            terrain = terrain,
            cx = cx,
            cz = cz,
            halfW = halfW,
            halfD = halfD,
            entryOffset = Vec3(0f, 0f, -halfD - 0.28f),
            entryHalfW = 2.6f,
            tileId = MaterialTile.CONCRETE,
            tint = Vec3(0.70f, 0.72f, 0.74f),
            targetTag = tag
        )

        val colCoords = ArrayList<Pair<Float, Float>>(8)
        for (px in listOf(-halfW + 0.38f, -4.45f, 4.45f, halfW - 0.38f)) {
            colCoords.add((cx + px) to (cz - halfD + 0.38f))
            colCoords.add((cx + px) to (cz + halfD - 0.38f))
        }
        val frameCols = ArchitecturalPrimitives.buildSteelCoreColumns(
            world = world,
            colPositions = colCoords,
            baseY = floor1Y,
            height = wallH,
            halfThick = 0.30f,
            hp = 660f,
            groundAnchored = false,
            supportIds = foundIds,
            targetTag = tag
        )

        ArchitecturalPrimitives.buildDoorwayWall(
            world, Vec3(cx, floor1Y + wallH * 0.5f, cz - halfD + 0.26f),
            halfLength = halfW - 0.68f, height = wallH, halfThick = 0.24f, alongX = true,
            wallTile = MaterialTile.CORRUGATED_METAL, tint = Vec3(0.68f, 0.72f, 0.76f),
            groundAnchored = false, supportIds = foundIds, doorHalfW = 2.6f, targetTag = tag
        )
        ArchitecturalPrimitives.buildWindowedWall(
            world, Vec3(cx, floor1Y + wallH * 0.5f, cz + halfD - 0.26f),
            halfLength = halfW - 0.68f, height = wallH, halfThick = 0.24f, alongX = true,
            wallTile = MaterialTile.BRICK, tint = Vec3(0.84f, 0.60f, 0.50f),
            groundAnchored = false, supportIds = foundIds, windowHalfW = 4.2f, targetTag = tag
        )
        for (side in listOf(-1f, 1f)) {
            ArchitecturalPrimitives.buildWindowedWall(
                world, Vec3(cx + side * (halfW - 0.26f), floor1Y + wallH * 0.5f, cz),
                halfLength = halfD - 0.68f, height = wallH, halfThick = 0.24f, alongX = false,
                wallTile = MaterialTile.CORRUGATED_METAL, tint = Vec3(0.66f, 0.70f, 0.74f),
                groundAnchored = false, supportIds = foundIds, windowHalfW = 3.2f, targetTag = tag
            )
        }

        // Interior Overhead Gantry Crane Beam spanning the spacious factory hall
        val craneIds = ArrayList<Int>(4)
        for (side in listOf(-1f, 1f)) {
            craneIds.add(
                world.addBlock(
                    poly = VoronoiPolyhedron.createBox(halfW - 0.55f, 0.26f, 0.24f),
                    center = Vec3(cx, floor1Y + 4.85f, cz + side * (halfD - 1.15f)),
                    tileId = MaterialTile.STEEL, tint = Vec3(0.95f, 0.72f, 0.16f),
                    hp = 380f, supportIds = frameCols.toMutableList(), targetTag = tag, walkable = false
                )
            )
        }
        world.addBlock(
            poly = VoronoiPolyhedron.createBox(0.52f, 0.30f, halfD - 1.05f),
            center = Vec3(cx - 3.2f, floor1Y + 5.20f, cz),
            tileId = MaterialTile.STEEL, tint = Vec3(0.96f, 0.75f, 0.18f),
            hp = 340f, supportIds = craneIds, targetTag = tag, walkable = false, checkCantilever = true
        )
        ArchitecturalPrimitives.buildWarehouseRack(world, cx + 6.4f, floor1Y, cz + 3.2f, alongX = true, supportIds = foundIds, targetTag = tag)
        ArchitecturalPrimitives.buildWarehouseRack(world, cx - 6.4f, floor1Y, cz - 3.2f, alongX = true, supportIds = foundIds, targetTag = tag)

        // 3 Wide Sawtooth Skylight Roof Bays centered over frame column bays
        val roofY = floor1Y + wallH
        val bayXs = listOf(cx - 8.9f, cx, cx + 8.9f)
        for (bx in bayXs) {
            val bayRoofId = world.addBlock(
                poly = VoronoiPolyhedron.createBox(4.55f, 0.16f, halfD + 0.18f),
                center = Vec3(bx, roofY + 0.16f, cz),
                tileId = MaterialTile.CORRUGATED_METAL, tint = Vec3(0.62f, 0.66f, 0.70f),
                hp = 210f, supportIds = frameCols.toMutableList(), targetTag = tag, checkCantilever = true
            )
            world.addBlock(
                poly = VoronoiPolyhedron.createPitchedRoof(4.25f, 1.35f, halfD - 0.25f),
                center = Vec3(bx, roofY + 1.62f, cz),
                tileId = MaterialTile.GLASS, tint = Vec3(0.76f, 0.90f, 0.96f),
                hp = 48f, supportIds = mutableListOf(bayRoofId), targetTag = tag, checkCantilever = true
            )
        }

        // Twin Tall Smokestacks flanking East rear of Factory
        for (sz in listOf(-4.2f, 4.2f)) {
            val sx = cx + halfW + 1.75f
            val scz = cz + sz
            val sBot = terrain.groundSurfaceY(sx, scz) - 0.15f
            var prevStack = world.addBlock(
                poly = VoronoiPolyhedron.createBox(1.15f, 1.1f, 1.15f),
                center = Vec3(sx, sBot + 1.1f, scz),
                tileId = MaterialTile.BRICK, tint = Vec3(0.72f, 0.44f, 0.36f),
                hp = 360f, groundAnchored = true, isCoreSupport = true, targetTag = tag
            )
            var curY = sBot + 2.2f
            for (seg in 0 until 5) {
                val r = 0.92f - seg * 0.06f
                val segH = 2.8f
                prevStack = world.addBlock(
                    poly = VoronoiPolyhedron.createCylinderApprox(r, segH * 0.5f, 10),
                    center = Vec3(sx, curY + segH * 0.5f, scz),
                    tileId = if (seg == 4) MaterialTile.STEEL else MaterialTile.BRICK,
                    tint = if (seg == 4) Vec3(0.32f, 0.34f, 0.38f) else Vec3(0.78f, 0.50f, 0.42f),
                    hp = 260f - seg * 16f,
                    supportIds = mutableListOf(prevStack),
                    targetTag = tag,
                    pointValue = 35
                )
                curY += segH
            }
        }

        // Exterior Pressurized Boiler / Fuel Tanks on West yard (isFuelExplosive = true!)
        for (tz in listOf(-3.2f, 3.2f)) {
            val tx = cx - halfW - 2.0f
            val tcz = cz + tz
            val tGy = terrain.groundSurfaceY(tx, tcz)
            val cradle = world.addBlock(
                poly = VoronoiPolyhedron.createBox(1.2f, 0.58f, 1.2f),
                center = Vec3(tx, tGy + 0.52f, tcz),
                tileId = MaterialTile.CONCRETE, tint = Vec3(0.74f, 0.74f, 0.72f),
                hp = 240f, groundAnchored = true, targetTag = tag
            )
            world.addBlock(
                poly = VoronoiPolyhedron.createCylinderApprox(1.05f, 1.75f, 12),
                center = Vec3(tx, tGy + 2.82f, tcz),
                tileId = MaterialTile.CORRUGATED_METAL, tint = Vec3(0.88f, 0.36f, 0.24f),
                hp = 92f, supportIds = mutableListOf(cradle),
                isFuelExplosive = true, targetTag = tag, pointValue = 50
            )
        }
    }

    /**
     * Atrium Shopping Mall (26.8m x 16.8m): twin 2-story retail wings flanking a grand glass-skylight galleria,
     * wide storefront entrances, interior mezzanine bridge, and patio plaza entrance facing North (-Z).
     */
    fun buildShoppingMall(
        world: StructuralWorld,
        terrain: ChunkedTerrain,
        cx: Float,
        cz: Float
    ) {
        val tag = "MALL"
        val halfW = 13.4f
        val halfD = 8.4f
        val hFloor = 3.65f
        val slabT = 0.34f

        val (foundIds, floor1Y) = ArchitecturalPrimitives.buildGradedFoundation(
            world = world,
            terrain = terrain,
            cx = cx,
            cz = cz,
            halfW = halfW,
            halfD = halfD,
            entryOffset = Vec3(0f, 0f, -halfD - 0.28f),
            entryHalfW = 2.6f,
            tileId = MaterialTile.CONCRETE,
            tint = Vec3(0.86f, 0.84f, 0.80f),
            targetTag = tag
        )

        val colCoords = ArrayList<Pair<Float, Float>>(8)
        for (px in listOf(-halfW + 0.36f, -4.1f, 4.1f, halfW - 0.36f)) {
            colCoords.add((cx + px) to (cz - halfD + 0.36f))
            colCoords.add((cx + px) to (cz + halfD - 0.36f))
        }
        val mallTint = Vec3(0.95f, 0.90f, 0.84f)
        var prevFloorIds = foundIds
        var curFloorY = floor1Y

        for (story in 1..2) {
            val cols = ArchitecturalPrimitives.buildSteelCoreColumns(
                world = world,
                colPositions = colCoords,
                baseY = curFloorY,
                height = hFloor,
                halfThick = 0.25f,
                hp = 540f,
                groundAnchored = false,
                supportIds = prevFloorIds,
                targetTag = tag
            )
            if (story == 1) {
                val frontIds = ArchitecturalPrimitives.buildDoorwayWall(
                    world, Vec3(cx, curFloorY + hFloor * 0.5f, cz - halfD + 0.26f),
                    halfLength = halfW - 0.64f, height = hFloor, halfThick = 0.22f, alongX = true,
                    wallTile = MaterialTile.STUCCO, tint = mallTint, groundAnchored = false,
                    supportIds = prevFloorIds, doorHalfW = 2.4f, targetTag = tag
                )
                world.addBlock(
                    poly = VoronoiPolyhedron.createBox(4.2f, 0.14f, 0.96f),
                    center = Vec3(cx, curFloorY + 3.15f, cz - halfD - 0.72f),
                    tileId = MaterialTile.AWNING_TRIM, tint = Vec3(0.22f, 0.52f, 0.78f),
                    hp = 82f, supportIds = (frontIds + cols).toMutableList(), targetTag = tag, walkable = false
                )
            } else {
                ArchitecturalPrimitives.buildWindowedWall(
                    world, Vec3(cx, curFloorY + hFloor * 0.5f, cz - halfD + 0.26f),
                    halfLength = halfW - 0.64f, height = hFloor, halfThick = 0.22f, alongX = true,
                    wallTile = MaterialTile.STUCCO, tint = mallTint, groundAnchored = false,
                    supportIds = prevFloorIds, windowHalfW = 4.2f, targetTag = tag
                )
            }
            ArchitecturalPrimitives.buildWindowedWall(
                world, Vec3(cx, curFloorY + hFloor * 0.5f, cz + halfD - 0.26f),
                halfLength = halfW - 0.64f, height = hFloor, halfThick = 0.22f, alongX = true,
                wallTile = MaterialTile.BRICK, tint = Vec3(0.92f, 0.84f, 0.78f), groundAnchored = false,
                supportIds = prevFloorIds, windowHalfW = 4.0f, targetTag = tag
            )
            for (side in listOf(-1f, 1f)) {
                ArchitecturalPrimitives.buildWindowedWall(
                    world, Vec3(cx + side * (halfW - 0.26f), curFloorY + hFloor * 0.5f, cz),
                    halfLength = halfD - 0.64f, height = hFloor, halfThick = 0.22f, alongX = false,
                    wallTile = MaterialTile.STUCCO, tint = mallTint, groundAnchored = false,
                    supportIds = prevFloorIds, windowHalfW = 3.0f, targetTag = tag
                )
            }

            if (story == 1) {
                ArchitecturalPrimitives.buildInteriorRoomProps(world, cx - 8.2f, curFloorY, cz + 1.6f, prevFloorIds, tag)
                ArchitecturalPrimitives.buildInteriorRoomProps(world, cx + 8.2f, curFloorY, cz + 1.6f, prevFloorIds, tag)
                val nextSlabCenterY = curFloorY + hFloor + slabT * 0.5f
                val nextFloorY = nextSlabCenterY + slabT * 0.5f
                val nextSlabs = ArchitecturalPrimitives.buildSegmentedFloorWithCutout(
                    world = world, cx = cx, cz = cz, centerY = nextSlabCenterY,
                    halfW = halfW - 0.1f, halfD = halfD - 0.1f, halfThick = slabT * 0.5f,
                    holeMinX = cx - 3.4f, holeMaxX = cx + 3.4f, holeMinZ = cz - 3.1f, holeMaxZ = cz + 3.1f,
                    tileId = MaterialTile.CONCRETE, tint = Vec3(0.88f, 0.87f, 0.84f),
                    hp = 230f, supports = cols, targetTag = tag
                )
                ArchitecturalPrimitives.buildStairFlight(
                    world = world,
                    start = Vec3(cx, curFloorY, cz + 2.8f),
                    end = Vec3(cx, nextFloorY, cz - 3.1f),
                    halfWidth = 1.15f, tileId = MaterialTile.STAIR_TREAD, hp = 145f,
                    groundAnchored = false, bottomSupports = prevFloorIds, topSupports = nextSlabs, targetTag = tag
                )
                prevFloorIds = nextSlabs
                curFloorY = nextFloorY
            } else {
                val roofY = curFloorY + hFloor + 0.16f
                for (side in listOf(-1f, 1f)) {
                    world.addBlock(
                        poly = VoronoiPolyhedron.createBox(4.7f, 0.18f, halfD),
                        center = Vec3(cx + side * 8.65f, roofY, cz),
                        tileId = MaterialTile.CONCRETE, tint = Vec3(0.84f, 0.84f, 0.82f),
                        hp = 215f, supportIds = cols.toMutableList(), targetTag = tag, checkCantilever = true
                    )
                }
                val skyBaseId = world.addBlock(
                    poly = VoronoiPolyhedron.createBox(4.05f, 0.16f, halfD),
                    center = Vec3(cx, roofY, cz),
                    tileId = MaterialTile.STEEL, tint = Vec3(0.45f, 0.50f, 0.56f),
                    hp = 290f, supportIds = cols.toMutableList(), targetTag = tag, checkCantilever = true
                )
                world.addBlock(
                    poly = VoronoiPolyhedron.createPitchedRoof(3.95f, 1.55f, halfD - 0.1f),
                    center = Vec3(cx, roofY + 1.70f, cz),
                    tileId = MaterialTile.GLASS, tint = Vec3(0.80f, 0.93f, 1.0f),
                    hp = 48f, supportIds = mutableListOf(skyBaseId), targetTag = tag, checkCantilever = true
                )
                CityParkAndStreetlifeBuilder.buildRooftopHvac(world, cx - 8.6f, roofY + 0.18f, cz, cols, tag)
            }
        }
    }
}
