package com.example.engine.sim

import com.example.model.CameraMode
import com.example.model.LightingPreset
import com.example.model.Vec3

class ChunkMeshData(
    val chunkIndex: Int,
    val version: Int,
    val vertices: FloatArray
)

class BuildingSectorMeshData(
    val sectorIndex: Int,
    val version: Int,
    val opaqueVertices: FloatArray,
    val glassVertices: FloatArray,
    val minX: Float,
    val minY: Float,
    val minZ: Float,
    val maxX: Float,
    val maxY: Float,
    val maxZ: Float,
    val hasGeometry: Boolean
)

data class DynamicPointLight(
    val pos: Vec3,
    val color: Vec3,
    val radius: Float
)

class RenderSnapshot(
    val chunkMeshes: Array<ChunkMeshData>,
    val terrainVersion: Int,
    val waterVerts: FloatArray,
    val structureVersion: Int,
    val sectorMeshes: Array<BuildingSectorMeshData>,
    val dynamicVerts: FloatArray,
    val dynamicFloatCount: Int = dynamicVerts.size,
    val shockwaveVerts: FloatArray,
    val shockwaveFloatCount: Int = shockwaveVerts.size,
    val particleVerts: FloatArray,
    val particleFloatCount: Int = particleVerts.size,
    val lineVerts: FloatArray,
    val lineFloatCount: Int = lineVerts.size,
    val cameraPos: Vec3,
    val cameraLookDir: Vec3,
    val cameraMode: CameraMode,
    val lightingPreset: LightingPreset,
    val waterTime: Float,
    val shakeIntensity: Float,
    val pointLights: List<DynamicPointLight> = emptyList(),
    val sleepingRubbleVersion: Int = 0,
    val sleepingRubbleVerts: FloatArray = FloatArray(0),
    val snapshotVersion: Int = 0,
    val activeShardCount: Int = 0
)
