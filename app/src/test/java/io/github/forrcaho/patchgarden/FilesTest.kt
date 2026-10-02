package io.github.forrcaho.patchgarden

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
 * Saving the recording, as far as Kotlin still does it: reading the window's header, which says
 * how much there is to save. The writing is native now, and recorder_test is where the window is
 * read back across its wrap, from where the sound starts, at each bit depth.
 */
class RecordingTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** A window's header as recorder.cpp writes it. */
    private fun windowFile(capacity: Long, window: Long, total: Long): File {
        val file = temp.newFile()
        RandomAccessFile(file, "rw").use { raf ->
            val header = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
            header.put("PGREC01".toByteArray()).put(0)
            header.putInt(1000).putInt(2).putLong(capacity).putLong(window).putLong(total)
            raf.write(header.array())
            raf.setLength(64 + capacity * 8)
        }
        return file
    }

    @Test
    fun `the header says how much a save can read`() {
        val header = readRecordingHeader(windowFile(1200, 1000, 2500))!!
        assertEquals(1000, header.sampleRate)
        assertEquals("the window, once the file has wrapped", 1000L, header.available)
        assertEquals(1.0, header.seconds, 1e-9)
        assertEquals("and all of it before then", 700L, readRecordingHeader(windowFile(1200, 1000, 700))!!.available)
    }

    @Test
    fun `a file that is not a window is not read as one`() {
        assertNull(readRecordingHeader(temp.newFile().also { it.writeText("hello") }))
        assertNull(readRecordingHeader(File(temp.root, "missing")))
    }

    /**
     * BitDepth crosses to C++ as its code, and a mismatch would write a float file under a 16-bit
     * name -- read out of recorder.h, like every other contract across the boundary.
     */
    @Test
    fun `the bit depths mean what recorder h says they mean`() {
        val header = File("src/main/cpp/recorder.h").readText()
        val enum = header.substringAfter("enum class Depth").substringBefore("};")
        mapOf("Pcm16" to BitDepth.PCM16, "Pcm24" to BitDepth.PCM24, "Float32" to BitDepth.FLOAT32).forEach { (cpp, kt) ->
            val value = Regex("""$cpp\s*=\s*(\d+)""").find(enum)?.groupValues?.get(1)?.toInt()
            assertEquals("$cpp in recorder.h", kt.code, value)
        }
        assertEquals(3, BitDepth.entries.size)
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
