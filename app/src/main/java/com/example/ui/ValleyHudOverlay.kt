package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.BrushItem
import com.example.model.BuildMaterial
import com.example.model.CameraMode
import com.example.model.GameMode
import com.example.model.HudUiState
import com.example.model.InteractionTool
import com.example.model.WeaponType
import com.example.ui.theme.AmberSignal
import com.example.ui.theme.CrimsonDetonate
import com.example.ui.theme.EmeraldReady
import com.example.ui.theme.InkPrimary
import com.example.ui.theme.InkSecondary
import com.example.ui.theme.RiverCyan
import com.example.ui.theme.TacticalBorder
import com.example.ui.theme.TacticalPanelBg
import com.example.ui.theme.TacticalPanelElevated
import kotlin.math.hypot

@Composable
fun ValleyHudOverlay(
    state: HudUiState,
    onMoveInput: (Float, Float) -> Unit,
    onLookDelta: (Float, Float) -> Unit,
    onJump: () -> Unit,
    onDroneVertical: (Float) -> Unit,
    onToggleSprint: () -> Unit,
    onCycleCamera: () -> Unit,
    onSelectWeapon: (WeaponType) -> Unit,
    onSelectBrush: (BrushItem) -> Unit,
    onSelectBuildMaterial: (BuildMaterial) -> Unit,
    onSelectToolMode: (InteractionTool) -> Unit,
    onFirePrimary: () -> Unit,
    onAutoFireChange: (Boolean) -> Unit,
    onDetonateC4: () -> Unit,
    onToggleGameMode: () -> Unit,
    onOpenOpsDialog: () -> Unit,
    onToggleMute: () -> Unit,
    onOpenStudio: () -> Unit,
    onResetValley: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Right-half invisible touch-look surface sitting behind HUD buttons
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxWidth(0.62f)
                .fillMaxHeight()
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onLookDelta(dragAmount.x * 0.0042f, -dragAmount.y * 0.0038f)
                    }
                }
        )

        // Center Crosshair
        val crosshairColor = if (state.interactionTool == InteractionTool.BUILD_BRUSH) {
            RiverCyan
        } else {
            AmberSignal
        }
        Canvas(
            modifier = Modifier
                .align(Alignment.Center)
                .size(30.dp)
        ) {
            val c = Offset(size.width * 0.5f, size.height * 0.5f)
            val gap = 4.dp.toPx()
            val arm = 10.dp.toPx()
            drawCircle(color = crosshairColor.copy(alpha = 0.85f), radius = 2.dp.toPx(), center = c)
            drawLine(crosshairColor, Offset(c.x - arm, c.y), Offset(c.x - gap, c.y), strokeWidth = 2.dp.toPx())
            drawLine(crosshairColor, Offset(c.x + gap, c.y), Offset(c.x + arm, c.y), strokeWidth = 2.dp.toPx())
            drawLine(crosshairColor, Offset(c.x, c.y - arm), Offset(c.x, c.y - gap), strokeWidth = 2.dp.toPx())
            drawLine(crosshairColor, Offset(c.x, c.y + gap), Offset(c.x, c.y + arm), strokeWidth = 2.dp.toPx())
        }

        // Top Telemetry, Ops HUD & Action Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                // Left: Zone badge + Physics telemetry
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = TacticalPanelBg,
                    modifier = Modifier.border(1.dp, TacticalBorder, RoundedCornerShape(14.dp))
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Explore,
                                contentDescription = null,
                                tint = AmberSignal,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = state.currentZone.displayName.uppercase(),
                                style = MaterialTheme.typography.titleMedium,
                                color = InkPrimary
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "${state.fps} FPS  •  ${state.activeShardCount} SHARDS  •  ${state.collapsedCount} BROKEN",
                            style = MaterialTheme.typography.labelSmall,
                            color = InkSecondary
                        )
                    }
                }

                // Right: Mode, Mute, Camera, World Studio, Reset
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    HudPillButton(
                        icon = Icons.Default.MilitaryTech,
                        label = state.gameMode.label,
                        contentDesc = stringResource(R.string.cd_game_mode),
                        accent = if (state.gameMode == GameMode.DEMOLITION_OPS) EmeraldReady else InkSecondary,
                        testTag = "game_mode_button",
                        onClick = onToggleGameMode
                    )
                    Surface(
                        shape = CircleShape,
                        color = TacticalPanelBg,
                        modifier = Modifier
                            .size(48.dp)
                            .border(1.dp, TacticalBorder, CircleShape)
                    ) {
                        IconButton(
                            onClick = onToggleMute,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("mute_audio_button")
                        ) {
                            Icon(
                                imageVector = if (state.audioMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = stringResource(R.string.cd_mute_audio),
                                tint = if (state.audioMuted) InkSecondary else EmeraldReady
                            )
                        }
                    }
                    HudPillButton(
                        icon = Icons.Default.CameraAlt,
                        label = state.cameraMode.label,
                        contentDesc = stringResource(R.string.cd_camera_mode),
                        accent = RiverCyan,
                        testTag = "camera_mode_button",
                        onClick = onCycleCamera
                    )
                    HudPillButton(
                        icon = Icons.Default.Tune,
                        label = "STUDIO",
                        contentDesc = stringResource(R.string.cd_world_studio),
                        accent = AmberSignal,
                        testTag = "world_studio_button",
                        onClick = onOpenStudio
                    )
                    Surface(
                        shape = CircleShape,
                        color = TacticalPanelBg,
                        modifier = Modifier
                            .size(48.dp)
                            .border(1.dp, TacticalBorder, CircleShape)
                    ) {
                        IconButton(
                            onClick = onResetValley,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("reset_valley_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.cd_reset_valley),
                                tint = InkPrimary
                            )
                        }
                    }
                }
            }

            // Active Demolition Ops Objective Strip
            val progress = state.activeMission
            if (state.gameMode == GameMode.DEMOLITION_OPS && progress != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = TacticalPanelBg,
                    modifier = Modifier
                        .border(
                            1.dp,
                            if (progress.isCompleted) EmeraldReady else AmberSignal.copy(alpha = 0.65f),
                            RoundedCornerShape(12.dp)
                        )
                        .clickable(onClick = onOpenOpsDialog)
                        .testTag("ops_progress_banner")
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${progress.mission.title.uppercase()} • ${progress.destructionPct}% / ${progress.mission.requiredDestructionPct}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (progress.isCompleted) EmeraldReady else AmberSignal
                            )
                            Text(
                                text = "${state.score} PTS${if (state.comboMultiplier > 1) " (x${state.comboMultiplier})" else ""}",
                                style = MaterialTheme.typography.labelSmall,
                                color = InkPrimary
                            )
                        }
                        LinearProgressIndicator(
                            progress = {
                                (progress.destructionPct.toFloat() / progress.mission.requiredDestructionPct.toFloat())
                                    .coerceIn(0f, 1f)
                            },
                            color = if (progress.isCompleted) EmeraldReady else AmberSignal,
                            trackColor = TacticalBorder,
                            modifier = Modifier
                                .width(240.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                        )
                    }
                }
            }
        }

        // Transient status banner
        AnimatedVisibility(
            visible = state.statusBanner != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .safeDrawingPadding()
                .padding(top = 74.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = TacticalPanelElevated,
                modifier = Modifier.border(1.dp, AmberSignal.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
            ) {
                Text(
                    text = state.statusBanner.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = InkPrimary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }

        // Bottom Controls, Build Material Picker & Ordnance / Brush Dock
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    VirtualThumbstick(onMove = onMoveInput)
                    Surface(
                        shape = CircleShape,
                        color = if (state.isSprinting) AmberSignal else TacticalPanelBg,
                        modifier = Modifier
                            .size(48.dp)
                            .border(1.dp, TacticalBorder, CircleShape)
                            .clickable(onClick = onToggleSprint)
                            .testTag("sprint_toggle_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.DirectionsRun,
                                contentDescription = stringResource(R.string.cd_sprint),
                                tint = if (state.isSprinting) Color(0xFF0E1318) else InkPrimary
                            )
                        }
                    }
                }

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (state.armedC4Count > 0) {
                        Button(
                            onClick = onDetonateC4,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CrimsonDetonate,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("detonate_c4_button")
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "DETONATE (${state.armedC4Count})",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (state.cameraMode == CameraMode.DRONE) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                DroneMoveButton(
                                    icon = Icons.Default.ArrowUpward,
                                    desc = stringResource(R.string.cd_jump),
                                    testTag = "drone_up_button",
                                    onPressChange = { pressed -> onDroneVertical(if (pressed) 1f else 0f) }
                                )
                                DroneMoveButton(
                                    icon = Icons.Default.ArrowDownward,
                                    desc = stringResource(R.string.cd_descend),
                                    testTag = "drone_down_button",
                                    onPressChange = { pressed -> onDroneVertical(if (pressed) -1f else 0f) }
                                )
                            }
                        } else {
                            Surface(
                                shape = CircleShape,
                                color = TacticalPanelBg,
                                modifier = Modifier
                                    .size(52.dp)
                                    .border(1.dp, TacticalBorder, CircleShape)
                                    .clickable(onClick = onJump)
                                    .testTag("jump_button")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowUpward,
                                        contentDescription = stringResource(R.string.cd_jump),
                                        tint = InkPrimary
                                    )
                                }
                            }
                        }

                        // Primary Fire / Place trigger (supports tap + hold-to-fire for Carbine)
                        val isBrush = state.interactionTool == InteractionTool.BUILD_BRUSH
                        Button(
                            onClick = onFirePrimary,
                            shape = CircleShape,
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isBrush) RiverCyan else AmberSignal,
                                contentColor = Color(0xFF0E1318)
                            ),
                            modifier = Modifier
                                .size(76.dp)
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            onAutoFireChange(event.changes.any { it.pressed })
                                        }
                                    }
                                }
                                .testTag("fire_primary_button")
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = if (isBrush) Icons.Default.Build else Icons.Default.LocalFireDepartment,
                                    contentDescription = stringResource(R.string.cd_fire_weapon),
                                    modifier = Modifier.size(24.dp)
                                )
                                Text(
                                    text = if (isBrush) "PLACE" else "FIRE",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }

            // Wall Material Options Bar (shown when Brush Mode + Wall Panel is active)
            if (state.interactionTool == InteractionTool.BUILD_BRUSH && state.selectedBrush == BrushItem.WALL_PANEL) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = TacticalPanelBg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, RiverCyan.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "WALL:",
                            style = MaterialTheme.typography.labelSmall,
                            color = RiverCyan,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                        BuildMaterial.entries.forEach { mat ->
                            val selected = state.selectedBuildMaterial == mat
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) RiverCyan else TacticalPanelElevated)
                                    .border(1.dp, if (selected) RiverCyan else TacticalBorder, RoundedCornerShape(8.dp))
                                    .clickable { onSelectBuildMaterial(mat) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                                    .testTag("material_${mat.name.lowercase()}")
                            ) {
                                Text(
                                    text = mat.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) Color(0xFF0E1318) else InkPrimary
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Weapon / Build Brush Dock
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = TacticalPanelBg,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, TacticalBorder, RoundedCornerShape(16.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val brushMode = state.interactionTool == InteractionTool.BUILD_BRUSH
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (brushMode) RiverCyan else TacticalPanelElevated)
                            .border(
                                1.dp,
                                if (brushMode) RiverCyan else TacticalBorder,
                                RoundedCornerShape(10.dp)
                            )
                            .minimumInteractiveComponentSize()
                            .clickable {
                                onSelectToolMode(
                                    if (brushMode) InteractionTool.WEAPON else InteractionTool.BUILD_BRUSH
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("toggle_tool_mode_button")
                    ) {
                        Text(
                            text = if (brushMode) "BRUSH MODE" else "ARSENAL",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (brushMode) Color(0xFF0E1318) else EmeraldReady
                        )
                    }

                    if (!brushMode) {
                        WeaponType.entries.forEach { weapon ->
                            val selected = state.selectedWeapon == weapon
                            val sub = if (state.gameMode == GameMode.DEMOLITION_OPS) {
                                val rem = state.ordnanceAmmo[weapon] ?: 0
                                "$rem left"
                            } else {
                                weapon.subtitle
                            }
                            DockItemChip(
                                title = weapon.label,
                                subtitle = sub,
                                selected = selected,
                                accent = AmberSignal,
                                testTag = "weapon_${weapon.name.lowercase()}",
                                onClick = { onSelectWeapon(weapon) }
                            )
                        }
                    } else {
                        BrushItem.entries.forEach { brush ->
                            val selected = state.selectedBrush == brush
                            val sub = if (brush == BrushItem.WALL_PANEL) {
                                state.selectedBuildMaterial.label
                            } else {
                                "Tap Place"
                            }
                            DockItemChip(
                                title = brush.label,
                                subtitle = sub,
                                selected = selected,
                                accent = RiverCyan,
                                testTag = "brush_${brush.name.lowercase()}",
                                onClick = { onSelectBrush(brush) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HudPillButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    contentDesc: String,
    accent: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = TacticalPanelBg,
        modifier = Modifier
            .height(48.dp)
            .border(1.dp, TacticalBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = contentDesc, tint = accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = InkPrimary)
        }
    }
}

@Composable
private fun DockItemChip(
    title: String,
    subtitle: String,
    selected: Boolean,
    accent: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) accent.copy(alpha = 0.22f) else TacticalPanelElevated)
            .border(1.dp, if (selected) accent else TacticalBorder, RoundedCornerShape(10.dp))
            .minimumInteractiveComponentSize()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(testTag)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = if (selected) accent else InkPrimary)
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = InkSecondary)
    }
}

@Composable
private fun DroneMoveButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    testTag: String,
    onPressChange: (Boolean) -> Unit
) {
    Surface(
        shape = CircleShape,
        color = TacticalPanelBg,
        modifier = Modifier
            .size(48.dp)
            .border(1.dp, TacticalBorder, CircleShape)
            .clickable { onPressChange(true) }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val anyPressed = event.changes.any { it.pressed }
                        onPressChange(anyPressed)
                    }
                }
            }
            .testTag(testTag)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = desc, tint = RiverCyan)
        }
    }
}

@Composable
private fun VirtualThumbstick(onMove: (Float, Float) -> Unit) {
    var knobOffset by remember { mutableStateOf(Offset.Zero) }
    val maxRadiusDp = 42.dp

    Box(
        modifier = Modifier
            .size(112.dp)
            .pointerInput(Unit) {
                val maxPx = maxRadiusDp.toPx()
                detectDragGestures(
                    onDragEnd = {
                        knobOffset = Offset.Zero
                        onMove(0f, 0f)
                    },
                    onDragCancel = {
                        knobOffset = Offset.Zero
                        onMove(0f, 0f)
                    }
                ) { change, dragAmount ->
                    change.consume()
                    val candidate = knobOffset + dragAmount
                    val len = hypot(candidate.x, candidate.y)
                    val clamped = if (len > maxPx) candidate * (maxPx / len) else candidate
                    knobOffset = clamped
                    onMove(clamped.x / maxPx, -clamped.y / maxPx)
                }
            }
            .testTag("virtual_thumbstick"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width * 0.5f, size.height * 0.5f)
            val outerR = size.minDimension * 0.44f
            drawCircle(color = TacticalPanelBg, radius = outerR, center = center)
            drawCircle(
                color = TacticalBorder,
                radius = outerR,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )
            val knobCenter = center + knobOffset
            drawCircle(color = AmberSignal.copy(alpha = 0.88f), radius = 19.dp.toPx(), center = knobCenter)
        }
    }
}
