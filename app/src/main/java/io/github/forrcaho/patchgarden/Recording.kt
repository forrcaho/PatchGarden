package io.github.forrcaho.patchgarden

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * Saving the always-on recording: the window the engine keeps on disk (recorder.h), read back
 * oldest first and written out as a WAV in `recordings/`.
 *
 * The engine keeps the samples as the stream received them, floats, and the bit depth is
 * chosen at save -- so a window is never recorded at the wrong one, and a 16-bit copy for a
 * message and a float one for an editor are two saves of the same minutes. Saving does not
 * clear the window.
 *
 * The writing is native (recording::exportWav, through AudioEngine.exportRecording). It was
 * here first, a sample at a time, and took five minutes on the phone for eight minutes of
 * sound in a debug build -- against storage that writes 360MB a second. What is left here is
 * the header, which the page reads every second to say how much there is.
 */

/** The window's file, in app-private storage: it is the engine's, not anything a person keeps. */
const val RECORDING_FILE = "recording.raw"

/** What the settings offer for the window, in minutes; 0 is off. The default is ten. */
val RECORDING_MINUTES = listOf(0, 1, 5, 10, 30)
const val DEFAULT_RECORDING_MINUTES = 10

/** The bit depths a save can be written at. [code] mirrors recording::Depth in recorder.h. */
enum class BitDepth(val label: String, val code: Int) {
    PCM16("16-bit", 0),
    PCM24("24-bit", 1),
    FLOAT32("32-bit float", 2),
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
