package io.github.forrcaho.patchgarden

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Time divided one way for every module the transport times: a step is some beats divided into
 * some divisions, each from 1 to 16 -- Forrest's model, in which 2/3 of a beat (2 ÷ 3) and 3/2
 * beats (3 ÷ 2) are ordinary choices rather than the special cases the first version kept --
 * with the LFO synced to it as the Delay already was.
 */
class IntervalTest {

    @Test
    fun `every step in beats or bars is written and read back as itself`() {
        // Every pair the chooser offers as tiles, and the corners of what "other…" can type.
        val counts = (1..OFFERED_COUNTS) + listOf(17, 255, 512, 1022, MAX_COUNT)
        val codes = mutableSetOf<Int>()
        listOf(false, true).forEach { bars ->
            counts.forEach { num ->
                counts.forEach { den ->
                    val step = Interval(num, den, bars)
                    assertEquals(step, intervalOf(step.code.toFloat()))
                    assertEquals("exact in the float a knob is", step.code, step.code.toFloat().toInt())
                    codes += step.code
                }
            }
        }
        assertEquals("all distinct, bars from beats as well", 2 * counts.size * counts.size, codes.size)
        // The same numbers node_test hands the engine: the formula is written twice, once a side,
        // and these are what hold the two to each other.
        assertEquals(1027, Interval(2, 3).code)
        assertEquals(Interval(3, 2), intervalOf(2050f))
        assertEquals("one beat in d is d, under any radix", 2, Interval(1, 2).code)
        assertTrue("and free is free", intervalOf(FREE_INTERVAL.toFloat()).free)
        assertEquals(FREE_INTERVAL, Interval.FREE.code)
    }

    /**
     * Below the codes is free and nothing else: the table of note lengths an older file indexed
     * went with format 19, so there is no value left that means "look it up".
     */
    @Test
    fun `free is the one value below the codes, and nothing reads past the last`() {
        assertEquals(0, FREE_INTERVAL)
        assertEquals(1, INTERVAL_CODE)
        assertTrue(intervalOf(-3f).free)
        assertEquals(Interval(1, 1), intervalOf(1f))
        assertEquals("past the last code, the last", Interval(MAX_COUNT, MAX_COUNT, bars = true), intervalOf(9.0e6f))
        assertEquals("and NaN is a step a beat rather than no step", Interval(1, 1), intervalOf(Float.NaN))
    }

    @Test
    fun `a step says how many beats or bars it is, and never what note that would be`() {
        assertEquals("1 beat", Interval(1, 1).label)
        assertEquals("2 beats", Interval(2, 1).label)
        assertEquals("1/2 beat", Interval(1, 2).label)
        assertEquals("reduced, since the chip says the length", "1/2 beat", Interval(2, 4).label)
        assertEquals("and a whole number when that is what it is", "2 beats", Interval(4, 2).label)
        assertEquals("2/3 beat", Interval(2, 3).label)
        assertEquals("more than one is plural", "3/2 beats", Interval(3, 2).label)
        assertEquals("16/15 beats", Interval(16, 15).label)
        assertEquals("1 bar", Interval(1, 1, bars = true).label)
        assertEquals("4 bars", Interval(4, 1, bars = true).label)
        assertEquals("2/3 bar", Interval(2, 3, bars = true).label)
        assertEquals("free", Interval.FREE.label)

        assertEquals("= 1/3 beat", Interval(1, 3).readout())
        assertEquals("the length reduced; the sentence says how it was chosen", "= 1/2 beat", Interval(2, 4).readout())
        assertEquals("= 4 bars", Interval(4, 1, bars = true).readout())
        assertEquals("divisions of", Interval(1, 3).words())
        assertEquals("and the words agree with the number", "division of", Interval(2, 1).words())
    }

    /**
     * Bars stay bars until the engine is sent them, so a four-bar step is four bars of whatever
     * the meter is: sixteen beats of 4/4, twelve of 3/4. Reduced on the way, and clamped at the
     * longest step a code can say rather than wrapping into a short one.
     */
    @Test
    fun `a step in bars is that many bars of the meter, in beats`() {
        val four = Interval(4, 1, bars = true)
        assertEquals(Interval(16, 1), four.inBeats(4))
        assertEquals(Interval(12, 1), four.inBeats(3))
        assertEquals("reduced", Interval(2, 1), Interval(2, 3, bars = true).inBeats(3))
        assertEquals("a step in beats ignores the meter", Interval(2, 3), Interval(2, 3).inBeats(7))
        assertEquals("clamped, never wrapped", Interval(MAX_COUNT, 1), Interval(MAX_COUNT, 1, bars = true).inBeats(4))
        assertTrue(Interval.FREE.inBeats(4).free)
    }

    @Test
    fun `the engine is sent a step in bars as beats, and every other knob as it stands`() {
        val patch = Patch()
        val seq = patch.add(Types.Seq, Offset.Zero)!!
        val index = Types.Seq.intervalParam
        seq.setParam(index, Interval(1, 4, bars = true).code.toFloat())
        val sent = seq.engineParams(beatsPerBar = 4)
        assertEquals("a quarter of a bar of 4/4 is a beat", Interval(1, 1).code.toFloat(), sent[index])
        assertEquals(seq.params.toList().filterIndexed { i, _ -> i != index }, sent.filterIndexed { i, _ -> i != index })
        seq.setParam(index, Interval(1, 4).code.toFloat())
        assertEquals("beats go as they are", seq.params.toList(), seq.engineParams(beatsPerBar = 4))
    }

    @Test
    fun `a number picked changes that number and keeps the rest`() {
        val half = Interval(1, 2)
        assertEquals(Interval(3, 2), half.with(IntervalPick.Count(IntervalPart.BEATS, 3)))
        assertEquals(Interval(1, 5), half.with(IntervalPick.Count(IntervalPart.DIVISIONS, 5)))
        assertEquals("and keeps bars", Interval(3, 2, bars = true),
            Interval(1, 2, bars = true).with(IntervalPick.Count(IntervalPart.BEATS, 3)))
        assertEquals("bars keeps the numbers", Interval(1, 2, bars = true), half.with(IntervalPick.Bars(true)))
        assertEquals("and so does beats", half, Interval(1, 2, bars = true).with(IntervalPick.Bars(false)))
        assertTrue(half.with(IntervalPick.Fixed).free)
        assertEquals("tempo leaves a step on the tempo as it was", half, half.with(IntervalPick.Tempo))
        assertEquals("opening a grid changes nothing", half, half.with(IntervalPick.Open(IntervalPart.BEATS)))
        assertEquals("nor does reaching for the keypad", half, half.with(IntervalPick.Other(IntervalPart.BEATS)))
        val free = Interval.FREE
        assertEquals("from fixed, a step a beat", Interval(1, 1), free.with(IntervalPick.Tempo))
        assertEquals("from fixed, the other number starts at 1", Interval(4, 1), free.with(IntervalPick.Count(IntervalPart.BEATS, 4)))
        assertEquals(Interval(1, 3), free.with(IntervalPick.Count(IntervalPart.DIVISIONS, 3)))
        assertEquals("typed past what a code holds, the most it holds", MAX_COUNT,
            half.withCount(IntervalPart.DIVISIONS, 5000).den)
        assertEquals(1, half.withCount(IntervalPart.BEATS, 0).num)
    }

    @Test
    fun `an LFO can be synced, is free until it is, and its rate means nothing while synced`() {
        assertTrue(Types.Lfo.canBeFree)
        val patch = Patch()
        val lfo = patch.add(Types.Lfo, Offset.Zero)!!
        val rate = Types.Lfo.params.indexOfFirst { it.name == "rate" }
        assertTrue("free by default", lfo.interval.free)
        assertTrue(lfo.isLive(rate))
        lfo.setParam(Types.Lfo.intervalParam, Interval(1, 1).code.toFloat())
        assertFalse("synced, the rate is faint", lfo.isLive(rate))
        assertEquals("and the interval is appended, leaving rate and wave where they were",
            2, Types.Lfo.intervalParam)
    }

    // ------------------------------------------------------------------ the chooser

    private val frame = Frame(
        canvas = Size(2404f, 1080f), density = 2.4375f,
        insetLeft = 160f, insetTop = 54f, insetRight = 0f, insetBottom = 58f,
    )
    private val panel = panelRect(frame)
    private val d = frame.density

    /**
     * The Pixel 8 emulator in landscape, its insets as measured off its screenshots: a panel body
     * 274dp tall against the reference device's 311, which makes it the tight case.
     */
    private val emulator = Frame(
        canvas = Size(2400f, 1080f), density = 2.625f,
        insetLeft = 132f, insetTop = 72f, insetRight = 0f, insetBottom = 62f,
    )

    @Test
    fun `the switch is only where a module can keep its own time, and the sentence follows it`() {
        val seq = intervalChooser(panel, d, 1f, Types.Seq.canBeFree, Interval(1, 4))
        assertFalse("a sequencer only steps on the tempo",
            seq.targets.any { it.second == IntervalPick.Tempo || it.second == IntervalPick.Fixed })
        val picks = seq.targets.map { it.second }
        assertEquals(listOf(
            IntervalPick.Open(IntervalPart.DIVISIONS), IntervalPick.Open(IntervalPart.BEATS),
            IntervalPick.Bars(false), IntervalPick.Bars(true),
        ), picks)
        val (divisions, beats) = seq.targets.take(2).map { it.first }
        assertTrue("divisions first, as the sentence reads", divisions.right < seq.words!!.left && seq.words!!.right <= beats.left)

        val lfo = intervalChooser(panel, d, 1f, Types.Lfo.canBeFree, Interval(1, 4))
        val tempo = lfo.targets.single { it.second == IntervalPick.Tempo }.first
        assertTrue("the switch above the sentence", lfo.targets.filter { it.second is IntervalPick.Open }.all { it.first.top > tempo.bottom })
        val fixed = intervalChooser(panel, d, 1f, Types.Lfo.canBeFree, Interval.FREE)
        assertEquals("fixed has no sentence", listOf(IntervalPick.Tempo, IntervalPick.Fixed), fixed.targets.map { it.second })
    }

    @Test
    fun `a grid offers 1 to 16 and other, under its own number`() {
        IntervalPart.entries.forEach { part ->
            val chooser = intervalChooser(panel, d, 1.5f, true, Interval(2, 3), open = part)
            val counts = chooser.targets.mapNotNull { (it.second as? IntervalPick.Count)?.takeIf { c -> c.part == part }?.n }
            assertEquals((1..16).toList(), counts)
            assertNotNull(chooser.targets.singleOrNull { it.second == IntervalPick.Other(part) })
            val button = chooser.targets.single { it.second == IntervalPick.Open(part) }.first
            assertTrue("under its number", chooser.grid!!.top > button.bottom)
            assertEquals("a tile picks before anything under it", IntervalPick.Count(part, 1),
                chooser.pickAt(chooser.targets.first { it.second == IntervalPick.Count(part, 1) }.first.center))
        }
        assertNull("no grid unless one is open", intervalChooser(panel, d, 1f, true, Interval(2, 3)).grid)
    }

    @Test
    fun `the chooser fits the panel at both text sizes, on the reference device and the emulator`() {
        listOf(frame, emulator).forEach { f ->
            val p = panelRect(f)
            val d = f.density
            listOf(1f, 1.5f).forEach { scale ->
                IntervalPart.entries.forEach { part ->
                    val chooser = intervalChooser(p, d, scale, true, Interval(2, 3), open = part)
                    val rects = chooser.targets.map { it.first }
                    val body = Rect(p.left, p.top + PatchModule.PANEL_HEADER * d, p.right, p.bottom)
                    (rects + chooser.grid!!).forEach { r ->
                        assertTrue("$r escapes the panel at $scale", r.left >= body.left && r.top >= body.top &&
                            r.right <= body.right && r.bottom <= body.bottom)
                    }
                    rects.forEach { r ->
                        assertTrue("too small for a finger at $scale: ${r.width / d}x${r.height / d}dp",
                            r.width / d >= 40f && r.height / d >= 40f)
                    }
                    rects.forEachIndexed { i, a ->
                        rects.drop(i + 1).forEach { b -> assertFalse("$a overlaps $b", a.overlaps(b)) }
                    }
                    val bars = chooser.targets.single { it.second == IntervalPick.Bars(true) }.first
                    assertTrue("the reading is right of the sentence", chooser.readout!!.x > bars.right + 60f * d)
                }
            }
        }
    }

    @Test
    fun `the interval chip grows with the text and stays in the header`() {
        val small = panelIntervalChip(panel, d, 1f)
        val large = panelIntervalChip(panel, d, 1.5f)
        assertEquals(1.5f * small.width, large.width, 0.5f)
        assertTrue(large.bottom <= panel.top + PatchModule.PANEL_HEADER * d)
        assertTrue("clear of the centered title", large.left > panel.center.x + 60f * d)
        assertTrue("and the lock beside it moves over", panelLockChip(panel, d, 1.5f).right < large.left)
    }

    // ------------------------------------------------------------------ beats on the grid

    @Test
    fun `a quarter-beat grid is marked every four steps, and every sixteen is a bar`() {
        assertEquals(
            listOf(4 to false, 8 to false, 12 to false, 16 to true, 20 to false, 24 to false, 28 to false),
            beatLines(32, Interval(1, 4), 4),
        )
    }

    /** Forrest's case: five beats to a bar, five steps to each. */
    @Test
    fun `five to a beat in a bar of five is marked in fives, and a bar every twenty-five`() {
        val lines = beatLines(50, Interval(1, 5), 5)
        assertEquals((1..9).map { it * 5 }, lines.map { it.first })
        assertEquals(listOf(25), lines.filter { it.second }.map { it.first })
    }

    @Test
    fun `steps of a beat or more mark only the bars, and a free interval nothing`() {
        assertEquals(listOf(2 to true, 4 to true, 6 to true), beatLines(8, Interval(2, 1), 4))
        assertEquals(listOf(4 to true), beatLines(8, Interval(1, 1), 4))
        assertEquals(
            "2/3 of a beat lands on a beat every third step",
            listOf(3 to false, 6 to true, 9 to false),
            beatLines(12, Interval(2, 3), 4),
        )
        assertEquals(
            "3/4 of a beat lands on a beat every fourth step",
            listOf(4 to false, 8 to false, 12 to false, 16 to true),
            beatLines(20, Interval(3, 4), 4).take(4).let { listOf(it[0], it[1], it[2], it[3]) },
        )
        assertTrue(beatLines(16, Interval.FREE, 4).isEmpty())
    }

    // ------------------------------------------------------------------ the file

    @Test
    fun `beats and divisions survive a save, unreduced`() {
        val patch = Patch()
        val seq = patch.add(Types.Seq, Offset.Zero)!!
        val lfo = patch.add(Types.Lfo, Offset(0f, 200f))!!
        seq.setParam(Types.Seq.intervalParam, Interval(2, 4).code.toFloat())
        lfo.setParam(Types.Lfo.intervalParam, Interval(3, 2).code.toFloat())
        val json = patch.toJson()
        val read = patchFromJson(json)!!
        assertEquals(Interval(2, 4), read.module(seq.id)!!.interval)
        assertEquals(Interval(3, 2), read.module(lfo.id)!!.interval)
        assertEquals("byte for byte", json, read.toJson())
    }
}
