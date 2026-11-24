package com.example.engine.sim

import com.example.model.DemolitionMission
import com.example.model.WeaponType

object DemolitionOpsCatalog {

    val missions: List<DemolitionMission> = listOf(
        DemolitionMission(
            id = "town_square",
            title = "Hotel Clearance",
            briefing = "Breach the 6-Story Brick Hotel facades and sever its reinforced steel core skeleton in Midtown.",
            targetTag = "HOTEL",
            targetLabel = "6-Story Brick Hotel",
            requiredDestructionPct = 70,
            parScore = 3600,
            ammoBudgets = mapOf(
                WeaponType.CARBINE to 160,
                WeaponType.HEAVY_RIFLE to 28,
                WeaponType.RPG to 14,
                WeaponType.C4 to 10,
                WeaponType.ARTILLERY to 3,
                WeaponType.WRECKING_BALL to 10
            )
        ),
        DemolitionMission(
            id = "bridgehead",
            title = "Bridgehead Cut",
            briefing = "Drop the Steel Truss River Bridges into the channel using .50 Cal AP and shaped charges.",
            targetTag = "BRIDGE",
            targetLabel = "Steel Truss Bridges",
            requiredDestructionPct = 65,
            parScore = 2400,
            ammoBudgets = mapOf(
                WeaponType.CARBINE to 90,
                WeaponType.HEAVY_RIFLE to 28,
                WeaponType.RPG to 10,
                WeaponType.C4 to 8,
                WeaponType.ARTILLERY to 2,
                WeaponType.WRECKING_BALL to 8
            )
        ),
        DemolitionMission(
            id = "fuel_farm",
            title = "Fuel Farm Chain",
            briefing = "Ignite the Army Base fuel silos and drum depots to trigger chain explosions.",
            targetTag = "SILO",
            targetLabel = "Fuel Silos & Drums",
            requiredDestructionPct = 75,
            parScore = 1800,
            ammoBudgets = mapOf(
                WeaponType.CARBINE to 100,
                WeaponType.HEAVY_RIFLE to 18,
                WeaponType.RPG to 8,
                WeaponType.C4 to 6,
                WeaponType.ARTILLERY to 2,
                WeaponType.WRECKING_BALL to 6
            )
        ),
        DemolitionMission(
            id = "command_hq",
            title = "Command HQ Strike",
            briefing = "Breach the reinforced 5-Story Command HQ and topple its rooftop radar array.",
            targetTag = "HQ",
            targetLabel = "5-Story Command HQ",
            requiredDestructionPct = 70,
            parScore = 4000,
            ammoBudgets = mapOf(
                WeaponType.CARBINE to 170,
                WeaponType.HEAVY_RIFLE to 32,
                WeaponType.RPG to 14,
                WeaponType.C4 to 10,
                WeaponType.ARTILLERY to 4,
                WeaponType.WRECKING_BALL to 10
            )
        ),
        DemolitionMission(
            id = "skyline_topple",
            title = "Skyline Topple",
            briefing = "Sever the steel core and perimeter columns of the 18-Story Financial Tower to trigger a pancake collapse.",
            targetTag = "TOWER_A",
            targetLabel = "18-Story Financial Tower",
            requiredDestructionPct = 65,
            parScore = 5200,
            ammoBudgets = mapOf(
                WeaponType.CARBINE to 200,
                WeaponType.HEAVY_RIFLE to 42,
                WeaponType.RPG to 22,
                WeaponType.C4 to 14,
                WeaponType.ARTILLERY to 5,
                WeaponType.WRECKING_BALL to 14
            )
        ),
        DemolitionMission(
            id = "vault_breach",
            title = "Vault Breach",
            briefing = "Crack the Central Vault Bank's limestone portico and reinforced interior vault core in Downtown.",
            targetTag = "BANK",
            targetLabel = "Central Vault Bank",
            requiredDestructionPct = 70,
            parScore = 3200,
            ammoBudgets = mapOf(
                WeaponType.CARBINE to 140,
                WeaponType.HEAVY_RIFLE to 30,
                WeaponType.RPG to 14,
                WeaponType.C4 to 10,
                WeaponType.ARTILLERY to 3,
                WeaponType.WRECKING_BALL to 10
            )
        ),
        DemolitionMission(
            id = "factory_sabotage",
            title = "Factory Sabotage",
            briefing = "Demolish the Heavy Manufacturing Factory's sawtooth bays, boiler smokestack, and industrial tanks.",
            targetTag = "FACTORY",
            targetLabel = "Heavy Manufacturing Factory",
            requiredDestructionPct = 70,
            parScore = 3800,
            ammoBudgets = mapOf(
                WeaponType.CARBINE to 160,
                WeaponType.HEAVY_RIFLE to 32,
                WeaponType.RPG to 16,
                WeaponType.C4 to 12,
                WeaponType.ARTILLERY to 4,
                WeaponType.WRECKING_BALL to 12
            )
        ),
        DemolitionMission(
            id = "midtown_apartments",
            title = "Midtown Renewal",
            briefing = "Clear the multi-story reinforced Midtown Apartment residential blocks to open the West Boulevard corridor.",
            targetTag = "APARTMENTS",
            targetLabel = "Midtown Apartments",
            requiredDestructionPct = 65,
            parScore = 5800,
            ammoBudgets = mapOf(
                WeaponType.CARBINE to 220,
                WeaponType.HEAVY_RIFLE to 44,
                WeaponType.RPG to 24,
                WeaponType.C4 to 16,
                WeaponType.ARTILLERY to 6,
                WeaponType.WRECKING_BALL to 16
            )
        ),
        DemolitionMission(
            id = "stadium_takedown",
            title = "Stadium Takedown",
            briefing = "Collapse the Municipal Stadium grandstands, cantilevered press canopies, and perimeter floodlight towers.",
            targetTag = "STADIUM",
            targetLabel = "Municipal Stadium",
            requiredDestructionPct = 65,
            parScore = 5400,
            ammoBudgets = mapOf(
                WeaponType.CARBINE to 180,
                WeaponType.HEAVY_RIFLE to 38,
                WeaponType.RPG to 20,
                WeaponType.C4 to 14,
                WeaponType.ARTILLERY to 5,
                WeaponType.WRECKING_BALL to 14
            )
        ),
        DemolitionMission(
            id = "rail_yard_blitz",
            title = "Rail Yard Blitz",
            briefing = "Detonate the freight tanker cars, gantry crane, and dispatch shed across the East Bank Freight Rail Yard.",
            targetTag = "RAIL_YARD",
            targetLabel = "Freight Rail Yard",
            requiredDestructionPct = 70,
            parScore = 4400,
            ammoBudgets = mapOf(
                WeaponType.CARBINE to 150,
                WeaponType.HEAVY_RIFLE to 30,
                WeaponType.RPG to 16,
                WeaponType.C4 to 10,
                WeaponType.ARTILLERY to 4,
                WeaponType.WRECKING_BALL to 12
            )
        )
    )
}
