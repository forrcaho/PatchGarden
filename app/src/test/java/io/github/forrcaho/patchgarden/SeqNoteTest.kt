package io.github.forrcaho.patchgarden

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The note sequencer's grid and what the file keeps of it. Playing the notes is the node
 * tests'; this is where a finger lands and what it changes.
 */
class SeqNoteTest {

    private val frame = Frame(
        canvas = Size(2404f, 1080f),
        density = 2.4375f,
        insetLeft = 160f,
        insetTop = 54f,
        insetRight = 0f,
        insetBottom = 58f,
    )
    private val d = frame.density
    private val panel = panelRect(frame)

    private fun seq(): Pair<Patch, PatchModule> {
        val patch = Patch()
        return patch to patch.add(Types.Seq, Offset.Zero)!!
    }

    @Test
    fun `a tap lands on the step and degree under it, as many steps as the loop is long`() {
        val (_, seq) = seq()
        val area = panelGrid(panel, d, seq.type)
        listOf(4, 16, 32, 64).forEach { length ->
            seq.setParam(0, length.toFloat())
            assertEquals(length, seqColumns(seq))
            val window = gridWindow(seq, area, d, Scale.Chromatic)
            // As many as fit, the rest a scroll away.
            val view = seqWindow(seq, area, d)
            repeat(view.shown) { k ->
                val at = Offset(area.left + (k + 0.5f) * area.width / view.shown, area.top + 0.5f * area.height / window.rows)
                assertEquals(view.first + k to window.top, panelCellAt(panel, d, seq, at, Scale.Chromatic))
            }
        }
        assertNull("outside the grid is not a cell", panelCellAt(panel, d, seq, Offset(area.left - 5f, area.center.y)))
    }

    @Test
    fun `a note covers every step it lasts, and grows only as far as there is room`() {
        val (_, seq) = seq()
        seq.addSeqNote(SeqNote(2, 5, 3))
        seq.addSeqNote(SeqNote(8, 5, 1))
        seq.addSeqNote(SeqNote(3, 9, 1))
        assertEquals(0, seq.seqNoteAt(2, 5))
        assertEquals(0, seq.seqNoteAt(4, 5))
        assertEquals(-1, seq.seqNoteAt(5, 5))
        assertEquals("another degree is another note", 2, seq.seqNoteAt(3, 9))
        assertEquals("up to the next note at its degree", 6, seq.seqNoteRoom(0))
        assertEquals("and the last runs to the end of the loop", 16 - 8, seq.seqNoteRoom(1))
    }

    /**
     * A note lasts whole steps (Forrest, 2026-10-09). It was quarter steps, so a note could end
     * partway through one, and that read as confusing: a shorter note is a finer step now.
     */
    @Test
    fun `a note lasts whole steps, one at the least`() {
        val (_, seq) = seq()
        seq.addSeqNote(SeqNote(2, 5))
        assertEquals("a new note is one step", 1, seq.seqNotes[0].length)
        seq.setSeqNoteLength(0, 3)
        assertEquals(3, seq.seqNotes[0].stepsSpanned)
        seq.setSeqNoteLength(0, 0)
        assertEquals("never shorter than a step", 1, seq.seqNotes[0].length)

        assertTrue("and Seq has no gate knob", Types.Seq.params.none { it.name == "gate" })
        assertEquals("seven knobs: four of them in the header -- length, interval, versions and the length's unit",
            listOf("len", "transp", "degree", "interval", "version", "versions", "lenBars"), Types.Seq.params.map { it.name })
    }

    /**
     * A long loop shows what fits and scrolls sideways (Forrest chose sixteen bars and a scroll,
     * 2026-10-09): a column never narrower than SEQ_CELL_MIN, and the step under a finger counted
     * from the first one showing.
     */
    @Test
    fun `a long loop shows a window of its steps, and the step under a finger counts from it`() {
        val (_, seq) = seq()
        val area = panelGrid(panel, d, seq.type)
        seq.setParam(0, 256f)
        val window = seqWindow(seq, area, d)
        assertTrue("more steps than fit", window.scrolls)
        assertTrue("no column narrower than the floor", area.width / window.shown >= SEQ_CELL_MIN * d - 0.01f)
        assertEquals(0, seqColumnAt(area, window, area.left + 1f))
        seq.seqScroll = 40
        val scrolled = seqWindow(seq, area, d)
        assertEquals(40, scrolled.first)
        assertEquals("from the first step showing", 40, seqColumnAt(area, scrolled, area.left + 1f))
        assertEquals("and past either edge holds at what shows", 40 + scrolled.shown - 1, seqColumnAt(area, scrolled, area.right + 99f))
        seq.seqScroll = 9999
        assertEquals("scrolled no further than the end", scrolled.maxFirst, seqWindow(seq, area, d).first)

        seq.setParam(0, 8f)
        assertFalse("a short loop fits whole", seqWindow(seq, area, d).scrolls)
    }

    @Test
    fun `a sequencer holds so many notes and no more`() {
        val (_, seq) = seq()
        repeat(MAX_SEQ_NOTES) { assertTrue(seq.addSeqNote(SeqNote(it % SEQ_STEPS, it / SEQ_STEPS))) }
        assertFalse(seq.addSeqNote(SeqNote(0, 99)))
        assertEquals(MAX_SEQ_NOTES, seq.seqNotes.size)
    }

    @Test
    fun `notes round-trip through the file, and a file off the grid is clamped onto it`() {
        val (patch, seq) = seq()
        seq.addSeqNote(SeqNote(0, 0, 4))
        seq.addSeqNote(SeqNote(31, -3, 1, 0.25f))
        val json = patch.toJson()
        assertTrue(json.contains("\"version\":24"))
        assertEquals(seq.seqNotes.toList(), patchFromJson(json)!!.modules.first { it.type == Types.Seq }.seqNotes.toList())

        // The fifth number is which versions the note is in; bits past the eight there can be
        // are dropped, and a note left in none is put in the first rather than lost.
        val wild = json.replace("[31,-3,1,0.25,1]", "[99,-3,500,7,512]")
        assertEquals(
            SeqNote(99, -3, 500, 1f),
            patchFromJson(wild)!!.modules.first { it.type == Types.Seq }.seqNotes[1],
        )
    }

    /**
     * The additive run that let 11, 12 and 13 all read a 10 file ended at 14.
     *
     * This test read a version-10 file and asserted every note came back at full, which was
     * right for as long as velocity was the only thing that had changed. Format 14 makes an
     * envelope segments, and an ADSR cannot be restated as segments -- only converted -- so
     * 14 reads nothing but 14 and the older file is refused whole. Kept as a refusal rather
     * than deleted, because "this version is not read" is the assertion that stops the
     * additive habit creeping back in.
     *
     * Asserted for every version this build used to accept, so raising FORMAT_VERSION
     * without thinking about the ladder fails here. 15 to 18 joined it with 19, which reads
     * nothing older because compatibility is dropped wherever that is an option while the app
     * is in development (CLAUDE.md).
     */
    @Test
    fun `a file from before segments is refused, not converted`() {
        val (patch, seq) = seq()
        seq.addSeqNote(SeqNote(2, 5, 6, 0.3f))
        val current = patch.toJson()
        assertNotNull("the current version still opens", patchFromJson(current))
        (10..20).forEach { version ->
            val older = current.replace("\"version\":24", "\"version\":$version")
            assertNull("format $version must be refused", patchFromJson(older))
        }
    }

    @Test
    fun `a note's velocity is set by a drag, relative to where it already was`() {
        val (_, seq) = seq()
        seq.addSeqNote(SeqNote(0, 0))
        assertEquals("a new note is struck at full", 1f, seq.seqNotes[0].velocity)

        seq.setSeqNoteVelocity(0, 0.5f)
        assertEquals(0.5f, seq.seqNotes[0].velocity)

        // Never to silence: a note at zero would draw and take its step with no way to tell
        // it was there, and a note nobody wants is removed with a tap.
        seq.setSeqNoteVelocity(0, -2f)
        assertEquals(MIN_VELOCITY, seq.seqNotes[0].velocity)
        seq.setSeqNoteVelocity(0, 9f)
        assertEquals(1f, seq.seqNotes[0].velocity)
    }

    @Test
    fun `a note moves to another step and degree, and stops at what is in the way`() {
        val (_, seq) = seq()
        seq.addSeqNote(SeqNote(0, 0, 2))
        seq.addSeqNote(SeqNote(6, 4, 1))

        assertTrue(seq.moveSeqNote(0, 3, 4))
        assertEquals(SeqNote(3, 4, 2), seq.seqNotes[0])

        // Two notes at one degree cannot overlap -- the second's start would be heard as
        // nothing -- so the move is refused and the note stays where the finger last left it.
        assertFalse("into the one at step 6", seq.moveSeqNote(0, 5, 4))
        assertEquals(SeqNote(3, 4, 2), seq.seqNotes[0])
        assertTrue("but past it is fine", seq.moveSeqNote(0, 7, 4))

        // Off the end of the loop holds at the last step rather than leaving the grid.
        seq.moveSeqNote(0, 99, 4)
        assertEquals(seqColumns(seq) - 1, seq.seqNotes[0].step)
    }

    @Test
    fun `the lock is view state, and changes no patch`() {
        val (patch, seq) = seq()
        seq.addSeqNote(SeqNote(0, 0))
        val before = patch.toJson()
        seq.notesLocked = true
        assertEquals("a lock is not part of the instrument", before, patch.toJson())
    }

    @Test
    fun `a duplicate and an undo keep the notes`() {
        val (patch, seq) = seq()
        seq.addSeqNote(SeqNote(1, 2, 3))
        assertEquals(listOf(SeqNote(1, 2, 3)), patch.duplicate(seq)!!.seqNotes.toList())

        val before = patch.toJson()
        seq.removeSeqNote(0)
        patch.replaceWith(patchFromJson(before)!!)
        assertEquals(listOf(SeqNote(1, 2, 3)), patch.module(seq.id)!!.seqNotes.toList())
        assertEquals("the undo is byte for byte", before, patch.toJson())
    }

    /** The patterns node_test checks EuclidNode::hit against, so the drawing and the sound agree. */
    @Test
    fun `a Euclid draws the pattern it plays`() {
        fun pattern(steps: Int, pulses: Int, rotate: Int) =
            (0 until steps).joinToString("") { if (euclidHit(it, steps, pulses, rotate)) "x" else "." }
        assertEquals("x..x..x.", pattern(8, 3, 0))
        assertEquals(5, pattern(8, 5, 0).count { it == 'x' })
        assertEquals("the same turned by one, as node_test has it", "..x..x.x", pattern(8, 3, 1))
        assertEquals("the rhythm, not a pitch grid: nothing to tap", null,
            Patch().add(Types.Euclid, Offset.Zero)!!.let { panelCellAt(panel, d, it, panelGrid(panel, d, it.type).center) })
    }

    @Test
    fun `a note is named with its octave, in the key it sounds in`() {
        assertEquals("C4", noteWithOctave(0f))
        assertEquals("C3", noteWithOctave(-1200f))
        assertEquals("B3", noteWithOctave(-100f))
        assertEquals("off the twelve-tone grid it says so", "\u2248C4", noteWithOctave(20f))
        assertEquals("C3", degreeName(-12, Scale.Chromatic, 0f))
        assertEquals("G4", degreeName(7, Scale.Chromatic, 0f))
        assertEquals("a key of D moves every name", "E4", degreeName(2, Scale.Chromatic, 200f))
        assertTrue("Euclid's degree is read as a note", Types.Euclid.params.first { it.name == "degree" }.degree)
    }

    @Test
    fun `Seq took Steps' place in the menu, and Steps still loads`() {
        assertTrue(Types.Seq in Types.palette)
        assertTrue("not offered", Types.Steps !in Types.palette)
        val patch = fixturePatch()
        assertTrue("the demo patch has one", patch.modules.any { it.type == Types.Steps })
        val back = patchFromJson(patch.toJson())!!
        assertEquals("and it comes back", patch.modules.count { it.type == Types.Steps }, back.modules.count { it.type == Types.Steps })
        assertEquals("with its sequence", patch.modules.first { it.type == Types.Steps }.steps.toList(), back.modules.first { it.type == Types.Steps }.steps.toList())
    }
}
