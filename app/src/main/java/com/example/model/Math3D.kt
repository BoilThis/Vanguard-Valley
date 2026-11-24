package com.example.model

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Vec3(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    operator fun div(s: Float) = Vec3(x / s, y / s, z / s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3): Vec3 = Vec3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x
    )

    fun lenSq(): Float = x * x + y * y + z * z
    fun len(): Float = sqrt(lenSq())

    fun normalized(): Vec3 {
        val l = len()
        return if (l > 1e-6f) Vec3(x / l, y / l, z / l) else Vec3(0f, 1f, 0f)
    }

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val UP = Vec3(0f, 1f, 0f)
    }
}

data class Quat(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f, val w: Float = 1f) {
    fun normalized(): Quat {
        val l = sqrt(x * x + y * y + z * z + w * w)
        return if (l > 1e-6f) Quat(x / l, y / l, z / l, w / l) else IDENTITY
    }

    operator fun times(q: Quat): Quat = Quat(
        w * q.x + x * q.w + y * q.z - z * q.y,
        w * q.y - x * q.z + y * q.w + z * q.x,
        w * q.z + x * q.y - y * q.x + z * q.w,
        w * q.w - x * q.x - y * q.y - z * q.z
    )

    fun rotate(v: Vec3): Vec3 {
        val qx = x; val qy = y; val qz = z; val qw = w
        val ix = qw * v.x + qy * v.z - qz * v.y
        val iy = qw * v.y + qz * v.x - qx * v.z
        val iz = qw * v.z + qx * v.y - qy * v.x
        val iw = -qx * v.x - qy * v.y - qz * v.z
        return Vec3(
            ix * qw + iw * -qx + iy * -qz - iz * -qy,
            iy * qw + iw * -qy + iz * -qx - ix * -qz,
            iz * qw + iw * -qz + ix * -qy - iy * -qx
        )
    }

    fun conjugate(): Quat = Quat(-x, -y, -z, w)

    companion object {
        val IDENTITY = Quat(0f, 0f, 0f, 1f)

        fun fromAxisAngle(axis: Vec3, radians: Float): Quat {
            val n = axis.normalized()
            val half = radians * 0.5f
            val s = sin(half)
            return Quat(n.x * s, n.y * s, n.z * s, cos(half)).normalized()
        }

        fun integrateAngular(q: Quat, angVel: Vec3, dt: Float): Quat {
            val speed = angVel.len()
            if (speed < 1e-5f) return q
            val dq = fromAxisAngle(angVel / speed, speed * dt)
            return (dq * q).normalized()
        }
    }
}

fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
fun clamp(v: Float, minV: Float, maxV: Float): Float = when {
    v < minV -> minV
    v > maxV -> maxV
    else -> v
}
fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = clamp((x - edge0) / (edge1 - edge0), 0f, 1f)
    return t * t * (3f - 2f * t)
}
