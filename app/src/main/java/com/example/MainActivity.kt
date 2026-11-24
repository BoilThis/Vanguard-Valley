package com.example

import android.opengl.GLSurfaceView
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.engine.render.ValleyRenderer
import com.example.ui.DemolitionOpsOverlay
import com.example.ui.SandboxViewModel
import com.example.ui.ValleyHudOverlay
import com.example.ui.WorldStudioSheet
import com.example.ui.theme.VanguardValleyTheme

class MainActivity : ComponentActivity() {

    private val viewModel: SandboxViewModel by viewModels()
    private var glSurfaceView: GLSurfaceView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            VanguardValleyTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            GLSurfaceView(context).apply {
                                setEGLContextClientVersion(2)
                                setRenderer(
                                    ValleyRenderer(
                                        snapshotProvider = { viewModel.currentSnapshot() },
                                        onFpsUpdate = { viewModel.onFpsMeasured(it) }
                                    )
                                )
                                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                                glSurfaceView = this
                            }
                        }
                    )

                    ValleyHudOverlay(
                        state = uiState,
                        onMoveInput = viewModel::setMoveInput,
                        onLookDelta = viewModel::addLookDelta,
                        onJump = viewModel::requestJump,
                        onDroneVertical = viewModel::setDroneVertical,
                        onToggleSprint = viewModel::toggleSprint,
                        onCycleCamera = viewModel::cycleCameraMode,
                        onSelectWeapon = viewModel::selectWeapon,
                        onSelectBrush = viewModel::selectBrush,
                        onSelectBuildMaterial = viewModel::selectBuildMaterial,
                        onSelectToolMode = viewModel::setInteractionTool,
                        onFirePrimary = viewModel::firePrimaryAction,
                        onAutoFireChange = viewModel::setAutoFire,
                        onDetonateC4 = viewModel::detonateAllC4,
                        onToggleGameMode = viewModel::toggleGameMode,
                        onOpenOpsDialog = { viewModel.openOpsDialog(true) },
                        onToggleMute = viewModel::toggleAudioMute,
                        onOpenStudio = { viewModel.openWorldStudio(true) },
                        onResetValley = viewModel::resetValley
                    )

                    if (uiState.isWorldStudioOpen) {
                        WorldStudioSheet(
                            initialConfig = uiState.worldConfig,
                            onDismiss = { viewModel.openWorldStudio(false) },
                            onPreviewLighting = viewModel::previewLightingPreset,
                            onJumpToDistrict = viewModel::jumpToDistrict,
                            onApplyAndRebuild = viewModel::applyWorldConfig
                        )
                    }

                    if (uiState.isOpsDialogOpen) {
                        DemolitionOpsOverlay(
                            activeProgress = uiState.activeMission,
                            savedStars = uiState.missionStars,
                            onStartMission = viewModel::startMission,
                            onDismiss = { viewModel.openOpsDialog(false) }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView?.onResume()
    }

    override fun onPause() {
        glSurfaceView?.onPause()
        super.onPause()
    }
}
