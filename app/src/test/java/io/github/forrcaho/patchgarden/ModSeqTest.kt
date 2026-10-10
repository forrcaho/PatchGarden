package io.github.forrcaho.patchgarden

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ModSeq, roadmap item 3: a loop of levels stepped by the transport and sent as modulation,
 * labeled in its target's own terms when it drives one knob.
 */
class ModSeqTest {

    private fun out(m: PatchModule) = PortRef(m.id, PortDirection.OUTPUT, 0)
    private fun knob(m: PatchModule, index: Int) = PortRef(m.id, PortDirection.MOD, index)

    @Test
    fun `a new ModSeq is sixteen steps of a bar, each at the middle`() {
        val patch = Patch()
        val seq = patch.add(Types.ModSeq, Offset.Zero)!!
        assertEquals(MODSEQ_STEPS, seq.levels.size)
        assertEquals(16, seq.levelCount)
        assertTrue(seq.levels.all { it == 0.5f })
        assertEquals(Interval(1, 1, bars = true), seq.interval)
        assertEquals("it sends modulation, so it is filed under Mod", Category.MOD, Types.ModSeq.category)
    }

    @Test
    fun `its levels are saved, read back and copied`() {
        val patch = Patch()
        val seq = patch.add(Types.ModSeq, Offset.Zero)!!
        seq.setLevel(0, 0.1f)
        seq.setLevel(3, 0.9f)
        seq.setLevel(4, 7f)
        assertEquals("clamped", 1f, seq.levels[4])
        val back = patchFromJson(patch.toJson())!!.modules.single { it.type == Types.ModSeq }
        assertEquals(seq.levels.toList(), back.levels.toList())
        assertEquals("and the round trip is byte-identical, which undo depends on", patch.toJson(), patchFromJson(patch.toJson())!!.toJson())
        val copy = PatchModule(99, Types.ModSeq, Offset.Zero).also { it.copyGridFrom(seq) }
        assertEquals(seq.levels.toList(), copy.levels.toList())
    }

    @Test
    fun `a level changed is one command, as a segment slot`() {
        val patch = Patch()
        val seq = patch.add(Types.ModSeq, Offset.Zero)!!
        val sent = mutableListOf<Triple<Long, Int, Float>>()
        val commands = object : GraphCommands by NoCommands {
            override fun setSegment(id: Long, slot: Int, time: Float, level: Float, curve: Float, sustain: Boolean) {
                sent += Triple(id, slot, level)
            }
        }
        val sync = GraphSync(commands)
        sync.sync(patch)
        assertEquals("every step, when the node is new", MODSEQ_STEPS, sent.size)
        sent.clear()
        seq.setLevel(2, 0.25f)
        sync.sync(patch)
        assertEquals(listOf(Triple(seq.id, 2, 0.25f)), sent)
    }

    @Test
    fun `a stepped knob's range tells the engine it is whole options`() {
        val patch = Patch()
        val osc = patch.add(Types.Osc, Offset.Zero)!!
        val wave = Types.Osc.params.indexOfFirst { it.name == "wave" }
        val cutoffOwner = patch.add(Types.Filter, Offset(200f, 0f))!!
        assertTrue(patch.expose(osc, wave, ModRange(0f, 3f)))
        assertTrue(patch.expose(cutoffOwner, 0, ModRange(200f, 4000f)))
        val ranges = mutableListOf<Pair<Int, Boolean>>()
        val commands = object : GraphCommands by NoCommands {
            override fun setModRange(id: Long, index: Int, low: Float, high: Float, exponential: Boolean, stepped: Boolean) {
                ranges += index to stepped
            }
        }
        GraphSync(commands).sync(patch)
        assertTrue("the waveform is", (wave to true) in ranges)
        assertTrue("a cutoff is not", (0 to false) in ranges)
    }

    @Test
    fun `a step is labeled in its one target's terms, and as 0 to 1 otherwise`() {
        val patch = Patch()
        val seq = patch.add(Types.ModSeq, Offset.Zero)!!
        val filter = patch.add(Types.Filter, Offset(200f, 0f))!!
        assertNull("nothing patched", patch.modSeqTarget(seq))
        assertEquals("0.50", patch.levelLabel(null, 0.5f))

        assertTrue(patch.expose(filter, 0, ModRange(200f, 4000f)))
        assertTrue(patch.connect(out(seq), knob(filter, 0)))
        val target = patch.modSeqTarget(seq)
        assertEquals(ParamRow(filter, 0), target)
        assertEquals("200Hz", patch.levelLabel(target, 0f))
        assertEquals("4000Hz", patch.levelLabel(target, 1f))
        assertEquals("geometric, as the cutoff's knob is", "894Hz", patch.levelLabel(target, 0.5f))

        val osc = patch.add(Types.Osc, Offset(0f, 200f))!!
        val wave = Types.Osc.params.indexOfFirst { it.name == "wave" }
        assertTrue(patch.expose(osc, wave, ModRange(0f, 3f)))
        assertTrue(patch.connect(out(seq), knob(osc, wave)))
        assertNull("two targets: no single terms to say it in", patch.modSeqTarget(seq))

        patch.disconnect(knob(filter, 0))
        val waveTarget = patch.modSeqTarget(seq)
        assertNotNull(waveTarget)
        assertEquals("a third of saw to sine is square", "square", patch.levelLabel(waveTarget, 1f / 3f))
        assertEquals("sine", patch.levelLabel(waveTarget, 1f))
    }

    @Test
    fun `a ModSeq inside a box finds a knob outside it`() {
        val patch = Patch()
        val seq = patch.add(Types.ModSeq, Offset.Zero)!!
        val filter = patch.add(Types.Filter, Offset(200f, 0f))!!
        assertTrue(patch.expose(filter, 0, ModRange(200f, 4000f)))
        assertTrue(patch.connect(out(seq), knob(filter, 0)))
        patch.makeSubpatch(setOf(seq.id))!!
        assertEquals("through the box's port, as the engine has it", ParamRow(filter, 0), patch.modSeqTarget(seq))
    }

    @Test
    fun `a column's level is where the finger is, from the bottom of its bar`() {
        val area = Rect(0f, 0f, 1600f, 400f)
        val columns = levelColumns(area, 2.4375f, 1.5f, 16)
        assertEquals(16, columns.size)
        val c = columns[3]
        assertEquals(0f, c.levelAt(c.track.bottom), 1e-5f)
        assertEquals(1f, c.levelAt(c.track.top), 1e-5f)
        assertEquals("and past either end, the end", 1f, c.levelAt(c.track.top - 100f), 1e-5f)
        assertEquals(0.25f, c.levelAt(c.yOf(0.25f)), 1e-5f)
        assertTrue("the number is above the bar", c.label.bottom <= c.track.top)
        assertTrue("columns do not overlap", columns.zipWithNext().all { (a, b) -> a.whole.right <= b.whole.left + 0.01f })
    }
}

/** Commands that go nowhere: what a test overrides only the one it is watching from. */
internal object NoCommands : GraphCommands {
    override fun addNode(id: Long, type: NodeType) = Unit
    override fun removeNode(id: Long) = Unit
    override fun connect(srcId: Long, srcPort: Int, dstId: Long, dstPort: Int) = Unit
    override fun disconnect(srcId: Long, srcPort: Int, dstId: Long, dstPort: Int) = Unit
    override fun setParam(id: Long, index: Int, value: Float) = Unit
    override fun setModRange(id: Long, index: Int, low: Float, high: Float, exponential: Boolean, stepped: Boolean) = Unit
    override fun connectMod(srcId: Long, srcPort: Int, dstId: Long, index: Int) = Unit
    override fun disconnectMod(srcId: Long, srcPort: Int, dstId: Long, index: Int) = Unit
    override fun setStep(id: Long, index: Int, degree: Int, gate: Boolean) = Unit
    override fun setScales(entries: List<ScaleEntry>, beatsPerBar: Int) = Unit
    override fun setTempo(bpm: Float) = Unit
    override fun setFont(id: Long, font: Long) = Unit
    override fun setSeqNote(id: Long, slot: Int, step: Int, degree: Int, length: Int, velocity: Float, versions: Int) = Unit
    override fun setSegment(id: Long, slot: Int, time: Float, level: Float, curve: Float, sustain: Boolean) = Unit
    override fun setSection(id: Long, slot: Int, scene: Int, length: Int) = Unit
    override fun collectGarbage() = Unit
}
