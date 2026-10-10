package io.github.forrcaho.patchgarden

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Seq reworked on Forrest's notes of 2026-10-09: whole-step notes, a length in beats or bars,
 * notes carried across a change of step, and versions drawn and chosen per note.
 */
class SeqTimingTest {

    private fun seq(): Pair<Patch, PatchModule> {
        val patch = Patch()
        return patch to patch.add(Types.Seq, Offset.Zero)!!
    }

    private fun PatchModule.stepTo(interval: Interval, patch: Patch) = changeStep(interval, patch.beatsPerBar)

    @Test
    fun `a new Seq is four bars of a beat a step`() {
        val (patch, seq) = seq()
        assertEquals(Interval(1, 1), seq.interval)
        assertEquals(16, seq.seqSteps)
        assertTrue(seq.lengthInBars)
        assertEquals("4 bars", seq.lengthLabel(patch.beatsPerBar))
    }

    /** Halves to quarters divides exactly: every note lands on twice the steps, the music unchanged. */
    @Test
    fun `a finer step that divides the old carries every note exactly`() {
        val (patch, seq) = seq()
        seq.stepTo(Interval(1, 2), patch)  // half beats: 32 steps
        seq.addSeqNote(SeqNote(1, 0, 1))
        seq.addSeqNote(SeqNote(4, 7, 3))
        seq.stepTo(Interval(1, 4), patch)
        assertEquals(listOf(SeqNote(2, 0, 2), SeqNote(8, 7, 6)), seq.seqNotes.toList())
        assertEquals("the length in beats is kept", 64, seq.seqSteps)
        assertEquals("4 bars", seq.lengthLabel(patch.beatsPerBar))
    }

    /**
     * Forrest's example: half beats to fifths. Each half beat becomes as many fifths as fit inside
     * it, two, beat by beat -- so notes span twice the steps, four fifths of each beat, and the
     * fifth fifth of every beat is silent.
     */
    @Test
    fun `half beats to fifths puts each note on twice the steps, beat by beat`() {
        val (patch, seq) = seq()
        seq.stepTo(Interval(1, 2), patch)
        seq.addSeqNote(SeqNote(0, 0, 2))  // a whole beat, from the beat
        seq.addSeqNote(SeqNote(3, 4, 1))  // the second half of beat 2
        seq.stepTo(Interval(1, 5), patch)
        assertEquals(SeqNote(0, 0, 4), seq.seqNotes[0])
        assertEquals("beat 2 starts at fifth 5, and its second half two fifths in", SeqNote(7, 4, 2), seq.seqNotes[1])
        assertEquals("four bars of fifths", 80, seq.seqSteps)
    }

    /** Coarser: a note snaps into the step it starts in and covers what it covered (Forrest's choice). */
    @Test
    fun `a coarser step snaps each note into its step, covering what it covered`() {
        val (patch, seq) = seq()
        seq.stepTo(Interval(1, 4), patch)
        seq.addSeqNote(SeqNote(1, 0, 1))  // the second quarter of beat 1
        seq.addSeqNote(SeqNote(5, 2, 2))  // the second and third quarters of beat 2
        seq.stepTo(Interval(1, 2), patch)
        assertEquals("into the half it starts in", SeqNote(0, 0, 1), seq.seqNotes[0])
        assertEquals("and long enough to cover what it covered", SeqNote(2, 2, 2), seq.seqNotes[1])
    }

    /** Notes of one version that collide at one pitch after a coarser step are merged, not stacked. */
    @Test
    fun `notes that collide after a coarser step are resolved`() {
        val (patch, seq) = seq()
        seq.stepTo(Interval(1, 4), patch)
        seq.addSeqNote(SeqNote(0, 0, 1))
        seq.addSeqNote(SeqNote(1, 0, 1))  // the same half beat once halved: goes
        seq.addSeqNote(SeqNote(2, 5, 4))
        seq.addSeqNote(SeqNote(4, 5, 1))  // starts inside the long one: the long one ends there
        seq.stepTo(Interval(1, 2), patch)
        assertEquals(listOf(SeqNote(0, 0, 1), SeqNote(1, 5, 1), SeqNote(2, 5, 1)), seq.seqNotes.toList())
    }

    @Test
    fun `the length is said in beats or bars, and the step count follows`() {
        val (patch, seq) = seq()
        seq.setSeqLength(6, bars = false, patch.beatsPerBar)
        assertEquals(6, seq.seqSteps)
        assertEquals("6 beats", seq.lengthLabel(patch.beatsPerBar))
        seq.stepTo(Interval(1, 3), patch)
        assertEquals("thirds: three a beat", 18, seq.seqSteps)
        seq.setSeqLength(2, bars = true, patch.beatsPerBar)
        assertEquals(24, seq.seqSteps)
        assertEquals("2 bars", seq.lengthLabel(patch.beatsPerBar))
        seq.setSeqLength(99, bars = true, patch.beatsPerBar)
        assertEquals("never past sixteen bars", "16 bars", seq.lengthLabel(patch.beatsPerBar))
    }

    /** A note split to edit one version, then edited back, is one shared note again. */
    @Test
    fun `identical notes in different versions merge into one`() {
        val (_, seq) = seq()
        seq.addVersion()
        seq.seqNotes.add(SeqNote(0, 0, 1, 1f, versions = 0b01))
        seq.seqNotes.add(SeqNote(0, 0, 1, 1f, versions = 0b10))
        seq.seqNotes.add(SeqNote(0, 0, 1, 0.5f, versions = 0b10))
        seq.tidySeqNotes()
        assertEquals(listOf(SeqNote(0, 0, 1, 1f, 0b11), SeqNote(0, 0, 1, 0.5f, 0b10)), seq.seqNotes.toList())
    }

    @Test
    fun `a note's versions are set from its menu, all of them or some`() {
        val (_, seq) = seq()
        seq.addVersion()
        seq.addVersion()
        seq.addSeqNote(SeqNote(0, 0, versions = 0b001))
        val menu = NoteMenu.of(seq, 0, Offset.Zero)
        assertFalse("a note in one version is not all", menu.all)
        seq.setNoteVersions(0, 0b101)
        assertEquals(0b101, seq.seqNotes[0].versions)
        assertTrue("one in every version opens on All", NoteMenu.of(seq.also { it.setNoteVersions(0, 0b111) }, 0, Offset.Zero).all)
        seq.setNoteVersions(0, 0)
        assertEquals("nothing ticked changes nothing", 0b111, seq.seqNotes[0].versions)
    }

    /** Gray for a note in every version, a version's color for one, stripes of theirs for several. */
    @Test
    fun `a note is drawn in its versions' colors, and gray when it is in all of them`() {
        val (_, seq) = seq()
        assertEquals("one version: the Seq's own", listOf(Types.Seq.accent), seq.noteColors(SeqNote(0, 0)))
        seq.addVersion()
        seq.addVersion()
        assertEquals(listOf(SHARED_NOTE_COLOR), seq.noteColors(SeqNote(0, 0, versions = 0b111)))
        assertEquals(listOf(versionColor(2)), seq.noteColors(SeqNote(0, 0, versions = 0b010)))
        assertEquals(listOf(versionColor(1), versionColor(3)), seq.noteColors(SeqNote(0, 0, versions = 0b101)))
        assertTrue(SeqNote(0, 0, versions = 0b101).playsIn(3))
        assertFalse(SeqNote(0, 0, versions = 0b101).playsIn(2))
        assertFalse("version 0 plays nothing", SeqNote(0, 0, versions = 0b111).playsIn(0))
    }

    /**
     * Version 0 reads "off", and a version knob reaches only the versions there are: exposed over
     * all eight, a modulator at its middle asked for version four of a Seq with two, and nothing
     * played -- which read as "0 is the default" (Forrest, 2026-10-09).
     */
    @Test
    fun `a version knob offers off and the versions there are, and is exposed over just those`() {
        val (patch, seq) = seq()
        seq.addVersion()
        val knob = Types.Seq.versionParam
        val row = seq.rowView(knob).param
        assertEquals("off, 1 and 2", 3, row.steps)
        assertEquals("off", choiceWord(row, 0))
        assertEquals("2", choiceWord(row, 2))
        assertTrue(patch.expose(seq, knob, initialModRange(seq.exposedParam(knob), 1f)))
        assertEquals(ModRange(0f, 2f), patch.rangeOf(seq, knob))
        assertEquals("off", patch.levelLabel(ParamRow(seq, knob), 0f))
        assertEquals("v2", patch.levelLabel(ParamRow(seq, knob), 1f))
        seq.addVersion()
        assertEquals("a version added is one the range reaches", ModRange(0f, 3f), patch.rangeOf(seq, knob))
        seq.deleteVersion(3)
        assertEquals(ModRange(0f, 2f), patch.rangeOf(seq, knob))
    }

    @Test
    fun `scrolling and the length round trip through the file byte for byte`() {
        val (patch, seq) = seq()
        seq.stepTo(Interval(1, 4), patch)
        seq.setSeqLength(3, bars = false, patch.beatsPerBar)
        seq.addSeqNote(SeqNote(2, 4, 3))
        seq.seqScroll = 4
        val json = patch.toJson()
        val back = patchFromJson(json)!!
        assertEquals(json, back.toJson())
        val read = back.modules.single { it.type == Types.Seq }
        assertEquals(12, read.seqSteps)
        assertFalse(read.lengthInBars)
        assertEquals("the scroll is view state", 0, read.seqScroll)
    }
}
