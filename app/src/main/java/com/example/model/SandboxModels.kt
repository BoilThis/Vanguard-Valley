package com.example.model

enum class ZoneId(val displayName: String) {
    DOWNTOWN_CORE("Downtown Core"),
    CENTRAL_PARK("Central Park"),
    CIVIC_PLAZA("Civic Plaza"),
    SMALL_TOWN("Midtown"),
    UNIVERSITY("University Quad"),
    STADIUM_RAIL("Stadium & Rail"),
    INDUSTRIAL_DISTRICT("Industrial Zone"),
    RIVER_CROSSING("River Crossing"),
    PINE_FOREST("Pine Forest"),
    ARMY_BASE("Army Base"),
    VALLEY_OUTSKIRTS("Vanguard Valley")
}

enum class DistrictJumpTarget(val label: String, val x: Float, val z: Float, val yaw: Float) {
    DOWNTOWN("Downtown", -58f, -14f, -1.8f),
    CENTRAL_PARK("Central Park", -104f, 28f, 1.55f),
    CIVIC_PLAZA("Civic Plaza", -80f, -84f, 0.0f),
    RIVERWALK("Riverwalk", -20f, 24f, 0.25f),
    UNIVERSITY("University", -128f, 84f, -0.25f),
    STADIUM_RAIL("Stadium", -104f, 114f, 0.0f),
    INDUSTRIAL("Industrial", 82f, 82f, 1.55f),
    ARMY_BASE("Army Base", 56f, -26f, -0.45f)
}

enum class CameraMode(val label: String) {
    FIRST_PERSON("1P"),
    THIRD_PERSON("3P"),
    DRONE("DRONE")
}

enum class WeaponType(val label: String, val subtitle: String) {
    CARBINE("M4 Carbine", "Rapid precision rounds"),
    HEAVY_RIFLE(".50 Cal AP", "Armor-piercing shot"),
    RPG("RPG-7", "Armor-piercing blast"),
    C4("C4 Pack", "Stick & detonate"),
    ARTILLERY("155mm", "3-shell barrage"),
    WRECKING_BALL("Kinetic", "Pure physics ram")
}

enum class BrushItem(val label: String) {
    WALL_PANEL("Wall Panel"),
    SKYSCRAPER("Skyscraper"),
    HOSPITAL("Hospital"),
    BANK("Vault Bank"),
    FACTORY("Factory"),
    MALL("Shopping Mall"),
    PARK_GAZEBO("Park Gazebo"),
    PARKED_CAR("Parked Car"),
    HOTEL_3F("Brick Hotel"),
    APARTMENT_BLOCK("Apartments"),
    HARDWARE_STORE("Hardware Store"),
    CORNER_SHOP("Corner Shop"),
    CLOCK_TOWER("Clock Tower"),
    WAREHOUSE("Warehouse"),
    RAIL_CAR("Freight Car"),
    GAS_STATION("Gas Station"),
    COMMAND_HQ("Command HQ"),
    FUEL_SILO("Fuel Silo"),
    WATCHTOWER("Watchtower"),
    BUNKER("Bunker"),
    FUEL_DEPOT("Fuel Drums"),
    PINE_TREE("Pine Tree"),
    RAISE_TERRAIN("Raise Hill"),
    LOWER_TERRAIN("Dig Crater")
}

enum class BuildMaterial(
    val label: String,
    val tileId: Int,
    val defaultTint: Vec3,
    val thickness: Float,
    val defaultHp: Float
) {
    WOOD("Wood", MaterialTile.TIMBER, Vec3(0.95f, 0.88f, 0.78f), 0.18f, 95f),
    SHEET_METAL("Metal", MaterialTile.CORRUGATED_METAL, Vec3(0.82f, 0.86f, 0.88f), 0.14f, 185f),
    STRUCTURAL_STEEL("Steel", MaterialTile.STEEL, Vec3(0.58f, 0.62f, 0.68f), 0.22f, 480f),
    PLASTIC("Plastic", MaterialTile.PLASTIC, Vec3(0.92f, 0.96f, 1.0f), 0.14f, 42f),
    GLASS("Glass", MaterialTile.GLASS, Vec3(0.80f, 0.92f, 1.0f), 0.08f, 14f),
    BRICK("Brick", MaterialTile.BRICK, Vec3(0.96f, 0.93f, 0.90f), 0.24f, 195f)
}

enum class InteractionTool {
    WEAPON,
    BUILD_BRUSH
}

enum class LightingPreset(val label: String) {
    MORNING("Morning"),
    NOON("High Noon"),
    GOLDEN_HOUR("Golden Hour"),
    OVERCAST("Overcast")
}

enum class GameMode(val label: String) {
    FREE_SANDBOX("SANDBOX"),
    DEMOLITION_OPS("OPS")
}

enum class MaterialSoundKind {
    GLASS,
    PLASTIC,
    WOOD,
    METAL,
    MASONRY
}

enum class FractureStyle {
    SPLINTER_PLANK,
    SHATTER_TRIANGLE,
    TEAR_SHEET,
    CRUMBLE_MASONRY,
    SHEAR_STEEL
}

data class MaterialProfile(
    val carbineMult: Float,
    val heavyApMult: Float,
    val blastMult: Float,
    val kineticMult: Float,
    val restitution: Float,
    val drag: Float,
    val shardPieces: Int,
    val soundKind: MaterialSoundKind,
    val fractureStyle: FractureStyle,
    val isCarbineRicochet: Boolean = false
)

object MaterialTile {
    const val TURF = 0
    const val SOIL = 1
    const val ASPHALT = 2
    const val RIVER_STONE = 3
    const val CONCRETE = 4
    const val STUCCO = 5
    const val BRICK = 6
    const val AWNING_TRIM = 7
    const val ROOF_SLATE = 8
    const val TIMBER = 9
    const val PINE_BARK = 10
    const val PINE_NEEDLE = 11
    const val STEEL = 12
    const val CORRUGATED_METAL = 13
    const val GLASS = 14
    const val SANDBAG = 15
    const val PLASTIC = 16
    const val STAIR_TREAD = 17
    const val CLOCK_FACE = 18
}

object MaterialProfiles {
    private val glass = MaterialProfile(
        carbineMult = 1.20f,
        heavyApMult = 1.50f,
        blastMult = 1.40f,
        kineticMult = 1.60f,
        restitution = 0.16f,
        drag = 1.5f,
        shardPieces = 14,
        soundKind = MaterialSoundKind.GLASS,
        fractureStyle = FractureStyle.SHATTER_TRIANGLE
    )
    private val plastic = MaterialProfile(
        carbineMult = 0.95f,
        heavyApMult = 1.18f,
        blastMult = 1.05f,
        kineticMult = 1.15f,
        restitution = 0.46f,
        drag = 2.5f,
        shardPieces = 12,
        soundKind = MaterialSoundKind.PLASTIC,
        fractureStyle = FractureStyle.TEAR_SHEET
    )
    private val wood = MaterialProfile(
        carbineMult = 0.52f,
        heavyApMult = 0.85f,
        blastMult = 0.68f,
        kineticMult = 0.75f,
        restitution = 0.28f,
        drag = 2.0f,
        shardPieces = 14,
        soundKind = MaterialSoundKind.WOOD,
        fractureStyle = FractureStyle.SPLINTER_PLANK
    )
    private val sheetMetal = MaterialProfile(
        carbineMult = 0.18f,
        heavyApMult = 0.72f,
        blastMult = 0.56f,
        kineticMult = 0.58f,
        restitution = 0.34f,
        drag = 1.4f,
        shardPieces = 12,
        soundKind = MaterialSoundKind.METAL,
        fractureStyle = FractureStyle.TEAR_SHEET
    )
    private val steel = MaterialProfile(
        carbineMult = 0.04f,
        heavyApMult = 0.60f,
        blastMult = 0.30f,
        kineticMult = 0.34f,
        restitution = 0.26f,
        drag = 1.0f,
        shardPieces = 10,
        soundKind = MaterialSoundKind.METAL,
        fractureStyle = FractureStyle.SHEAR_STEEL,
        isCarbineRicochet = true
    )
    private val masonry = MaterialProfile(
        carbineMult = 0.20f,
        heavyApMult = 0.65f,
        blastMult = 0.52f,
        kineticMult = 0.56f,
        restitution = 0.18f,
        drag = 1.1f,
        shardPieces = 16,
        soundKind = MaterialSoundKind.MASONRY,
        fractureStyle = FractureStyle.CRUMBLE_MASONRY
    )

    fun forTile(tileId: Int): MaterialProfile = when (tileId) {
        MaterialTile.GLASS -> glass
        MaterialTile.PLASTIC -> plastic
        MaterialTile.TIMBER,
        MaterialTile.STAIR_TREAD,
        MaterialTile.PINE_BARK,
        MaterialTile.PINE_NEEDLE,
        MaterialTile.AWNING_TRIM -> wood
        MaterialTile.CORRUGATED_METAL -> sheetMetal
        MaterialTile.STEEL -> steel
        else -> masonry
    }
}

data class WorldConfig(
    val seed: Int = 4207,
    val riverWidth: Float = 1.0f,
    val forestDensity: Float = 1.0f,
    val townScale: Float = 1.0f,
    val baseFortification: Float = 1.0f,
    val lightingPreset: LightingPreset = LightingPreset.NOON
)

data class DemolitionMission(
    val id: String,
    val title: String,
    val briefing: String,
    val targetTag: String,
    val targetLabel: String,
    val requiredDestructionPct: Int,
    val parScore: Int,
    val ammoBudgets: Map<WeaponType, Int>
)

data class MissionProgress(
    val mission: DemolitionMission,
    val destructionPct: Int = 0,
    val isCompleted: Boolean = false,
    val starsEarned: Int = 0
)

data class HudUiState(
    val currentZone: ZoneId = ZoneId.SMALL_TOWN,
    val cameraMode: CameraMode = CameraMode.THIRD_PERSON,
    val interactionTool: InteractionTool = InteractionTool.WEAPON,
    val selectedWeapon: WeaponType = WeaponType.RPG,
    val selectedBrush: BrushItem = BrushItem.WALL_PANEL,
    val selectedBuildMaterial: BuildMaterial = BuildMaterial.WOOD,
    val gameMode: GameMode = GameMode.FREE_SANDBOX,
    val activeMission: MissionProgress? = null,
    val score: Int = 0,
    val comboMultiplier: Int = 1,
    val ordnanceAmmo: Map<WeaponType, Int> = emptyMap(),
    val missionStars: Map<String, Int> = emptyMap(),
    val audioMuted: Boolean = false,
    val armedC4Count: Int = 0,
    val activeShardCount: Int = 0,
    val intactBlocksCount: Int = 0,
    val collapsedCount: Int = 0,
    val fps: Int = 60,
    val isSprinting: Boolean = false,
    val isWorldStudioOpen: Boolean = false,
    val isOpsDialogOpen: Boolean = false,
    val worldConfig: WorldConfig = WorldConfig(),
    val statusBanner: String? = null
)
