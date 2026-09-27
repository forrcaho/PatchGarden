package io.github.forrcaho.patchgarden

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 11's last additions, as the interface sees them: an Osc's tune, and the Noise, Delay
 * and Reverb modules. The DSP is node_test's; what is here is the model, the file and the
 * contracts a new module has to keep.
 */
class CatalogTest {

    /**
     * 16 is additive over 15, so a 15 file opens as it stands -- and the one knob it cannot
     * name comes back at the value that restates what the file already sounded like.
     */
    @Test
    fun `a format 15 file still opens, with its Osc in tune`() {
        val patch = Patch()
        val osc = patch.add(Types.Osc, Offset.Zero)!!
        osc.setParam(0, 3f) // a sine, so the file says something about the Osc
        val current = patch.toJson()
        assertTrue(current.contains("\"version\":18"))

        // What a 15 build wrote: the same file, with no tune in it.
        val older = current.replace("\"version\":18", "\"version\":15").replace(",\"tune\":0", "")
        assertTrue("the edit took: $older", !older.contains("tune"))
        val back = patchFromJson(older)
        assertNotNull("15 is still read", back)
        val read = back!!.modules.first { it.type == Types.Osc }
        assertEquals("the waveform it did name", 3f, read.params[0])
        assertEquals("and in tune, which is what it was", 0f, read.params[1])
    }

    @Test
    fun `an Osc's tune is in cents, and can be exposed for vibrato`() {
        val tune = Types.Osc.params[1]
        assertEquals("tune", tune.name)
        assertEquals("¢", tune.unit)
        assertEquals(-TUNE_RANGE, tune.min)
        assertEquals(TUNE_RANGE, tune.max)
        assertEquals(0f, tune.default)
        val osc = Patch().add(Types.Osc, Offset.Zero)!!
        assertTrue("a modulator reaches it through an exposed jack", osc.canExpose(1))
    }

    /** Everything a new module has to be to exist: offered, named in a file, and sent. */
    private fun assertAModule(type: ModuleType, node: NodeType) {
        assertTrue("${type.name} is offered", type in Types.palette)
        assertEquals("a file can name it", type, Types.byName[type.name])
        assertEquals("the engine is told what it is", node, type.engine)
        val patch = Patch()
        val module = patch.add(type, Offset.Zero)!!
        val back = patchFromJson(patch.toJson())!!
        assertEquals("and it survives the file", type, back.module(module.id)?.type)
    }

    @Test
    fun `Noise is a module, and its colors are named`() {
        assertAModule(Types.Noise, NodeType.Noise)
        assertEquals(listOf("white", "pink", "brown"), NOISE_TYPES)
        assertTrue("a source: nothing goes in", Types.Noise.inputs.isEmpty())
        assertEquals(listOf(SignalKind.AUDIO), Types.Noise.outputs.map { it.kind })
    }

    @Test
    fun `Delay is a module, synced in its header or free in milliseconds`() {
        assertAModule(Types.Delay, NodeType.Delay)
        val interval = Types.Delay.params[Types.Delay.intervalParam]
        assertTrue("the interval is a header chip", interval.header)
        assertTrue("which can be free", Types.Delay.canBeFree)
        assertEquals("and defaults to a step a beat", Interval(1, 1), intervalOf(interval.default))
        assertFalse("a sequencer's cannot", Types.Seq.canBeFree)
    }

    /**
     * Synced, the time knob is a number nobody is listening to: drawn faint, and neither dragged
     * nor typed. It is not hidden, since that would move every row under it.
     */
    @Test
    fun `a Delay's time knob is live only while the interval is free`() {
        val patch = Patch()
        val delay = patch.add(Types.Delay, Offset.Zero)!!
        val time = Types.Delay.params.indexOfFirst { it.name == "time" }
        assertTrue(!delay.isLive(time))
        delay.setParam(Types.Delay.intervalParam, FREE_INTERVAL.toFloat())
        assertTrue("free, the knob is the time", delay.isLive(time))
        assertTrue("and every other knob always is", delay.isLive(time + 1))

        val frame = Frame(
            canvas = androidx.compose.ui.geometry.Size(2404f, 1080f), density = 2.4375f,
            insetLeft = 160f, insetTop = 54f, insetRight = 0f, insetBottom = 58f,
        )
        val d = frame.density
        val panel = panelRect(frame)
        val rows = patch.panelRows(delay)
        val brackets = { row: ParamRow -> patch.rangeOf(row.owner, row.index) }
        val slot = rows.indexOfFirst { it.index == time }
        val on = panelRowAt(panel, d, Types.Delay, rows.size, slot).center
        assertEquals(ParamRow(delay, time), panelKnobAt(panel, d, delay, rows, brackets, on))
        delay.setParam(Types.Delay.intervalParam, 3f) // back to half a beat
        assertEquals("synced, a finger on it takes nothing", null, panelKnobAt(panel, d, delay, rows, brackets, on))
    }

    @Test
    fun `Reverb is a module, mono in and stereo out`() {
        assertAModule(Types.Reverb, NodeType.Reverb)
        assertEquals(listOf("room", "plate"), REVERB_TYPES)
        assertEquals(listOf("in"), Types.Reverb.inputs.map { it.name })
        assertEquals(listOf("L", "R"), Types.Reverb.outputs.map { it.name })
        assertTrue(Types.Reverb.outputs.all { it.kind == SignalKind.AUDIO })
    }

    /** kMaxParams is the engine's ceiling on knobs, and a module past it would lose the rest. */
    @Test
    fun `tonight's modules fit the engine's knobs`() {
        listOf(Types.Osc, Types.Noise, Types.Delay, Types.Reverb).forEach {
            assertTrue("${it.name} has ${it.params.size}", it.params.size <= MAX_PARAMS)
        }
    }

    // ------------------------------------------------------------------ the catalog

    /**
     * A module is one declaration: what it is, where the menu offers it and what the engine
     * builds are all said in its `ModuleType`, and [Types.modules] is the one list it is added
     * to. Read by reflection, so a module declared and never registered -- which would load
     * from no file and be offered by no menu -- fails here rather than silently.
     */
    @Test
    fun `every module declared is registered, and everything else is derived from it`() {
        val declared = Types::class.java.declaredFields
            .filter { it.type == ModuleType::class.java }
            .map { it.isAccessible = true; it.get(Types) as ModuleType }
        val structural = listOf(Types.Subpatch, Types.Poly, Types.SubpatchIn, Types.SubpatchOut)
        assertEquals(
            "every declared module is in Types.modules or is structure",
            declared.toSet(), (Types.modules + structural).toSet(),
        )
        Types.modules.forEach { type ->
            assertTrue("${type.name} is built by the engine", type.engine != NodeType.Unknown)
            assertEquals("a file can name ${type.name}", type, Types.byName[type.name])
        }
        assertEquals(
            "each node type is one module's",
            Types.modules.size, Types.modules.map { it.engine }.toSet().size,
        )
        assertEquals("offered is exactly what has a category", Types.modules.filter { it.category != null }, Types.palette)
        structural.forEach { assertEquals("${it.name} never reaches the engine as itself", NodeType.Unknown, it.engine) }
    }

    /**
     * The add menu's categories are what a module sends, which is what its color already says.
     * Pinned against the ports rather than as a list, so a new module filed under the wrong chip
     * fails here -- the list in the roadmap is only where the rule came from.
     */
    @Test
    fun `each category holds what its modules send`() {
        fun sends(type: ModuleType) = type.outputs.map { it.kind }.toSet()
        fun takes(type: ModuleType) = type.inputs.map { it.kind }.toSet()
        Types.offered(Category.SYNTHS).forEach {
            assertEquals("${it.name} sounds", setOf(SignalKind.AUDIO), sends(it))
            assertTrue("${it.name} is sounded by notes, or by nothing", takes(it) - SignalKind.NOTE == emptySet<SignalKind>())
        }
        Types.offered(Category.NOTES).forEach {
            assertEquals("${it.name} sends notes", setOf(SignalKind.NOTE), sends(it))
            assertTrue("${it.name} makes them from nothing", takes(it).isEmpty())
        }
        Types.offered(Category.NOTE_FX).forEach {
            assertEquals("${it.name} takes notes and sends notes", setOf(SignalKind.NOTE), sends(it) + takes(it))
        }
        Types.offered(Category.EFFECTS).forEach {
            assertEquals("${it.name} sends audio", setOf(SignalKind.AUDIO), sends(it))
            assertTrue("${it.name} takes audio", SignalKind.AUDIO in takes(it))
        }
        Types.offered(Category.MOD).forEach {
            assertEquals("${it.name} sends modulation", setOf(SignalKind.MODULATION), sends(it))
        }
        assertEquals(listOf(Types.Subpatch, Types.Poly), Types.boxes.values.filter { it.category == Category.BOXES })
        assertTrue("Boxes and Patch hold no module of their own", Types.palette.none { it.category in setOf(Category.BOXES, Category.PATCH) })
    }

    /** Five at most, which is what keeps every category of modules to one row: two taps to a module. */
    @Test
    fun `no category holds more modules than one row of the menu`() {
        Category.entries.forEach { assertTrue("$it", Types.offered(it).size <= 5) }
    }
}
