package com.example.chess

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import kotlin.math.exp
import kotlin.math.sin

/**
 * Tiny sound engine that synthesizes short tones at runtime (no asset files).
 * Each effect is a pre-rendered 16-bit PCM buffer played on a worker thread.
 */
class SoundFx {

    enum class Type { MOVE, CAPTURE, CHECK, GAMEOVER, CLICK }

    private val rate = 44100
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile var enabled = true

    private val move = synth(90, floatArrayOf(196f, 294f), 26f, 0.04f, 0.9f)
    private val capture = synth(130, floatArrayOf(140f, 150f), 16f, 0.20f, 0.95f)
    private val check = concat(
        synth(110, floatArrayOf(587f), 13f, 0f, 0.7f),
        synth(150, floatArrayOf(880f), 11f, 0f, 0.7f)
    )
    private val gameOver = concat(
        synth(130, floatArrayOf(523f), 9f, 0f, 0.65f),
        synth(130, floatArrayOf(659f), 9f, 0f, 0.65f),
        synth(220, floatArrayOf(784f), 7f, 0f, 0.7f)
    )
    private val click = synth(45, floatArrayOf(880f), 40f, 0f, 0.45f)

    fun play(type: Type) {
        if (!enabled) return
        val buf = when (type) {
            Type.MOVE -> move
            Type.CAPTURE -> capture
            Type.CHECK -> check
            Type.GAMEOVER -> gameOver
            Type.CLICK -> click
        }
        exec.execute { emit(buf) }
    }

    private fun emit(buf: ShortArray) {
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(buf.size * 2)
                .build()
            track.write(buf, 0, buf.size)
            track.play()
            Thread.sleep((buf.size * 1000L / rate) + 60)
            track.release()
        } catch (_: Throwable) {
            // Audio is non-essential; never let it crash the game.
        }
    }

    private fun synth(durMs: Int, freqs: FloatArray, decay: Float, noise: Float, gain: Float): ShortArray {
        val n = rate * durMs / 1000
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toFloat() / rate
            val env = exp((-decay * t).toDouble()).toFloat()
            var s = 0f
            for (f in freqs) s += sin(2.0 * Math.PI * f * t).toFloat()
            s /= freqs.size
            if (noise > 0f) s += ((Math.random().toFloat() * 2f) - 1f) * noise
            val v = (s * env * gain).coerceIn(-1f, 1f)
            out[i] = (v * 32767f).toInt().toShort()
        }
        return out
    }

    private fun concat(vararg parts: ShortArray): ShortArray {
        val total = parts.sumOf { it.size }
        val out = ShortArray(total)
        var off = 0
        for (p in parts) { p.copyInto(out, off); off += p.size }
        return out
    }
}
