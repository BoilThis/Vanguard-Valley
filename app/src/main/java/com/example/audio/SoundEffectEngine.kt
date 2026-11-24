package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

enum class SoundCue {
    CARBINE_SHOT,
    HEAVY_RIFLE_SHOT,
    RPG_LAUNCH,
    C4_BEEP,
    EXPLOSION,
    GLASS_SHATTER,
    PLASTIC_SNAP,
    WOOD_SPLINTER,
    METAL_RICOCHET,
    COLLAPSE_RUMBLE,
    WATER_SPLASH,
    OBJECTIVE_CHIME
}

/**
 * Pure-Kotlin procedural 16-bit PCM WAV synthesizer backed by Android SoundPool.
 * Safe in headless/JVM test environments via runCatching guards.
 */
class SoundEffectEngine(context: Context) {

    companion object {
        private const val SAMPLE_RATE = 22050
    }

    private var soundPool: SoundPool? = null
    private val soundIds = LinkedHashMap<SoundCue, Int>()
    private val lastPlayedMs = LinkedHashMap<SoundCue, Long>()
    @Volatile
    var isMuted: Boolean = false

    init {
        runCatching {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val pool = SoundPool.Builder()
                .setMaxStreams(10)
                .setAudioAttributes(attrs)
                .build()
            soundPool = pool

            val cacheDir = File(context.cacheDir, "proc_sfx").apply { mkdirs() }
            for (cue in SoundCue.entries) {
                val samples = synthesizeCue(cue)
                val wavFile = File(cacheDir, "${cue.name.lowercase()}.wav")
                writeWavFile(wavFile, samples)
                soundIds[cue] = pool.load(wavFile.absolutePath, 1)
            }
        }
    }

    fun play(cue: SoundCue, distanceMeters: Float = 0f, gain: Float = 1.0f) {
        if (isMuted) return
        val pool = soundPool ?: return
        val sid = soundIds[cue] ?: return

        val now = System.currentTimeMillis()
        val minCooldownMs = when (cue) {
            SoundCue.CARBINE_SHOT -> 45L
            SoundCue.GLASS_SHATTER, SoundCue.METAL_RICOCHET, SoundCue.WOOD_SPLINTER, SoundCue.PLASTIC_SNAP -> 55L
            SoundCue.COLLAPSE_RUMBLE -> 160L
            else -> 70L
        }
        val prev = lastPlayedMs[cue] ?: 0L
        if (now - prev < minCooldownMs) return
        lastPlayedMs[cue] = now

        val atten = (1f / (1f + (distanceMeters / 26f) * (distanceMeters / 26f))).coerceIn(0.08f, 1.0f)
        val vol = (gain * atten).coerceIn(0.02f, 1.0f)
        val rateJitter = when (cue) {
            SoundCue.CARBINE_SHOT, SoundCue.GLASS_SHATTER, SoundCue.METAL_RICOCHET -> 0.94f + (now % 13) * 0.01f
            else -> 1.0f
        }
        runCatching {
            pool.play(sid, vol, vol, 1, 0, rateJitter)
        }
    }

    fun release() {
        runCatching { soundPool?.release() }
        soundPool = null
        soundIds.clear()
    }

    private fun synthesizeCue(cue: SoundCue): ShortArray {
        val rng = Random(cue.ordinal * 7919 + 31)
        val durationSec = when (cue) {
            SoundCue.CARBINE_SHOT -> 0.14f
            SoundCue.HEAVY_RIFLE_SHOT -> 0.34f
            SoundCue.RPG_LAUNCH -> 0.42f
            SoundCue.C4_BEEP -> 0.16f
            SoundCue.EXPLOSION -> 0.85f
            SoundCue.GLASS_SHATTER -> 0.36f
            SoundCue.PLASTIC_SNAP -> 0.16f
            SoundCue.WOOD_SPLINTER -> 0.28f
            SoundCue.METAL_RICOCHET -> 0.32f
            SoundCue.COLLAPSE_RUMBLE -> 0.78f
            SoundCue.WATER_SPLASH -> 0.45f
            SoundCue.OBJECTIVE_CHIME -> 0.48f
        }
        val count = (SAMPLE_RATE * durationSec).toInt().coerceAtLeast(128)
        val out = ShortArray(count)
        var lpState = 0f

        for (i in 0 until count) {
            val t = i.toFloat() / SAMPLE_RATE
            val noise = rng.nextFloat() * 2f - 1f
            val v = when (cue) {
                SoundCue.CARBINE_SHOT -> {
                    val crack = noise * exp(-t * 38f)
                    val punch = sin(2f * PI.toFloat() * (220f * exp(-t * 24f) + 60f) * t) * exp(-t * 28f)
                    (crack * 0.72f + punch * 0.55f)
                }
                SoundCue.HEAVY_RIFLE_SHOT -> {
                    val crack = noise * exp(-t * 16f)
                    val boom = sin(2f * PI.toFloat() * (140f * exp(-t * 14f) + 42f) * t) * exp(-t * 11f)
                    (crack * 0.65f + boom * 0.78f)
                }
                SoundCue.RPG_LAUNCH -> {
                    lpState = lpState * 0.72f + noise * 0.28f
                    val whoosh = (noise - lpState * 0.4f) * sin((t / durationSec) * PI.toFloat())
                    val ign = sin(2f * PI.toFloat() * 95f * t) * exp(-t * 9f) * 0.35f
                    whoosh * 0.75f + ign
                }
                SoundCue.C4_BEEP -> {
                    val f = if (t < 0.075f) 1480f else 1960f
                    sin(2f * PI.toFloat() * f * t) * exp(-t * 10f) * 0.55f
                }
                SoundCue.EXPLOSION -> {
                    lpState = lpState * 0.90f + noise * 0.10f
                    val sub = sin(2f * PI.toFloat() * (68f * exp(-t * 4.5f) + 26f) * t) * exp(-t * 3.8f)
                    val roar = lpState * exp(-t * 3.2f) * 1.9f
                    val snap = noise * exp(-t * 24f) * 0.5f
                    sub * 0.75f + roar * 0.65f + snap
                }
                SoundCue.GLASS_SHATTER -> {
                    val hpNoise = (noise - lpState) * exp(-t * 11f)
                    lpState = noise
                    val shard1 = sin(2f * PI.toFloat() * 3420f * t) * exp(-t * 16f)
                    val shard2 = sin(2f * PI.toFloat() * 4890f * t) * exp(-t * 20f)
                    val shard3 = sin(2f * PI.toFloat() * 6120f * t) * exp(-t * 22f)
                    hpNoise * 0.42f + (shard1 + shard2 + shard3) * 0.25f
                }
                SoundCue.PLASTIC_SNAP -> {
                    val hollow = sin(2f * PI.toFloat() * (640f * exp(-t * 14f) + 240f) * t) * exp(-t * 24f)
                    val pop = noise * exp(-t * 44f)
                    hollow * 0.68f + pop * 0.48f
                }
                SoundCue.WOOD_SPLINTER -> {
                    val crack = sin(2f * PI.toFloat() * 320f * t) * exp(-t * 22f)
                    val splinters = if ((i % 410) < 60) noise * exp(-t * 9f) else noise * exp(-t * 16f) * 0.4f
                    crack * 0.45f + splinters * 0.65f
                }
                SoundCue.METAL_RICOCHET -> {
                    val fm = sin(2f * PI.toFloat() * 190f * t) * 280f
                    val ping = sin(2f * PI.toFloat() * (2150f * exp(-t * 2.2f) + fm) * t) * exp(-t * 8.5f)
                    val zing = sin(2f * PI.toFloat() * 3280f * t) * exp(-t * 12f)
                    (ping * 0.65f + zing * 0.35f)
                }
                SoundCue.COLLAPSE_RUMBLE -> {
                    lpState = lpState * 0.93f + noise * 0.07f
                    val rumble = lpState * 2.6f * exp(-t * 2.1f)
                    val thud = sin(2f * PI.toFloat() * 52f * t) * exp(-t * 3.5f) * 0.5f
                    rumble * 0.72f + thud
                }
                SoundCue.WATER_SPLASH -> {
                    lpState = lpState * 0.62f + noise * 0.38f
                    val spray = (noise - lpState * 0.3f) * exp(-t * 5.8f)
                    spray * 0.72f
                }
                SoundCue.OBJECTIVE_CHIME -> {
                    val note = if (t < 0.18f) 659.25f else 987.77f
                    val env = exp(-(if (t < 0.18f) t else t - 0.18f) * 7f)
                    sin(2f * PI.toFloat() * note * t) * env * 0.55f
                }
            }
            out[i] = (v.coerceIn(-0.98f, 0.98f) * 32767f).toInt().toShort()
        }
        return out
    }

    private fun writeWavFile(file: File, samples: ShortArray) {
        val dataBytes = samples.size * 2
        val totalSize = 36 + dataBytes
        val buf = ByteArrayOutputStream(44 + dataBytes)
        fun writeAscii(s: String) = s.forEach { buf.write(it.code) }
        fun writeIntLE(v: Int) {
            buf.write(v and 0xFF)
            buf.write((v shr 8) and 0xFF)
            buf.write((v shr 16) and 0xFF)
            buf.write((v shr 24) and 0xFF)
        }
        fun writeShortLE(v: Int) {
            buf.write(v and 0xFF)
            buf.write((v shr 8) and 0xFF)
        }

        writeAscii("RIFF")
        writeIntLE(totalSize)
        writeAscii("WAVE")
        writeAscii("fmt ")
        writeIntLE(16)
        writeShortLE(1) // PCM
        writeShortLE(1) // Mono
        writeIntLE(SAMPLE_RATE)
        writeIntLE(SAMPLE_RATE * 2)
        writeShortLE(2)
        writeShortLE(16)
        writeAscii("data")
        writeIntLE(dataBytes)
        for (s in samples) {
            val v = s.toInt()
            buf.write(v and 0xFF)
            buf.write((v shr 8) and 0xFF)
        }
        FileOutputStream(file).use { it.write(buf.toByteArray()) }
    }
}
