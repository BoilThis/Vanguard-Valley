package com.example

import com.example.engine.physics.ManifoldScratch
import com.example.engine.physics.StructuralWorld
import com.example.engine.physics.VoronoiPolyhedron
import com.example.engine.sim.SandboxSimulation
import com.example.engine.terrain.ChunkedTerrain
import com.example.model.MaterialTile
import com.example.model.Quat
import com.example.model.Vec3
import com.example.model.WorldConfig
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicsAndWorldTest {

    @Test
    fun chunkedTerrain_sculptsRiverBelowWaterAndLocalizesCraterDirtyChunks() {
        val terrain = ChunkedTerrain(WorldConfig(seed = 4207, riverWidth = 1.0f))

        // River centerline at z = 0 should be submerged below WATER_LEVEL
        val rx = terrain.riverCenterX(0f)
        val riverDepth = terrain.groundSurfaceY(rx, 0f)
        assertTrue("Expected river bed below water level, got $riverDepth", riverDepth < ChunkedTerrain.WATER_LEVEL)

        // Small town plateau & Army base ridge should be dry ground
        assertTrue(terrain.groundSurfaceY(-40f, 0f) > ChunkedTerrain.WATER_LEVEL + 1.0f)
        assertTrue(terrain.groundSurfaceY(42f, 0f) > ChunkedTerrain.WATER_LEVEL + 2.0f)

        val versionsBefore = terrain.chunkVersions.copyOf()
        // Crater deep in Army Base (east bank)
        terrain.applyCrater(cx = 45f, cz = 10f, radius = 4.0f, depth = 1.5f)
        val changedChunks = terrain.chunkVersions.indices.count { terrain.chunkVersions[it] != versionsBefore[it] }

        assertTrue("Crater should dirty at least 1 chunk", changedChunks >= 1)
        assertTrue("Crater should only dirty local chunks, got $changedChunks", changedChunks <= 4)

        // Blasting a riverbank lowers terrain below water level (flooding)
        val bankX = rx + terrain.riverHalfWidth() + 0.5f
        terrain.applyCrater(cx = bankX, cz = 0f, radius = 4.5f, depth = 2.8f)
        assertTrue(terrain.groundSurfaceY(bankX, 0f) < ChunkedTerrain.WATER_LEVEL)
    }

    @Test
    fun voronoiPolyhedron_halfSpaceClipAndShatterProduceValidShards() {
        val box = VoronoiPolyhedron.createBox(1.5f, 1.5f, 1.5f)
        val clipped = VoronoiPolyhedron.clipPolyhedronHalfSpace(box, Vec3(1f, 0.3f, 0f).normalized(), 0.2f)
        assertNotNull(clipped)
        assertTrue(clipped!!.faces.size >= 4)
        assertTrue(clipped.volume > 0f)

        val shards = VoronoiPolyhedron.shatter(box, Vec3(0.4f, 0.2f, 0f), maxPieces = 6, rng = Random(123))
        assertTrue("Expected multiple shards from shatter, got ${shards.size}", shards.size >= 2)
    }

    @Test
    fun structuralWorld_underminedFoundationAndLostSupportTriggerCollapse() {
        val terrain = ChunkedTerrain(WorldConfig(seed = 101))
        val world = StructuralWorld()

        val pillarX = -30f
        val pillarZ = 10f
        val gy = terrain.groundSurfaceY(pillarX, pillarZ)

        val pillarId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(0.5f, 1.2f, 0.5f),
            center = Vec3(pillarX, gy + 1.2f, pillarZ),
            tileId = MaterialTile.BRICK,
            hp = 80f,
            groundAnchored = true
        )
        val roofId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(1.5f, 0.4f, 1.5f),
            center = Vec3(pillarX, gy + 2.8f, pillarZ),
            tileId = MaterialTile.ROOF_SLATE,
            hp = 60f,
            supportIds = mutableListOf(pillarId)
        )

        assertEquals(2, world.blocks.size)

        // Blast a deep crater right under the pillar so it gets undermined
        terrain.applyCrater(pillarX, pillarZ, radius = 3.5f, depth = 2.2f)
        world.checkStabilityAndCollapse(terrain)

        assertFalseContains(world.blocks.keys, pillarId)
        assertFalseContains(world.blocks.keys, roofId)
        assertTrue("Detached pillar & roof should become dynamic shards", world.shards.size >= 2)
    }

    @Test
    fun simulation_populatesAllFourZonesWithGlassAndRunsStepsCleanly() {
        val sim = SandboxSimulation(WorldConfig(seed = 4207))
        assertTrue("Expected rich AAA valley blocks populated", sim.world.blocks.size > 200)
        assertTrue("Expected translucent glass window panes emitted separately", sim.latestSnapshot.sectorMeshes.any { it.glassVertices.isNotEmpty() })

        // Step 30 frames of simulation without error
        var hud = sim.step(0.016f)
        repeat(29) {
            hud = sim.step(0.016f)
        }
        assertTrue(hud.intactBlocksCount > 200)

        // Detonating a modest blast near a fuel silo triggers secondary blast events
        val siloFuelBlock = sim.world.blocks.values.firstOrNull { it.isFuelExplosive }
        assertNotNull("Expected fuel explosive blocks in base/gas station", siloFuelBlock)
        val (secondary, _) = sim.world.applyExplosion(
            pos = siloFuelBlock!!.center,
            radius = 2.5f,
            damage = 95f,
            impulseStrength = 14f,
            terrain = sim.terrain
        )
        assertTrue("Explosive tank/drum should trigger secondary chain blast", secondary.isNotEmpty())
    }

    @Test
    fun simulation_buildsDepthAnnotatedWaterMeshWithPositiveRiverDepth() {
        val sim = SandboxSimulation(WorldConfig(seed = 4207))
        val snap = sim.latestSnapshot
        assertTrue("Water mesh should be populated", snap.waterVerts.isNotEmpty())
        assertEquals("Water mesh stride must be 4 floats (x,y,z,depth)", 0, snap.waterVerts.size % 4)

        var maxDepth = Float.NEGATIVE_INFINITY
        var minDepth = Float.POSITIVE_INFINITY
        for (i in 0 until snap.waterVerts.size step 4) {
            val d = snap.waterVerts[i + 3]
            if (d > maxDepth) maxDepth = d
            if (d < minDepth) minDepth = d
        }
        assertTrue("Expected positive water depth in river channel, got $maxDepth", maxDepth > 1.0f)
        assertTrue("Expected negative water depth on dry hills, got $minDepth", minDepth < -0.5f)
    }

    @Test
    fun simulation_setLightingPresetUpdatesSnapshotImmediately() {
        val sim = SandboxSimulation(WorldConfig(seed = 4207))
        val initialBlocks = sim.world.blocks.size
        sim.setLightingPreset(com.example.model.LightingPreset.MORNING)
        assertEquals(com.example.model.LightingPreset.MORNING, sim.latestSnapshot.lightingPreset)
        sim.setLightingPreset(com.example.model.LightingPreset.GOLDEN_HOUR)
        assertEquals(com.example.model.LightingPreset.GOLDEN_HOUR, sim.latestSnapshot.lightingPreset)
        assertEquals(initialBlocks, sim.world.blocks.size)
    }

    @Test
    fun materialProfiles_carbineBreaksGlassAndPlasticWithoutCrateringWhileSteelRicochets() {
        val terrain = ChunkedTerrain(WorldConfig(seed = 101))
        val world = StructuralWorld()
        val x = -28f
        val z = 0f
        val gy = terrain.groundSurfaceY(x, z)

        val glassId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(0.8f, 0.8f, 0.1f),
            center = Vec3(x, gy + 1.0f, z),
            tileId = MaterialTile.GLASS,
            hp = 14f,
            groundAnchored = true
        )
        val plasticId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(0.8f, 0.8f, 0.14f),
            center = Vec3(x + 3f, gy + 1.0f, z),
            tileId = MaterialTile.PLASTIC,
            hp = 34f,
            groundAnchored = true
        )
        val steelId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(0.3f, 1.5f, 0.3f),
            center = Vec3(x + 6f, gy + 1.5f, z),
            tileId = MaterialTile.STEEL,
            hp = 290f,
            groundAnchored = true,
            isCoreSupport = true
        )

        // Carbine breaks Glass in a single round
        val glassRes = world.applyBulletImpact(
            origin = Vec3(x, gy + 1.0f, z + 4f),
            dir = Vec3(0f, 0f, -1f),
            isHeavyAp = false,
            terrain = terrain
        )
        assertTrue(glassRes.hitBlock)
        assertTrue("Glass pane should break from single Carbine shot", glassRes.blockBroken)
        assertEquals(com.example.model.MaterialSoundKind.GLASS, glassRes.soundKind)
        assertFalseContains(world.blocks.keys, glassId)

        // Carbine breaks Plastic in two rapid rounds (26 * 1.30 = 33.8 per hit vs 34 HP)
        val plastic1 = world.applyBulletImpact(Vec3(x + 3f, gy + 1.0f, z + 4f), Vec3(0f, 0f, -1f), false, terrain)
        assertTrue(plastic1.hitBlock && !plastic1.blockBroken)
        val plastic2 = world.applyBulletImpact(Vec3(x + 3f, gy + 1.0f, z + 4f), Vec3(0f, 0f, -1f), false, terrain)
        assertTrue("Plastic should shatter by second Carbine round", plastic2.blockBroken)
        assertFalseContains(world.blocks.keys, plasticId)

        // Carbine ricochets off Steel Core Column
        val steelHpBefore = world.blocks[steelId]!!.hp
        val steelCarbine = world.applyBulletImpact(Vec3(x + 6f, gy + 1.5f, z + 4f), Vec3(0f, 0f, -1f), false, terrain)
        assertTrue(steelCarbine.ricochet)
        assertTrue(!steelCarbine.blockBroken)
        assertTrue("Steel column must resist Carbine rounds", world.blocks[steelId]!!.hp > steelHpBefore - 5f)

        // .50 Cal AP penetrates Steel strongly
        val steelAp = world.applyBulletImpact(Vec3(x + 6f, gy + 1.5f, z + 4f), Vec3(0f, 0f, -1f), true, terrain)
        assertTrue(!steelAp.ricochet)
        assertTrue(".50 Cal AP must deal major damage to steel", world.blocks[steelId]!!.hp < steelHpBefore - 50f)

        // Ballistic shot into bare terrain causes zero cratering
        val groundX = -20f
        val groundZ = 8f
        val beforeY = terrain.groundSurfaceY(groundX, groundZ)
        val groundRes = world.applyBulletImpact(Vec3(groundX, beforeY + 5f, groundZ), Vec3(0f, -1f, 0f), true, terrain)
        assertTrue(!groundRes.hitBlock)
        assertEquals(beforeY, terrain.groundSurfaceY(groundX, groundZ), 1e-4f)
    }

    @Test
    fun voronoiPolyhedron_fourCutShatterProducesTenPlusShards() {
        val box = VoronoiPolyhedron.createBox(1.8f, 1.8f, 1.8f)
        val shards = VoronoiPolyhedron.shatter(box, Vec3(0.3f, 0.2f, -0.1f), maxPieces = 12, rng = Random(42))
        assertTrue("Expected >= 10 high-density Voronoi shards, got ${shards.size}", shards.size >= 10)
        assertTrue(shards.all { it.second.volume > 0f && it.second.faces.size >= 4 })
    }

    @Test
    fun multiStoryBuildings_preserveSteelSkeletonAndSupportUpperFloorClimbing() {
        val terrain = ChunkedTerrain(WorldConfig(seed = 4207))
        val world = StructuralWorld()
        com.example.engine.physics.ZoneBuilder.buildBrickHotel3F(world, terrain, cx = -38f, cz = -13.5f)

        val hotelBlocks = world.blocks.values.filter { it.targetTag == "HOTEL" }
        val steelCols = hotelBlocks.filter { it.isCoreSupport && it.tileId == MaterialTile.STEEL }
        val stairs = hotelBlocks.filter { it.tileId == MaterialTile.STAIR_TREAD }
        val glassPanes = hotelBlocks.filter { it.tileId == MaterialTile.GLASS }

        assertTrue("Hotel must have steel core columns", steelCols.size >= 12)
        assertTrue("Hotel must have interior stairs across all stories", stairs.size >= 3)
        assertTrue("Hotel must have glazed windows", glassPanes.isNotEmpty())

        // Strip all outer glass and brick cladding via ID removal, keeping steel core & slabs
        val toStrip = hotelBlocks.filter { !it.isCoreSupport && it.tileId == MaterialTile.GLASS }.map { it.id }
        for (id in toStrip) world.blocks.remove(id)
        world.checkStabilityAndCollapse(terrain)

        val remainingSteel = world.blocks.values.count { it.targetTag == "HOTEL" && it.isCoreSupport && it.tileId == MaterialTile.STEEL }
        assertEquals("Steel core columns must stay intact after outer windows break", steelCols.size, remainingSteel)

        // Probe walkable support on a Floor 2+ stair tread / slab
        val highStair = stairs.maxByOrNull { it.center.y }!!
        val (_, standY) = world.resolveCharacterSupport(
            pos = Vec3(highStair.center.x, highStair.center.y, highStair.center.z),
            radius = 0.42f,
            height = 1.75f
        )
        assertNotNull("Upper story stair tread must support player footing", standY)
        assertTrue("Upper stair footing must be well above ground", standY!! > terrain.groundSurfaceY(-38f, -13.5f) + 3.0f)
    }

    @Test
    fun buildBrush_spawnsAllSixWallMaterialsAndOpsEvaluatesMission() {
        val sim = SandboxSimulation(WorldConfig(seed = 4207))
        val hit = Vec3(-20f, sim.terrain.groundSurfaceY(-20f, 8f), 8f)

        for (mat in com.example.model.BuildMaterial.entries) {
            val countBefore = sim.world.blocks.size
            com.example.engine.physics.ZoneBuilder.spawnBrushStructure(
                item = com.example.model.BrushItem.WALL_PANEL,
                buildMaterial = mat,
                world = sim.world,
                terrain = sim.terrain,
                hit = hit,
                yaw = 0.4f
            )
            assertEquals("Wall panel for ${mat.label} should spawn 5-piece modular frame+panels", countBefore + 5, sim.world.blocks.size)
            val spawned = sim.world.blocks.values.last()
            assertEquals(mat.tileId, spawned.tileId)
            assertEquals(mat.defaultHp, spawned.hp, 1e-3f)
        }

        // Start Demolition Ops Contract and verify score/stars progression
        val hotelMission = com.example.engine.sim.DemolitionOpsCatalog.missions.first { it.targetTag == "HOTEL" }
        sim.startMission(hotelMission)
        val startHud = sim.step(0.016f)
        assertEquals(com.example.model.GameMode.DEMOLITION_OPS, startHud.gameMode)
        assertEquals(hotelMission.id, startHud.activeMission?.mission?.id)

        // Destroy 80% of HOTEL blocks directly to verify mission completion & star rating
        val hotelIds = sim.world.blocks.values.filter { it.targetTag == "HOTEL" }.map { it.id }
        val killCount = (hotelIds.size * 0.82f).toInt()
        for (i in 0 until killCount) {
            val b = sim.world.blocks[hotelIds[i]] ?: continue
            sim.world.applyExplosionDetailed(b.center, radius = 3.2f, damage = 500f, impulseStrength = 10f, terrain = sim.terrain)
        }
        val endHud = sim.step(0.016f)
        val missionState = endHud.activeMission!!
        assertTrue("Expected >= ${hotelMission.requiredDestructionPct}% destruction, got ${missionState.destructionPct}%", missionState.destructionPct >= hotelMission.requiredDestructionPct)
        assertTrue("Mission should mark completed", missionState.isCompleted)
        assertTrue("Completed mission should award >= 1 star", missionState.starsEarned >= 1)
    }

    @Test
    fun structuralPhysics_intactValleyHasZeroFloatingOrSelfCollapsingBlocks() {
        val sim = SandboxSimulation(WorldConfig(seed = 4207))
        val beforeIds = sim.world.blocks.keys.toSet()
        val beforeSnapshot = sim.world.blocks.mapValues { (_, b) ->
            "${b.targetTag ?: "UNTAGGED"} tile=${b.tileId} anchored=${b.groundAnchored} cantilever=${b.checkCantilever} sups=${b.supportIds.size} grp=${b.supportGroups.size} pos=${b.center} he=${b.poly.halfExtents}"
        }
        assertTrue("Expected rich valley blocks populated", beforeIds.size > 300)

        sim.world.checkStabilityAndCollapse(sim.terrain)
        val dropped = beforeIds - sim.world.blocks.keys
        val droppedMsg = dropped.take(12).joinToString("\n") { id -> "  id=$id -> ${beforeSnapshot[id]}" }
        assertEquals("Intact valley must not self-collapse any block:\n$droppedMsg", 0, sim.world.collapsedCount)
        assertEquals(beforeIds.size, sim.world.blocks.size)
    }

    @Test
    fun structuralPhysics_destroyingLocalSlabsDropsColumnsTankStairsAndCantilevers() {
        val terrain = ChunkedTerrain(WorldConfig(seed = 4207))
        val world = StructuralWorld()
        com.example.engine.physics.ZoneBuilder.buildBrickHotel3F(world, terrain, cx = -38f, cz = -13.5f)
        com.example.engine.physics.ZoneBuilder.buildGasStation(world, terrain, cx = -39.5f, cz = 12.2f)
        world.rebuildPhysicalSupports()

        // 1. Rooftop Plastic Water Tank must collapse immediately when the roof slab directly below it breaks
        val waterTank = world.blocks.values
            .filter { it.targetTag == "HOTEL" && it.tileId == MaterialTile.PLASTIC }
            .maxByOrNull { it.center.y }!!
        val tankSupports = waterTank.supportIds.toList()
        assertTrue("Water tank should bind only to local roof slab(s), got ${tankSupports.size}", tankSupports.size <= 2)
        for (sid in tankSupports) world.blocks.remove(sid)
        world.checkStabilityAndCollapse(terrain)
        assertFalseContains(world.blocks.keys, waterTank.id)

        // 2. Story 3 Steel Core Column must collapse when the slab bay directly under it is destroyed
        val upperSteelCol = world.blocks.values
            .filter { it.targetTag == "HOTEL" && it.isCoreSupport && it.tileId == MaterialTile.STEEL }
            .maxByOrNull { it.center.y }!!
        for (sid in upperSteelCol.supportIds.toList()) world.blocks.remove(sid)
        world.checkStabilityAndCollapse(terrain)
        assertFalseContains(world.blocks.keys, upperSteelCol.id)

        // 3. Staircase whose upper landing was destroyed must collapse rather than point into thin air
        val hotelStair = world.blocks.values.first { it.targetTag == "HOTEL" && it.tileId == MaterialTile.STAIR_TREAD }
        val upperLandingIds = hotelStair.supportGroups.last().toList()
        for (sid in upperLandingIds) world.blocks.remove(sid)
        world.checkStabilityAndCollapse(terrain)
        assertFalseContains(world.blocks.keys, hotelStair.id)

        // 4. Cantilever gravity: removing 2 columns on one side of Gas Station canopy causes canopy to collapse
        val canopy = world.blocks.values.first {
            it.tileId == MaterialTile.PLASTIC && it.poly.halfExtents.x > 3.0f && it.center.z > 5f
        }
        val canopyCols = canopy.supportIds.mapNotNull { world.blocks[it] }.sortedBy { it.center.x }
        assertEquals(4, canopyCols.size)
        // Destroy the 2 left-side columns
        world.blocks.remove(canopyCols[0].id)
        world.blocks.remove(canopyCols[1].id)
        world.checkStabilityAndCollapse(terrain)
        assertFalseContains(world.blocks.keys, canopy.id)
    }

    @Test
    fun structuralPhysics_fallingHeavyShardCrushesFragileBlockBelow() {
        val terrain = ChunkedTerrain(WorldConfig(seed = 101))
        val world = StructuralWorld()
        val x = -22f
        val z = 4f
        val gy = terrain.groundSurfaceY(x, z)

        val glassId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(0.9f, 0.5f, 0.9f),
            center = Vec3(x, gy + 0.5f, z),
            tileId = MaterialTile.GLASS,
            hp = 14f,
            groundAnchored = true
        )

        world.shards.add(
            com.example.engine.physics.DynamicShard(
                id = 9999,
                poly = VoronoiPolyhedron.createBox(0.8f, 0.6f, 0.8f),
                center = Vec3(x, gy + 3.2f, z),
                orientation = com.example.model.Quat.IDENTITY,
                uvOrigin = Vec3.ZERO,
                tileId = MaterialTile.CONCRETE,
                tint = Vec3(0.8f, 0.8f, 0.8f),
                vel = Vec3(0f, -6.0f, 0f),
                angVel = Vec3.ZERO
            )
        )

        repeat(25) {
            world.stepShards(0.016f, terrain)
        }
        assertFalseContains(world.blocks.keys, glassId)
    }

    @Test
    fun expandedValley_buildsSixStoryHotelFiveStoryHqWithBalconiesAndZeroCollapse() {
        val sim = SandboxSimulation(WorldConfig(seed = 4207))
        val hotelFloors = sim.world.blocks.values
            .filter { it.targetTag == "HOTEL" && it.tileId == MaterialTile.STAIR_TREAD }
            .map { (it.center.y * 2f).toInt() }
            .distinct()
        assertTrue("Expected >= 6 stair elevations in 6-Story Hotel, got ${hotelFloors.size}", hotelFloors.size >= 6)

        val hqFloors = sim.world.blocks.values
            .filter { it.targetTag == "HQ" && it.tileId == MaterialTile.STAIR_TREAD }
            .map { (it.center.y * 2f).toInt() }
            .distinct()
        assertTrue("Expected >= 5 stair elevations in 5-Story Command HQ, got ${hqFloors.size}", hqFloors.size >= 5)

        val maxHotelY = sim.world.blocks.values.filter { it.targetTag == "HOTEL" }.maxOf { it.center.y }
        assertTrue("6-Story Hotel must reach high above town plateau, maxY=$maxHotelY", maxHotelY > 21.0f)

        sim.world.checkStabilityAndCollapse(sim.terrain)
        assertEquals("Expanded valley must have 0 self-collapsing blocks", 0, sim.world.collapsedCount)
    }

    @Test
    fun materialFracture_woodPlanksAndGlassTrianglesProduceDistinctShardGeometries() {
        val plankBox = VoronoiPolyhedron.createBox(1.2f, 1.2f, 0.2f)
        val woodShards = VoronoiPolyhedron.shatter(
            plankBox, Vec3(0.2f, 0.1f, 0f), maxPieces = 10,
            rng = Random(99), fractureStyle = com.example.model.FractureStyle.SPLINTER_PLANK
        )
        assertTrue("Expected multiple wood plank splinters", woodShards.size >= 6)
        val avgWoodAspect = woodShards.map { (_, p) ->
            val h = p.halfExtents
            maxOf(h.x, h.y, h.z) / maxOf(0.02f, minOf(h.x, h.y, h.z))
        }.average()
        assertTrue("Wood splinters should stay elongated, avg aspect=$avgWoodAspect", avgWoodAspect > 2.8)

        val glassPane = VoronoiPolyhedron.createBox(1.1f, 0.9f, 0.05f)
        val glassShards = VoronoiPolyhedron.shatter(
            glassPane, Vec3(0f, 0f, 0f), maxPieces = 10,
            rng = Random(77), fractureStyle = com.example.model.FractureStyle.SHATTER_TRIANGLE
        )
        assertTrue("Expected flat glass shard triangles", glassShards.size >= 6)
        val allFlat = glassShards.all { (_, p) -> p.halfExtents.z <= 0.06f }
        assertTrue("Glass shards should remain flat thin plates along pane thickness", allFlat)
    }

    @Test
    fun localizedBreach_heavyRifleCarvesHoleLeavingSurvivingWallFraming() {
        val terrain = ChunkedTerrain(WorldConfig(seed = 101))
        val world = StructuralWorld()
        val x = -28f
        val z = 0f
        val gy = terrain.groundSurfaceY(x, z)

        val wallId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(2.2f, 1.6f, 0.24f),
            center = Vec3(x, gy + 1.6f, z),
            tileId = MaterialTile.BRICK,
            hp = 120f,
            groundAnchored = true
        )

        // Two .50 Cal AP hits into the center of a broad brick wall carve a local breach opening
        val hit1 = world.applyBulletImpact(Vec3(x, gy + 1.6f, z + 4f), Vec3(0f, 0f, -1f), isHeavyAp = true, terrain = terrain)
        assertTrue("First AP hit should strike brick wall", hit1.hitBlock && !hit1.blockBroken)
        val res = world.applyBulletImpact(Vec3(x, gy + 1.6f, z + 4f), Vec3(0f, 0f, -1f), isHeavyAp = true, terrain = terrain)

        assertTrue("Breaching shot should break target wall segment", res.blockBroken)
        assertFalseContains(world.blocks.keys, wallId)
        assertTrue("Breached broad wall should preserve framing sub-blocks (>= 2), got ${world.blocks.size}", world.blocks.size >= 2)
        assertTrue("Carved breach should spawn debris shards", world.shards.isNotEmpty())
        val throughShot = world.applyBulletImpact(Vec3(x, gy + 1.6f, z + 4f), Vec3(0f, 0f, -1f), isHeavyAp = true, terrain = terrain)
        assertTrue("Follow-up bullet should pass cleanly through carved breach hole", !throughShot.hitBlock)
        world.checkStabilityAndCollapse(terrain)
        assertTrue("Surviving grounded framing sub-blocks should stay standing", world.blocks.size >= 2)
    }

    @Test
    fun cinematicExplosions_spawnProlongedFireSitesAndContinuousParticles() {
        val sim = SandboxSimulation(WorldConfig(seed = 4207))
        sim.cameraMode = com.example.model.CameraMode.FIRST_PERSON
        sim.interactionTool = com.example.model.InteractionTool.WEAPON
        sim.selectedWeapon = com.example.model.WeaponType.HEAVY_RIFLE

        val drum = sim.world.blocks.values
            .filter { it.isFuelExplosive }
            .minByOrNull { it.center.y }!!
        sim.playerPos = drum.center + Vec3(0f, -1.68f, -2.4f)
        sim.yaw = (Math.PI * 0.5).toFloat()
        sim.pitch = 0f
        sim.firePrimaryAction()
        repeat(8) { sim.step(0.03f) }

        assertTrue("Fuel blast should spawn prolonged active fire site(s)", sim.activeFireSiteCount >= 1)
        assertTrue("Explosion should populate volumetric particle quads", sim.latestSnapshot.particleVerts.isNotEmpty())

        // Step 3 seconds forward and confirm sustained particle emission continues from active fire
        repeat(100) { sim.step(0.03f) }
        assertTrue("Active fire site should keep emitting smoke/flame particles after 3s", sim.activeParticleCount > 0)
    }

    @Test
    fun debrisPhysics_tallSixStoryCollapseNeverFreezesAirborneAndReachesGround() {
        val sim = SandboxSimulation(WorldConfig(seed = 4207))
        val hotelFloor = sim.world.blocks.values
            .filter { it.targetTag == "HOTEL" }
            .minByOrNull { it.center.y }!!

        // Blast out lower hotel core supports to trigger massive multi-story cascade (250+ shards)
        sim.world.applyExplosionDetailed(
            hotelFloor.center,
            radius = 9.5f,
            damage = 420f,
            impulseStrength = 22f,
            terrain = sim.terrain
        )

        repeat(180) { sim.step(0.03f) }

        assertTrue("Expected shards generated from 6-story hotel collapse", sim.world.shards.isNotEmpty())
        val floatingSlept = sim.world.shards.filter { shard ->
            if (!shard.sleeping) return@filter false
            val gy = sim.terrain.groundSurfaceY(shard.center.x, shard.center.z)
            val supId = shard.supportedByBlockId
            val hasIntactSup = supId != null && sim.world.blocks.containsKey(supId)
            !hasIntactSup && shard.center.y > gy + shard.poly.halfExtents.len() + 0.85f
        }
        assertEquals(
            "Zero sleeping shards may float mid-air without ground or intact slab support",
            0,
            floatingSlept.size
        )
    }

    @Test
    fun debrisPhysics_sleepingRubbleWakesWhenSupportingFloorDestroyed() {
        val terrain = ChunkedTerrain(WorldConfig(seed = 101))
        val world = StructuralWorld()
        val x = -28f
        val z = 0f
        val gy = terrain.groundSurfaceY(x, z)

        val pillar1 = world.addBlock(
            poly = VoronoiPolyhedron.createBox(0.25f, 1.5f, 0.25f),
            center = Vec3(x - 1.5f, gy + 1.5f, z),
            tileId = MaterialTile.STEEL, hp = 200f, groundAnchored = true
        )
        val pillar2 = world.addBlock(
            poly = VoronoiPolyhedron.createBox(0.25f, 1.5f, 0.25f),
            center = Vec3(x + 1.5f, gy + 1.5f, z),
            tileId = MaterialTile.STEEL, hp = 200f, groundAnchored = true
        )
        val slabId = world.addBlock(
            poly = VoronoiPolyhedron.createBox(2.0f, 0.2f, 2.0f),
            center = Vec3(x, gy + 3.2f, z),
            tileId = MaterialTile.CONCRETE, hp = 50f,
            supportIds = mutableListOf(pillar1, pillar2)
        )

        val rubblePoly = VoronoiPolyhedron.createBox(0.35f, 0.20f, 0.35f)
        world.shards.add(
            com.example.engine.physics.DynamicShard(
                id = 99001,
                poly = rubblePoly,
                center = Vec3(x, gy + 3.65f, z),
                orientation = com.example.model.Quat.IDENTITY,
                uvOrigin = Vec3.ZERO,
                tileId = MaterialTile.BRICK,
                tint = Vec3(1f, 1f, 1f),
                vel = Vec3.ZERO,
                angVel = Vec3.ZERO
            )
        )

        // Let shard land on the 2nd-floor slab and fall asleep
        repeat(60) { world.stepShards(0.02f, terrain) }
        val shard = world.shards.first()
        assertTrue("Shard should sleep on intact slab", shard.sleeping)
        assertEquals("Shard should record supporting slab id", slabId, shard.supportedByBlockId)

        // Destroy only the slab underneath from just below
        world.applyExplosionDetailed(Vec3(x, gy + 2.9f, z), radius = 1.6f, damage = 200f, impulseStrength = 2f, terrain = terrain)
        assertFalseContains(world.blocks.keys, slabId)
        assertTrue("Removing supporting slab must immediately wake sleeping rubble", !shard.sleeping)

        // Step physics and verify rubble drops down to ground / fallen slab rubble layer
        repeat(90) { world.stepShards(0.02f, terrain) }
        assertTrue("Woken rubble should fall down to ground level (y=${shard.center.y}, gy=$gy)", shard.center.y < gy + 0.85f)
    }

    @Test
    fun debrisPhysics_rubbleCollidesAndWakesSleepingRubble() {
        val terrain = ChunkedTerrain(WorldConfig(seed = 101))
        val world = StructuralWorld()
        val x = -28f
        val z = 0f
        val gy = terrain.groundSurfaceY(x, z)

        val poly = VoronoiPolyhedron.createBox(0.45f, 0.35f, 0.45f)
        val sleeper = com.example.engine.physics.DynamicShard(
            id = 99002,
            poly = poly,
            center = Vec3(x, gy + 0.35f, z),
            orientation = com.example.model.Quat.IDENTITY,
            uvOrigin = Vec3.ZERO,
            tileId = MaterialTile.BRICK,
            tint = Vec3(1f, 1f, 1f),
            vel = Vec3.ZERO,
            angVel = Vec3.ZERO,
            sleeping = true,
            isGroundedContact = true
        )
        val striker = com.example.engine.physics.DynamicShard(
            id = 99003,
            poly = poly,
            center = Vec3(x - 1.2f, gy + 0.42f, z),
            orientation = com.example.model.Quat.IDENTITY,
            uvOrigin = Vec3.ZERO,
            tileId = MaterialTile.STEEL,
            tint = Vec3(0.7f, 0.7f, 0.7f),
            vel = Vec3(8.5f, 0f, 0f),
            angVel = Vec3.ZERO,
            massEstimate = 4.0f
        )
        world.shards.add(sleeper)
        world.shards.add(striker)

        repeat(20) { world.stepShards(0.02f, terrain) }
        assertTrue("Incoming heavy shard must wake stationary rubble on collision", !sleeper.sleeping || sleeper.center.x > x + 0.15f)
        assertTrue("Colliding rubble must transfer forward momentum", sleeper.center.x > x + 0.15f)
    }

    @Test
    fun smokeOptimization_particleCapAndFireSiteMergeRespectOverdrawBudget() {
        val sim = SandboxSimulation(WorldConfig(seed = 4207))
        sim.cameraMode = com.example.model.CameraMode.FIRST_PERSON
        val anchor = sim.world.blocks.values.first { it.isFuelExplosive }.center

        // Trigger 7 large explosions at close & spread intervals via C4 simulation
        repeat(7) { idx ->
            val offset = if (idx < 4) Vec3(idx * 0.5f, 0f, 0f) else Vec3(idx * 8.0f, 0f, 0f)
            val p = anchor + offset
            sim.selectedWeapon = com.example.model.WeaponType.RPG
            sim.playerPos = p + Vec3(0f, 0f, -2.5f)
            sim.yaw = (Math.PI * 0.5).toFloat()
            sim.pitch = 0f
            sim.firePrimaryAction()
            repeat(6) { sim.step(0.03f) }
        }

        assertTrue("Active fire sites must merge & stay <= 5 to avoid overdraw", sim.activeFireSiteCount <= 5)
        assertTrue("Active particle pool must stay within <= 180 GPU budget", sim.activeParticleCount <= 180)
    }

    @Test
    fun debrisPhysics_tiltedMasonryShardTipsOntoFlatFaceAndStopsSpinning() {
        val terrain = ChunkedTerrain(WorldConfig(seed = 101))
        val world = StructuralWorld()
        val x = -28f
        val z = 0f
        val gy = terrain.groundSurfaceY(x, z)

        // Tall narrow brick shard slightly tilted from upright — must tip flat onto its broad face and sleep
        val tallBrick = VoronoiPolyhedron.createBox(0.22f, 0.95f, 0.22f)
        val slightTilt = com.example.model.Quat.fromAxisAngle(Vec3(0f, 0f, 1f), 0.14f)
        val shard = com.example.engine.physics.DynamicShard(
            id = 88001,
            poly = tallBrick,
            center = Vec3(x, gy + 1.05f, z),
            orientation = slightTilt,
            uvOrigin = Vec3.ZERO,
            tileId = MaterialTile.BRICK,
            tint = Vec3(1f, 1f, 1f),
            vel = Vec3.ZERO,
            angVel = Vec3(0f, 3.5f, 0f)
        )
        world.shards.add(shard)

        repeat(160) { world.stepShards(0.016f, terrain) }

        val upNow = shard.orientation.rotate(Vec3.UP)
        assertTrue(
            "Tall brick shard must sleep (sleep=${shard.sleeping}, y=${shard.center.y - gy}, vel=${shard.vel}, angVel=${shard.angVel}, grounded=${shard.isGroundedContact}, settle=${shard.settleTimer}, upY=${upNow.y})",
            shard.sleeping
        )
        assertEquals(0f, shard.angVel.len(), 1e-4f)
        // Broad side resting flat on ground means local Y axis tipped close to horizontal (|rotatedY.y| < 0.35)
        val rotatedUp = shard.orientation.rotate(Vec3.UP)
        assertTrue(
            "Tall masonry shard should tip flat rather than balance upright (rotatedUp.y=${rotatedUp.y}, centerY=${shard.center.y})",
            kotlin.math.abs(rotatedUp.y) < 0.35f && shard.center.y < gy + 0.55f
        )
    }

    @Test
    fun debrisInteractions_bulletsC4WreckingBallInteractWithRubbleAndStackedWake() {
        val sim = SandboxSimulation(WorldConfig(seed = 4207))
        val x = -26f
        val z = 2f
        val gy = sim.terrain.groundSurfaceY(x, z)

        // Stack a sleeping top shard on top of a sleeping base shard
        val box = VoronoiPolyhedron.createBox(0.55f, 0.25f, 0.55f)
        val base = com.example.engine.physics.DynamicShard(
            id = 88101,
            poly = box,
            center = Vec3(x, gy + 0.25f, z),
            orientation = com.example.model.Quat.IDENTITY,
            uvOrigin = Vec3.ZERO,
            tileId = MaterialTile.CONCRETE,
            tint = Vec3(1f, 1f, 1f),
            vel = Vec3.ZERO,
            angVel = Vec3.ZERO,
            sleeping = true,
            isGroundedContact = true
        )
        val top = com.example.engine.physics.DynamicShard(
            id = 88102,
            poly = VoronoiPolyhedron.createBox(0.45f, 0.22f, 0.45f),
            center = Vec3(x, gy + 0.72f, z),
            orientation = com.example.model.Quat.IDENTITY,
            uvOrigin = Vec3.ZERO,
            tileId = MaterialTile.BRICK,
            tint = Vec3(1f, 1f, 1f),
            vel = Vec3.ZERO,
            angVel = Vec3.ZERO,
            sleeping = true,
            isGroundedContact = true,
            supportedByShardId = 88101
        )
        sim.world.shards.add(base)
        sim.world.shards.add(top)

        // 1. Bullet shot strikes sleeping base shard -> kicks & wakes BOTH base and stacked top shard
        val bulletRes = sim.world.applyBulletImpact(
            origin = Vec3(x, gy + 0.25f, z + 3f),
            dir = Vec3(0f, 0f, -1f),
            isHeavyAp = true,
            terrain = sim.terrain
        )
        assertTrue("Bullet should detect and hit dynamic shard", bulletRes.hitShard)
        assertTrue("Bullet should wake base shard and impart momentum", !base.sleeping && base.vel.len() > 0.5f)
        assertTrue("Waking base shard must cascade wake sleeping stacked top shard", !top.sleeping)

        // 2. Wrecking ball ricochets cleanly off an intact high-HP steel wall instead of tunneling through
        val wallId = sim.world.addBlock(
            poly = VoronoiPolyhedron.createBox(2.5f, 2.0f, 0.35f),
            center = Vec3(x + 8f, gy + 2.0f, z),
            tileId = MaterialTile.STEEL,
            hp = 500f,
            groundAnchored = true
        )
        val kinetic = sim.world.applyKineticImpactDetailed(
            pos = Vec3(x + 8f, gy + 2.0f, z + 1.0f),
            vel = Vec3(0f, 0f, -32f),
            radius = 0.9f,
            terrain = sim.terrain
        )
        assertTrue("Wrecking ball must register hit against surviving steel wall", kinetic.hitAny)
        assertTrue("Wrecking ball must ricochet off high-HP intact steel wall", kinetic.bounced && kinetic.bounceNormal.z > 0.7f)
        assertTrue("Surviving steel wall should remain in world", sim.world.blocks.containsKey(wallId))
    }

    @Test
    fun visualAndMaterialUpgrades_strongerMaterialsDynamicLightsAndAnimated3PAvatar() {
        val sim = SandboxSimulation(WorldConfig(seed = 4207))
        // Material profiles toughened across Brick/Wood/Metal
        assertTrue(com.example.model.MaterialProfiles.forTile(MaterialTile.BRICK).carbineMult < 0.38f)
        assertTrue(com.example.model.MaterialProfiles.forTile(MaterialTile.TIMBER).carbineMult < 0.80f)
        assertTrue(com.example.model.MaterialProfiles.forTile(MaterialTile.CORRUGATED_METAL).carbineMult < 0.35f)

        // Dynamic point lights emit when an explosion triggers
        val fuel = sim.world.blocks.values.first { it.isFuelExplosive }
        sim.cameraMode = com.example.model.CameraMode.THIRD_PERSON
        sim.selectedWeapon = com.example.model.WeaponType.RPG
        sim.playerPos = fuel.center + Vec3(0f, -1.6f, -3.5f)
        sim.yaw = (Math.PI * 0.5).toFloat()
        sim.pitch = 0f
        sim.firePrimaryAction()
        repeat(8) { sim.step(0.025f) }
        assertTrue("Explosions should emit dynamic point lights to RenderSnapshot", sim.latestSnapshot.pointLights.isNotEmpty())

        // 3P articulated avatar & held weapon animates while moving
        val idleDynamicCount = sim.latestSnapshot.dynamicVerts.size
        sim.moveInputY = 1f
        sim.step(0.03f)
        val movingSnapshot = sim.latestSnapshot
        assertTrue("3P articulated character + weapon must emit dynamic geometry", idleDynamicCount > 0 && movingSnapshot.dynamicVerts.isNotEmpty())
    }

    @Test
    fun optimization_sleepingRubbleCacheAndBroadPhaseGridWorkCorrectly() {
        val sim = SandboxSimulation(WorldConfig(seed = 4207))
        sim.cameraMode = com.example.model.CameraMode.FIRST_PERSON
        val x = -28f
        val z = 0f
        val gy = sim.terrain.groundSurfaceY(x, z)

        // Build an elevated platform tested via the broad-phase spatial block hash grid
        val floorId = sim.world.addBlock(
            poly = VoronoiPolyhedron.createBox(2.2f, 0.2f, 2.2f),
            center = Vec3(x, gy + 2.4f, z),
            tileId = MaterialTile.CONCRETE,
            hp = 140f,
            groundAnchored = true
        )
        val shardPoly = VoronoiPolyhedron.createBox(0.35f, 0.22f, 0.35f)
        val shard = com.example.engine.physics.DynamicShard(
            id = 99501,
            poly = shardPoly,
            center = Vec3(x, gy + 3.0f, z),
            orientation = com.example.model.Quat.IDENTITY,
            uvOrigin = Vec3.ZERO,
            tileId = MaterialTile.BRICK,
            tint = Vec3(1f, 1f, 1f),
            vel = Vec3.ZERO,
            angVel = Vec3.ZERO
        )
        sim.world.shards.add(shard)

        // Initially awake: sleeping rubble cache has 0 verts
        sim.step(0.016f)
        val verBeforeSleep = sim.latestSnapshot.sleepingRubbleVersion
        assertEquals(0, sim.latestSnapshot.sleepingRubbleVerts.size)

        // Step until the shard lands on the spatial-grid floor block and goes to sleep
        repeat(70) { sim.step(0.02f) }
        assertTrue("Shard should settle and sleep on spatial-grid slab", shard.sleeping)
        assertEquals("Shard should be supported by broad-phase block", floorId, shard.supportedByBlockId)

        val sleptSnap1 = sim.latestSnapshot
        assertTrue("sleepingRubbleVersion should bump when shard sleeps", sleptSnap1.sleepingRubbleVersion > verBeforeSleep)
        assertTrue("Sleeping rubble should populate cached sleepingRubbleVerts", sleptSnap1.sleepingRubbleVerts.isNotEmpty())

        // Subsequent idle step must reuse the exact cached FloatArray reference without re-tessellating
        sim.step(0.016f)
        val sleptSnap2 = sim.latestSnapshot
        assertEquals(sleptSnap1.sleepingRubbleVersion, sleptSnap2.sleepingRubbleVersion)
        assertTrue("Cached sleepingRubbleVerts array must be reused across idle frames", sleptSnap1.sleepingRubbleVerts === sleptSnap2.sleepingRubbleVerts)

        // Wake the shard via bullet impact -> must bump sleepingRubbleVersion and empty sleeping cache
        val hit = sim.world.applyBulletImpact(
            origin = Vec3(x, shard.center.y, z + 3f),
            dir = Vec3(0f, 0f, -1f),
            isHeavyAp = false,
            terrain = sim.terrain
        )
        assertTrue("Bullet impact must wake sleeping shard", hit.hitShard && !shard.sleeping)
        sim.step(0.016f)
        val wokenSnap = sim.latestSnapshot
        assertTrue("Waking sleeping shard must bump sleepingRubbleVersion", wokenSnap.sleepingRubbleVersion > sleptSnap2.sleepingRubbleVersion)
        assertEquals("Woken shard should leave sleepingRubbleVerts empty until re-settled", 0, wokenSnap.sleepingRubbleVerts.size)
    }

    @Test
    fun collapseFracture_oversizedSlabsAndBeamsBreakApartAndDiagonalBeamsTipFlat() {
        val terrain = ChunkedTerrain(WorldConfig(seed = 101))
        val world = StructuralWorld()
        val x = -28f
        val z = 0f
        val gy = terrain.groundSurfaceY(x, z)

        // 1. Oversized floor slab & long steel girder collapse -> stress-fracture into multiple grounded chunks
        val col = world.addBlock(
            poly = VoronoiPolyhedron.createBox(0.25f, 1.8f, 0.25f),
            center = Vec3(x, gy + 1.8f, z),
            tileId = MaterialTile.STEEL, hp = 40f, groundAnchored = true
        )
        world.addBlock(
            poly = VoronoiPolyhedron.createBox(3.4f, 0.14f, 3.4f),
            center = Vec3(x, gy + 3.74f, z),
            tileId = MaterialTile.CONCRETE, hp = 200f,
            supportIds = mutableListOf(col)
        )
        world.addBlock(
            poly = VoronoiPolyhedron.createBox(3.2f, 0.16f, 0.16f),
            center = Vec3(x, gy + 4.10f, z),
            tileId = MaterialTile.STEEL, hp = 200f,
            supportIds = mutableListOf(col)
        )

        world.applyExplosionDetailed(Vec3(x, gy + 1.5f, z), radius = 1.6f, damage = 200f, impulseStrength = 4f, terrain = terrain)
        assertTrue("Collapsing huge slab and long beam must stress-fracture into >= 6 chunks (got ${world.shards.size})", world.shards.size >= 6)
        repeat(160) { world.stepShards(0.02f, terrain) }
        val topShard = world.shards.maxBy { it.center.y }
        val maxSettledY = topShard.center.y
        assertTrue(
            "All fractured slab and beam pieces should reach ground level (maxY=$maxSettledY, gy=$gy, he=${topShard.poly.halfExtents}, cluster=${topShard.clusterPieces?.size}, vel=${topShard.vel}, groundHere=${terrain.groundSurfaceY(topShard.center.x, topShard.center.z)}, total=${world.shards.size})",
            maxSettledY < gy + 1.6f
        )

        // 2. 45-degree diagonal beam must tip flat instead of freezing at 45 degrees
        world.clear()
        val diagBeam = com.example.engine.physics.DynamicShard(
            id = 99801,
            poly = VoronoiPolyhedron.createBox(1.8f, 0.15f, 0.15f),
            center = Vec3(x, gy + 1.45f, z),
            orientation = com.example.model.Quat.fromAxisAngle(Vec3(0f, 0f, 1f), 0.78f),
            uvOrigin = Vec3.ZERO,
            tileId = MaterialTile.STEEL,
            tint = Vec3(0.7f, 0.7f, 0.7f),
            vel = Vec3.ZERO,
            angVel = Vec3.ZERO,
            canSecondarySplit = false
        )
        world.shards.add(diagBeam)
        repeat(160) { world.stepShards(0.02f, terrain) }
        val beamLongWorld = diagBeam.orientation.rotate(Vec3(1f, 0f, 0f))
        assertTrue("45-deg diagonal beam must tip flat and sleep (longWorld.y=${beamLongWorld.y}, sleeping=${diagBeam.sleeping})", kotlin.math.abs(beamLongWorld.y) < 0.25f && diagBeam.sleeping)
    }

    @Test
    fun textureLock_shardUVsRemainInvariantAcrossTranslationAndRotation() {
        val world = StructuralWorld()
        val poly = VoronoiPolyhedron.createBox(0.8f, 0.45f, 0.35f)
        val localOffset = Vec3(0.4f, -0.2f, 0.1f)

        val outIdle = com.example.engine.physics.FloatArrayBuilder(4096)
        world.emitPolyhedronTriangles(
            poly = poly,
            center = Vec3(2f, 4f, 1f),
            orientation = com.example.model.Quat.IDENTITY,
            uvOrigin = Vec3(2f, 4f, 1f),
            tileId = MaterialTile.BRICK,
            tint = Vec3(1f, 1f, 1f),
            out = outIdle,
            uvLocalOffset = localOffset
        )

        val outMovedRotated = com.example.engine.physics.FloatArrayBuilder(4096)
        val rot = com.example.model.Quat.fromAxisAngle(Vec3(0.4f, 1f, 0.3f).normalized(), 1.48f)
        world.emitPolyhedronTriangles(
            poly = poly,
            center = Vec3(28f, 12f, -19f),
            orientation = rot,
            uvOrigin = Vec3(2f, 4f, 1f),
            tileId = MaterialTile.BRICK,
            tint = Vec3(1f, 1f, 1f),
            out = outMovedRotated,
            uvLocalOffset = localOffset
        )

        val arrA = outIdle.toFloatArray()
        val arrB = outMovedRotated.toFloatArray()
        assertEquals("Moved/rotated mesh must have identical vertex count", arrA.size, arrB.size)
        assertTrue(arrA.isNotEmpty())
        var i = 0
        while (i < arrA.size) {
            // Vertex stride = 13: [0..2]=pos, [3..5]=normal, [6..9]=rgba, [10]=u, [11]=v, [12]=tile
            assertEquals("UV U must stay locked onto object after 26m move & 85deg rotation", arrA[i + 10], arrB[i + 10], 1e-5f)
            assertEquals("UV V must stay locked onto object after 26m move & 85deg rotation", arrA[i + 11], arrB[i + 11], 1e-5f)
            i += 13
        }
    }

    @Test
    fun realisticPhysics_subMeterFractureHeavyOnLightCrushAndPrincipalInertia() {
        val terrain = ChunkedTerrain(WorldConfig(seed = 101))
        val world = StructuralWorld()
        val x = -28f
        val z = 0f
        val gy = terrain.groundSurfaceY(x, z)

        // 1. Principal-axis inertia differs correctly for elongated beam vs cubic block
        val beamPoly = VoronoiPolyhedron.createBox(2.0f, 0.2f, 0.2f)
        val invBeam = com.example.engine.physics.computeInvInertiaLocal(beamPoly.halfExtents, 5.0f)
        assertTrue(
            "Rolling around beam long axis (X) should have much higher inverse inertia than pitching around Z",
            invBeam.x > invBeam.z * 5.0f
        )

        // 2. Heavy concrete slab falling onto fragile glass shard crushes/shatters the lighter glass shard
        val heavyPoly = VoronoiPolyhedron.createBox(1.1f, 0.35f, 1.1f)
        val lightGlassPoly = VoronoiPolyhedron.createBox(0.65f, 0.55f, 0.15f)
        val lightGlass = com.example.engine.physics.DynamicShard(
            id = 99901,
            poly = lightGlassPoly,
            center = Vec3(x, gy + 0.55f, z),
            orientation = com.example.model.Quat.IDENTITY,
            uvOrigin = Vec3.ZERO,
            tileId = MaterialTile.GLASS,
            tint = Vec3(0.8f, 0.9f, 1f),
            vel = Vec3.ZERO,
            angVel = Vec3.ZERO,
            sleeping = true,
            isGroundedContact = true,
            massEstimate = 0.6f
        )
        val heavySlab = com.example.engine.physics.DynamicShard(
            id = 99902,
            poly = heavyPoly,
            center = Vec3(x, gy + 2.2f, z),
            orientation = com.example.model.Quat.IDENTITY,
            uvOrigin = Vec3.ZERO,
            tileId = MaterialTile.CONCRETE,
            tint = Vec3(0.75f, 0.75f, 0.75f),
            vel = Vec3(0f, -7.5f, 0f),
            angVel = Vec3.ZERO,
            canSecondarySplit = false,
            massEstimate = 12.0f
        )
        world.shards.add(lightGlass)
        world.shards.add(heavySlab)

        repeat(20) { world.stepShards(0.016f, terrain) }
        assertTrue(
            "Heavy slab falling onto glass shard must crush & shatter it into smaller shards",
            !world.shards.contains(lightGlass) && world.shards.size > 2
        )

        // 3. Large falling slab undergoes sub-meter two-stage shatter on collapse & ground impact
        world.clear()
        val col = world.addBlock(
            poly = VoronoiPolyhedron.createBox(0.25f, 2.2f, 0.25f),
            center = Vec3(x, gy + 2.2f, z),
            tileId = MaterialTile.STEEL, hp = 40f, groundAnchored = true
        )
        world.addBlock(
            poly = VoronoiPolyhedron.createBox(3.0f, 0.18f, 3.0f),
            center = Vec3(x, gy + 4.58f, z),
            tileId = MaterialTile.CONCRETE, hp = 200f,
            supportIds = mutableListOf(col)
        )
        world.applyExplosionDetailed(Vec3(x, gy + 1.5f, z), radius = 1.6f, damage = 200f, impulseStrength = 2f, terrain = terrain)
        val initialCount = world.shards.size
        assertTrue("Large slab collapse must fracture into >= 5 chunks immediately (got $initialCount)", initialCount >= 5)

        repeat(120) { world.stepShards(0.018f, terrain) }
        val avgExtent = world.shards.map { it.poly.halfExtents.len() }.average()
        assertTrue("High-impact ground slam should secondary-shatter chunks into sub-meter average half-extent (< 1.25m, got $avgExtent)", avgExtent < 1.25)
    }

    @Test
    fun aaaDestruction_loadStressStaggerClusterBreakAndMultiPointManifolds() {
        // 1) Multi-point Sutherland-Hodgman face clipping extracts >= 3 corner contacts on overlapping slabs
        val scratch = ManifoldScratch()
        val slabA = VoronoiPolyhedron.createBox(1.2f, 0.25f, 1.2f)
        val slabB = VoronoiPolyhedron.createBox(1.4f, 0.25f, 1.4f)
        val rotY = Quat.fromAxisAngle(Vec3(0f, 1f, 0f), 0.35f)
        scratch.collectPolyClippedManifold(
            polyA = slabA, centerA = Vec3(0f, 0.48f, 0f), qA = rotY,
            polyB = slabB, centerB = Vec3(0f, 0.0f, 0f), qB = Quat.IDENTITY,
            nx = 0f, ny = 1f, nz = 0f, fallbackPen = 0.02f
        )
        assertTrue("Clipped slab-on-slab manifold must extract >= 3 corner contacts (got ${scratch.count})", scratch.count >= 3)

        // 2) Load stress redistribution + sequential staggered overload collapse + compound cluster breakup
        val terrain = ChunkedTerrain(WorldConfig(seed = 4207))
        val world = StructuralWorld()
        val x = -42f
        val z = 6f
        val gy = terrain.groundSurfaceY(x, z)

        // 4 concrete columns carrying a heavy multi-ton roof slab
        val cols = listOf(
            world.addBlock(
                poly = VoronoiPolyhedron.createBox(0.32f, 2.0f, 0.32f),
                center = Vec3(x - 2.0f, gy + 2.0f, z - 2.0f),
                tileId = MaterialTile.CONCRETE, hp = 160f, groundAnchored = true
            ),
            world.addBlock(
                poly = VoronoiPolyhedron.createBox(0.32f, 2.0f, 0.32f),
                center = Vec3(x + 2.0f, gy + 2.0f, z - 2.0f),
                tileId = MaterialTile.CONCRETE, hp = 160f, groundAnchored = true
            ),
            world.addBlock(
                poly = VoronoiPolyhedron.createBox(0.32f, 2.0f, 0.32f),
                center = Vec3(x - 2.0f, gy + 2.0f, z + 2.0f),
                tileId = MaterialTile.CONCRETE, hp = 160f, groundAnchored = true
            ),
            world.addBlock(
                poly = VoronoiPolyhedron.createBox(0.32f, 2.0f, 0.32f),
                center = Vec3(x + 2.0f, gy + 2.0f, z + 2.0f),
                tileId = MaterialTile.CONCRETE, hp = 160f, groundAnchored = true
            )
        )
        val heavyRoof = world.addBlock(
            poly = VoronoiPolyhedron.createBox(3.4f, 0.45f, 3.4f),
            center = Vec3(x, gy + 4.45f, z),
            tileId = MaterialTile.CONCRETE, hp = 300f,
            supportIds = cols.toMutableList()
        )
        world.rebuildPhysicalSupports()
        world.checkStabilityAndCollapse(terrain)
        assertTrue("Intact 4-column roof must not overload or collapse", world.blocks.containsKey(heavyRoof))
        assertTrue("Intact structure must have zero pending collapses", world.pendingCollapses.isEmpty())

        // Destroy 3 columns so all roof load concentrates onto the single remaining column -> triggers overload stagger
        for (i in 0 until 3) {
            val colCenter = world.blocks[cols[i]]?.center ?: continue
            world.applyExplosionDetailed(colCenter, radius = 1.2f, damage = 500f, impulseStrength = 2f, terrain = terrain)
        }
        world.checkStabilityAndCollapse(terrain)
        val stagedCount = world.pendingCollapses.size
        assertTrue(
            "Destroying 3 of 4 columns should overload the remaining column into pendingCollapses (staged=$stagedCount, blocks=${world.blocks.size})",
            stagedCount > 0 || !world.blocks.containsKey(heavyRoof)
        )

        // Advance simulation so staggered wave collapses the overloaded column & roof, releasing compound clusters
        var sawClusteredShard = false
        var collapseEventCount = 0
        repeat(18) {
            world.stepShards(0.02f, terrain)
            collapseEventCount += world.drainCollapseFailureEvents().size
            if (world.shards.any { it.clusterPieces != null }) sawClusteredShard = true
        }
        assertFalseContains(world.blocks.keys, heavyRoof)
        assertTrue("Collapsed oversized roof slab should spawn compound cluster shards before breaking", sawClusteredShard)
        assertTrue(
            "Staggered collapse should emit collapse failure events for dust/rumble (events=$collapseEventCount, staged=$stagedCount, blocksNow=${world.blocks.size}, pendingNow=${world.pendingCollapses.size})",
            collapseEventCount > 0
        )

        // Continue falling until compound clusters slam terrain and break strain bonds / settle fast
        repeat(140) { world.stepShards(0.018f, terrain) }
        val unbrokenClusters = world.shards.count { it.clusterPieces != null }
        assertTrue("Compound clusters hitting terrain from >4m height should mostly break strain bonds (unbroken=$unbrokenClusters, total=${world.shards.size})",
            unbrokenClusters < world.shards.size)
        val fastShard = world.shards.maxByOrNull { it.vel.lenSq() }
        val maxVel = fastShard?.vel?.len() ?: 0f
        assertTrue(
            "Settled multi-point rubble should sleep / damp below 1.0 m/s (maxVel=$maxVel, pos=${fastShard?.center}, vel=${fastShard?.vel}, ang=${fastShard?.angVel}, age=${fastShard?.age}, he=${fastShard?.poly?.halfExtents}, cluster=${fastShard?.clusterPieces?.size})",
            maxVel < 1.0f
        )
    }

    @Test
    fun joltNativePhysics_craterSyncWakesAndSettlesRubbleInsideCrater() {
        val terrain = ChunkedTerrain(WorldConfig(seed = 4207))
        val world = StructuralWorld()
        assertTrue("Native C++ Jolt Physics must be active in unit test runtime", world.isUsingJoltPhysics)

        val x = -42f
        val z = 6f
        val initialGy = terrain.groundSurfaceY(x, z)
        val rubble = com.example.engine.physics.DynamicShard(
            id = 99950,
            poly = VoronoiPolyhedron.createBox(0.35f, 0.20f, 0.35f),
            center = Vec3(x, initialGy + 0.30f, z),
            orientation = Quat.IDENTITY,
            uvOrigin = Vec3.ZERO,
            tileId = MaterialTile.CONCRETE,
            tint = Vec3(1f, 1f, 1f),
            vel = Vec3.ZERO,
            angVel = Vec3.ZERO
        )
        world.shards.add(rubble)
        repeat(75) { world.stepShards(0.016f, terrain) }
        assertTrue("Rubble should settle and sleep on initial terrain (sleeping=${rubble.sleeping})", rubble.sleeping)
        val settledY = rubble.center.y

        // Blast a deep crater right beneath the sleeping rubble -> Jolt HeightField must update & drop shard
        terrain.applyCrater(x, z, radius = 3.5f, depth = 2.0f)
        val craterGy = terrain.groundSurfaceY(x, z)
        assertTrue("Crater must lower ground surface", craterGy < initialGy - 1.2f)

        repeat(90) { world.stepShards(0.016f, terrain) }
        assertTrue(
            "Updated Jolt HeightField must let rubble fall into crater and re-sleep (y=${rubble.center.y}, oldSettledY=$settledY, craterGy=$craterGy, sleep=${rubble.sleeping})",
            rubble.center.y < settledY - 0.8f && rubble.sleeping
        )
    }

    @Test
    fun expandedCity_512x384TerrainDistrictsLandmarksAnd48Sectors() {
        val sim = SandboxSimulation(WorldConfig(seed = 4207))
        val terrain = sim.terrain

        assertEquals(256f, ChunkedTerrain.MAX_X, 1e-4f)
        assertEquals(192f, ChunkedTerrain.MAX_Z, 1e-4f)
        assertEquals(48, StructuralWorld.SECTOR_COUNT)

        // Verify East Bank Industrial & West Bank Downtown craters work and localize chunk updates
        val versionsBefore = terrain.chunkVersions.copyOf()
        terrain.applyCrater(cx = 92f, cz = 84f, radius = 4.5f, depth = 1.6f)
        val eastChanged = terrain.chunkVersions.indices.count { terrain.chunkVersions[it] != versionsBefore[it] }
        assertTrue("Industrial east-bank crater should dirty 1..4 local chunks, got $eastChanged", eastChanged in 1..4)

        // Verify all 48 spatial building sectors exist in RenderSnapshot and non-empty sectors emit valid bounds
        val sectors = sim.latestSnapshot.sectorMeshes
        assertEquals(StructuralWorld.SECTOR_COUNT, sectors.size)
        val nonEmptySectors = sectors.filter { it.hasGeometry }
        assertTrue("Expected >= 14 populated building sectors across the 512x384 metropolis, got ${nonEmptySectors.size}", nonEmptySectors.size >= 14)
        for (sector in nonEmptySectors) {
            assertTrue("Sector ${sector.sectorIndex} hasGeometry must have vertices", sector.opaqueVertices.isNotEmpty() || sector.glassVertices.isNotEmpty())
            assertTrue("Sector ${sector.sectorIndex} AABB minX <= maxX", sector.minX <= sector.maxX)
            assertTrue("Sector ${sector.sectorIndex} AABB minZ <= maxZ", sector.minZ <= sector.maxZ)
        }

        // Verify all major city landmarks, 11 skyscrapers, 3 bridges, stadium, university & rail yard are populated
        val tags = sim.world.blocks.values.mapNotNull { it.targetTag }.toSet()
        for (expectedTag in listOf(
            "TOWER_A", "TOWER_B", "TOWER_C", "TOWER_M1", "TOWER_M2", "TOWER_M3",
            "TOWER_M4", "TOWER_M5", "TOWER_M6", "TOWER_M7", "TOWER_M8",
            "HOSPITAL", "BANK", "FACTORY", "MALL", "APARTMENTS", "UNIVERSITY",
            "STADIUM", "RAIL_YARD", "WAREHOUSE", "CIVIC_PLAZA",
            "PARK_FOUNTAIN", "PARK_GAZEBO", "RIVERWALK", "VEHICLE", "BRIDGE"
        )) {
            assertTrue("Expected metropolis landmark tag '$expectedTag' in populated valley", expectedTag in tags)
        }

        // Verify 3 steel truss bridges span z = -56, 0, +56
        val bridgeZs = sim.world.blocks.values.filter { it.targetTag == "BRIDGE" }.map { it.center.z }
        assertTrue("Expected north river bridge around z=-56", bridgeZs.any { kotlin.math.abs(it + 56f) < 6f })
        assertTrue("Expected center river bridge around z=0", bridgeZs.any { kotlin.math.abs(it) < 6f })
        assertTrue("Expected south river bridge around z=+56", bridgeZs.any { kotlin.math.abs(it - 56f) < 6f })

        // Verify Tower A reaches 18-story height (> 55m above ground)
        val towerABlocks = sim.world.blocks.values.filter { it.targetTag == "TOWER_A" }
        val towerAMinY = towerABlocks.minOf { it.center.y }
        val towerAMaxY = towerABlocks.maxOf { it.center.y }
        assertTrue("Tower A must span > 55m vertical height (got ${towerAMaxY - towerAMinY}m)", towerAMaxY - towerAMinY > 55f)

        // Verify sector dirty isolation when modifying a single block
        val sectorVersionsBefore = sim.world.sectorVersions.copyOf()
        val targetBlock = towerABlocks.firstOrNull { !it.isCoreSupport } ?: towerABlocks.first()
        val expectedSector = sim.world.sectorIndexFor(targetBlock.center.x, targetBlock.center.z)
        sim.world.applyExplosionDetailed(targetBlock.center, radius = 0.8f, damage = 900f, impulseStrength = 2f, terrain = terrain)
        val dirtySectors = sim.world.sectorVersions.indices.filter { sim.world.sectorVersions[it] != sectorVersionsBefore[it] }
        assertTrue("Expected target sector $expectedSector to be dirtied", expectedSector in dirtySectors)
        assertTrue("Small localized blast should dirty <= 4 sectors, got ${dirtySectors.size}", dirtySectors.size <= 4)
    }

    @Test
    fun skyscraperPancakeCollapse_staggersStoryByStoryAndDropsUpperFloors() {
        val sim = SandboxSimulation(WorldConfig(seed = 4207))
        val towerAInitial = sim.world.blocks.values.filter { it.targetTag == "TOWER_A" }
        val baseY = towerAInitial.minOf { it.center.y }
        val lowerCutBlocks = towerAInitial.filter { it.center.y < baseY + 6.5f }
        assertTrue("Tower A should have ground-level structural blocks", lowerCutBlocks.isNotEmpty())

        // Sever the reinforced base structural core and perimeter columns of Tower A with heavy demolition blasts
        val uniqueSpots = lowerCutBlocks
            .map { Vec3(it.center.x, baseY + 1.4f, it.center.z) }
            .distinctBy { "${(it.x / 3f).toInt()}_${(it.z / 3f).toInt()}" }
        for (spot in uniqueSpots) {
            sim.world.applyExplosionDetailed(spot, radius = 4.6f, damage = 4200f, impulseStrength = 14f, terrain = sim.terrain)
        }
        sim.world.checkStabilityAndCollapse(sim.terrain)

        val queued = sim.world.pendingCollapses.size
        assertTrue("Base demolition on Tower A should queue multi-story pancake collapses or collapse tower (queued=$queued, remaining=${sim.world.blocks.values.count { it.targetTag == "TOWER_A" }})",
            queued > 0 || sim.world.blocks.values.count { it.targetTag == "TOWER_A" } < towerAInitial.size * 0.4f)

        // Advance simulation across staggered collapse waves
        repeat(150) {
            sim.world.stepShards(0.02f, sim.terrain)
        }
        val remainingTowerA = sim.world.blocks.values.count { it.targetTag == "TOWER_A" }
        assertTrue(
            "Severing Tower A base should collapse most of the skyscraper (remaining=$remainingTowerA of ${towerAInitial.size})",
            remainingTowerA < (towerAInitial.size * 0.35f).toInt()
        )
    }

    @Test
    fun buildingDurabilityAndRealism_singleRpgDoesNotToppleSkyscraperAndCylindersHaveSmoothNormals() {
        val sim = SandboxSimulation(WorldConfig(seed = 4207))
        val hotelInitial = sim.world.blocks.values.filter { it.targetTag == "HOTEL" }
        val hotelFrontWall = hotelInitial
            .filter { !it.isCoreSupport && it.tileId == MaterialTile.BRICK }
            .minByOrNull { it.center.y }!!

        // Single standard RPG-7 impact (damage=155f, radius=4.6f) breaches locally without toppling reinforced 6-story building
        sim.world.applyExplosionDetailed(
            hotelFrontWall.center,
            radius = 4.6f,
            damage = 155f,
            impulseStrength = 18f,
            terrain = sim.terrain
        )
        repeat(40) { sim.step(0.02f) }

        val hotelRemaining = sim.world.blocks.values.count { it.targetTag == "HOTEL" }
        assertTrue(
            "Single RPG hit should NOT collapse the whole 6-Story Hotel (remaining=$hotelRemaining of ${hotelInitial.size})",
            hotelRemaining > (hotelInitial.size * 0.80f).toInt()
        )
        val hotelWidth = hotelInitial.maxOf { it.center.x } - hotelInitial.minOf { it.center.x }
        assertTrue("Enlarged 6-Story Hotel footprint should span >= 17m wide (got $hotelWidth)", hotelWidth >= 17f)

        // Curved cylinders/canopies use smooth radial vertex normals while cut Voronoi fracture faces stay flat (vertexNormals == null)
        // AND every face must have strictly outward CCW winding so front halves are never back-face culled
        val cyl = VoronoiPolyhedron.createCylinderApprox(0.8f, 1.5f, 10)
        val canopy = VoronoiPolyhedron.createTaperedCanopy(1.8f, 0.5f, 1.1f, 9)
        assertTrue("Cylinder side faces should carry smooth radial vertex normals", cyl.faces.any { it.vertexNormals != null })
        for (poly in listOf(cyl, canopy, VoronoiPolyhedron.createBox(1f, 1f, 1f), VoronoiPolyhedron.createPitchedRoof(1f, 1f, 1f))) {
            for (face in poly.faces) {
                val v0 = face.vertices[0]
                val v1 = face.vertices[1]
                val v2 = face.vertices[2]
                val ccwN = (v1 - v0).cross(v2 - v0).normalized()
                var centroid = Vec3.ZERO
                for (v in face.vertices) centroid += v
                centroid /= face.vertices.size.toFloat()
                assertTrue("Face CCW cross normal must align with outward face normal", ccwN.dot(face.normal) > 0.9f)
                assertTrue("Face CCW cross normal must point outward from polyhedron center", ccwN.dot(centroid) > 0f)
                face.vertexNormals?.forEachIndexed { idx, vn ->
                    assertTrue("Vertex normal must point outward", vn.dot(face.vertices[idx]) > 0f)
                }
            }
        }
        val shattered = VoronoiPolyhedron.shatter(VoronoiPolyhedron.createBox(1.2f, 1.2f, 1.2f), Vec3.ZERO, maxPieces = 6, rng = Random(11))
        assertTrue("Fractured Voronoi shards must keep crisp flat face normals", shattered.all { (_, poly) -> poly.faces.all { it.vertexNormals == null } })
    }

    @Test
    fun cityFeatures_opsMissionsDistrictJumpsAndNewBrushSpawnsWork() {
        val sim = SandboxSimulation(WorldConfig(seed = 4207))

        // 1. Verify all 6 expanded Ops missions are registered & playable
        for (missionId in listOf("skyline_topple", "vault_breach", "factory_sabotage", "midtown_apartments", "stadium_takedown", "rail_yard_blitz")) {
            val mission = com.example.engine.sim.DemolitionOpsCatalog.missions.firstOrNull { it.id == missionId }
            assertNotNull("Missing Ops mission $missionId", mission)
            sim.startMission(mission!!)
            val hud = sim.step(0.016f)
            assertEquals(missionId, hud.activeMission?.mission?.id)
        }

        // 2. Verify all 8 District Quick-Jump targets move player cleanly above terrain
        for (target in com.example.model.DistrictJumpTarget.entries) {
            sim.jumpToDistrict(target)
            sim.step(0.016f)
            val gy = sim.terrain.groundSurfaceY(target.x, target.z)
            assertTrue("Player Y after jump to ${target.name} should be >= ground", sim.playerPos.y >= gy - 0.1f)
            assertEquals(target.x, sim.playerPos.x, 1.0f)
            assertEquals(target.z, sim.playerPos.z, 1.0f)
        }

        // 3. Verify all BrushItem structure spawns (including Apartments, Warehouse & Freight Car) add blocks cleanly without self-collapse
        val brushSpots = listOf(
            com.example.model.BrushItem.SKYSCRAPER to Vec3(-232f, sim.terrain.groundSurfaceY(-232f, -135f), -135f),
            com.example.model.BrushItem.HOSPITAL to Vec3(-232f, sim.terrain.groundSurfaceY(-232f, -95f), -95f),
            com.example.model.BrushItem.BANK to Vec3(-232f, sim.terrain.groundSurfaceY(-232f, -55f), -55f),
            com.example.model.BrushItem.FACTORY to Vec3(-232f, sim.terrain.groundSurfaceY(-232f, -15f), -15f),
            com.example.model.BrushItem.MALL to Vec3(-232f, sim.terrain.groundSurfaceY(-232f, 25f), 25f),
            com.example.model.BrushItem.APARTMENT_BLOCK to Vec3(-232f, sim.terrain.groundSurfaceY(-232f, 65f), 65f),
            com.example.model.BrushItem.WAREHOUSE to Vec3(-232f, sim.terrain.groundSurfaceY(-232f, 105f), 105f),
            com.example.model.BrushItem.RAIL_CAR to Vec3(-232f, sim.terrain.groundSurfaceY(-232f, 145f), 145f),
            com.example.model.BrushItem.PARK_GAZEBO to Vec3(218f, sim.terrain.groundSurfaceY(218f, -135f), -135f),
            com.example.model.BrushItem.PARKED_CAR to Vec3(218f, sim.terrain.groundSurfaceY(218f, -95f), -95f)
        )
        for ((brush, spot) in brushSpots) {
            val before = sim.world.blocks.size
            com.example.engine.physics.ZoneBuilder.spawnBrushStructure(
                item = brush,
                buildMaterial = com.example.model.BuildMaterial.STRUCTURAL_STEEL,
                world = sim.world,
                terrain = sim.terrain,
                hit = spot,
                yaw = 0f
            )
            assertTrue("Brush $brush should spawn blocks", sim.world.blocks.size > before)
        }
        val beforeCheck = sim.world.collapsedCount
        sim.world.checkStabilityAndCollapse(sim.terrain)
        assertEquals("Newly spawned brush structures should not self-collapse", beforeCheck, sim.world.collapsedCount)
    }

    private fun assertFalseContains(keys: Set<Int>, id: Int) {
        assertTrue("Expected block $id to be detached/removed", !keys.contains(id))
    }
}
