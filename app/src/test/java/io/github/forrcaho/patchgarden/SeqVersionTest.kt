package io.github.forrcaho.patchgarden

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seq versions, roadmap item 4: a phrase's variations as sets of versions per note, edited
 * against the version shown, with "all" for a note in every one.
 */
class SeqVersionTest {

    private fun seq(): Pair<Patch, PatchModule> {
        val patch = Patch()
        return patch to patch.add(Types.Seq, Offset.Zero)!!
    }

    @Test
    fun `a new Seq has one version, and its notes are in it`() {
        val (_, seq) = seq()
        assertEquals(1, seq.versionCount)
        assertEquals(1, seq.shownVersion)
        assertTrue(seq.addSeqNote(SeqNote(0, 0, versions = seq.shownBits)))
        assertEquals(1, seq.seqNotes.single().versions)
        assertEquals("the version playing is 1", 1f, seq.params[Types.Seq.versionParam])
    }

    @Test
    fun `plus copies the version shown, every note common to both`() {
        val (_, seq) = seq()
        seq.addSeqNote(SeqNote(0, 0))
        seq.addSeqNote(SeqNote(2, 4))
        assertTrue(seq.addVersion())
        assertEquals(2, seq.versionCount)
        assertEquals("shown at once", 2, seq.shownVersion)
        assertTrue(seq.seqNotes.all { it.versions == 0b11 })
    }

    /**
     * Viewing a version, a new note is that version's alone; a note it shares is taken out of
     * it, not deleted; and a shared note is split before it is edited, so the others keep it.
     */
    @Test
    fun `edits to a version change that version's notes and no other's`() {
        val (_, seq) = seq()
        seq.addSeqNote(SeqNote(0, 0))
        seq.addSeqNote(SeqNote(2, 4))
        seq.addVersion()

        seq.addSeqNote(SeqNote(5, 1, versions = seq.shownBits))
        assertEquals("a new note is version 2's", 0b10, seq.seqNotes.last().versions)

        seq.removeShownSeqNote(0)
        assertEquals("a shared note leaves version 2 and stays in 1", 0b01, seq.seqNotes[0].versions)

        val edited = seq.ownSeqNote(1)
        assertEquals("split: the original keeps version 1", 0b01, seq.seqNotes[1].versions)
        assertEquals("and the copy to edit is version 2's", 0b10, seq.seqNotes[edited].versions)
        seq.setSeqNoteLength(edited, 8)
        assertEquals("so version 1's note is the length it was", 1, seq.seqNotes[1].length)
        assertEquals("a note version 2 has alone is edited in place", edited, seq.ownSeqNote(edited))

        seq.removeShownSeqNote(seq.seqNotes.indexOfFirst { it.step == 5 })
        assertFalse("a note in this version alone goes", seq.seqNotes.any { it.step == 5 })
    }

    @Test
    fun `a faint note from another version can be taken into this one`() {
        val (_, seq) = seq()
        seq.addVersion()
        seq.shownVersion = 1
        seq.addSeqNote(SeqNote(3, 2, versions = seq.shownBits))
        seq.shownVersion = 2
        assertEquals(-1, seq.seqNoteAt(3, 2))
        val faint = seq.faintSeqNoteAt(3, 2)
        assertTrue(faint >= 0)
        seq.adoptSeqNote(faint)
        assertEquals("common now", 0b11, seq.seqNotes[faint].versions)
        assertEquals(faint, seq.seqNoteAt(3, 2))
    }

    @Test
    fun `the all view edits a note in every version it is in`() {
        val (_, seq) = seq()
        seq.addSeqNote(SeqNote(0, 0))
        seq.addVersion()
        seq.shownVersion = 0
        seq.addSeqNote(SeqNote(1, 1, versions = seq.shownBits))
        assertEquals("a note added in all is in every version", 0b11, seq.seqNotes.last().versions)
        assertEquals("an edit there is in place", 0, seq.ownSeqNote(0))
        seq.removeShownSeqNote(0)
        assertEquals("and removing there removes it from all", 1, seq.seqNotes.size)
    }

    @Test
    fun `a version deleted takes its notes, and the ones above move down`() {
        val (_, seq) = seq()
        seq.addSeqNote(SeqNote(0, 0))
        seq.addVersion()
        seq.addSeqNote(SeqNote(1, 0, versions = seq.shownBits))  // version 2 only
        seq.shownVersion = 1  // so version 3 copies version 1, not 2
        seq.addVersion()
        seq.addSeqNote(SeqNote(2, 0, versions = seq.shownBits))  // version 3 only
        seq.setParam(Types.Seq.versionParam, 3f)
        assertTrue(seq.deleteVersion(2))
        assertEquals(2, seq.versionCount)
        assertFalse("version 2's own note went with it", seq.seqNotes.any { it.step == 1 })
        assertEquals("version 3's is version 2's now", 0b10, seq.seqNotes.single { it.step == 2 }.versions)
        assertEquals("the common one is in both still", 0b11, seq.seqNotes.single { it.step == 0 }.versions)
        assertEquals("the knob follows its version down", 2f, seq.params[Types.Seq.versionParam])
        seq.deleteVersion(2)
        assertFalse("the last version cannot go", seq.deleteVersion(1))
    }

    @Test
    fun `notes in different versions do not block each other`() {
        val (_, seq) = seq()
        seq.addSeqNote(SeqNote(0, 0))
        seq.addVersion()
        seq.removeShownSeqNote(0)  // now version 1's alone
        seq.addSeqNote(SeqNote(3, 0, versions = seq.shownBits))
        assertTrue("version 2's note moves over version 1's", seq.moveSeqNote(1, 0, 0))
    }

    @Test
    fun `versions are saved and read back, byte for byte`() {
        val (patch, seq) = seq()
        seq.addSeqNote(SeqNote(0, 0))
        seq.addVersion()
        seq.addSeqNote(SeqNote(4, 2, versions = seq.shownBits))
        val json = patch.toJson()
        val back = patchFromJson(json)!!
        val read = back.modules.single { it.type == Types.Seq }
        assertEquals(seq.seqNotes.toList(), read.seqNotes.toList())
        assertEquals(2, read.versionCount)
        assertEquals(json, back.toJson())
    }

    @Test
    fun `the engine is sent which versions each note is in`() {
        val (patch, seq) = seq()
        seq.addSeqNote(SeqNote(0, 0))
        seq.addVersion()
        val sent = mutableListOf<Int>()
        val commands = object : GraphCommands by NoCommands {
            override fun setSeqNote(id: Long, slot: Int, step: Int, degree: Int, length: Int, velocity: Float, versions: Int) {
                if (length > 0) sent += versions
            }
        }
        GraphSync(commands).sync(patch)
        assertEquals(listOf(0b11), sent)
    }
}
