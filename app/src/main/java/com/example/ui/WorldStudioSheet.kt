package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.DistrictJumpTarget
import com.example.model.LightingPreset
import com.example.model.WorldConfig
import com.example.ui.theme.AmberSignal
import com.example.ui.theme.InkPrimary
import com.example.ui.theme.InkSecondary
import com.example.ui.theme.TacticalBorder
import com.example.ui.theme.TacticalPanelBg
import com.example.ui.theme.TacticalPanelElevated
import kotlin.math.roundToInt
import kotlin.random.Random

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorldStudioSheet(
    initialConfig: WorldConfig,
    onDismiss: () -> Unit,
    onPreviewLighting: (LightingPreset) -> Unit,
    onJumpToDistrict: (DistrictJumpTarget) -> Unit,
    onApplyAndRebuild: (WorldConfig) -> Unit
) {
    var draft by remember { mutableStateOf(initialConfig) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99080B0E))
            .clickable(onClick = onDismiss)
            .safeDrawingPadding()
            .padding(16.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 390.dp)
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.95f)
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
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = AmberSignal,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "WORLD STUDIO",
                            style = MaterialTheme.typography.headlineSmall,
                            color = InkPrimary
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("close_world_studio_button")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close World Studio", tint = InkSecondary)
                    }
                }

                Text(
                    text = "Customize terrain, zone density, and atmospheric lighting before testing weapons.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkSecondary
                )

                Spacer(Modifier.height(14.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "ATMOSPHERE & TIME OF DAY",
                        style = MaterialTheme.typography.labelMedium,
                        color = AmberSignal
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LightingPreset.entries.forEach { preset ->
                            val selected = draft.lightingPreset == preset
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) AmberSignal else TacticalPanelElevated)
                                    .border(
                                        1.dp,
                                        if (selected) AmberSignal else TacticalBorder,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .clickable {
                                        draft = draft.copy(lightingPreset = preset)
                                        onPreviewLighting(preset)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                                    .testTag("lighting_${preset.name.lowercase()}")
                            ) {
                                Text(
                                    text = preset.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) Color(0xFF0E1318) else InkPrimary
                                )
                            }
                        }
                    }

                    Text(
                        text = "DISTRICT QUICK-JUMP",
                        style = MaterialTheme.typography.labelMedium,
                        color = AmberSignal
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DistrictJumpTarget.entries.forEach { target ->
                            OutlinedButton(
                                onClick = { onJumpToDistrict(target) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("jump_${target.name.lowercase()}"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    text = target.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = InkPrimary
                                )
                            }
                        }
                    }

                    StudioSlider(
                        label = "River Width",
                        valueText = "${(draft.riverWidth * 100).roundToInt()}%",
                        value = draft.riverWidth,
                        range = 0.6f..1.7f,
                        testTag = "river_width_slider",
                        onValueChange = { draft = draft.copy(riverWidth = it) }
                    )

                    StudioSlider(
                        label = "Pine Forest Density",
                        valueText = "${(draft.forestDensity * 100).roundToInt()}%",
                        value = draft.forestDensity,
                        range = 0.3f..1.7f,
                        testTag = "forest_density_slider",
                        onValueChange = { draft = draft.copy(forestDensity = it) }
                    )

                    StudioSlider(
                        label = "Small Town Scale",
                        valueText = "${(draft.townScale * 100).roundToInt()}%",
                        value = draft.townScale,
                        range = 0.3f..1.0f,
                        testTag = "town_scale_slider",
                        onValueChange = { draft = draft.copy(townScale = it) }
                    )

                    StudioSlider(
                        label = "Army Base Fortification",
                        valueText = "${(draft.baseFortification * 100).roundToInt()}%",
                        value = draft.baseFortification,
                        range = 0.5f..1.5f,
                        testTag = "base_fortification_slider",
                        onValueChange = { draft = draft.copy(baseFortification = it) }
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(TacticalPanelElevated)
                            .border(1.dp, TacticalBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("VALLEY SEED", style = MaterialTheme.typography.labelSmall, color = InkSecondary)
                            Text("#${draft.seed}", style = MaterialTheme.typography.titleMedium, color = InkPrimary)
                        }
                        OutlinedButton(
                            onClick = { draft = draft.copy(seed = Random.nextInt(1000, 9999)) },
                            modifier = Modifier.testTag("randomize_seed_button")
                        ) {
                            Icon(Icons.Default.Casino, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("New Seed")
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                Button(
                    onClick = { onApplyAndRebuild(draft) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("apply_world_config_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AmberSignal,
                        contentColor = Color(0xFF0E1318)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("APPLY & REBUILD VALLEY", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun StudioSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    testTag: String,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = InkPrimary)
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = AmberSignal)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.testTag(testTag),
            colors = SliderDefaults.colors(
                thumbColor = AmberSignal,
                activeTrackColor = AmberSignal,
                inactiveTrackColor = TacticalBorder
            )
        )
    }
}
