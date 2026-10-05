package io.github.forrcaho.patchgarden

import android.util.Log

/**
 * Which slot-indexed list an entry belongs to, mirroring SlotKind in node.h.
 *
 * Part of the JNI contract like [NodeType], so append rather than reorder; NodeTypeTest
 * asserts the numbering against the C++ side.
 */
enum class SlotKind(val id: Int) {
    STEP(0),
    DOT(1),
    SEGMENT(2),
    SECTION(3),
}

/**
 * Node type ids, mirroring the enum in nodes.h. The numbering is part of the JNI
 * contract, so append rather than reorder. Which one a module is, is said in its own
 * declaration ([ModuleType.engine]) rather than mapped from its name here.
 */
enum class NodeType(val id: Int) {
    Unknown(0),
    // 1 was the monophonic Osc, retired when polyphony moved inside the synths and the
    // polyphonic one took its name. Polyphony has since moved out again, into the poly
    // subpatch, and Osc is monophonic once more -- but an id is retired for good. As with
    // 7 and 8.
    Filter(2),
    Env(3),
    Steps(4),
    Out(5),
    In(6),
    // 7 was Vca, retired with CV: its entire reason was a control-voltage input, and a
    // gain with a modulatable level is what Mix already is.
    // 8 was Clock, retired when the transport replaced it.
    Mix(9),
    /** The oscillator, called Voice for as long as a second, monophonic Osc existed. */
    Osc(10),
    Lfo(11),
    Drone(12),
    Pluck(13),
    Fm(14),
    Sf(15),
    Seq(16),
    Chance(17),
    Chord(18),
    Arp(19),
    Euclid(20),
    /** The VCA, back, and called Amp until 2026-10-02. Id 7 stays retired: that module took a control voltage. */
    Gain(21),
    /**
     * A poly subpatch's two edges: the note input shared out one per instance, and the
     * instance outputs summed back into one. Neither is a module -- nothing offers them in
     * a menu and no file names them. See Patch.engineGraph.
     */
    PolyIn(22),
    PolySum(23),
    Noise(24),
    Delay(25),
    Reverb(26),
    ModSeq(27),
    Arranger(28),
}

/**
 * Where graph commands go. An interface only so the diff below can be tested without a
 * device: the ordering it produces is the kind of thing that is silently wrong, and
 * welding it to JNI would leave nothing able to observe it.
 */
interface GraphCommands {
    fun addNode(id: Long, type: NodeType)
    fun removeNode(id: Long)
    fun connect(srcId: Long, srcPort: Int, dstId: Long, dstPort: Int)
    /**
     * One cable, named at both ends.
     *
     * The source is named because a note input takes several of them: a disconnect that
     * only said which port would take the other sequencer with it.
     */
    fun disconnect(srcId: Long, srcPort: Int, dstId: Long, dstPort: Int)
    fun setParam(id: Long, index: Int, value: Float)
    /**
     * What an exposed parameter sweeps between, in its own units, and whether geometrically.
     * There is no clearing one: a parameter nothing modulates ignores its range.
     */
    fun setModRange(id: Long, index: Int, low: Float, high: Float, exponential: Boolean, stepped: Boolean)
    /** A cable onto parameter [index] of [dstId]. A parameter takes one, so this replaces. */
    fun connectMod(srcId: Long, srcPort: Int, dstId: Long, index: Int)
    fun disconnectMod(srcId: Long, srcPort: Int, dstId: Long, index: Int)
    /** One step of a sequence, as a degree: the engine resolves it against the scale list. */
    fun setStep(id: Long, index: Int, degree: Int, gate: Boolean)
    /** The patch's scale list, whole, with each entry's length worked out in beats. */
    fun setScales(entries: List<ScaleEntry>, beatsPerBar: Int)
    /** The transport's rate, in beats per minute. */
    fun setTempo(bpm: Float)
    /** Gives SF node [id] a synth over the loaded font [font], a native handle. */
    fun setFont(id: Long, font: Long)
    /** Dot [slot] of dot sequencer [id]; a length of 0 clears the slot. */
    fun setDot(id: Long, slot: Int, step: Int, degree: Int, length: Int, velocity: Float, versions: Int)

    /** Segment [slot] of envelope [id]; a time of 0 clears the slot. */
    fun setSegment(id: Long, slot: Int, time: Float, level: Float, curve: Float, sustain: Boolean)
    /**
     * Section [slot] of Arranger [id] -- song * MAX_SECTIONS + section -- plays [scene] for
     * [length] steps; a length of 0 is the end of that song.
     */
    fun setSection(id: Long, slot: Int, scene: Int, length: Int)
    fun collectGarbage()
}

/**
 * The real one.
 *
 * Debug builds log every command that crosses, because "does this gesture disturb the
 * audio graph?" is otherwise answered by guessing. Anything that changes a cable makes a
 * 30ms crossfade, so a command arriving when none was expected is audible.
 */
object EngineCommands : GraphCommands {
    private const val TAG = "PatchSync"

    private inline fun trace(what: () -> String) {
        if (BuildConfig.DEBUG) Log.d(TAG, what())
    }

    override fun addNode(id: Long, type: NodeType) {
        trace { "add $id $type" }
        AudioEngine.addNode(id, type)
    }

    override fun removeNode(id: Long) {
        trace { "remove $id" }
        AudioEngine.removeNode(id)
    }

    override fun connect(srcId: Long, srcPort: Int, dstId: Long, dstPort: Int) {
        trace { "connect $srcId[$srcPort] -> $dstId[$dstPort]" }
        AudioEngine.connect(srcId, srcPort, dstId, dstPort)
    }

    override fun disconnect(srcId: Long, srcPort: Int, dstId: Long, dstPort: Int) {
        trace { "disconnect $srcId[$srcPort] -> $dstId[$dstPort]" }
        AudioEngine.disconnect(srcId, srcPort, dstId, dstPort)
    }

    override fun setParam(id: Long, index: Int, value: Float) {
        trace { "param $id[$index] = $value" }
        AudioEngine.setParam(id, index, value)
    }

    override fun setModRange(id: Long, index: Int, low: Float, high: Float, exponential: Boolean, stepped: Boolean) {
        trace { "range $id[$index] = $low..$high${if (exponential) " exp" else ""}${if (stepped) " stepped" else ""}" }
        AudioEngine.setModRange(id, index, low, high, exponential, stepped)
    }

    override fun connectMod(srcId: Long, srcPort: Int, dstId: Long, index: Int) {
        trace { "modulate $srcId[$srcPort] -> $dstId.param[$index]" }
        AudioEngine.connectMod(srcId, srcPort, dstId, index)
    }

    override fun disconnectMod(srcId: Long, srcPort: Int, dstId: Long, index: Int) {
        trace { "unmodulate $srcId[$srcPort] -> $dstId.param[$index]" }
        AudioEngine.disconnectMod(srcId, srcPort, dstId, index)
    }

    override fun setStep(id: Long, index: Int, degree: Int, gate: Boolean) {
        trace { "step $id[$index] = degree $degree ${if (gate) "on" else "rest"}" }
        AudioEngine.setStep(id, index, degree, gate)
    }

    override fun setScales(entries: List<ScaleEntry>, beatsPerBar: Int) {
        trace {
            "scales " + entries.joinToString {
                "${it.scale.name} at ${it.rootCents}c for ${it.lengthInBeats(beatsPerBar)}"
            }
        }
        AudioEngine.setScales(entries, beatsPerBar)
    }

    override fun setTempo(bpm: Float) {
        trace { "tempo $bpm" }
        AudioEngine.setTempo(bpm)
    }

    override fun setDot(id: Long, slot: Int, step: Int, degree: Int, length: Int, velocity: Float, versions: Int) {
        trace { "dot $id[$slot] = step $step degree $degree for $length at $velocity" }
        AudioEngine.setDot(id, slot, step, degree, length, velocity, versions)
    }

    override fun setSegment(
        id: Long, slot: Int, time: Float, level: Float, curve: Float, sustain: Boolean,
    ) {
        trace { "seg $id[$slot] = ${time}s to $level curve $curve${if (sustain) " sustain" else ""}" }
        AudioEngine.setSegment(id, slot, time, level, curve, sustain)
    }

    override fun setSection(id: Long, slot: Int, scene: Int, length: Int) {
        trace { "section $id[$slot] = scene $scene for $length" }
        AudioEngine.setSection(id, slot, scene, length)
    }

    override fun setFont(id: Long, font: Long) {
        trace { "font $id = $font" }
        AudioEngine.setNodeFont(id, font)
    }

    override fun collectGarbage() { AudioEngine.collectGarbage() }
}

/**
 * The ranges the engine is told about for [module]: every stored one, and each driven knob's
 * *effective* range whether stored or not -- see [PatchModule.drivenRange].
 *
 * Sent always rather than left to a default in the engine, so the two sides cannot disagree
 * about what an unmoved bracket means, and so an undo that takes a stored range away is a
 * range sent -- the default again -- rather than one the engine quietly goes on using. No
 * command removes a range, which is harmless for an exposed knob with nothing in its jack and
 * would not be for a driven one.
 */
internal fun rangesFor(module: PatchModule): Map<Int, ModRange> =
    module.modRanges + module.type.params.indices
        .filter { module.isDriven(it) }
        .associateWith { module.drivenRange(it) }

/**
 * Keeps the audio graph tracking the patch.
 *
 * Diffing a shadow copy rather than emitting commands at each mutation point, because
 * the same code then handles every way a patch can change -- an edit, a file loaded at
 * launch, and eventually an undo -- instead of each needing its own hook that can be
 * forgotten.
 *
 * Commands are ordered so the graph is never asked to reference something that is not
 * there yet: drop cables, then nodes, then add nodes, then make cables.
 */
class GraphSync(private val commands: GraphCommands = EngineCommands) {

    private var syncedNodes = emptyMap<Long, NodeType>()
    private var syncedCables = emptySet<Connection>()
    private var syncedParams = emptyMap<Long, List<Float>>()
    private var syncedRanges = emptyMap<Long, Map<Int, ModRange>>()
    private var syncedSteps = emptyMap<Long, List<Step>>()
    private var syncedScales: List<ScaleEntry>? = null
    private var syncedBeatsPerBar: Int? = null
    private var syncedTempo: Float? = null
    private var syncedFonts = emptyMap<Long, Long>()
    private var syncedDots = emptyMap<Long, List<Dot>>()
    private var syncedSegments = emptyMap<Long, List<EnvSegment>>()
    private var syncedLevels = emptyMap<Long, List<Float>>()
    private var syncedCells = emptyMap<Long, List<SceneCell>>()
    private var syncedSections = emptyMap<Long, List<SectionEntry>>()

    /**
     * One slot-indexed list, diffed against what the engine was last told, and the new
     * shadow to keep.
     *
     * Steps, dots and segments were three near-identical copies of this, and the copies had
     * already drifted: the steps one compared against `syncedSteps[id]` where the other two
     * compared against null for a node in [fresh]. Unreachable, because a module's type is a
     * `val` and an id is never reused by a different type -- but it is the drift, not the
     * bug, that is the point. A fourth list would have been a fourth copy and a fourth
     * chance, and the ones before it each cost a real defect somewhere on this path.
     *
     * [applies] picks the modules that have this list; [clear] is null for a fixed-length
     * one, whose slots are never vacated.
     */
    private fun <T> diffSlots(
        sounding: List<EngineNode>,
        fresh: Set<Long>,
        previousAll: Map<Long, List<T>>,
        applies: (ModuleType) -> Boolean,
        read: (PatchModule) -> List<T>,
        send: (Long, Int, T) -> Unit,
        clear: ((Long, Int) -> Unit)?,
    ): Map<Long, List<T>> {
        val now = sounding.filter { it.module?.type?.let(applies) == true }
            .associate { it.id to read(it.module!!) }
        now.forEach { (id, list) ->
            // Everything for a node that was just made: the engine's copy knows none of it.
            val previous = if (id in fresh) null else previousAll[id]
            list.forEachIndexed { slot, value ->
                if (previous?.getOrNull(slot) != value) send(id, slot, value)
            }
            if (clear != null) {
                for (slot in list.size until (previous?.size ?: 0)) clear(id, slot)
            }
        }
        return now
    }

    /** [diffSlots] for entries that say their own slot: compared by that, not by place in the list. */
    private fun <T> diffKeyed(
        sounding: List<EngineNode>,
        fresh: Set<Long>,
        previousAll: Map<Long, List<T>>,
        applies: (ModuleType) -> Boolean,
        read: (PatchModule) -> List<T>,
        key: (T) -> Int,
        send: (Long, T) -> Unit,
    ): Map<Long, List<T>> {
        val now = sounding.filter { it.module?.type?.let(applies) == true }
            .associate { it.id to read(it.module!!) }
        now.forEach { (id, list) ->
            val previous = if (id in fresh) emptyMap() else previousAll[id].orEmpty().associateBy(key)
            list.forEach { entry -> if (previous[key(entry)] != entry) send(id, entry) }
        }
        return now
    }

    /** Forget what the engine has, so the next sync re-sends everything. */
    fun invalidate() {
        syncedNodes = emptyMap()
        syncedCables = emptySet()
        syncedParams = emptyMap()
        syncedRanges = emptyMap()
        syncedSteps = emptyMap()
        syncedScales = null
        syncedBeatsPerBar = null
        syncedTempo = null
        syncedFonts = emptyMap()
        syncedDots = emptyMap()
        syncedSegments = emptyMap()
        syncedLevels = emptyMap()
        syncedCells = emptyMap()
        syncedSections = emptyMap()
    }

    /**
     * [fonts] is which SoundFonts are loaded, by name, as native handles. An SF module whose
     * font is not among them yet is sent nothing and stays silent; the sync after its font
     * lands hands it over.
     */
    fun sync(patch: Patch, fonts: Map<String, Long> = emptyMap()) {
        // The patch flattened. A plain subpatch and its rails are not nodes, and a cable
        // through its ports arrives as the one cable it stands for -- so subpatching modules
        // that are already playing sends the engine nothing at all. A poly subpatch is
        // flattened by copying: as many of everything inside it as it has voices, with a node
        // at each of its edges. Either way the engine is handed a flat graph.
        val graph = patch.engineGraph()
        val sounding = graph.nodes
        val nodes = sounding.associate { it.id to it.type }
        val cables = graph.cables

        // Note inputs are excluded: they merge rather than replace, so a new cable into
        // one supersedes nothing and the cable that left still has to be sent. Asked of the
        // flattened graph, because half of these are ports on nodes no module has.
        val replaced = (cables - syncedCables)
            .map { it.to }
            .filter { it !in graph.noteInputs }
            .toSet()

        (syncedCables - cables).forEach {
            // A connect to the same input supersedes a disconnect, because signal inputs
            // are single-source. Sending both makes the engine fade the old source out to
            // silence and then fade the new one in from silence -- and since they arrive
            // in the same drain, the second fade starts from silence rather than from
            // what was playing, which steps. Letting the connect stand on its own is
            // what makes replacing a cable an actual crossfade.
            if (it.to !in replaced) {
                if (it.to.dir == PortDirection.MOD) {
                    commands.disconnectMod(it.from.moduleId, it.from.index, it.to.moduleId, it.to.index)
                } else {
                    commands.disconnect(it.from.moduleId, it.from.index, it.to.moduleId, it.to.index)
                }
            }
        }

        (syncedNodes.keys - nodes.keys).forEach { commands.removeNode(it) }

        val fresh = mutableSetOf<Long>()
        nodes.forEach { (id, type) ->
            val had = syncedNodes[id]
            if (had != type) {
                // A type change would be a different node wearing the same id.
                if (had != null) commands.removeNode(id)
                commands.addNode(id, type)
                fresh += id
            }
        }

        // Ranges before the cables that use them. The engine ignores a modulator on a
        // parameter that has no range yet, so the other order would be silent rather than
        // wrong -- but only until the range arrived, and a queue drained partway through a
        // sync would let a block render in between. Every range of a node that was just
        // made, because the engine's node knows none of them.
        val ranges = sounding.associate { it.id to it.module?.let(::rangesFor).orEmpty() }
        sounding.forEach { node ->
            val type = node.module?.type ?: return@forEach
            val previous = if (node.id in fresh) null else syncedRanges[node.id]
            ranges.getValue(node.id).forEach { (index, range) ->
                if (previous?.get(index) != range) {
                    val curve = type.params.getOrNull(index)?.curve
                    commands.setModRange(
                        node.id, index, range.low, range.high,
                        curve == ParamCurve.EXPONENTIAL, curve == ParamCurve.STEPPED,
                    )
                }
            }
        }

        (cables - syncedCables).forEach {
            if (it.to.dir == PortDirection.MOD) {
                commands.connectMod(it.from.moduleId, it.from.index, it.to.moduleId, it.to.index)
            } else {
                commands.connect(it.from.moduleId, it.from.index, it.to.moduleId, it.to.index)
            }
        }

        // A font before the knobs, though either order sounds the same: the node applies
        // its preset whenever either arrives. Resent to a node that was just made, and to
        // one whose font changed or has only now finished loading.
        val wanted = sounding.filter { it.module?.type == Types.Sf }
            .mapNotNull { n -> n.module?.font?.let { name -> fonts[name] }?.let { n.id to it } }
            .toMap()
        wanted.forEach { (id, handle) ->
            if (id in fresh || syncedFonts[id] != handle) commands.setFont(id, handle)
        }

        // Knobs last, and every knob of a node that was just added: the engine's node
        // starts at its own C++ defaults, which are not required to agree with the ones
        // declared here, and a patch loaded from disk has values for all of them.
        val params = sounding.associate { it.id to (it.module?.engineParams(patch.beatsPerBar) ?: emptyList()) }
        params.forEach { (id, values) ->
            val previous = syncedParams[id]
            values.forEachIndexed { index, value ->
                if (previous == null || previous.getOrNull(index) != value) {
                    commands.setParam(id, index, value)
                }
            }
        }

        // The slot-indexed lists, all three through one pass. A sequence's steps are as
        // degrees: the engine resolves each note against the scale sounding on the beat it
        // starts, so a change of scale resends the list below and not a single step.
        val steps = diffSlots(
            sounding, fresh, syncedSteps,
            applies = { it.stepCount > 0 },
            read = { it.steps.toList() },
            send = { id, slot, step -> commands.setStep(id, slot, step.degree, step.on) },
            // Fixed length: a sequencer always has every step, so none is ever vacated.
            clear = null,
        )
        val dots = diffSlots(
            sounding, fresh, syncedDots,
            applies = { it.grid == GridKind.DOTS },
            read = { it.dots.toList() },
            send = { id, slot, dot ->
                commands.setDot(id, slot, dot.step, dot.degree, dot.length, dot.velocity, dot.versions)
            },
            clear = { id, slot -> commands.setDot(id, slot, 0, 0, 0, 1f, 0) },
        )
        // A ModSeq's steps go as segment slots -- a level, and one day a curve, without the time
        // a step already is -- through the same pass; the time only says the slot is in use.
        val levels = diffSlots(
            sounding, fresh, syncedLevels,
            applies = { it.grid == GridKind.LEVELS },
            read = { it.levels.toList() },
            send = { id, slot, level -> commands.setSegment(id, slot, 1f, level, 0f, false) },
            // Fixed length: every step is always there.
            clear = null,
        )
        val segments = diffSlots(
            sounding, fresh, syncedSegments,
            applies = { it.grid == GridKind.ENVELOPE },
            read = { it.segments.toList() },
            send = { id, slot, seg ->
                commands.setSegment(id, slot, seg.time, seg.level, seg.curve, seg.sustain)
            },
            // A time of 0 vacates the slot, and because segments are contiguous the engine
            // reads the first vacated one as the end of the envelope.
            clear = { id, slot -> commands.setSegment(id, slot, 0f, 0f, 0f, false) },
        )

        // An Arranger's scenes as segment slots, a level a cell at scene * LANES_MAX + lane, as a
        // ModSeq's steps; and its songs as section slots, each song at its own stride with a
        // section of length 0 after its last. Compared by the slot each goes to rather than by
        // where it sits in these flat lists, since a song growing moves every entry after it in
        // the list and none of them in the engine. Nothing is ever cleared: scenes are only
        // added, and every song's end is in the list, so what lies past one is never read.
        val cells = diffKeyed(
            sounding, fresh, syncedCells,
            applies = { it.grid == GridKind.SONG },
            read = { m -> sceneCells(m) },
            key = { it.slot },
            send = { id, cell -> commands.setSegment(id, cell.slot, 1f, cell.level, 0f, false) },
        )
        val sections = diffKeyed(
            sounding, fresh, syncedSections,
            applies = { it.grid == GridKind.SONG },
            read = { m -> sectionEntries(m) },
            key = { it.slot },
            send = { id, entry -> commands.setSection(id, entry.slot, entry.scene, entry.length) },
        )

        // The scale list, whole, when it or the bar length changes: entries last bars and
        // beats, and the engine counts only beats.
        if (syncedScales != patch.scales || syncedBeatsPerBar != patch.beatsPerBar) {
            commands.setScales(patch.scales, patch.beatsPerBar)
        }

        // The transport's rate, on a change of value alone. Not a node, so not part of the
        // diff above -- and only the rate: where the transport has got to belongs to the
        // engine, and is not the patch's to send.
        if (syncedTempo != patch.tempo) commands.setTempo(patch.tempo)

        syncedNodes = nodes
        syncedCables = cables
        syncedParams = params
        syncedRanges = ranges
        syncedSteps = steps
        syncedScales = patch.scales
        syncedBeatsPerBar = patch.beatsPerBar
        syncedTempo = patch.tempo
        syncedFonts = wanted
        syncedDots = dots
        syncedSegments = segments
        syncedLevels = levels
        syncedCells = cells
        syncedSections = sections

        // Whatever the audio thread retired during the last block is ours to free.
        commands.collectGarbage()
    }
}

/** One cell of an Arranger's scenes as the engine keeps it: its slot and its level. */
internal data class SceneCell(val slot: Int, val level: Float)

/** One section slot of an Arranger's songs as the engine keeps it; a [length] of 0 ends a song. */
internal data class SectionEntry(val slot: Int, val scene: Int, val length: Int)

/** Every cell of every scene, in slot order. */
internal fun sceneCells(module: PatchModule): List<SceneCell> =
    module.scenes.flatMapIndexed { scene, s ->
        s.levels.mapIndexed { lane, level -> SceneCell(scene * LANES_MAX + lane, level) }
    }

/**
 * Every song's sections, each song followed by the end that stops it, for every song there could
 * be -- so a song deleted, or one never made, is an empty song in the engine rather than whatever
 * was last there.
 */
internal fun sectionEntries(module: PatchModule): List<SectionEntry> =
    (0 until MAX_SONGS).flatMap { song ->
        val sections = module.songs.getOrNull(song)?.sections.orEmpty().take(MAX_SECTIONS)
        val base = song * MAX_SECTIONS
        sections.mapIndexed { at, section -> SectionEntry(base + at, section.scene, section.steps) } +
            (if (sections.size < MAX_SECTIONS) listOf(SectionEntry(base + sections.size, 0, 0)) else emptyList())
    }
