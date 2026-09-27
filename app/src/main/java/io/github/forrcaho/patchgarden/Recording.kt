package io.github.forrcaho.patchgarden

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/*
 * Saving the always-on recording: the window the engine keeps on disk (recorder.h), read back
 * oldest first and written out as a WAV in `recordings/`.
 *
 * The engine keeps the samples as the stream received them, floats, and the bit depth is
 * chosen at save -- so a window is never recorded at the wrong one, and a 16-bit copy for a
 * message and a float one for an editor are two saves of the same minutes. Saving does not
 * clear the window.
 */

/** The window's file, in app-private storage: it is the engine's, not anything a person keeps. */
const val RECORDING_FILE = "recording.raw"

/** What the settings offer for the window, in minutes; 0 is off. The default is ten. */
val RECORDING_MINUTES = listOf(0, 1, 5, 10, 30)
const val DEFAULT_RECORDING_MINUTES = 10

/** The bit depths a save can be written at. */
enum class BitDepth(val label: String, val bytes: Int) {
    PCM16("16-bit", 2),
    PCM24("24-bit", 3),
    FLOAT32("32-bit float", 4),
}

/** The header of the engine's window: the recorder's, read as it wrote it. */
data class RecordingHeader(
    val sampleRate: Int,
    val channels: Int,
    val capacity: Long,
    val window: Long,
    val total: Long,
) {
    /** Frames a save can read: the window, or all of it that has been written if less. */
    val available: Long get() = minOf(total, window)
    val seconds: Double get() = available.toDouble() / sampleRate
}

private const val HEADER_BYTES = 64
private val MAGIC = "PGREC01".toByteArray() + 0.toByte()

/** The window's header, or null for no file or one that is not a window. */
fun readRecordingHeader(file: File): RecordingHeader? = try {
    RandomAccessFile(file, "r").use { raf ->
        if (raf.length() < HEADER_BYTES) return null
        val bytes = ByteArray(40)
        raf.readFully(bytes)
        if (!bytes.copyOfRange(0, 8).contentEquals(MAGIC)) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        RecordingHeader(b.getInt(8), b.getInt(12), b.getLong(16), b.getLong(24), b.getLong(32))
            .takeIf { it.sampleRate > 0 && it.channels == 2 && it.capacity > 0 && it.window in 1..it.capacity }
    }
} catch (e: Exception) {
    null
}

/**
 * Below this a sample is silence, for finding where the window's sound starts: about -110dB,
 * under anything that plays but over what the output's fading gain leaves behind.
 */
private const val SILENT = 3e-6f

/**
 * Writes the window in [file] as a WAV to [out] at [depth], and returns how many frames it
 * wrote -- 0 when there was nothing but silence, in which case [out] has nothing useful in it.
 *
 * From the first sound rather than the window's first frame: a window begins wherever it
 * began, and minutes of the output switched off are nobody's recording. Everything after that
 * is kept, silences and all, since a gap in the middle of something is part of it. Read oldest
 * first, which is what lets the engine go on writing during a save: see recorder.h's margin.
 */
fun exportRecording(file: File, out: OutputStream, depth: BitDepth, header: RecordingHeader): Long {
    RandomAccessFile(file, "r").use { raf ->
        val start = header.total - header.available
        val first = firstSound(raf, header, start) ?: return 0L
        val frames = header.total - first
        out.write(wavHeader(header.sampleRate, header.channels, depth, frames))
        val random = Random(0)
        val chunk = 4096
        val raw = ByteArray(chunk * 8)
        val cooked = ByteBuffer.allocate(chunk * header.channels * depth.bytes).order(ByteOrder.LITTLE_ENDIAN)
        var f = first
        while (f < header.total) {
            val n = readFrames(raf, header, f, minOf(chunk.toLong(), header.total - f).toInt(), raw)
            val floats = ByteBuffer.wrap(raw, 0, n * 8).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            cooked.clear()
            repeat(n * 2) { encode(floats.get(), depth, cooked, random) }
            out.write(cooked.array(), 0, cooked.position())
            f += n
        }
        return frames
    }
}

/** The first frame from [start] with any sound in it, or null for none. */
private fun firstSound(raf: RandomAccessFile, header: RecordingHeader, start: Long): Long? {
    val chunk = 4096
    val raw = ByteArray(chunk * 8)
    var f = start
    while (f < header.total) {
        val n = readFrames(raf, header, f, minOf(chunk.toLong(), header.total - f).toInt(), raw)
        val floats = ByteBuffer.wrap(raw, 0, n * 8).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        for (i in 0 until n) {
            if (abs(floats.get(i * 2)) > SILENT || abs(floats.get(i * 2 + 1)) > SILENT) return f + i
        }
        f += n
    }
    return null
}

/** Up to [count] frames from frame [f] into [into], as far as the file runs without wrapping. */
private fun readFrames(raf: RandomAccessFile, header: RecordingHeader, f: Long, count: Int, into: ByteArray): Int {
    val at = f % header.capacity
    val n = minOf(count.toLong(), header.capacity - at).toInt()
    raf.seek(HEADER_BYTES + at * 8)
    raf.readFully(into, 0, n * 8)
    return n
}

private fun encode(sample: Float, depth: BitDepth, out: ByteBuffer, random: Random) {
    when (depth) {
        BitDepth.FLOAT32 -> out.putFloat(sample)
        BitDepth.PCM24 -> {
            val v = (sample.coerceIn(-1f, 1f) * 8388607f).roundToInt()
            out.put(v.toByte())
            out.put((v shr 8).toByte())
            out.put((v shr 16).toByte())
        }
        BitDepth.PCM16 -> {
            // Triangular dither of one step, so a quiet fade ends as noise rather than as the
            // stepped distortion truncating to sixteen bits makes of it.
            val dither = random.nextFloat() - random.nextFloat()
            val v = (sample.coerceIn(-1f, 1f) * 32767f + dither).roundToInt().coerceIn(-32768, 32767)
            out.putShort(v.toShort())
        }
    }
}

/**
 * A canonical WAV header for [frames] frames: PCM for the integer depths, IEEE float with the
 * `fact` chunk the format asks for otherwise.
 */
fun wavHeader(sampleRate: Int, channels: Int, depth: BitDepth, frames: Long): ByteArray {
    val float = depth == BitDepth.FLOAT32
    val dataBytes = frames * channels * depth.bytes
    val fact = if (float) 12 else 0
    val b = ByteBuffer.allocate(44 + fact).order(ByteOrder.LITTLE_ENDIAN)
    b.put("RIFF".toByteArray()).putInt((36 + fact + dataBytes).toInt())
    b.put("WAVE".toByteArray())
    b.put("fmt ".toByteArray()).putInt(16)
    b.putShort(if (float) 3 else 1)
    b.putShort(channels.toShort())
    b.putInt(sampleRate)
    b.putInt(sampleRate * channels * depth.bytes)
    b.putShort((channels * depth.bytes).toShort())
    b.putShort((depth.bytes * 8).toShort())
    if (float) b.put("fact".toByteArray()).putInt(4).putInt(frames.toInt())
    b.put("data".toByteArray()).putInt(dataBytes.toInt())
    return b.array()
}
