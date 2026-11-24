package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.engine.sim.DemolitionOpsCatalog
import com.example.model.DemolitionMission
import com.example.model.MissionProgress
import com.example.ui.theme.AmberSignal
import com.example.ui.theme.EmeraldReady
import com.example.ui.theme.InkPrimary
import com.example.ui.theme.InkSecondary
import com.example.ui.theme.RiverCyan
import com.example.ui.theme.TacticalBorder
import com.example.ui.theme.TacticalPanelBg
import com.example.ui.theme.TacticalPanelElevated

@Composable
fun DemolitionOpsOverlay(
    activeProgress: MissionProgress?,
    savedStars: Map<String, Int>,
    onStartMission: (DemolitionMission) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99080B0E))
            .clickable(onClick = onDismiss)
            .safeDrawingPadding()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.88f)
                .clickable(enabled = false, onClick = {})
                .border(1.dp, TacticalBorder, RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            color = TacticalPanelBg,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.MilitaryTech,
                            contentDescription = null,
                            tint = AmberSignal,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "DEMOLITION OPS",
                            style = MaterialTheme.typography.headlineSmall,
                            color = InkPrimary
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("close_ops_dialog_button")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close Demolition Ops", tint = InkSecondary)
                    }
                }

                Text(
                    text = "Select a structural contract. Complete targets within the tactical ordnance budget to earn 1–3 stars.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkSecondary
                )

                Spacer(Modifier.height(14.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    DemolitionOpsCatalog.missions.forEach { mission ->
                        val isCurrent = activeProgress?.mission?.id == mission.id
                        val bestStars = savedStars[mission.id] ?: 0

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(TacticalPanelElevated)
                                .border(
                                    1.dp,
                                    if (isCurrent) AmberSignal else TacticalBorder,
                                    RoundedCornerShape(14.dp)
                                )
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = mission.title.uppercase(),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (isCurrent) AmberSignal else InkPrimary
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    for (s in 1..3) {
                                        Icon(
                                            imageVector = if (s <= bestStars) Icons.Filled.Star else Icons.Outlined.StarOutline,
                                            contentDescription = null,
                                            tint = if (s <= bestStars) AmberSignal else InkSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }

                            Text(
                                text = mission.briefing,
                                style = MaterialTheme.typography.bodyMedium,
                                color = InkSecondary
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "TARGET: ${mission.targetLabel} (${mission.requiredDestructionPct}%)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = RiverCyan
                                    )
                                    Text(
                                        text = "PAR SCORE: ${mission.parScore} PTS",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = EmeraldReady
                                    )
                                }

                                Button(
                                    onClick = { onStartMission(mission) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isCurrent) EmeraldReady else AmberSignal,
                                        contentColor = Color(0xFF0E1318)
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .height(44.dp)
                                        .testTag("ops_mission_${mission.id}")
                                ) {
                                    Text(
                                        text = if (isCurrent) "REPLAY" else "DEPLOY",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
