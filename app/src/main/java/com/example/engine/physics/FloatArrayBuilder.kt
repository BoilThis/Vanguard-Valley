package com.example.engine.physics

import com.example.model.Vec3
import kotlin.math.max

class FloatArrayBuilder(initialFloats: Int = 65536) {
    private var data = FloatArray(initialFloats)
    var size = 0
        private set

    fun reset() {
        size = 0
    }

    private fun ensureCapacity(extra: Int) {
        if (size + extra > data.size) {
            data = data.copyOf(max(data.size * 2, size + extra + 4096))
        }
    }

    fun emitVertex(p: Vec3, n: Vec3, tint: Vec3, u: Float, v: Float, tile: Float, alpha: Float = 1f) {
        ensureCapacity(13)
        var i = size
        data[i++] = p.x; data[i++] = p.y; data[i++] = p.z
        data[i++] = n.x; data[i++] = n.y; data[i++] = n.z
        data[i++] = tint.x; data[i++] = tint.y; data[i++] = tint.z; data[i++] = alpha
        data[i++] = u; data[i++] = v; data[i++] = tile
        size = i
    }

    fun writeInto(dest: FloatArray) {
        data.copyInto(dest, 0, 0, size)
    }

    fun appendFrom(src: FloatArrayBuilder) {
        if (src.size == 0) return
        ensureCapacity(src.size)
        src.data.copyInto(data, size, 0, src.size)
        size += src.size
    }

    fun toFloatArray(): FloatArray = data.copyOf(size)
}

/**
 * Three slots so the GL thread can still be uploading the previous snapshot
 * while the sim fills the next one. Published [floatCount] is the live length;
 * the backing array may be longer and must not be drawn past that count.
 */
class MeshRing(private val slots: Int = 3) {
    private val buffers = arrayOfNulls<FloatArray>(slots)
    private var cursor = 0

    fun publish(builder: FloatArrayBuilder): PublishedMesh {
        val n = builder.size
        if (n == 0) return PublishedMesh.EMPTY
        cursor = (cursor + 1) % slots
        var buf = buffers[cursor]
        if (buf == null || buf.size < n) {
            buf = FloatArray(n)
            buffers[cursor] = buf
        }
        builder.writeInto(buf)
        return PublishedMesh(buf, n)
    }
}

class PublishedMesh(val data: FloatArray, val floatCount: Int) {
    companion object {
        val EMPTY = PublishedMesh(FloatArray(0), 0)
    }
}
