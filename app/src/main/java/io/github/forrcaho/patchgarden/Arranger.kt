package io.github.forrcaho.patchgarden

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// The Arranger, roadmap item 5: what plays when. Lanes of modulation down the side, each named
// by the knob its cable reaches; sections of a song across, each a scene played for some steps.
//
// Designed overnight 2026-10-04 and decided by Forrest on 2026-10-05, every question taking the
// proposal but one: scenes and a song rather than a plain table, so editing A edits every A;
// four lanes, adding up to eight; loop or stop at the end as a switch of its own; holding where
// it is when the transport stops; a jump to a section left for later; and a long song scrolled
// sideways by dragging the row of section heads.
//
// The engine's half is ArrangerNode, which never learns what a scene is called or what a lane
// is patched to: scenes cross as segment slots, a level each as a ModSeq's steps do, and songs
// as section slots.

/** How many lanes there can be: an output each. Mirrors ArrangerNode::kLanes. */
internal const val LANES_MAX = 8

/** How many lanes a new Arranger has. */
internal const val DEFAULT_LANES = 4

/** Mirrors ArrangerNode::kMaxScenes. Sixteen letters, A to P, and their primes. */
internal const val MAX_SCENES = 16

/** A song's sections at most. Mirrors ArrangerNode::kMaxSections, the stride of a section slot. */
internal const val MAX_SECTIONS = 64

/** Mirrors ArrangerNode::kMaxSongs: the Arranger's versions. */
internal const val MAX_SONGS = 8

/** How many steps a section lasts at most, typed on the keypad. */
internal const val MAX_SECTION_STEPS = 256

/**
 * A value for every lane, each 0 to 1 between the brackets of whatever knob the lane drives, as a
 * ModSeq's steps are. [levels] is always [LANES_MAX] long: a lane added later already has a value,
 * and one taken away takes its column with it.
 */
data class Scene(val name: String, val levels: List<Float>) {
    init {
        require(levels.size == LANES_MAX)
    }

    fun with(lane: Int, level: Float): Scene =
        copy(levels = levels.toMutableList().also { it[lane] = level.coerceIn(0f, 1f) })
}

/** Scene [scene] (an index into the Arranger's scenes) for [steps] steps of the interval. */
data class Section(val scene: Int, val steps: Int)

/** A song: sections in the order they play. */
data class Song(val sections: List<Section>)

/** A new Arranger's: one scene, A, with every lane at its middle, and a song of it for four steps. */
internal fun defaultScenes() = listOf(Scene("A", List(LANES_MAX) { 0.5f }))
internal fun defaultSongs() = listOf(Song(listOf(Section(0, 4))))

/** The letter a scene's name starts with, primes and all taken off: B for B, B′ and B″. */
internal fun sceneBase(name: String): String = name.trimEnd('′', '″', '‴')

/**
 * What to call a new scene. A copy of one is that one primed -- B′ from B, B″ from B′ -- since a
 * variation of a section is how B′ reads in music; anything else is the first letter unused.
 */
internal fun newSceneName(names: List<String>, copyOf: String?): String {
    if (copyOf != null) {
        val base = sceneBase(copyOf)
        val primes = listOf("′", "″", "‴")
        primes.forEach { prime -> if ("$base$prime" !in names) return "$base$prime" }
        var n = 4
        while ("$base′$n" in names) n++
        return "$base′$n"
    }
    val used = names.map { sceneBase(it) }.toSet()
    return ('A'..'Z').map { it.toString() }.firstOrNull { it !in used } ?: "S${names.size + 1}"
}

/** How many lanes show: an output each, the first [laneCount] of [LANES_MAX]. */
internal val PatchModule.laneCount: Int
    get() {
        val index = type.params.indexOfFirst { it.header && it.name == "lanes" }
        if (index < 0) return 0
        return params.getOrElse(index) { type.params[index].default }.roundToInt().coerceIn(1, LANES_MAX)
    }

private val PatchModule.lanesParam: Int get() = type.params.indexOfFirst { it.header && it.name == "lanes" }

/** The `end` switch: true when the song stops at its end rather than looping. */
internal val PatchModule.stopsAtEnd: Boolean
    get() {
        val index = type.params.indexOfFirst { it.header && it.name == "end" }
        return index >= 0 && params.getOrElse(index) { 0f } >= 0.5f
    }

internal fun PatchModule.toggleEnd() {
    val index = type.params.indexOfFirst { it.header && it.name == "end" }
    if (index >= 0) setParam(index, if (stopsAtEnd) 0f else 1f)
}

/** The song the table shows and edits: [PatchModule.shownVersion], which for an Arranger is never 0. */
internal val PatchModule.shownSong: Int get() = (shownVersion - 1).coerceIn(0, (songs.size - 1).coerceAtLeast(0))

/** The sections of the song shown. */
internal val PatchModule.shownSections: List<Section> get() = songs.getOrNull(shownSong)?.sections.orEmpty()

private fun PatchModule.editShown(edit: (MutableList<Section>) -> Unit) {
    val at = shownSong
    val song = songs.getOrNull(at) ?: return
    songs[at] = Song(song.sections.toMutableList().also(edit))
}

/** Sets lane [lane] of scene [scene]. Every section that plays the scene follows. */
internal fun PatchModule.setCell(scene: Int, lane: Int, level: Float) {
    val old = scenes.getOrNull(scene) ?: return
    if (lane !in 0 until LANES_MAX) return
    scenes[scene] = old.with(lane, level)
}

/** Section [at] of the song shown plays [scene]. */
internal fun PatchModule.setSectionScene(at: Int, scene: Int) {
    if (scene !in scenes.indices) return
    editShown { sections -> if (at in sections.indices) sections[at] = sections[at].copy(scene = scene) }
}

/** Section [at] of the song shown lasts [steps]. */
internal fun PatchModule.setSectionSteps(at: Int, steps: Int) {
    editShown { sections ->
        if (at in sections.indices) sections[at] = sections[at].copy(steps = steps.coerceIn(1, MAX_SECTION_STEPS))
    }
}

/**
 * A new scene copying [from]'s values and named for it (B′ from B), played by section [at] of the
 * song shown -- the head's "+", which copies the scene showing as a Seq's "+" copies a version.
 */
internal fun PatchModule.branchScene(at: Int): Boolean {
    val section = shownSections.getOrNull(at) ?: return false
    val from = scenes.getOrNull(section.scene) ?: return false
    if (scenes.size >= MAX_SCENES) return false
    scenes.add(from.copy(name = newSceneName(scenes.map { it.name }, from.name)))
    setSectionScene(at, scenes.size - 1)
    return true
}

/**
 * A section after the last: a new scene, the next letter, starting from the last section's
 * values and length -- the column of "+" at the end. A new scene rather than the last one again,
 * since a scene repeated back to back changes no version and so restarts nothing. When the
 * scenes are full it repeats the last one instead.
 */
internal fun PatchModule.appendSection(): Boolean {
    val sections = shownSections
    if (sections.size >= MAX_SECTIONS) return false
    val last = sections.lastOrNull() ?: Section(0, 4)
    val scene = if (scenes.size < MAX_SCENES) {
        val from = scenes.getOrNull(last.scene) ?: scenes.first()
        scenes.add(Scene(newSceneName(scenes.map { it.name }, null), from.levels))
        scenes.size - 1
    } else {
        last.scene
    }
    editShown { it.add(Section(scene, last.steps)) }
    return true
}

/** Section [at] of the song shown, gone; a song always keeps one. */
internal fun PatchModule.deleteSection(at: Int): Boolean {
    if (shownSections.size <= 1 || at !in shownSections.indices) return false
    editShown { it.removeAt(at) }
    return true
}

/** One more lane, up to [LANES_MAX]: a new jack under the others, so nothing that was there moves. */
internal fun PatchModule.addLane(): Boolean {
    val index = lanesParam
    if (index < 0 || laneCount >= LANES_MAX) return false
    setParam(index, (laneCount + 1).toFloat())
    return true
}

/**
 * Lane [lane] gone, its cables with it, and every lane after it moved up one -- its column of
 * every scene and its jack, so each cable after it is renumbered to the jack it was on. The
 * last lane cannot go. Ports are positional, so this is the one way an Arranger's jacks move,
 * as dropping a subpatch's port is a box's.
 */
internal fun Patch.removeLane(module: PatchModule, lane: Int): Boolean {
    val count = module.laneCount
    if (count <= 1 || lane !in 0 until count) return false
    val index = module.type.params.indexOfFirst { it.header && it.name == "lanes" }
    fun ours(ref: PortRef) = ref.moduleId == module.id && ref.dir == PortDirection.OUTPUT
    // In one snapshot, as removeSubpatchPort does: the sync must never see the cables cleared and
    // not yet put back, which the engine would render faithfully as everything unpatched.
    Snapshot.withMutableSnapshot {
        connections.removeAll { ours(it.from) && it.from.index == lane }
        val renumbered = connections.map { c ->
            if (ours(c.from) && c.from.index > lane) c.copy(from = c.from.copy(index = c.from.index - 1)) else c
        }
        connections.clear()
        connections.addAll(renumbered)
        module.scenes.indices.forEach { i ->
            val levels = module.scenes[i].levels.toMutableList()
            levels.removeAt(lane)
            levels.add(0.5f)
            module.scenes[i] = module.scenes[i].copy(levels = levels)
        }
        module.setParam(index, (count - 1).toFloat())
    }
    return true
}

/**
 * The knob lane [lane] drives, when it drives exactly one: [Patch.modulationTarget] for that
 * output, which is what names a lane and says its cells in the knob's own terms.
 */
internal fun Patch.laneTarget(module: PatchModule, lane: Int): ParamRow? = modulationTarget(module, lane)

/** A lane's name: the module and the knob it drives, or its number with nothing patched. */
internal fun Patch.laneName(module: PatchModule, lane: Int): Pair<String, String> =
    laneTarget(module, lane)?.let { it.owner.title to it.param.name } ?: ("lane ${lane + 1}" to "")

// ---------------------------------------------------------------- the table

/** What a touch on the table lands on. */
internal sealed interface ArrangerHit {
    /** The corner above the lane names: another lane. */
    data object AddLane : ArrangerHit
    /** The column of "+" after the last section: another section. */
    data object AddSection : ArrangerHit
    /** Section [at]'s head: its scene and its length. */
    data class Head(val at: Int) : ArrangerHit
    /** Lane [lane]'s name. */
    data class Lane(val lane: Int) : ArrangerHit
    /** Lane [lane]'s value in the scene section [at] plays. */
    data class Cell(val lane: Int, val at: Int) : ArrangerHit
}

/**
 * The table laid out, for the drawing and the hit test alike: lane names down the left under a
 * corner, section heads across the top, a cell where each lane meets each section, and a "+"
 * after the last section. Sideways it shows the sections from [firstSection] that fit; down, the
 * lanes from [firstLane] -- past four at the reference device's text size, the lanes scroll too,
 * dragged by their names as the sections are by their heads.
 */
internal class ArrangerTable(
    val area: Rect,
    val corner: Rect,
    /** The row of heads, the corner excepted: where a sideways drag scrolls the sections. */
    val headBand: Rect,
    /** The column of names, the corner excepted: where a drag down scrolls the lanes. */
    val nameBand: Rect,
    val heads: List<Pair<Int, Rect>>,
    val plus: Rect?,
    val names: List<Pair<Int, Rect>>,
    val cells: List<Triple<Int, Int, Rect>>,
    val colW: Float,
    val laneH: Float,
    val firstSection: Int,
    val firstLane: Int,
    val maxSectionScroll: Int,
    val maxLaneScroll: Int,
    val canAddLane: Boolean,
) {
    fun hit(at: Offset): ArrangerHit? {
        if (corner.contains(at)) return if (canAddLane) ArrangerHit.AddLane else null
        heads.firstOrNull { it.second.contains(at) }?.let { return ArrangerHit.Head(it.first) }
        if (plus?.contains(at) == true) return ArrangerHit.AddSection
        names.firstOrNull { it.second.contains(at) }?.let { return ArrangerHit.Lane(it.first) }
        cells.firstOrNull { it.third.contains(at) }?.let { return ArrangerHit.Cell(it.first, it.second) }
        return null
    }

    fun headOf(at: Int): Rect? = heads.firstOrNull { it.first == at }?.second
    fun cellOf(lane: Int, at: Int): Rect? = cells.firstOrNull { it.first == lane && it.second == at }?.third
}

/** Widths and heights that hold their text: in dp, grown with the font as a menu tile is. */
private const val ARRANGER_NAME_W = 96f
private const val ARRANGER_HEAD_H = 32f
private const val ARRANGER_LANE_H = 29f
private const val ARRANGER_COL_W = 76f
private const val ARRANGER_PLUS_W = 40f

internal fun arrangerTable(area: Rect, d: Float, fontScale: Float, module: PatchModule): ArrangerTable {
    val text = fontScale.coerceAtLeast(1f)
    val nameW = minOf(ARRANGER_NAME_W * text * d, area.width * 0.3f)
    val headH = ARRANGER_HEAD_H * text * d
    val laneH = ARRANGER_LANE_H * text * d
    val colW = ARRANGER_COL_W * text * d
    val gap = 3f * d

    // The "+" after the last section is narrow -- it holds a "+" and nothing else -- so that four
    // sections and it fit together on the phone. A full column for it, the first build, scrolled
    // it out of view at exactly four, which left adding a section hidden behind a drag.
    val plusW = ARRANGER_PLUS_W * minOf(text, 1.25f) * d
    val sections = module.shownSections
    val canAddSection = sections.size < MAX_SECTIONS
    val fitCols = ((area.width - nameW) / colW).toInt().coerceAtLeast(1)
    // Scrolled to the end, the last sections and the "+" after them are all in view.
    val fitBeforePlus = ((area.width - nameW - (if (canAddSection) plusW else 0f)) / colW).toInt().coerceAtLeast(1)
    val maxSectionScroll = (sections.size - fitBeforePlus).coerceAtLeast(0)
    val firstSection = module.sectionScroll.coerceIn(0, maxSectionScroll)

    val lanes = module.laneCount
    val fitLanes = ((area.height - headH) / laneH).toInt().coerceAtLeast(1)
    val maxLaneScroll = (lanes - fitLanes).coerceAtLeast(0)
    val firstLane = module.laneScroll.coerceIn(0, maxLaneScroll)

    val corner = Rect(area.left, area.top, area.left + nameW, area.top + headH)
    val heads = mutableListOf<Pair<Int, Rect>>()
    var plus: Rect? = null
    // One more than the full columns that fit, since the "+" after them is narrower than one.
    for (k in 0..fitCols) {
        val at = firstSection + k
        val left = area.left + nameW + k * colW
        if (at < sections.size) {
            if (k == fitCols) break
            heads += at to Rect(left + gap, area.top + gap, left + colW - gap, area.top + headH - gap)
        } else {
            if (canAddSection && left + plusW <= area.right + 0.5f) {
                plus = Rect(left + gap, area.top + gap, left + plusW - gap, area.top + headH - gap)
            }
            break
        }
    }
    val names = mutableListOf<Pair<Int, Rect>>()
    val cells = mutableListOf<Triple<Int, Int, Rect>>()
    for (k in 0 until fitLanes) {
        val lane = firstLane + k
        if (lane >= lanes) break
        val top = area.top + headH + k * laneH
        names += lane to Rect(area.left, top + gap, area.left + nameW - gap, top + laneH - gap)
        heads.forEach { (at, head) -> cells += Triple(lane, at, Rect(head.left, top + gap, head.right, top + laneH - gap)) }
    }
    return ArrangerTable(
        area = area,
        corner = corner,
        headBand = Rect(corner.right, area.top, area.right, corner.bottom),
        nameBand = Rect(area.left, corner.bottom, corner.right, area.bottom),
        heads = heads, plus = plus, names = names, cells = cells,
        colW = colW, laneH = laneH,
        firstSection = firstSection, firstLane = firstLane,
        maxSectionScroll = maxSectionScroll, maxLaneScroll = maxLaneScroll,
        canAddLane = lanes < LANES_MAX,
    )
}

/** How long a section of [steps] lasts, said as the interval says a step: "4 bars", "3/2 beats". */
internal fun PatchModule.sectionLength(steps: Int): String {
    val step = interval
    return if (step.free) "$steps" else Interval(step.num * steps, step.den, step.bars).label
}

// ---------------------------------------------------------------- the choosers

/** Which chooser is open over the table. View state, as the version strip's is. */
internal sealed interface ArrangerPop {
    data class Cell(val lane: Int, val at: Int) : ArrangerPop
    data class Head(val at: Int) : ArrangerPop
}

/** What a tap in a chooser picks. */
internal sealed interface ArrangerPick {
    /** One of a stepped knob's options, at its level between the brackets. */
    data class Level(val level: Float) : ArrangerPick
    data class Scene(val scene: Int) : ArrangerPick
    /** A new scene copying this section's, primed: B′ from B. */
    data object NewScene : ArrangerPick
    data class Steps(val steps: Int) : ArrangerPick
    /** A length past the tiles, typed. */
    data object OtherSteps : ArrangerPick
    /** The cell's value as a number, typed. */
    data object Type : ArrangerPick
}

/** The lengths offered as tiles; anything else is typed. */
internal val SECTION_LENGTHS = listOf(1, 2, 4, 8, 16)

/** A section's length as typed: whole steps. */
internal val SECTION_STEPS = Param("steps", 1f, MAX_SECTION_STEPS.toFloat(), 4f, curve = ParamCurve.STEPPED)

/**
 * A chooser laid out: its ground, a caption over each band of tiles, the tiles, and for a cell
 * whose knob is not whole options a slider and its reading in place of tiles.
 */
internal class ArrangerChooser(
    val ground: Rect,
    val captions: List<Pair<Offset, String>>,
    val tiles: List<Triple<Rect, ArrangerPick, String>>,
    val slider: Rect?,
    val reading: Rect?,
) {
    fun pickAt(at: Offset): ArrangerPick? {
        tiles.firstOrNull { it.first.contains(at) }?.let { return it.second }
        if (reading?.contains(at) == true) return ArrangerPick.Type
        return null
    }

    /** The level a finger at [x] on the slider means. */
    fun levelAt(x: Float): Float = slider?.let { ((x - it.left) / it.width).coerceIn(0f, 1f) } ?: 0f
}

/**
 * The options of the knob lane [lane] drives, when it is whole options and few enough for tiles:
 * each as the level that reaches it and the word it is said by. Null for a knob that is not, or
 * for nothing patched, whose cells take a slider.
 */
internal fun Patch.laneOptions(module: PatchModule, lane: Int): List<Pair<Float, String>>? {
    val target = laneTarget(module, lane) ?: return null
    if (target.param.curve != ParamCurve.STEPPED) return null
    val range = rangeOf(target.owner, target.index) ?: return null
    val low = range.low.roundToInt()
    val high = range.high.roundToInt()
    val count = kotlin.math.abs(high - low) + 1
    if (count > 12) return null
    return (0 until count).map { i ->
        val level = if (count == 1) 0f else i / (count - 1).toFloat()
        level to levelLabel(target, level)
    }
}

internal fun arrangerChooser(
    patch: Patch,
    module: PatchModule,
    table: ArrangerTable,
    body: Rect,
    d: Float,
    fontScale: Float,
    pop: ArrangerPop,
): ArrangerChooser? {
    val text = fontScale.coerceAtLeast(1f)
    val tileH = maxOf(44f, 29f * text) * d
    val captionH = 22f * text * d
    val gap = 6f * d
    val pad = 12f * d
    val maxW = body.width - 16f * d

    // Bands of tiles, each under its caption, wrapped to the width there is.
    fun lay(anchor: Rect, bands: List<Pair<String, List<Pair<ArrangerPick, String>>>>, tileW: List<Float>, extra: Float): ArrangerChooser {
        val widest = bands.indices.maxOf { b -> bands[b].second.size * (tileW[b] + gap) - gap } + 2f * pad
        val width = minOf(maxOf(widest, extra + 2f * pad, 200f * d), maxW)
        val rows = bands.indices.map { b ->
            val perRow = ((width - 2f * pad + gap) / (tileW[b] + gap)).toInt().coerceAtLeast(1)
            (bands[b].second.size + perRow - 1) / perRow
        }
        val height = pad + bands.indices.sumOf { b -> (captionH + rows[b] * (tileH + gap)).toDouble() }.toFloat() +
            (if (extra > 0f) captionH + tileH + gap else 0f) + pad - gap
        val left = (anchor.center.x - width / 2f).coerceIn(body.left + 8f * d, body.right - 8f * d - width)
        val below = anchor.bottom + 6f * d
        val top = if (below + height <= body.bottom - 4f * d) below
        else (anchor.top - 6f * d - height).coerceAtLeast(body.top + 4f * d)
        val ground = Rect(left, top, left + width, top + height)
        val captions = mutableListOf<Pair<Offset, String>>()
        val tiles = mutableListOf<Triple<Rect, ArrangerPick, String>>()
        var y = top + pad
        bands.forEachIndexed { b, (caption, picks) ->
            captions += Offset(left + pad, y) to caption
            y += captionH
            val perRow = ((width - 2f * pad + gap) / (tileW[b] + gap)).toInt().coerceAtLeast(1)
            picks.forEachIndexed { i, (pick, label) ->
                val x = left + pad + (i % perRow) * (tileW[b] + gap)
                val ty = y + (i / perRow) * (tileH + gap)
                tiles += Triple(Rect(x, ty, x + tileW[b], ty + tileH), pick, label)
            }
            y += rows[b] * (tileH + gap)
        }
        return ArrangerChooser(ground, captions, tiles, null, null)
    }

    return when (pop) {
        is ArrangerPop.Head -> {
            val anchor = table.headOf(pop.at) ?: return null
            val scenes = module.scenes.mapIndexed { i, s -> ArrangerPick.Scene(i) to s.name } +
                (if (module.scenes.size < MAX_SCENES) listOf(ArrangerPick.NewScene to "+") else emptyList())
            val lengths = SECTION_LENGTHS.map { ArrangerPick.Steps(it) to module.sectionLength(it) } +
                listOf(ArrangerPick.OtherSteps to "other…")
            lay(anchor, listOf("scene" to scenes, "length" to lengths), listOf(46f * text * d, 76f * text * d), 0f)
        }
        is ArrangerPop.Cell -> {
            val anchor = table.cellOf(pop.lane, pop.at) ?: return null
            val section = module.shownSections.getOrNull(pop.at) ?: return null
            val scene = module.scenes.getOrNull(section.scene) ?: return null
            val (owner, knob) = patch.laneName(module, pop.lane)
            val caption = listOf(owner, knob).filter { it.isNotEmpty() }.joinToString(" · ") + " in ${scene.name}"
            val options = patch.laneOptions(module, pop.lane)
            if (options != null) {
                lay(anchor, listOf(caption to options.map { (level, word) -> ArrangerPick.Level(level) to word }),
                    listOf(64f * text * d), 0f)
            } else {
                // A slider and its reading, for a knob of any value: the same 0 to 1 between the
                // brackets as a ModSeq's step, said in the knob's terms.
                val sliderW = minOf(320f * d, maxW - 2f * pad)
                val shell = lay(anchor, listOf(caption to emptyList()), listOf(64f * text * d), sliderW)
                val y = shell.ground.top + pad + captionH
                val readingW = 92f * text * d
                val slider = Rect(shell.ground.left + pad, y, shell.ground.right - pad - readingW - gap, y + tileH)
                val reading = Rect(slider.right + gap, y, shell.ground.right - pad, y + tileH)
                ArrangerChooser(shell.ground, shell.captions, emptyList(), slider, reading)
            }
        }
    }
}

// ---------------------------------------------------------------- drawing

private val ArrangerSceneStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE4E7EC))
private val ArrangerSmallStyle = TextStyle(fontSize = 9.sp, color = Color(0xFF8A93A3))
private val ArrangerCellStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFFE4E7EC))
private val ArrangerNameStyle = TextStyle(fontSize = 12.sp, color = Color(0xFFE4E7EC))
private val ArrangerKnobStyle = TextStyle(fontSize = 10.sp, color = Color(0xFF8A93A3))
private val ArrangerSilentStyle = ArrangerCellStyle.copy(color = Color(0xFF7E8896))

private fun DrawScope.drawCentered(measurer: TextMeasurer, text: String, style: TextStyle, center: Offset, width: Float) {
    val laid = measurer.measure(
        text, style, overflow = TextOverflow.Ellipsis, maxLines = 1,
        constraints = Constraints(maxWidth = width.toInt().coerceAtLeast(1)),
    )
    drawText(laid, topLeft = Offset(center.x - laid.size.width / 2f, center.y - laid.size.height / 2f))
}

/**
 * The table: lane names, heads, cells, the "+" column and corner, and where it has scrolled. A
 * cell says its value in its knob's terms and shows how far between the brackets it is as a bar
 * along its foot; "off" is drawn faint. The section playing is underlined, when the song
 * playing is the one shown.
 */
internal fun DrawScope.drawArranger(
    patch: Patch,
    module: PatchModule,
    table: ArrangerTable,
    d: Float,
    measurer: TextMeasurer,
    playingSection: Int,
    accent: Color,
) {
    val corner = CornerRadius(7f * d, 7f * d)
    val sections = module.shownSections

    // The corner: another lane, while there is room for one.
    if (table.canAddLane) {
        val chip = Rect(table.corner.left + 6f * d, table.corner.top + 6f * d, table.corner.right - 6f * d, table.corner.bottom - 6f * d)
        drawChip(chip, d, "+ lane", false, accent, measurer)
    }

    table.heads.forEach { (at, rect) ->
        val section = sections[at]
        drawRoundRect(ChipFill, rect.topLeft, rect.size, corner)
        drawRoundRect(ChipEdge, rect.topLeft, rect.size, corner, style = Stroke(width = 1.5f * d))
        val name = module.scenes.getOrNull(section.scene)?.name ?: "?"
        drawCentered(measurer, name, ArrangerSceneStyle, Offset(rect.center.x, rect.top + rect.height * 0.38f), rect.width - 6f * d)
        drawCentered(measurer, module.sectionLength(section.steps), ArrangerSmallStyle,
            Offset(rect.center.x, rect.top + rect.height * 0.78f), rect.width - 6f * d)
        if (at == playingSection) {
            drawRect(Color.White, Offset(rect.left, rect.bottom + 1f * d), Size(rect.width, 3f * d))
        }
    }
    table.plus?.let { rect ->
        drawRoundRect(ChipFill, rect.topLeft, rect.size, corner)
        drawRoundRect(ChipEdge, rect.topLeft, rect.size, corner, style = Stroke(width = 1.5f * d))
        drawCentered(measurer, "+", ArrangerSceneStyle, rect.center, rect.width)
    }

    table.names.forEach { (lane, rect) ->
        val (owner, knob) = patch.laneName(module, lane)
        val x = rect.left + 8f * d
        val first = measurer.measure(
            "${lane + 1}  $owner", ArrangerNameStyle, overflow = TextOverflow.Ellipsis, maxLines = 1,
            constraints = Constraints(maxWidth = (rect.width - 10f * d).toInt().coerceAtLeast(1)),
        )
        val lineGap = if (knob.isEmpty()) 0f else first.size.height / 2f
        drawText(first, topLeft = Offset(x, rect.center.y - first.size.height / 2f - lineGap))
        if (knob.isNotEmpty()) {
            val second = measurer.measure(
                knob, ArrangerKnobStyle, overflow = TextOverflow.Ellipsis, maxLines = 1,
                constraints = Constraints(maxWidth = (rect.width - 10f * d).toInt().coerceAtLeast(1)),
            )
            drawText(second, topLeft = Offset(x, rect.center.y))
        }
    }

    table.cells.forEach { (lane, at, rect) ->
        val section = sections[at]
        val level = module.scenes.getOrNull(section.scene)?.levels?.getOrNull(lane) ?: 0f
        val target = patch.laneTarget(module, lane)
        val word = patch.levelLabel(target, level)
        val silent = word == "off"
        val playing = at == playingSection
        drawRoundRect(
            if (silent) Color(0xFF1A1E24) else ChipFill, rect.topLeft, rect.size, corner,
        )
        drawRoundRect(
            if (playing) accent.copy(alpha = 0.9f) else ChipEdge, rect.topLeft, rect.size, corner,
            style = Stroke(width = (if (playing) 2f else 1.5f) * d),
        )
        if (!silent) {
            val foot = 4f * d
            drawRect(
                ModulationColor.copy(alpha = 0.8f),
                Offset(rect.left + 4f * d, rect.bottom - foot - 3f * d),
                Size((rect.width - 8f * d) * level, foot),
            )
        }
        drawCentered(measurer, word, if (silent) ArrangerSilentStyle else ArrangerCellStyle,
            Offset(rect.center.x, rect.center.y - 2f * d), rect.width - 6f * d)
    }

    // Where it has scrolled, sideways and down: a thumb along the table's foot and its right edge,
    // clear of the line under the section playing.
    fun thumb(track: Rect, first: Int, max: Int, shown: Int, across: Boolean) {
        if (max <= 0) return
        val total = (max + shown).coerceAtLeast(first + shown)
        val start = first / total.toFloat()
        val length = shown / total.toFloat()
        if (across) {
            drawRect(Color(0xFF2B323C), Offset(track.left, track.bottom - 3f * d), Size(track.width, 3f * d))
            drawRect(accent, Offset(track.left + start * track.width, track.bottom - 3f * d), Size(length * track.width, 3f * d))
        } else {
            drawRect(Color(0xFF2B323C), Offset(track.right - 3f * d, track.top), Size(3f * d, track.height))
            drawRect(accent, Offset(track.right - 3f * d, track.top + start * track.height), Size(3f * d, length * track.height))
        }
    }
    val area = table.area
    thumb(
        Rect(table.headBand.left, area.top, table.headBand.right, area.bottom),
        table.firstSection, table.maxSectionScroll, table.heads.size, true,
    )
    thumb(
        Rect(area.left, table.nameBand.top, area.right, area.bottom),
        table.firstLane, table.maxLaneScroll, table.names.size, false,
    )
}

/** A chooser over the table: its ground, captions and tiles, or its slider and reading. */
internal fun DrawScope.drawArrangerChooser(
    chooser: ArrangerChooser,
    d: Float,
    measurer: TextMeasurer,
    chosen: (ArrangerPick) -> Boolean,
    level: Float,
    reading: String,
) {
    val ground = chooser.ground
    drawRoundRect(Color(0xFF1F232A), ground.topLeft, ground.size, CornerRadius(10f * d, 10f * d))
    drawRoundRect(ChipEdge, ground.topLeft, ground.size, CornerRadius(10f * d, 10f * d), style = Stroke(width = 1.5f * d))
    chooser.captions.forEach { (at, caption) ->
        val laid = measurer.measure(
            caption, PanelParamStyle, overflow = TextOverflow.Ellipsis, maxLines = 1,
            constraints = Constraints(maxWidth = (ground.right - at.x - 12f * d).toInt().coerceAtLeast(1)),
        )
        drawText(laid, topLeft = at)
    }
    chooser.tiles.forEach { (rect, pick, label) -> drawIntervalTile(rect, d, label, chosen(pick), measurer) }
    chooser.slider?.let { track ->
        val corner = CornerRadius(7f * d, 7f * d)
        drawRoundRect(Color(0xFF12151A), track.topLeft, track.size, corner)
        drawRoundRect(ModulationColor.copy(alpha = 0.85f), track.topLeft, Size(track.width * level, track.height), corner)
        drawRoundRect(ChipEdge, track.topLeft, track.size, corner, style = Stroke(width = 1.5f * d))
    }
    chooser.reading?.let { rect ->
        drawChip(rect, d, reading, false, ModulationColor, measurer)
    }
}
