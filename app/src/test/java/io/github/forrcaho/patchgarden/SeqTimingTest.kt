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
     * Forrest, 2026-10-11: Steps' triangles for notes out of sight, which the Seq lacked. One at
     * each hidden note's start (or the first step on screen, for one that started before it), in
     * its colors, the version being edited on top and another version's faint.
     */
    @Test
    fun `a note out of sight is a triangle at its start, the version edited on top`() {
        val (_, seq) = seq()
        seq.addVersion()
        seq.addVersion()
        seq.showVersion(2)
        seq.seqNotes.add(SeqNote(2, 20, 3, versions = 0b010))  // v2, above
        seq.seqNotes.add(SeqNote(2, 21, 1, versions = 0b001))  // v1, above, starting with it
        seq.seqNotes.add(SeqNote(5, -9, 1, versions = 0b100))  // v3, below
        seq.seqNotes.add(SeqNote(0, 3, 1, versions = 0b001))   // in sight: no mark
        seq.seqNotes.add(SeqNote(1, 30, 6, versions = 0b111))  // shared, started before the window
        val marks = seq.seqEdgeMarks(bottom = 0, top = 7, first = 2, shown = 8, playingStep = -1, playingVersion = 1)
        assertEquals(
            listOf(
                EdgeMark(2, true, listOf(
                    MarkLayer(versionColor(1), 0.35f),
                    MarkLayer(SHARED_NOTE_COLOR, 0.9f),
                    MarkLayer(versionColor(2), 0.9f),
                )),
                EdgeMark(5, false, listOf(MarkLayer(versionColor(3), 0.35f))),
            ),
            marks.sortedBy { it.step },
        )
        val playing = seq.seqEdgeMarks(0, 7, 2, 8, playingStep = 3, playingVersion = 2).single { it.step == 2 }
        assertTrue("a note sounding is on top", playing.layers.last().sounding)
    }

    /** "Start here" starts the loop on the beat the press was in, or on the step where beats and steps do not line up. */
    @Test
    fun `the loop starts on the beat pressed`() {
        val (patch, seq) = seq()
        seq.stepTo(Interval(1, 4), patch)
        assertEquals("quarter steps: the beat step 6 is in begins at step 4", 4, seq.loopStartAt(6, patch.beatsPerBar))
        seq.stepTo(Interval(2, 3), patch)
        assertEquals("two-thirds of a beat: beat 1 begins between steps, so the step itself", 2, seq.loopStartAt(2, patch.beatsPerBar))
    }

    /**
     * Every note moves back round the loop, and one held across the new start is split there,
     * the part after it struck again at the top -- Forrest's choice of two.
     */
    @Test
    fun `starting the loop elsewhere moves every note round, and splits one held across the start`() {
        val (patch, seq) = seq()
        seq.setSeqLength(4, bars = false, patch.beatsPerBar)
        seq.addSeqNote(SeqNote(0, 0, 1))
        seq.addSeqNote(SeqNote(1, 2, 2, 0.5f))  // held across step 2
        seq.addSeqNote(SeqNote(3, 4, 1))
        seq.startLoopAt(2)
        assertEquals(
            listOf(SeqNote(0, 2, 1, 0.5f), SeqNote(1, 4, 1), SeqNote(2, 0, 1), SeqNote(3, 2, 1, 0.5f)),
            seq.seqNotes.sortedWith(compareBy({ it.step }, { it.degree })),
        )
        assertEquals("the loop is as long as it was", 4, seq.seqSteps)
    }

    /** Forrest, 2026-10-11: striped overlaps still say how hard each note is struck. */
    @Test
    fun `an overlap's stripes reach each note's own velocity`() {
        val (_, seq) = seq()
        seq.addVersion()
        val soft = SeqNote(0, 0, 2, 0.5f, versions = 0b01)
        val loud = SeqNote(1, 0, 2, 1f, versions = 0b10)
        assertEquals(listOf(versionColor(1) to 0.5f, versionColor(2) to 1f), seq.overlapFill(soft, loud))
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
