package com.example.engine.physics

import com.example.engine.terrain.ChunkedTerrain
import com.example.model.MaterialTile
import com.example.model.Vec3
import com.example.model.WorldConfig
import kotlin.random.Random

/**
 * The valley is a list of sites, not a sequence of hand-written calls.
 * Geometry still lives in the builders. Positions, storeys, and tags live here.
 */
internal enum class SiteKind {
    TOWER,
    VAULT_BANK,
    HOSPITAL,
    CORNER_SHOP,
    APARTMENT,
    CLOCK_TOWER,
    CIVIC_PLAZA,
    MALL,
    CAFE,
    ROW_SHOPS,
    CENTRAL_PARK,
    HOTEL,
    UNIVERSITY,
    HARDWARE,
    GAS_STATION,
    STADIUM,
    WAREHOUSE,
    HANGAR,
    FACTORY,
    FUEL_SILO,
    FUEL_DEPOT,
    RAIL_YARD,
}

internal data class BuildingSite(
    val kind: SiteKind,
    val x: Float = 0f,
    val z: Float = 0f,
    val stories: Int = 0,
    val style: SkyscraperStyle = SkyscraperStyle.GLASS_CURTAIN,
    val tag: String? = null,
    val tile: Int = MaterialTile.BRICK,
    val tint: Vec3 = Vec3(1f, 1f, 1f),
    val halfW: Float = 8.2f,
    val halfD: Float = 6.0f,
)

internal data class CottageSite(val x: Float, val z: Float, val tile: Int)

internal object ValleyLayout {

    val westBank: List<BuildingSite> = listOf(
        BuildingSite(SiteKind.TOWER, -36f, -18f, stories = 18, style = SkyscraperStyle.LIMESTONE_SETBACK, tag = "TOWER_A"),
        BuildingSite(SiteKind.TOWER, -36f, -40f, stories = 20, style = SkyscraperStyle.GLASS_CURTAIN, tag = "TOWER_B"),
        BuildingSite(SiteKind.VAULT_BANK, -36f, 28f),
        BuildingSite(SiteKind.HOSPITAL, -36f, -84f),
        BuildingSite(SiteKind.TOWER, -36f, 74f, stories = 14, style = SkyscraperStyle.BRICK_TERRACED, tag = "TOWER_M5"),
        BuildingSite(SiteKind.CORNER_SHOP, -36f, 96f),
        BuildingSite(SiteKind.APARTMENT, -37f, -132f, stories = 4, tile = MaterialTile.BRICK, tint = Vec3(0.88f, 0.56f, 0.46f)),
        BuildingSite(SiteKind.TOWER, -81f, -18f, stories = 22, style = SkyscraperStyle.GLASS_CURTAIN, tag = "TOWER_C"),
        BuildingSite(SiteKind.TOWER, -81f, -40f, stories = 15, style = SkyscraperStyle.LIMESTONE_SETBACK, tag = "TOWER_M1"),
        BuildingSite(SiteKind.CLOCK_TOWER, -81f, -74f),
        BuildingSite(SiteKind.CIVIC_PLAZA, -81f, -96f),
        BuildingSite(SiteKind.MALL, -81f, 86f),
        BuildingSite(SiteKind.CAFE, -81f, 69f),
        BuildingSite(SiteKind.ROW_SHOPS, -81f, -132f),
        BuildingSite(SiteKind.CENTRAL_PARK),
        BuildingSite(SiteKind.HOTEL, -127f, -18f),
        BuildingSite(SiteKind.TOWER, -127f, -40f, stories = 16, style = SkyscraperStyle.BRICK_TERRACED, tag = "TOWER_M2"),
        BuildingSite(SiteKind.TOWER, -127f, -74f, stories = 15, style = SkyscraperStyle.GLASS_CURTAIN, tag = "TOWER_M6"),
        BuildingSite(SiteKind.APARTMENT, -127f, -96f, stories = 5, tile = MaterialTile.STUCCO, tint = Vec3(0.95f, 0.91f, 0.82f)),
        BuildingSite(SiteKind.UNIVERSITY, -127f, 84f),
        BuildingSite(SiteKind.APARTMENT, -127f, -132f, stories = 4, tile = MaterialTile.CONCRETE, tint = Vec3(0.86f, 0.88f, 0.90f)),
        BuildingSite(SiteKind.TOWER, -173f, -18f, stories = 14, style = SkyscraperStyle.LIMESTONE_SETBACK, tag = "TOWER_M3"),
        BuildingSite(SiteKind.TOWER, -173f, -40f, stories = 13, style = SkyscraperStyle.GLASS_CURTAIN, tag = "TOWER_M4"),
        BuildingSite(SiteKind.HARDWARE, -173f, -74f),
        BuildingSite(SiteKind.GAS_STATION, -173f, -96f),
        BuildingSite(SiteKind.APARTMENT, -173f, 20f, stories = 5, tile = MaterialTile.BRICK, tint = Vec3(0.88f, 0.58f, 0.48f)),
        BuildingSite(SiteKind.ROW_SHOPS, -173f, 41f),
        BuildingSite(SiteKind.APARTMENT, -173f, 74f, stories = 4, tile = MaterialTile.STUCCO, tint = Vec3(0.92f, 0.88f, 0.78f)),
        BuildingSite(SiteKind.CORNER_SHOP, -173f, 96f),
        BuildingSite(SiteKind.CAFE, -173f, 104.5f),
        BuildingSite(SiteKind.STADIUM, -104f, 136f),
    )

    val cottages: List<CottageSite> = listOf(
        CottageSite(-208f, -84f, MaterialTile.STUCCO),
        CottageSite(-208f, -28f, MaterialTile.BRICK),
        CottageSite(-208f, 28f, MaterialTile.STUCCO),
        CottageSite(-208f, 84f, MaterialTile.BRICK),
        CottageSite(-166f, -132f, MaterialTile.STUCCO),
        CottageSite(-181f, -132f, MaterialTile.BRICK),
        CottageSite(-173f, 132f, MaterialTile.STUCCO),
    )

    val westLampX: List<Float> = listOf(-184f, -162f, -138f, -116f, -92f, -70f, -46f, -24f)
    val westHydrantX: List<Float> = listOf(-26f, -52f, -96f, -142f, -188f)
    val westIntersections: List<Pair<Float, Float>> = listOf(
        -58f to 0f, -104f to 0f, -150f to 0f,
        -58f to -56f, -104f to -56f, -150f to -56f,
        -58f to 56f, -104f to 56f, -150f to 56f,
        -58f to 112f, -150f to 112f,
    )

    val eastBank: List<BuildingSite> = listOf(
        BuildingSite(SiteKind.APARTMENT, 37f, 28f, stories = 4, tile = MaterialTile.CONCRETE, tint = Vec3(0.88f, 0.90f, 0.92f)),
        BuildingSite(SiteKind.TOWER, 82f, 18f, stories = 16, style = SkyscraperStyle.GLASS_CURTAIN, tag = "TOWER_M7"),
        BuildingSite(SiteKind.ROW_SHOPS, 82f, 41f),
        BuildingSite(SiteKind.TOWER, 135f, 28f, stories = 13, style = SkyscraperStyle.LIMESTONE_SETBACK, tag = "TOWER_M8"),
        BuildingSite(SiteKind.WAREHOUSE, 182f, 28f),
        BuildingSite(SiteKind.HANGAR, 37f, 84f, halfW = 8.2f, halfD = 6.0f),
        BuildingSite(SiteKind.FACTORY, 82f, 84f),
        BuildingSite(SiteKind.WAREHOUSE, 135f, 72f),
        BuildingSite(SiteKind.WAREHOUSE, 135f, 96f),
        BuildingSite(SiteKind.FUEL_SILO, 180f, 72f),
        BuildingSite(SiteKind.FUEL_SILO, 180f, 88f),
        BuildingSite(SiteKind.FUEL_DEPOT, 190f, 98f),
        BuildingSite(SiteKind.RAIL_YARD, 108f, 136f),
    )

    val eastIntersections: List<Pair<Float, Float>> = listOf(
        56f to 0f, 108f to 0f, 162f to 0f,
        56f to 56f, 108f to 56f, 162f to 56f,
        56f to 112f, 108f to 112f, 162f to 112f,
    )
    val eastLampX: List<Float> = listOf(30f, 48f, 68f, 94f, 120f, 148f, 174f)

    fun placeWestBank(world: StructuralWorld, terrain: ChunkedTerrain, config: WorldConfig) {
        for (site in westBank) place(world, terrain, site, rng = null)
        val count = ((cottages.size * config.townScale).toInt()).coerceIn(2, cottages.size)
        for (i in 0 until count) {
            val cottage = cottages[i]
            ZoneBuilder.buildPorchCottage(world, terrain, cottage.x, cottage.z, wallTile = cottage.tile)
        }
        for (lx in westLampX) {
            ArchitecturalPrimitives.buildStreetLamp(world, terrain, lx, -5.2f)
            ArchitecturalPrimitives.buildStreetLamp(world, terrain, lx + 4.5f, 5.2f)
            ArchitecturalPrimitives.buildStreetLamp(world, terrain, lx, -61.0f)
            ArchitecturalPrimitives.buildStreetLamp(world, terrain, lx + 4.5f, 61.0f)
            ArchitecturalPrimitives.buildStreetLamp(world, terrain, lx, 106.8f)
        }
        for (hx in westHydrantX) {
            ArchitecturalPrimitives.buildFireHydrant(world, terrain, hx, -5.8f)
            ArchitecturalPrimitives.buildFireHydrant(world, terrain, hx - 3.0f, 5.8f)
        }
        ArchitecturalPrimitives.buildParkBench(world, terrain, -44.0f, -6.0f, alongX = true)
        ArchitecturalPrimitives.buildParkBench(world, terrain, -76.0f, -6.0f, alongX = true)
        ArchitecturalPrimitives.buildParkBench(world, terrain, -124.0f, -6.0f, alongX = true)
        ArchitecturalPrimitives.buildParkBench(world, terrain, -51.5f, 20.0f, alongX = false)
        ArchitecturalPrimitives.buildParkBench(world, terrain, -51.5f, 36.0f, alongX = false)
        CityParkAndStreetlifeBuilder.buildBusShelter(world, terrain, -127.0f, -5.6f, alongX = true)
        CityParkAndStreetlifeBuilder.buildBusShelter(world, terrain, -81.0f, 5.6f, alongX = true)
        CityParkAndStreetlifeBuilder.buildBusShelter(world, terrain, -81.0f, 61.5f, alongX = true)
        CityParkAndStreetlifeBuilder.buildBusShelter(world, terrain, -104.0f, 106.8f, alongX = true)
        for ((ix, iz) in westIntersections) {
            CityParkAndStreetlifeBuilder.buildTrafficLight(world, terrain, ix - 4.8f, iz - 4.8f, armDirX = 1f, armDirZ = 0f)
            CityParkAndStreetlifeBuilder.buildTrafficLight(world, terrain, ix + 4.8f, iz + 4.8f, armDirX = -1f, armDirZ = 0f)
        }
        CityParkAndStreetlifeBuilder.buildParkedVehicle(world, terrain, -134f, -3.3f, alongX = true, kind = ParkedVehicleKind.SEDAN, bodyTint = Vec3(0.95f, 0.78f, 0.16f))
        CityParkAndStreetlifeBuilder.buildParkedVehicle(world, terrain, -86f, -3.3f, alongX = true, kind = ParkedVehicleKind.VAN, bodyTint = Vec3(0.24f, 0.50f, 0.84f))
        CityParkAndStreetlifeBuilder.buildParkedVehicle(world, terrain, -34f, -3.3f, alongX = true, kind = ParkedVehicleKind.SEDAN, bodyTint = Vec3(0.84f, 0.24f, 0.22f))
        CityParkAndStreetlifeBuilder.buildParkedVehicle(world, terrain, -118f, 53.2f, alongX = true, kind = ParkedVehicleKind.BOX_TRUCK, bodyTint = Vec3(0.28f, 0.56f, 0.36f))
        CityParkAndStreetlifeBuilder.buildParkedVehicle(world, terrain, -72f, -53.2f, alongX = true, kind = ParkedVehicleKind.VAN, bodyTint = Vec3(0.94f, 0.94f, 0.96f))
        CityParkAndStreetlifeBuilder.buildParkedVehicle(world, terrain, -112f, 109.2f, alongX = true, kind = ParkedVehicleKind.SEDAN, bodyTint = Vec3(0.22f, 0.62f, 0.78f))
        CityParkAndStreetlifeBuilder.buildDumpster(world, terrain, -49.5f, -28.0f, alongX = false)
        CityParkAndStreetlifeBuilder.buildDumpster(world, terrain, -97.5f, 86.0f, alongX = false)
        CityParkAndStreetlifeBuilder.buildDumpster(world, terrain, -141.5f, -84.0f, alongX = false)
        for (bz in listOf(-56f, 0f, 56f)) {
            ArchitecturalPrimitives.buildPlasticBarrier(world, terrain, -21.0f, bz - 4.8f, alongX = false)
            ArchitecturalPrimitives.buildPlasticBarrier(world, terrain, -21.0f, bz + 4.8f, alongX = false)
        }
        ArchitecturalPrimitives.buildSupplyCrate(world, terrain, -24.0f, 7.2f, plastic = true)
        ArchitecturalPrimitives.buildSupplyCrate(world, terrain, -68.0f, 7.2f, plastic = true)
    }

    fun placeEastBank(world: StructuralWorld, terrain: ChunkedTerrain, rng: Random) {
        for (site in eastBank) place(world, terrain, site, rng)
        for ((ix, iz) in eastIntersections) {
            CityParkAndStreetlifeBuilder.buildTrafficLight(world, terrain, ix - 4.6f, iz - 4.6f, armDirX = 1f, armDirZ = 0f)
            CityParkAndStreetlifeBuilder.buildTrafficLight(world, terrain, ix + 4.6f, iz + 4.6f, armDirX = -1f, armDirZ = 0f)
        }
        for (lx in eastLampX) {
            ArchitecturalPrimitives.buildStreetLamp(world, terrain, lx, 50.8f)
            ArchitecturalPrimitives.buildStreetLamp(world, terrain, lx + 4.0f, 61.2f)
            ArchitecturalPrimitives.buildStreetLamp(world, terrain, lx, 106.8f)
        }
        CityParkAndStreetlifeBuilder.buildParkedVehicle(world, terrain, 37.0f, 63.0f, alongX = true, kind = ParkedVehicleKind.BOX_TRUCK, bodyTint = Vec3(0.86f, 0.56f, 0.18f))
        CityParkAndStreetlifeBuilder.buildParkedVehicle(world, terrain, 82.0f, 63.0f, alongX = true, kind = ParkedVehicleKind.VAN, bodyTint = Vec3(0.36f, 0.44f, 0.52f))
        CityParkAndStreetlifeBuilder.buildParkedVehicle(world, terrain, 135.0f, 109.2f, alongX = true, kind = ParkedVehicleKind.BOX_TRUCK, bodyTint = Vec3(0.26f, 0.48f, 0.78f))
        CityParkAndStreetlifeBuilder.buildDumpster(world, terrain, 65.0f, 84.0f, alongX = false)
    }

    private fun place(world: StructuralWorld, terrain: ChunkedTerrain, site: BuildingSite, rng: Random?) {
        when (site.kind) {
            SiteKind.TOWER -> SkyscraperAndComplexBuilder.buildSkyscraper(
                world = world, terrain = terrain, cx = site.x, cz = site.z,
                stories = site.stories, style = site.style, targetTag = site.tag ?: "TOWER_A",
            )
            SiteKind.VAULT_BANK -> SkyscraperAndComplexBuilder.buildCentralVaultBank(world, terrain, cx = site.x, cz = site.z)
            SiteKind.HOSPITAL -> SkyscraperAndComplexBuilder.buildMetroHospital(world, terrain, cx = site.x, cz = site.z)
            SiteKind.CORNER_SHOP -> ZoneBuilder.buildCornerShop(world, terrain, cx = site.x, cz = site.z)
            SiteKind.APARTMENT -> UrbanExpansionBuilder.buildApartmentBuilding(
                world, terrain, cx = site.x, cz = site.z, stories = site.stories,
                facadeTile = site.tile, facadeTint = site.tint,
            )
            SiteKind.CLOCK_TOWER -> ZoneBuilder.buildClockTowerHall(world, terrain, cx = site.x, cz = site.z)
            SiteKind.CIVIC_PLAZA -> UrbanExpansionBuilder.buildCivicPlazaMonuments(world, terrain, cx = site.x, cz = site.z)
            SiteKind.MALL -> SkyscraperAndComplexBuilder.buildShoppingMall(world, terrain, cx = site.x, cz = site.z)
            SiteKind.CAFE -> CityParkAndStreetlifeBuilder.buildCafePatio(world, terrain, cx = site.x, cz = site.z)
            SiteKind.ROW_SHOPS -> UrbanExpansionBuilder.buildRowShopBlock(world, terrain, cx = site.x, cz = site.z)
            SiteKind.CENTRAL_PARK -> CityParkAndStreetlifeBuilder.buildCentralPark(world, terrain)
            SiteKind.HOTEL -> ZoneBuilder.buildBrickHotel3F(world, terrain, cx = site.x, cz = site.z)
            SiteKind.UNIVERSITY -> UrbanExpansionBuilder.buildUniversityHall(world, terrain, cx = site.x, cz = site.z)
            SiteKind.HARDWARE -> ZoneBuilder.buildHardwareStore(world, terrain, cx = site.x, cz = site.z)
            SiteKind.GAS_STATION -> ZoneBuilder.buildGasStation(world, terrain, cx = site.x, cz = site.z)
            SiteKind.STADIUM -> UrbanExpansionBuilder.buildMunicipalStadium(world, terrain, cx = site.x, cz = site.z)
            SiteKind.WAREHOUSE -> UrbanExpansionBuilder.buildWarehouseDepot(world, terrain, cx = site.x, cz = site.z)
            SiteKind.HANGAR -> MilitaryZoneBuilder.buildHangar(world, terrain, site.x, site.z, halfW = site.halfW, halfD = site.halfD)
            SiteKind.FACTORY -> SkyscraperAndComplexBuilder.buildHeavyFactory(world, terrain, cx = site.x, cz = site.z)
            SiteKind.FUEL_SILO -> MilitaryZoneBuilder.buildFuelSilo(world, terrain, site.x, site.z, rng ?: Random(1))
            SiteKind.FUEL_DEPOT -> MilitaryZoneBuilder.buildFuelDepot(world, terrain, site.x, site.z)
            SiteKind.RAIL_YARD -> UrbanExpansionBuilder.buildRailYard(world, terrain, cx = site.x, cz = site.z)
        }
    }
}
