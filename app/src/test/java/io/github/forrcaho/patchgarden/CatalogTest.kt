package io.github.forrcaho.patchgarden

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 11's last additions, as the interface sees them: an Osc's tune, and the Noise, Delay
 * and Reverb modules. The DSP is node_test's; what is here is the model, the file and the
 * contracts a new module has to keep.
 */
class CatalogTest {

    /**
     * Every sound source has a level with a jack of its own, from one definition, so an Env
     * patches straight into the synth. The jack is the knob's ([Param.drivenBy]): a modulation
     * input, never exposable as well. The knob's index is the engine's to read, so it is read
     * out of each node's `kLevel` in the header -- a mismatch would sweep the wrong knob.
     */
    @Test
    fun `every sound source has a level, and the level has a jack`() {
        val header = java.io.File("src/main/cpp/nodes.h").readText() +
            java.io.File("src/main/cpp/soundfont.h").readText()
        fun engineLevel(node: String): Int {
            val body = header.substringAfter("class $node ")
            return Regex("""static constexpr int32_t kLevel = (\d+);""").find(body)!!.groupValues[1].toInt()
        }
        mapOf(
            Types.Osc to "OscNode", Types.Pluck to "PluckNode", Types.Fm to "FmNode",
            Types.Sf to "SfNode", Types.Noise to "NoiseNode",
        ).forEach { (type, node) ->
            val level = type.params.indexOfFirst { it.name == "level" }
            assertTrue("${type.name} has a level", level >= 0)
            assertEquals("${type.name}: the engine's level", engineLevel(node), level)
            val port = type.params[level].drivenBy
            assertTrue("${type.name}: driven by a port", port >= 0)
            assertEquals("${type.name}: a modulation input", SignalKind.MODULATION, type.inputs[port].kind)
            val module = Patch().add(type, Offset.Zero)!!
            assertTrue("${type.name}: and so not exposable", !module.canExpose(level))
        }
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
        assertEquals("a source: nothing goes in but its level", listOf(Port("level", SignalKind.MODULATION)), Types.Noise.inputs)
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
        delay.setParam(Types.Delay.intervalParam, Interval(1, 2).code.toFloat()) // back to half a beat
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
            assertTrue("${it.name} is sounded by notes, or by nothing", takes(it) - SignalKind.NOTE - SignalKind.MODULATION == emptySet<SignalKind>())
            // And the one modulation it takes is its level's own jack, not a way in for sound.
            it.inputs.forEachIndexed { port, input ->
                if (input.kind == SignalKind.MODULATION) {
                    assertTrue("${it.name}'s ${input.name} drives its level", it.params.any { p -> p.name == "level" && p.drivenBy == port })
                }
            }
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
