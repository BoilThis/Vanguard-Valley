package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.SoundEffectEngine
import com.example.data.SandboxSettingsRepository
import com.example.engine.sim.RenderSnapshot
import com.example.engine.sim.SandboxSimulation
import com.example.model.BrushItem
import com.example.model.BuildMaterial
import com.example.model.DemolitionMission
import com.example.model.DistrictJumpTarget
import com.example.model.GameMode
import com.example.model.HudUiState
import com.example.model.InteractionTool
import com.example.model.WeaponType
import com.example.model.WorldConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SandboxViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo = SandboxSettingsRepository(application)
    private val soundEngine = SoundEffectEngine(application)
    val simulation = SandboxSimulation()

    private val _uiState = MutableStateFlow(HudUiState())
    val uiState: StateFlow<HudUiState> = _uiState.asStateFlow()

    @Volatile
    private var latestFps = 60

    init {
        simulation.soundListener = { cue, dist, gain ->
            soundEngine.play(cue, dist, gain)
        }
        simulation.onMissionCompleted = { missionId, stars ->
            viewModelScope.launch {
                settingsRepo.saveMissionStars(missionId, stars)
            }
        }

        viewModelScope.launch {
            val savedConfig = settingsRepo.worldConfigFlow.first()
            if (savedConfig != simulation.config) {
                simulation.rebuildWorld(savedConfig)
            }
            val muted = settingsRepo.audioMutedFlow.first()
            soundEngine.isMuted = muted
            _uiState.update { it.copy(worldConfig = savedConfig, audioMuted = muted) }
        }

        viewModelScope.launch {
            settingsRepo.missionStarsFlow.collect { starsMap ->
                _uiState.update { it.copy(missionStars = starsMap) }
            }
        }

        // Fixed ~60Hz physics & simulation loop
        viewModelScope.launch(Dispatchers.Default) {
            var lastNs = System.nanoTime()
            while (true) {
                val now = System.nanoTime()
                val dt = (now - lastNs) / 1_000_000_000f
                lastNs = now

                val simHud = simulation.step(dt)
                _uiState.update { current ->
                    simHud.copy(
                        fps = latestFps,
                        audioMuted = soundEngine.isMuted,
                        missionStars = current.missionStars,
                        isWorldStudioOpen = current.isWorldStudioOpen,
                        isOpsDialogOpen = current.isOpsDialogOpen
                    )
                }
                delay(16L)
            }
        }
    }

    fun currentSnapshot(): RenderSnapshot = simulation.latestSnapshot

    fun onFpsMeasured(fps: Int) {
        latestFps = fps
    }

    fun setMoveInput(x: Float, y: Float) {
        simulation.moveInputX = x
        simulation.moveInputY = y
    }

    fun setDroneVertical(v: Float) {
        simulation.droneVertical = v
    }

    fun addLookDelta(dx: Float, dy: Float) {
        simulation.addLookDelta(dx, dy)
    }

    fun requestJump() {
        simulation.requestJump()
    }

    fun toggleSprint() {
        simulation.isSprinting = !simulation.isSprinting
    }

    fun cycleCameraMode() {
        simulation.cycleCameraMode()
    }

    fun selectWeapon(weapon: WeaponType) {
        simulation.interactionTool = InteractionTool.WEAPON
        simulation.selectedWeapon = weapon
    }

    fun selectBrush(brush: BrushItem) {
        simulation.interactionTool = InteractionTool.BUILD_BRUSH
        simulation.selectedBrush = brush
    }

    fun selectBuildMaterial(material: BuildMaterial) {
        simulation.interactionTool = InteractionTool.BUILD_BRUSH
        simulation.selectedBrush = BrushItem.WALL_PANEL
        simulation.selectedBuildMaterial = material
    }

    fun setInteractionTool(tool: InteractionTool) {
        simulation.interactionTool = tool
    }

    fun firePrimaryAction() {
        simulation.firePrimaryAction()
    }

    fun setAutoFire(active: Boolean) {
        simulation.autoFireActive = active
    }

    fun detonateAllC4() {
        simulation.detonateAllC4()
    }

    fun resetValley() {
        if (_uiState.value.gameMode == GameMode.DEMOLITION_OPS) {
            val active = _uiState.value.activeMission?.mission
            if (active != null) {
                simulation.startMission(active)
                return
            }
        }
        simulation.resetDestruction()
    }

    fun toggleGameMode() {
        val next = if (simulation.gameMode == GameMode.FREE_SANDBOX) {
            GameMode.DEMOLITION_OPS
        } else {
            GameMode.FREE_SANDBOX
        }
        simulation.setGameMode(next)
        if (next == GameMode.DEMOLITION_OPS) {
            _uiState.update { it.copy(isOpsDialogOpen = true) }
        }
    }

    fun openOpsDialog(open: Boolean) {
        _uiState.update { it.copy(isOpsDialogOpen = open) }
    }

    fun startMission(mission: DemolitionMission) {
        _uiState.update { it.copy(isOpsDialogOpen = false) }
        simulation.startMission(mission)
    }

    fun toggleAudioMute() {
        val next = !soundEngine.isMuted
        soundEngine.isMuted = next
        _uiState.update { it.copy(audioMuted = next) }
        viewModelScope.launch {
            settingsRepo.setAudioMuted(next)
        }
    }

    fun openWorldStudio(open: Boolean) {
        _uiState.update { it.copy(isWorldStudioOpen = open) }
    }

    fun jumpToDistrict(target: DistrictJumpTarget) {
        _uiState.update { it.copy(isWorldStudioOpen = false) }
        simulation.jumpToDistrict(target)
    }

    fun previewLightingPreset(preset: com.example.model.LightingPreset) {
        val updated = _uiState.value.worldConfig.copy(lightingPreset = preset)
        _uiState.update { it.copy(worldConfig = updated) }
        simulation.setLightingPreset(preset)
        viewModelScope.launch {
            settingsRepo.saveWorldConfig(updated)
        }
    }

    fun applyWorldConfig(newConfig: WorldConfig) {
        _uiState.update { it.copy(worldConfig = newConfig, isWorldStudioOpen = false) }
        viewModelScope.launch {
            settingsRepo.saveWorldConfig(newConfig)
        }
        simulation.rebuildWorld(newConfig)
    }

    override fun onCleared() {
        soundEngine.release()
        super.onCleared()
    }
}
