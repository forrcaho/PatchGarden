package io.github.forrcaho.patchgarden

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * The PatchGarden folder, as far as it can be tested off a phone: moving between two homes,
 * which is the same code whatever the homes are, and the libraries reading through [Folder].
 * The chosen folder's own half -- a Storage Access Framework tree -- is only on a device.
 */
class FilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun home(name: String) = DirHome(temp.newFolder(name))

    @Test
    fun `a move carries what is not there yet, and leaves what is there on both sides`() {
        val from = home("app")
        val to = home("chosen")
        from.folder(Folders.SUBPATCHES)!!.write("Bass.json", "bass".toByteArray())
        from.folder(Folders.SUBPATCHES)!!.write("Lead.json", "old lead".toByteArray())
        from.folder(Folders.SOUNDFONTS)!!.write("Piano.sf2", ByteArray(300_000) { it.toByte() })
        to.folder(Folders.SUBPATCHES)!!.write("Lead.json", "new lead".toByteArray())

        val files = movable(from, to)
        assertEquals(
            mapOf(Folders.SOUNDFONTS to listOf("Piano.sf2"), Folders.SUBPATCHES to listOf("Bass.json")),
            files.mapValues { it.value.sorted() },
        )
        assertEquals("1 SoundFont and 1 saved subpatch", describeMove(files))

        assertEquals(2, move(from, to, files))
        assertEquals("bass", to.folder(Folders.SUBPATCHES)!!.read("Bass.json")!!.decodeToString())
        assertEquals(300_000L, to.folder(Folders.SOUNDFONTS)!!.length("Piano.sf2"))
        assertFalse("moved, not copied", from.folder(Folders.SUBPATCHES)!!.exists("Bass.json"))
        assertEquals(
            "the one already there is not replaced", "new lead",
            to.folder(Folders.SUBPATCHES)!!.read("Lead.json")!!.decodeToString(),
        )
        assertEquals(
            "and the one it would have replaced is not deleted", "old lead",
            from.folder(Folders.SUBPATCHES)!!.read("Lead.json")!!.decodeToString(),
        )
        assertTrue("nothing left to move", movable(from, to).isEmpty())
    }

    /** Copy, check, then delete: a copy that did not land must not cost the original. */
    @Test
    fun `a file that cannot be copied is left where it was`() {
        val from = home("app")
        from.folder(Folders.SCALES)!!.write("Odd.scl", "! odd".toByteArray())
        val nowhere = object : Home {
            override val label = "nowhere"
            override val chosen = true
            override fun folder(name: String): Folder = object : Folder by DirFolder(temp.newFolder()) {
                override fun output(name: String, mime: String) = null
            }
        }
        assertEquals(0, move(from, nowhere, movable(from, nowhere)))
        assertTrue(from.folder(Folders.SCALES)!!.exists("Odd.scl"))
    }

    @Test
    fun `the libraries read through the folder`() {
        val home = home("home")
        val library = SubpatchLibrary.load(home)
        assertTrue(library.write("Bass", "{}"))
        assertEquals(listOf("Bass"), library.names())
        assertTrue(File(temp.root, "home/subpatches/Bass.json").isFile)
        home.folder(Folders.SOUNDFONTS)!!.write("GM.sf2", ByteArray(4))
        home.folder(Folders.SOUNDFONTS)!!.write("notes.txt", ByteArray(4))
        assertEquals("only what is a SoundFont", listOf("GM"), SoundFontLibrary.load(home).names())
    }

    @Test
    fun `a chosen folder is named the way a person would say where it is`() {
        assertEquals("Music/PatchGarden", describeTreeId("primary:Music/PatchGarden"))
        assertEquals("Internal storage", describeTreeId("primary:"))
        assertEquals("a card keeps its volume", "1A2B-3C4D/PatchGarden", describeTreeId("1A2B-3C4D:PatchGarden"))
    }
}

/**
 * Saving the recording: the engine's window, read back oldest first and written as a WAV. The
 * window here is made the way recorder.cpp makes one -- header, then frames at their place in
 * the circle -- and recorder_test checks that the engine does make it that way.
 */
class RecordingTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** A window of [capacity] frames, [window] of them readable, holding frames 0 until [total]. */
    private fun windowFile(capacity: Long, window: Long, total: Long, sample: (Long) -> Float): File {
        val file = temp.newFile()
        RandomAccessFile(file, "rw").use { raf ->
            val header = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
            header.put("PGREC01".toByteArray()).put(0)
            header.putInt(1000).putInt(2).putLong(capacity).putLong(window).putLong(total)
            raf.write(header.array())
            raf.setLength(64 + capacity * 8)
            val frame = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            for (f in 0 until total) {
                frame.clear()
                frame.putFloat(sample(f)).putFloat(-sample(f))
                raf.seek(64 + (f % capacity) * 8)
                raf.write(frame.array())
            }
        }
        return file
    }

    private fun wav(file: File, depth: BitDepth): Pair<Long, ByteBuffer> {
        val header = readRecordingHeader(file)!!
        val out = ByteArrayOutputStream()
        val frames = exportRecording(file, out, depth, header)
        return frames to ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
    }

    @Test
    fun `the window reads back oldest first across the wrap, as float`() {
        // 2500 frames through a circle of 1200, the last 1000 readable.
        val file = windowFile(1200, 1000, 2500) { f -> f / 4096f }
        val header = readRecordingHeader(file)!!
        assertEquals(1000L, header.available)
        assertEquals(1.0, header.seconds, 1e-9)

        val (frames, b) = wav(file, BitDepth.FLOAT32)
        assertEquals(1000L, frames)
        assertEquals("RIFF", String(ByteArray(4).also { b.get(0, it) }))
        assertEquals("IEEE float", 3, b.getShort(20).toInt())
        assertEquals(2, b.getShort(22).toInt())
        assertEquals(1000, b.getInt(24))
        assertEquals(32, b.getShort(34).toInt())
        assertEquals("fact", String(ByteArray(4).also { b.get(36, it) }))
        assertEquals("data", String(ByteArray(4).also { b.get(48, it) }))
        assertEquals(8000, b.getInt(52))
        assertEquals("RIFF size", b.capacity() - 8, b.getInt(4))
        for (i in 0 until 1000) {
            val f = 1500 + i
            assertEquals("frame $f left", f / 4096f, b.getFloat(56 + i * 8), 0f)
            assertEquals("frame $f right", -f / 4096f, b.getFloat(60 + i * 8), 0f)
        }
    }

    @Test
    fun `it starts where the sound starts, and keeps a silence in the middle`() {
        // Silent until 400, sound until 600, silent again until 800, sound after.
        val file = windowFile(2000, 1000, 1000) { f -> if (f in 400 until 600 || f >= 800) 0.5f else 0f }
        val (frames, b) = wav(file, BitDepth.PCM24)
        assertEquals("from 400 to the end", 600L, frames)
        assertEquals(1, b.getShort(20).toInt())
        assertEquals(24, b.getShort(34).toInt())
        assertEquals(600 * 6, b.getInt(40))
        fun s24(at: Int) = (b.get(at).toInt() and 0xFF) or ((b.get(at + 1).toInt() and 0xFF) shl 8) or (b.get(at + 2).toInt() shl 16)
        assertEquals((0.5f * 8388607f).roundToInt(), s24(44))
        assertEquals((-0.5f * 8388607f).roundToInt(), s24(47))
        assertEquals("the gap in the middle is kept", 0, s24(44 + 200 * 6))
    }

    @Test
    fun `nothing but silence saves nothing`() {
        val file = windowFile(2000, 1000, 1000) { 0f }
        val (frames, _) = wav(file, BitDepth.PCM16)
        assertEquals(0L, frames)
    }

    @Test
    fun `sixteen bits is dithered by a step at most, and clipped rather than wrapped`() {
        val file = windowFile(2000, 1000, 1000) { f -> if (f < 500) 0.25f else 1.5f }
        val (frames, b) = wav(file, BitDepth.PCM16)
        assertEquals(1000L, frames)
        assertEquals(16, b.getShort(34).toInt())
        val quarter = (0.25f * 32767f).roundToInt()
        for (i in 0 until 500) assertTrue("within a step of a quarter", kotlin.math.abs(b.getShort(44 + i * 4) - quarter) <= 1)
        for (i in 500 until 1000) {
            assertTrue("over full scale is full scale", b.getShort(44 + i * 4) >= 32766)
            assertTrue("and under it, under", b.getShort(46 + i * 4) <= -32766)
        }
    }

    @Test
    fun `a file that is not a window is not read as one`() {
        assertNull(readRecordingHeader(temp.newFile().also { it.writeText("hello") }))
        assertNull(readRecordingHeader(File(temp.root, "missing")))
    }

    @Test
    fun `the settings say how long and how big`() {
        assertEquals("7:32", minutesAndSeconds(452.9))
        assertEquals("0:04", minutesAndSeconds(4.0))
        assertEquals(0, recordingMegabytes(0))
        assertEquals("ten minutes and the margin, float stereo at 48kHz", 241, recordingMegabytes(10))
        assertEquals(DEFAULT_RECORDING_MINUTES, 10)
        assertTrue(0 in RECORDING_MINUTES)
    }
}
