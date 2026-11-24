package com.example.engine.physics

import com.example.engine.terrain.ChunkedTerrain
import com.example.model.MaterialTile
import com.example.model.Quat
import com.example.model.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class ParkedVehicleKind {
    SEDAN,
    VAN,
    BOX_TRUCK
}

object CityParkAndStreetlifeBuilder {

    /**
     * Populates Central Park (x in -142..-66, z in 10..46) with a tiered stone central fountain,
     * twin timber-and-slate gazebos, flowerbeds, benches, lamps, and park trees.
     */
    fun buildCentralPark(world: StructuralWorld, terrain: ChunkedTerrain) {
        buildParkFountain(world, terrain, cx = -104f, cz = 28f)
        buildParkGazebo(world, terrain, cx = -126f, cz = 20f)
        buildParkGazebo(world, terrain, cx = -82f, cz = 36f)

        // Benches framing the fountain ring & pathways
        for ((bx, bz, alongX) in listOf(
            Triple(-104f, 19.2f, true),
            Triple(-104f, 36.8f, true),
            Triple(-112.8f, 28f, false),
            Triple(-95.2f, 28f, false),
            Triple(-120f, 31.2f, true),
            Triple(-88f, 24.8f, true),
            Triple(-134f, 24.8f, true),
            Triple(-74f, 31.2f, true)
        )) {
            ArchitecturalPrimitives.buildParkBench(world, terrain, bx, bz, alongX = alongX)
        }

        // Park lampposts illuminating cross paths
        for ((lx, lz) in listOf(
            -136f to 25.0f, -118f to 25.0f, -90f to 31.0f, -72f to 31.0f,
            -107.0f to 14.5f, -101.0f to 41.5f
        )) {
            ArchitecturalPrimitives.buildStreetLamp(world, terrain, lx, lz)
        }

        // Decorative landscaped flowerbeds in the 4 park quadrants
        for ((fx, fz, tint) in listOf(
            Triple(-113.5f, 16.5f, Vec3(0.95f, 0.34f, 0.42f)),
            Triple(-94.5f, 16.5f, Vec3(0.96f, 0.78f, 0.22f)),
            Triple(-113.5f, 39.5f, Vec3(0.76f, 0.42f, 0.90f)),
            Triple(-94.5f, 39.5f, Vec3(0.96f, 0.46f, 0.28f))
        )) {
            buildFlowerBed(world, terrain, fx, fz, tint)
        }

        // Park trees placed in the 4 lawn quadrants away from paths, fountain, and gazebos
        val treeCoords = listOf(
            -137f to 14f, -138f to 38f, -128f to 40f, -118f to 41f,
            -88f to 15f, -74f to 16f, -70f to 40f, -92f to 41f,
            -118f to 14f, -72f to 21f, -133f to 34f, -92f to 21f,
            -136f to 21f, -74f to 29f, -122f to 38f, -80f to 14f
        )
        for ((idx, pt) in treeCoords.withIndex()) {
            val (tx, tz) = pt
            val scale = 0.92f + (idx % 3) * 0.14f
            ZoneBuilder.buildPineTree(world, terrain, tx, tz, scale)
        }
    }

    fun buildParkFountain(world: StructuralWorld, terrain: ChunkedTerrain, cx: Float, cz: Float) {
        val tag = "PARK_FOUNTAIN"
        val gy = terrain.groundSurfaceY(cx, cz)
        val baseId = world.addBlock(
            poly = VoronoiPolyhedron.createCylinderApprox(3.2f, 0.24f, 12),
            center = Vec3(cx, gy + 0.20f, cz),
            tileId = MaterialTile.RIVER_STONE,
            tint = Vec3(0.86f, 0.85f, 0.82f),
            hp = 220f,
            groundAnchored = true,
            isCoreSupport = true,
            targetTag = tag
        )
        // Outer stone rim segments around water basin
        for (i in 0 until 8) {
            val ang = (i * PI * 0.25).toFloat()
            val rx = cx + cos(ang) * 2.65f
            val rz = cz + sin(ang) * 2.65f
            world.addBlock(
                poly = VoronoiPolyhedron.createBox(1.08f, 0.34f, 0.28f),
                center = Vec3(rx, gy + 0.64f, rz),
                orientation = Quat.fromAxisAngle(Vec3.UP, -ang + (PI * 0.5).toFloat()),
                tileId = MaterialTile.CONCRETE,
                tint = Vec3(0.90f, 0.88f, 0.84f),
                hp = 120f,
                supportIds = mutableListOf(baseId),
                targetTag = tag
            )
        }
        // Translucent shimmering water disc inside basin
        world.addBlock(
            poly = VoronoiPolyhedron.createCylinderApprox(2.42f, 0.12f, 12),
            center = Vec3(cx, gy + 0.56f, cz),
            tileId = MaterialTile.GLASS,
            tint = Vec3(0.32f, 0.78f, 0.98f),
            hp = 30f,
            supportIds = mutableListOf(baseId),
            isHydrant = true,
            targetTag = tag,
            walkable = false
        )
        // Central carved pedestal + upper bowl + finial spire
        val pedId = world.addBlock(
            poly = VoronoiPolyhedron.createCylinderApprox(0.55f, 0.85f, 10),
            center = Vec3(cx, gy + 1.25f, cz),
            tileId = MaterialTile.CONCRETE,
            tint = Vec3(0.92f, 0.90f, 0.86f),
            hp = 160f,
            supportIds = mutableListOf(baseId),
            targetTag = tag
        )
        val bowlId = world.addBlock(
            poly = VoronoiPolyhedron.createCylinderApprox(1.15f, 0.16f, 10),
            center = Vec3(cx, gy + 2.20f, cz),
            tileId = MaterialTile.CONCRETE,
            tint = Vec3(0.94f, 0.92f, 0.88f),
            hp = 110f,
            supportIds = mutableListOf(pedId),
            targetTag = tag
        )
        world.addBlock(
            poly = VoronoiPolyhedron.createCylinderApprox(0.24f, 0.52f, 8),
            center = Vec3(cx, gy + 2.86f, cz),
            tileId = MaterialTile.CONCRETE,
            tint = Vec3(0.95f, 0.93f, 0.90f),
            hp = 80f,
            supportIds = mutableListOf(bowlId),
            isHydrant = true,
            targetTag = tag,
            walkable = false
        )
    }

    fun buildParkGazebo(world: StructuralWorld, terrain: ChunkedTerrain, cx: Float, cz: Float) {
        val tag = "PARK_GAZEBO"
        val gy = terrain.groundSurfaceY(cx, cz)
        val deckH = 0.24f
        val deckId = world.addBlock(
            poly = VoronoiPolyhedron.createCylinderApprox(2.55f, deckH, 8),
            center = Vec3(cx, gy + deckH, cz),
            tileId = MaterialTile.RIVER_STONE,
            tint = Vec3(0.84f, 0.82f, 0.78f),
            hp = 180f,
            groundAnchored = true,
            isCoreSupport = true,
            targetTag = tag
        )
        val floorY = gy + deckH * 2f
        val postH = 2.65f
        val posts = ArrayList<Int>(6)
        for (i in 0 until 6) {
            val ang = (i * PI / 3.0).toFloat()
            val px = cx + cos(ang) * 2.15f
            val pz = cz + sin(ang) * 2.15f
            posts.add(
                world.addBlock(
                    poly = VoronoiPolyhedron.createBox(0.14f, postH * 0.5f, 0.14f),
                    center = Vec3(px, floorY + postH * 0.5f, pz),
                    tileId = MaterialTile.TIMBER,
                    tint = Vec3(0.86f, 0.72f, 0.54f),
                    hp = 85f,
                    supportIds = mutableListOf(deckId),
                    isCoreSupport = true,
                    targetTag = tag
                )
            )
            // Perimeter railing between posts (leave front +Z opening clear)
            if (i != 1) {
                val nextAng = ((i + 1) * PI / 3.0).toFloat()
                val midX = cx + (cos(ang) + cos(nextAng)) * 0.5f * 2.15f
                val midZ = cz + (sin(ang) + sin(nextAng)) * 0.5f * 2.15f
                val railYaw = -(ang + (PI / 6.0).toFloat())
                world.addBlock(
                    poly = VoronoiPolyhedron.createBox(0.08f, 0.36f, 0.96f),
                    center = Vec3(midX, floorY + 0.42f, midZ),
                    orientation = Quat.fromAxisAngle(Vec3.UP, railYaw),
                    tileId = MaterialTile.TIMBER,
                    tint = Vec3(0.90f, 0.78f, 0.62f),
                    hp = 45f,
                    supportIds = mutableListOf(deckId),
                    targetTag = tag,
                    walkable = false
                )
            }
        }
        val roofBaseId = world.addBlock(
            poly = VoronoiPolyhedron.createCylinderApprox(2.55f, 0.14f, 8),
            center = Vec3(cx, floorY + postH + 0.14f, cz),
            tileId = MaterialTile.TIMBER,
            tint = Vec3(0.82f, 0.68f, 0.50f),
            hp = 95f,
            supportIds = posts,
            targetTag = tag,
            checkCantilever = true
        )
        world.addBlock(
            poly = VoronoiPolyhedron.createTaperedCanopy(2.80f, 0.18f, 1.35f, 8),
            center = Vec3(cx, floorY + postH + 0.28f + 0.68f, cz),
            tileId = MaterialTile.ROOF_SLATE,
            tint = Vec3(0.32f, 0.42f, 0.36f),
            hp = 80f,
            supportIds = mutableListOf(roofBaseId),
            targetTag = tag
        )
    }

    private fun buildFlowerBed(
        world: StructuralWorld,
        terrain: ChunkedTerrain,
        cx: Float,
        cz: Float,
        bloomTint: Vec3
    ) {
        val gy = terrain.groundSurfaceY(cx, cz)
        val borderId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(1.55f, 0.16f, 0.85f),
            center = Vec3(cx, gy + 0.14f, cz),
            tileId = MaterialTile.TIMBER,
            tint = Vec3(0.72f, 0.54f, 0.38f),
            hp = 42f,
            groundAnchored = true,
            walkable = false
        )
        world.addBlock(
            poly = VoronoiPolyhedron.createBox(1.38f, 0.18f, 0.68f),
            center = Vec3(cx, gy + 0.38f, cz),
            tileId = MaterialTile.PINE_NEEDLE,
            tint = Vec3(0.82f, 1.0f, 0.80f),
            hp = 22f,
            supportIds = mutableListOf(borderId),
            walkable = false
        )
        for (fx in listOf(-0.75f, 0f, 0.75f)) {
            world.addBlock(
                poly = VoronoiPolyhedron.createBox(0.22f, 0.12f, 0.22f),
                center = Vec3(cx + fx, gy + 0.60f, cz),
                tileId = MaterialTile.AWNING_TRIM,
                tint = bloomTint,
                hp = 14f,
                supportIds = mutableListOf(borderId),
                walkable = false
            )
        }
    }

    /**
     * Builds a timber Riverwalk boardwalk along the West riverbank clear of the triple bridges (z=-56, 0, +56).
     */
    fun buildRiverwalk(world: StructuralWorld, terrain: ChunkedTerrain) {
        val spans = listOf(-102f to -66f, -46f to -10f, 10f to 46f, 66f to 102f)
        val stepZ = 4.0f
        for ((zStart, zEnd) in spans) {
            var z = zStart
            while (z <= zEnd + 0.1f) {
                val bx = terrain.riverCenterX(z) - terrain.riverHalfWidth() - 2.15f
                val bankGy = terrain.groundSurfaceY(bx, z)
                val deckY = max(bankGy + 0.28f, 3.22f)
                val pilings = ArrayList<Int>(2)
                for (sz in listOf(-1.45f, 1.45f)) {
                    val pz = z + sz
                    val pGy = terrain.groundSurfaceY(bx + 1.0f, pz) - 0.20f
                    val pH = max(0.55f, deckY - pGy)
                    pilings.add(
                        world.addBlock(
                            poly = VoronoiPolyhedron.createCylinderApprox(0.14f, pH * 0.5f, 6),
                            center = Vec3(bx + 1.0f, pGy + pH * 0.5f, pz),
                            tileId = MaterialTile.PINE_BARK,
                            tint = Vec3(0.66f, 0.52f, 0.38f),
                            hp = 78f,
                            groundAnchored = true,
                            targetTag = "RIVERWALK"
                        )
                    )
                }
                val deckId = world.addBlock(
                    poly = VoronoiPolyhedron.createBox(1.42f, 0.12f, stepZ * 0.49f),
                    center = Vec3(bx, deckY, z),
                    tileId = MaterialTile.TIMBER,
                    tint = Vec3(0.84f, 0.68f, 0.48f),
                    hp = 68f,
                    groundAnchored = true,
                    supportIds = pilings,
                    targetTag = "RIVERWALK"
                )
                // River-side safety railing along +X edge
                world.addBlock(
                    poly = VoronoiPolyhedron.createBox(0.06f, 0.44f, stepZ * 0.46f),
                    center = Vec3(bx + 1.32f, deckY + 0.52f, z),
                    tileId = MaterialTile.TIMBER,
                    tint = Vec3(0.88f, 0.74f, 0.56f),
                    hp = 36f,
                    supportIds = mutableListOf(deckId),
                    targetTag = "RIVERWALK",
                    walkable = false
                )
                val stepIdx = ((z - zStart) / stepZ).toInt()
                if (stepIdx % 3 == 1) {
                    ArchitecturalPrimitives.buildStreetLamp(world, terrain, bx - 1.1f, z)
                } else if (stepIdx % 3 == 2) {
                    ArchitecturalPrimitives.buildParkBench(world, terrain, bx - 0.65f, z, alongX = false)
                }
                z += stepZ
            }
        }
    }

    fun buildTrafficLight(
        world: StructuralWorld,
        terrain: ChunkedTerrain,
        x: Float,
        z: Float,
        armDirX: Float = 1f,
        armDirZ: Float = 0f
    ) {
        val gy = terrain.groundSurfaceY(x, z)
        val poleH = 4.5f
        val poleId = world.addBlock(
            poly = VoronoiPolyhedron.createCylinderApprox(0.13f, poleH * 0.5f, 8),
            center = Vec3(x, gy + poleH * 0.5f, z),
            tileId = MaterialTile.STEEL,
            tint = Vec3(0.24f, 0.26f, 0.30f),
            hp = 95f,
            groundAnchored = true,
            pointValue = 16,
            walkable = false
        )
        val normLen = max(1f, sqrt(armDirX * armDirX + armDirZ * armDirZ))
        val dx = armDirX / normLen
        val dz = armDirZ / normLen
        val armLen = 1.6f
        val armId = world.addBlock(
            poly = if (abs(dx) > abs(dz)) VoronoiPolyhedron.createBox(armLen, 0.08f, 0.08f) else VoronoiPolyhedron.createBox(0.08f, 0.08f, armLen),
            center = Vec3(x + dx * (armLen * 0.85f), gy + poleH - 0.18f, z + dz * (armLen * 0.85f)),
            tileId = MaterialTile.STEEL,
            tint = Vec3(0.28f, 0.30f, 0.34f),
            hp = 60f,
            supportIds = mutableListOf(poleId),
            walkable = false
        )
        val hx = x + dx * 2.25f
        val hz = z + dz * 2.25f
        val housingId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(0.18f, 0.44f, 0.18f),
            center = Vec3(hx, gy + poleH - 0.55f, hz),
            tileId = MaterialTile.STEEL,
            tint = Vec3(0.18f, 0.20f, 0.22f),
            hp = 35f,
            supportIds = mutableListOf(armId),
            pointValue = 14,
            walkable = false
        )
        // Red / Amber / Green signal indicator lenses
        val colors = listOf(
            0.24f to Vec3(0.98f, 0.18f, 0.16f),
            0.0f to Vec3(0.98f, 0.74f, 0.14f),
            -0.24f to Vec3(0.18f, 0.92f, 0.36f)
        )
        for ((yOff, col) in colors) {
            world.addBlock(
                poly = VoronoiPolyhedron.createBox(0.12f, 0.09f, 0.20f),
                center = Vec3(hx, gy + poleH - 0.55f + yOff, hz),
                tileId = MaterialTile.PLASTIC,
                tint = col,
                hp = 12f,
                supportIds = mutableListOf(housingId),
                walkable = false
            )
        }
    }

    fun buildBusShelter(
        world: StructuralWorld,
        terrain: ChunkedTerrain,
        cx: Float,
        cz: Float,
        alongX: Boolean = true
    ) {
        val gy = terrain.groundSurfaceY(cx, cz)
        val halfSpan = 1.65f
        val shelterH = 2.35f
        val posts = ArrayList<Int>(2)
        for (s in listOf(-halfSpan + 0.12f, halfSpan - 0.12f)) {
            val px = if (alongX) cx + s else cx
            val pz = if (alongX) cz else cz + s
            val pGy = terrain.groundSurfaceY(px, pz)
            posts.add(
                world.addBlock(
                    poly = VoronoiPolyhedron.createBox(0.08f, shelterH * 0.5f, 0.08f),
                    center = Vec3(px, pGy + shelterH * 0.5f, pz),
                    tileId = MaterialTile.STEEL,
                    tint = Vec3(0.32f, 0.38f, 0.46f),
                    hp = 72f,
                    groundAnchored = true,
                    walkable = false
                )
            )
        }
        // Back glass windscreen
        world.addBlock(
            poly = if (alongX) VoronoiPolyhedron.createBox(halfSpan - 0.20f, 0.95f, 0.05f) else VoronoiPolyhedron.createBox(0.05f, 0.95f, halfSpan - 0.20f),
            center = Vec3(cx, gy + 1.15f, cz),
            tileId = MaterialTile.GLASS,
            tint = Vec3(0.80f, 0.92f, 0.98f),
            hp = 16f,
            supportIds = posts,
            walkable = false
        )
        // Curved/sloped transit shelter roof
        world.addBlock(
            poly = if (alongX) VoronoiPolyhedron.createBox(halfSpan + 0.15f, 0.09f, 0.72f) else VoronoiPolyhedron.createBox(0.72f, 0.09f, halfSpan + 0.15f),
            center = Vec3(cx, gy + shelterH + 0.09f, cz),
            tileId = MaterialTile.AWNING_TRIM,
            tint = Vec3(0.20f, 0.56f, 0.82f),
            hp = 52f,
            supportIds = posts,
            checkCantilever = true
        )
        ArchitecturalPrimitives.buildParkBench(world, terrain, cx, cz + if (alongX) 0.35f else 0f, alongX = alongX)
    }

    fun buildParkedVehicle(
        world: StructuralWorld,
        terrain: ChunkedTerrain,
        cx: Float,
        cz: Float,
        alongX: Boolean = true,
        kind: ParkedVehicleKind = ParkedVehicleKind.SEDAN,
        bodyTint: Vec3 = Vec3(0.24f, 0.52f, 0.84f)
    ) {
        val gy = terrain.groundSurfaceY(cx, cz)
        val halfLen = when (kind) {
            ParkedVehicleKind.SEDAN -> 2.05f
            ParkedVehicleKind.VAN -> 2.25f
            ParkedVehicleKind.BOX_TRUCK -> 3.05f
        }
        val halfW = when (kind) {
            ParkedVehicleKind.SEDAN -> 0.90f
            ParkedVehicleKind.VAN -> 0.98f
            ParkedVehicleKind.BOX_TRUCK -> 1.15f
        }
        val wheelR = if (kind == ParkedVehicleKind.BOX_TRUCK) 0.38f else 0.32f

        fun box(lx: Float, ly: Float, lz: Float) = if (alongX) {
            VoronoiPolyhedron.createBox(lx, ly, lz)
        } else {
            VoronoiPolyhedron.createBox(lz, ly, lx)
        }
        fun pt(along: Float, y: Float, side: Float = 0f) = if (alongX) {
            Vec3(cx + along, y, cz + side)
        } else {
            Vec3(cx + side, y, cz + along)
        }

        // 4 Wheels (graded to local slope so vehicles stay grounded even on hillside brush placement)
        val wheelIds = ArrayList<Int>(4)
        for (ax in listOf(-halfLen * 0.62f, halfLen * 0.62f)) {
            for (sd in listOf(-halfW + 0.10f, halfW - 0.10f)) {
                val wPos = pt(ax, gy + wheelR, sd)
                val wGy = minOf(gy, terrain.groundSurfaceY(wPos.x, wPos.z)) - 0.04f
                val wTop = gy + wheelR * 2f
                val wHy = max(wheelR, (wTop - wGy) * 0.5f)
                wheelIds.add(
                    world.addBlock(
                        poly = VoronoiPolyhedron.createBox(0.26f, wHy, 0.18f),
                        center = Vec3(wPos.x, wGy + wHy, wPos.z),
                        tileId = MaterialTile.PLASTIC,
                        tint = Vec3(0.16f, 0.18f, 0.20f),
                        hp = 38f,
                        groundAnchored = true,
                        pointValue = 8,
                        walkable = false
                    )
                )
            }
        }

        // Lower chassis body
        val chassisHy = if (kind == ParkedVehicleKind.BOX_TRUCK) 0.36f else 0.34f
        val chassisId = world.addBlock(
            poly = box(halfLen, chassisHy, halfW),
            center = pt(0f, gy + wheelR + chassisHy * 0.85f, 0f),
            tileId = MaterialTile.STEEL,
            tint = bodyTint,
            hp = 92f,
            supportIds = wheelIds,
            targetTag = "VEHICLE",
            pointValue = 25,
            checkCantilever = true
        )

        val deckTop = gy + wheelR + chassisHy * 1.85f
        when (kind) {
            ParkedVehicleKind.SEDAN -> {
                val cabId = world.addBlock(
                    poly = box(1.02f, 0.34f, halfW - 0.08f),
                    center = pt(-0.10f, deckTop + 0.34f, 0f),
                    tileId = MaterialTile.STEEL,
                    tint = bodyTint,
                    hp = 68f,
                    supportIds = mutableListOf(chassisId),
                    pointValue = 16
                )
                world.addBlock(
                    poly = box(0.12f, 0.28f, halfW - 0.14f),
                    center = pt(0.95f, deckTop + 0.28f, 0f),
                    tileId = MaterialTile.GLASS,
                    tint = Vec3(0.76f, 0.90f, 0.98f),
                    hp = 12f,
                    supportIds = mutableListOf(chassisId, cabId),
                    walkable = false
                )
            }
            ParkedVehicleKind.VAN -> {
                world.addBlock(
                    poly = box(halfLen - 0.35f, 0.56f, halfW - 0.06f),
                    center = pt(-0.18f, deckTop + 0.56f, 0f),
                    tileId = MaterialTile.CORRUGATED_METAL,
                    tint = bodyTint,
                    hp = 78f,
                    supportIds = mutableListOf(chassisId),
                    pointValue = 20
                )
                world.addBlock(
                    poly = box(0.10f, 0.36f, halfW - 0.12f),
                    center = pt(halfLen - 0.42f, deckTop + 0.38f, 0f),
                    tileId = MaterialTile.GLASS,
                    tint = Vec3(0.76f, 0.90f, 0.98f),
                    hp = 12f,
                    supportIds = mutableListOf(chassisId),
                    walkable = false
                )
            }
            ParkedVehicleKind.BOX_TRUCK -> {
                // Cab at front + Tall Cargo Box at rear
                world.addBlock(
                    poly = box(0.78f, 0.65f, halfW - 0.08f),
                    center = pt(halfLen - 0.85f, deckTop + 0.65f, 0f),
                    tileId = MaterialTile.STEEL,
                    tint = bodyTint,
                    hp = 85f,
                    supportIds = mutableListOf(chassisId),
                    pointValue = 20
                )
                world.addBlock(
                    poly = box(1.95f, 1.05f, halfW),
                    center = pt(-0.98f, deckTop + 1.05f, 0f),
                    tileId = MaterialTile.CORRUGATED_METAL,
                    tint = Vec3(0.94f, 0.95f, 0.96f),
                    hp = 96f,
                    supportIds = mutableListOf(chassisId),
                    pointValue = 30,
                    checkCantilever = true
                )
            }
        }
    }

    fun buildCafePatio(world: StructuralWorld, terrain: ChunkedTerrain, cx: Float, cz: Float) {
        val gy = terrain.groundSurfaceY(cx, cz)
        for ((ox, tint) in listOf(-1.55f to Vec3(0.94f, 0.36f, 0.28f), 1.55f to Vec3(0.26f, 0.62f, 0.86f))) {
            val tx = cx + ox
            val poleId = world.addBlock(
                poly = VoronoiPolyhedron.createCylinderApprox(0.06f, 1.15f, 6),
                center = Vec3(tx, gy + 1.15f, cz),
                tileId = MaterialTile.STEEL,
                tint = Vec3(0.36f, 0.38f, 0.42f),
                hp = 38f,
                groundAnchored = true,
                walkable = false
            )
            world.addBlock(
                poly = VoronoiPolyhedron.createCylinderApprox(0.55f, 0.06f, 8),
                center = Vec3(tx, gy + 0.78f, cz),
                tileId = MaterialTile.TIMBER,
                tint = Vec3(0.88f, 0.76f, 0.58f),
                hp = 24f,
                supportIds = mutableListOf(poleId)
            )
            world.addBlock(
                poly = VoronoiPolyhedron.createTaperedCanopy(1.10f, 0.10f, 0.34f, 8),
                center = Vec3(tx, gy + 2.38f, cz),
                tileId = MaterialTile.AWNING_TRIM,
                tint = tint,
                hp = 18f,
                supportIds = mutableListOf(poleId),
                walkable = false
            )
        }
    }

    fun buildDumpster(world: StructuralWorld, terrain: ChunkedTerrain, x: Float, z: Float, alongX: Boolean = true) {
        val gy = terrain.groundSurfaceY(x, z)
        val poly = if (alongX) VoronoiPolyhedron.createBox(0.95f, 0.55f, 0.62f) else VoronoiPolyhedron.createBox(0.62f, 0.55f, 0.95f)
        val bodyId = world.addBlock(
            poly = poly,
            center = Vec3(x, gy + 0.55f, z),
            tileId = MaterialTile.STEEL,
            tint = Vec3(0.20f, 0.48f, 0.34f),
            hp = 85f,
            groundAnchored = true,
            pointValue = 16
        )
        val lidPoly = if (alongX) VoronoiPolyhedron.createBox(0.92f, 0.07f, 0.60f) else VoronoiPolyhedron.createBox(0.60f, 0.07f, 0.92f)
        world.addBlock(
            poly = lidPoly,
            center = Vec3(x, gy + 1.17f, z),
            tileId = MaterialTile.PLASTIC,
            tint = Vec3(0.16f, 0.18f, 0.20f),
            hp = 22f,
            supportIds = mutableListOf(bodyId),
            walkable = false
        )
    }

    fun buildRooftopWaterTower(
        world: StructuralWorld,
        cx: Float,
        roofY: Float,
        cz: Float,
        supports: List<Int>,
        targetTag: String? = null
    ) {
        val legs = ArrayList<Int>(4)
        for (sx in listOf(-0.62f, 0.62f)) {
            for (sz in listOf(-0.62f, 0.62f)) {
                legs.add(
                    world.addBlock(
                        poly = VoronoiPolyhedron.createBox(0.09f, 0.72f, 0.09f),
                        center = Vec3(cx + sx, roofY + 0.72f, cz + sz),
                        tileId = MaterialTile.STEEL,
                        tint = Vec3(0.38f, 0.42f, 0.46f),
                        hp = 72f,
                        supportIds = supports.toMutableList(),
                        targetTag = targetTag,
                        walkable = false
                    )
                )
            }
        }
        val tankId = world.addBlock(
            poly = VoronoiPolyhedron.createCylinderApprox(0.95f, 0.95f, 10),
            center = Vec3(cx, roofY + 1.44f + 0.95f, cz),
            tileId = MaterialTile.TIMBER,
            tint = Vec3(0.76f, 0.60f, 0.44f),
            hp = 54f,
            supportIds = legs,
            targetTag = targetTag
        )
        world.addBlock(
            poly = VoronoiPolyhedron.createTaperedCanopy(1.06f, 0.10f, 0.52f, 10),
            center = Vec3(cx, roofY + 3.34f + 0.26f, cz),
            tileId = MaterialTile.CORRUGATED_METAL,
            tint = Vec3(0.46f, 0.50f, 0.54f),
            hp = 36f,
            supportIds = mutableListOf(tankId),
            targetTag = targetTag,
            walkable = false
        )
    }

    fun buildRooftopHvac(
        world: StructuralWorld,
        cx: Float,
        roofY: Float,
        cz: Float,
        supports: List<Int>,
        targetTag: String? = null
    ) {
        val unitId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(0.95f, 0.48f, 0.78f),
            center = Vec3(cx, roofY + 0.48f, cz),
            tileId = MaterialTile.CORRUGATED_METAL,
            tint = Vec3(0.78f, 0.82f, 0.86f),
            hp = 64f,
            supportIds = supports.toMutableList(),
            targetTag = targetTag
        )
        world.addBlock(
            poly = VoronoiPolyhedron.createCylinderApprox(0.32f, 0.14f, 8),
            center = Vec3(cx, roofY + 1.08f, cz),
            tileId = MaterialTile.STEEL,
            tint = Vec3(0.32f, 0.36f, 0.40f),
            hp = 38f,
            supportIds = mutableListOf(unitId),
            targetTag = targetTag,
            walkable = false
        )
    }

    fun buildRooftopBillboard(
        world: StructuralWorld,
        cx: Float,
        roofY: Float,
        cz: Float,
        alongX: Boolean = true,
        supports: List<Int>,
        targetTag: String? = null
    ) {
        val posts = ArrayList<Int>(2)
        for (s in listOf(-1.25f, 1.25f)) {
            posts.add(
                world.addBlock(
                    poly = VoronoiPolyhedron.createBox(0.10f, 1.05f, 0.10f),
                    center = if (alongX) Vec3(cx + s, roofY + 1.05f, cz) else Vec3(cx, roofY + 1.05f, cz + s),
                    tileId = MaterialTile.STEEL,
                    tint = Vec3(0.34f, 0.36f, 0.40f),
                    hp = 80f,
                    supportIds = supports.toMutableList(),
                    targetTag = targetTag,
                    walkable = false
                )
            )
        }
        world.addBlock(
            poly = if (alongX) VoronoiPolyhedron.createBox(1.95f, 0.85f, 0.10f) else VoronoiPolyhedron.createBox(0.10f, 0.85f, 1.95f),
            center = Vec3(cx, roofY + 2.15f, cz),
            tileId = MaterialTile.AWNING_TRIM,
            tint = Vec3(0.24f, 0.64f, 0.94f),
            hp = 36f,
            supportIds = posts,
            targetTag = targetTag,
            walkable = false
        )
    }
}
