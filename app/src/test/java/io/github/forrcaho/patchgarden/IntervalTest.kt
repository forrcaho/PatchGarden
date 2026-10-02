package io.github.forrcaho.patchgarden

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `every beats and divisions from 1 to 16 is written and read back as itself`() {
        (1..MAX_BEATS).forEach { beats ->
            (1..MAX_BEATS).forEach { divisions ->
                val step = Interval(beats, divisions)
                assertEquals(step, intervalOf(step.code.toFloat()))
            }
        }
        assertEquals("all 256 of them distinct", 256,
            (1..MAX_BEATS).flatMap { b -> (1..MAX_BEATS).map { Interval(b, it).code } }.toSet().size)
        // The same number node_test hands the engine as two beats in three: the formula is
        // written twice, once a side, and this is what holds the two to each other.
        assertEquals(19, Interval(2, 3).code)
        assertEquals(Interval(3, 2), intervalOf(34f))
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
        assertEquals("past the last code, the last", Interval(16, 16), intervalOf(9999f))
        assertEquals("and NaN is a step a beat rather than no step", Interval(1, 1), intervalOf(Float.NaN))
    }

    @Test
    fun `a step says how many beats it is, and never what note that would be`() {
        assertEquals("1 beat", Interval(1, 1).label)
        assertEquals("2 beats", Interval(2, 1).label)
        assertEquals("1/2 beat", Interval(1, 2).label)
        assertEquals("reduced, since the chip says the length", "1/2 beat", Interval(2, 4).label)
        assertEquals("and a whole number when that is what it is", "2 beats", Interval(4, 2).label)
        assertEquals("2/3 beat", Interval(2, 3).label)
        assertEquals("more than one is plural", "3/2 beats", Interval(3, 2).label)
        assertEquals("16/15 beats", Interval(16, 15).label)
        assertEquals("free", Interval.FREE.label)

        assertEquals("1 beat ÷ 3 = 1/3 beat", Interval(1, 3).readout())
        assertEquals("the rows as chosen, the length reduced", "2 beats ÷ 4 = 1/2 beat", Interval(2, 4).readout())
        assertEquals("5 beats ÷ 1 = 5 beats", Interval(5, 1).readout())
    }

    @Test
    fun `a tap on a row changes that row and keeps the other`() {
        val half = Interval(1, 2)
        assertEquals(Interval(3, 2), half.with(IntervalPick.Beats(3)))
        assertEquals(Interval(1, 5), half.with(IntervalPick.Divisions(5)))
        assertTrue(half.with(IntervalPick.Free).free)
        val free = Interval.FREE
        assertEquals("from free, the other row starts at 1", Interval(4, 1), free.with(IntervalPick.Beats(4)))
        assertEquals(Interval(1, 3), free.with(IntervalPick.Divisions(3)))
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

    @Test
    fun `beats run 1 to 16 over divisions 1 to 16, and free only where something answers to it`() {
        val sequencer = intervalChooser(panel, d, 1f, free = Types.Seq.canBeFree).tiles
        val beats = sequencer.filter { it.second is IntervalPick.Beats }
        val divisions = sequencer.filter { it.second is IntervalPick.Divisions }
        assertEquals((1..16).map { IntervalPick.Beats(it) }, beats.map { it.second })
        assertEquals((1..16).map { IntervalPick.Divisions(it) }, divisions.map { it.second })
        assertTrue("beats on top", beats.all { b -> divisions.all { b.first.bottom < it.first.top } })
        assertTrue("left to right", beats.zipWithNext().all { (a, b) -> a.first.left < b.first.left })
        assertTrue("columns line up", beats.zip(divisions).all { (b, v) -> b.first.left == v.first.left })
        assertFalse(sequencer.any { it.second == IntervalPick.Free })

        val delay = intervalChooser(panel, d, 1f, free = Types.Delay.canBeFree).tiles
        val free = delay.single { it.second == IntervalPick.Free }.first
        assertTrue("free ends the beats row", free.top == beats.first().first.top && free.left > beats.last().first.right)
    }

    @Test
    fun `the chooser fits the panel at the reference device's text sizes, with room for a finger`() {
        listOf(1f, 1.5f).forEach { scale ->
            val chooser = intervalChooser(panel, d, scale, free = true)
            val tiles = chooser.tiles.map { it.first }
            tiles.forEach { tile ->
                assertTrue("$tile escapes the panel at $scale", panel.contains(tile.topLeft) &&
                    tile.right <= panel.right && tile.bottom <= panel.bottom)
                assertTrue("too narrow at $scale: ${tile.width / d}dp", tile.width / d >= 40f)
                assertTrue("too short at $scale: ${tile.height / d}dp", tile.height / d >= 40f)
            }
            tiles.forEachIndexed { i, a ->
                tiles.drop(i + 1).forEach { b -> assertFalse("$a overlaps $b", a.overlaps(b)) }
            }
            val header = panel.top + PatchModule.PANEL_HEADER * d
            assertTrue("the readout is below the header at $scale", chooser.readout.y >= header)
            assertTrue("and above the rows", tiles.all { it.top > chooser.readout.y })
            chooser.captions.forEach { (at, _) -> assertTrue(panel.contains(at)) }
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
