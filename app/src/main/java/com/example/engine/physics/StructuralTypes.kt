package com.example.engine.physics

import com.example.model.MaterialSoundKind
import com.example.model.Quat
import com.example.model.Vec3
import kotlin.math.max

data class StructuralBlock(
    val id: Int,
    val poly: ConvexPolyhedron,
    var center: Vec3,
    var orientation: Quat,
    val uvOrigin: Vec3,
    val tileId: Int,
    val tint: Vec3,
    var hp: Float,
    val maxHp: Float,
    var groundAnchored: Boolean,
    val supportIds: MutableList<Int>,
    val isFuelExplosive: Boolean = false,
    val isCoreSupport: Boolean = false,
    val targetTag: String? = null,
    val pointValue: Int = 15,
    val isHydrant: Boolean = false,
    val walkable: Boolean = true,
    val supportGroups: MutableList<MutableList<Int>> = mutableListOf(),
    var checkCantilever: Boolean = false,
    val breachDepth: Int = 0,
    val uvLocalOffset: Vec3 = Vec3.ZERO,
    var baselineLoad: Float = -1f,
    var sustainedLoad: Float = 0f,
    var loadCapacity: Float = -1f
) {
    val baseY: Float get() = center.y - poly.halfExtents.y
}

fun computeInvInertiaLocal(he: Vec3, mass: Float): Vec3 {
    val hx2 = he.x * he.x
    val hy2 = he.y * he.y
    val hz2 = he.z * he.z
    val factor = mass * (1f / 3f)
    val ix = max(0.03f, factor * (hy2 + hz2))
    val iy = max(0.03f, factor * (hx2 + hz2))
    val iz = max(0.03f, factor * (hx2 + hy2))
    return Vec3(1f / ix, 1f / iy, 1f / iz)
}

data class DynamicShard(
    val id: Int,
    val poly: ConvexPolyhedron,
    var center: Vec3,
    var orientation: Quat,
    val uvOrigin: Vec3,
    val tileId: Int,
    val tint: Vec3,
    var vel: Vec3,
    var angVel: Vec3,
    var sleeping: Boolean = false,
    var settleTimer: Float = 0f,
    var age: Float = 0f,
    var canSecondarySplit: Boolean = true,
    val restitution: Float = 0.22f,
    val drag: Float = 1.4f,
    var supportedByBlockId: Int? = null,
    var supportedByShardId: Int? = null,
    var isGroundedContact: Boolean = false,
    val massEstimate: Float = 1.0f,
    val uvLocalOffset: Vec3 = Vec3.ZERO,
    val splitDepth: Int = 0,
    val boundRadius: Float = poly.boundRadius,
    val invInertiaLocal: Vec3 = computeInvInertiaLocal(poly.halfExtents, massEstimate),
    val clusterPieces: List<ClusterPiece>? = null,
    val clusterStrainBudget: Float = 0f,
    var lastManifoldCount: Int = 0,
    var asleepFor: Float = 0f
)

data class SecondaryBlastEvent(
    val pos: Vec3,
    val radius: Float,
    val craterDepth: Float,
    val impulse: Float
)

data class WaterSplashEvent(
    val pos: Vec3,
    val intensity: Float
)

data class BlockRayHit(
    val blockId: Int,
    val hitPoint: Vec3,
    val hitNormal: Vec3,
    val distance: Float
)

data class ShardRayHit(
    val shardId: Int,
    val hitPoint: Vec3,
    val hitNormal: Vec3,
    val distance: Float
)

data class KineticImpactResult(
    val hitAny: Boolean,
    val bounced: Boolean,
    val bounceNormal: Vec3,
    val correctedPos: Vec3?,
    val secondaryBlasts: List<SecondaryBlastEvent>
)

data class ExplosionOutcome(
    val secondaryBlasts: List<SecondaryBlastEvent>,
    val splashes: List<WaterSplashEvent>,
    val destroyedCount: Int,
    val collapsedByCascade: Int,
    val pointsAwarded: Int,
    val brokenSounds: Set<MaterialSoundKind>
)

data class BulletImpactResult(
    val hitPoint: Vec3?,
    val hitNormal: Vec3,
    val hitBlock: Boolean,
    val tileId: Int,
    val soundKind: MaterialSoundKind?,
    val ricochet: Boolean,
    val blockBroken: Boolean,
    val collapsedCascade: Int,
    val pointsAwarded: Int,
    val secondaryBlasts: List<SecondaryBlastEvent>,
    val hydrantBurstPos: Vec3?,
    val hitShard: Boolean = false
)

