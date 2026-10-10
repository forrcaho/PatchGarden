package io.github.forrcaho.patchgarden

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

// The Seq's timing, reworked on Forrest's notes of 2026-10-09: a note lasts whole steps, and a
// shorter one is a finer step; the sequence's length is said in beats or bars, and changing the
// step changes how many steps that is, carrying the notes across as exactly as the two steps
// allow. The engine still counts steps -- this is where beats become them.

/** A step's length, or a sequence's, as a fraction of a beat: [num] / [den] beats. */
internal data class Beats(val num: Long, val den: Long) {
    init {
        require(den > 0)
    }

    operator fun times(n: Long): Beats = reduced(num * n, den)
    operator fun div(other: Beats): Beats = reduced(num * other.den, den * other.num)
    val isWhole: Boolean get() = num % den == 0L

    /** Rounded down and up to whole numbers. */
    val floor: Long get() = Math.floorDiv(num, den)
    val ceil: Long get() = -Math.floorDiv(-num, den)

    companion object {
        fun reduced(num: Long, den: Long): Beats {
            val g = gcd(kotlin.math.abs(num), kotlin.math.abs(den)).coerceAtLeast(1)
            val sign = if (den < 0) -1 else 1
            return Beats(sign * num / g, sign * den / g)
        }

        private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
    }
}

/** How long [interval] is in beats, at [beatsPerBar]; a free one counts as a beat. */
internal fun stepBeats(interval: Interval, beatsPerBar: Int): Beats {
    if (interval.free) return Beats(1, 1)
    val inBeats = interval.inBeats(beatsPerBar)
    return Beats.reduced(inBeats.num.toLong(), inBeats.den.toLong())
}

/** The longest sequence, in bars (Forrest, 2026-10-09); past what fits, the grid scrolls. */
internal const val MAX_SEQ_BARS = 16

/** How many steps a Seq loops over: its first knob, which the engine reads as steps. */
internal val PatchModule.seqSteps: Int
    get() = params.getOrElse(0) { type.params.getOrNull(0)?.default ?: 16f }.roundToInt().coerceIn(1, SEQ_STEPS)

private val PatchModule.lengthUnitParam: Int get() = type.params.indexOfFirst { it.header && it.name == "lenBars" }

/** Whether a Seq's length is said in bars, where it would otherwise be beats. */
internal val PatchModule.lengthInBars: Boolean
    get() = lengthUnitParam >= 0 && params.getOrElse(lengthUnitParam) { 1f } >= 0.5f

/** A Seq's length in beats: its steps at its step. */
internal fun PatchModule.lengthBeats(beatsPerBar: Int): Beats = stepBeats(interval, beatsPerBar) * seqSteps.toLong()

/** A Seq's length in the unit it is said in: how many beats, or how many bars. */
internal fun PatchModule.lengthCount(beatsPerBar: Int): Beats {
    val beats = lengthBeats(beatsPerBar)
    return if (lengthInBars) beats / Beats(beatsPerBar.toLong().coerceAtLeast(1), 1) else beats
}

/** "4 bars", "6 beats" -- or "13/4 beats" if a change of meter left it between whole ones. */
internal fun PatchModule.lengthLabel(beatsPerBar: Int): String {
    val count = lengthCount(beatsPerBar)
    val unit = if (lengthInBars) "bar" else "beat"
    val n = if (count.isWhole) "${count.num / count.den}" else "${count.num}/${count.den}"
    val plural = count.num != count.den
    return "$n $unit" + if (plural) "s" else ""
}

/** A Seq's length as its step chooser shows it; null for any other module, which has no such line. */
internal fun PatchModule.lengthView(beatsPerBar: Int): LengthView? {
    if (type.grid != GridKind.NOTES) return null
    val count = lengthCount(beatsPerBar)
    return LengthView((count.num.toDouble() / count.den).roundToInt().coerceAtLeast(1), lengthInBars)
}

/**
 * Says the sequence is [count] beats or bars long: as many steps as that takes, a step that does
 * not divide it rounding up, and never past [MAX_SEQ_BARS] or the engine's [SEQ_STEPS].
 */
internal fun PatchModule.setSeqLength(count: Int, bars: Boolean, beatsPerBar: Int) {
    val bar = beatsPerBar.coerceAtLeast(1)
    val most = if (bars) MAX_SEQ_BARS else MAX_SEQ_BARS * bar
    val beats = Beats(count.coerceIn(1, most).toLong() * (if (bars) bar else 1), 1)
    val steps = (beats / stepBeats(interval, beatsPerBar)).ceil.toInt().coerceIn(1, SEQ_STEPS)
    Snapshot.withMutableSnapshot {
        setParam(0, steps.toFloat())
        if (lengthUnitParam >= 0) setParam(lengthUnitParam, if (bars) 1f else 0f)
    }
}

/**
 * Changes a Seq's step to [to] and carries its notes and its length across, as one edit:
 *
 * - **exactly**, when the new step divides the old -- a note on one half beat is on two quarters;
 * - when the new step is finer and does not divide the old, by Forrest's rule: each old step
 *   becomes as many new ones as fit inside it, beat by beat, and what is left of each beat is
 *   silent -- half beats to fifths puts each note on twice the steps, four fifths of every beat;
 * - when it is coarser, **each note snaps into the step it starts in**, its length rounding up to
 *   cover what it covered (Forrest's choice of three, 2026-10-09), and notes of one version that
 *   then collide at one pitch are merged -- a second start in the same step goes, an earlier note
 *   ends where a later one begins.
 *
 * The length in beats is kept: the step count is worked out again for the new step. Any other
 * module just takes the knob.
 */
internal fun PatchModule.changeStep(to: Interval, beatsPerBar: Int) {
    val index = type.intervalParam
    if (index < 0) return
    if (type.grid != GridKind.NOTES || to.free || interval.free) {
        setParam(index, to.code.toFloat())
        return
    }
    val old = stepBeats(interval, beatsPerBar)
    val new = stepBeats(to, beatsPerBar)
    val length = lengthBeats(beatsPerBar)
    val moved = seqNotes.map { retime(it, old, new) }
    val kept = resolveCollisions(moved.filter { it.step < SEQ_STEPS })
    val steps = (length / new).ceil.toInt().coerceIn(1, SEQ_STEPS)
    Snapshot.withMutableSnapshot {
        seqNotes.clear()
        seqNotes.addAll(kept)
        setParam(index, to.code.toFloat())
        setParam(0, steps.toFloat())
    }
}

/** Where [note] lands when a step of [old] beats becomes one of [new]; see [changeStep]. */
internal fun retime(note: SeqNote, old: Beats, new: Beats): SeqNote {
    val ratio = old / new
    // Finer, dividing: exact.
    if (ratio.isWhole) {
        val k = ratio.num / ratio.den
        return note.copy(step = (note.step * k).toInt(), length = (note.length * k).toInt())
    }
    // Finer, not dividing, both steps a whole number to a beat: Forrest's rule, beat by beat.
    if (ratio.num > ratio.den && old.num == 1L && new.num == 1L) {
        val perOld = old.den
        val perNew = new.den
        val k = perNew / perOld
        val beat = note.step / perOld
        val within = note.step % perOld
        return note.copy(step = (beat * perNew + within * k).toInt(), length = (note.length * k).toInt().coerceAtLeast(1))
    }
    // Coarser, or anything else: snapped into the step it starts in, covering what it covered.
    val startIn = (Beats.reduced(note.step * old.num, old.den) / new).floor
    val endIn = (Beats.reduced((note.step + note.length) * old.num, old.den) / new).ceil
    return note.copy(step = startIn.toInt(), length = (endIn - startIn).toInt().coerceAtLeast(1))
}

/**
 * Notes that share a version and a pitch may not overlap, since the second's start would be heard
 * as nothing: a later note starting where an earlier one does is dropped, and an earlier one that
 * runs into a later one ends where the later begins.
 */
internal fun resolveCollisions(notes: List<SeqNote>): List<SeqNote> {
    val sorted = notes.sortedWith(compareBy({ it.step }, { it.degree }))
    val out = mutableListOf<SeqNote>()
    for (note in sorted) {
        var kept = note
        var dropped = false
        for (i in out.indices) {
            val earlier = out[i]
            if (earlier.degree != kept.degree || (earlier.versions and kept.versions) == 0) continue
            if (earlier.step == kept.step) {
                dropped = true
                break
            }
            if (earlier.step + earlier.length > kept.step) out[i] = earlier.copy(length = kept.step - earlier.step)
        }
        if (!dropped) out += kept
    }
    return out
}

/** How long [note] lasts in beats at [module]'s step: what an audition of it plays for. */
internal fun PatchModule.noteBeats(note: SeqNote, beatsPerBar: Int): Double {
    val step = stepBeats(interval, beatsPerBar)
    return note.length * step.num.toDouble() / step.den
}

// ---------------------------------------------------------------- versions on the grid

/** A note in every version of a Seq that has several: gray, which is all of their colors at once. */
internal val SHARED_NOTE_COLOR = Color(0xFFAEB4BD)

/**
 * The colors [note] is drawn in: the Seq's own while it has one version, gray when the note is in
 * every version, the version's when it is in one, and each of its versions' -- drawn as stripes --
 * when it is in several but not all.
 */
internal fun PatchModule.noteColors(note: SeqNote): List<Color> = when {
    versionCount <= 1 -> listOf(type.accent)
    note.versions and everyVersion == everyVersion -> listOf(SHARED_NOTE_COLOR)
    else -> (1..versionCount).filter { note.versions and (1 shl (it - 1)) != 0 }.map { versionColor(it) }
}

/** Whether [note] plays in [version] -- the one the engine is playing, when the transport runs. */
internal fun SeqNote.playsIn(version: Int): Boolean = version > 0 && versions and (1 shl (version - 1)) != 0

/**
 * Notes that are the same in every way but which versions they are in become one note in all of
 * them -- so a note split to edit it in one version, then edited back, is shared again, and a note
 * that is the same for every version is the gray shared note rather than one per version.
 */
internal fun mergeIdentical(notes: List<SeqNote>): List<SeqNote> {
    val merged = LinkedHashMap<List<Any>, SeqNote>()
    notes.forEach { note ->
        val key = listOf(note.step, note.degree, note.length, note.velocity)
        merged[key] = merged[key]?.let { it.copy(versions = it.versions or note.versions) } ?: note
    }
    return merged.values.toList()
}

/** [mergeIdentical] on a Seq's notes, after an edit; sends nothing if nothing merged. */
internal fun PatchModule.tidySeqNotes() {
    val merged = mergeIdentical(seqNotes.toList())
    if (merged.size == seqNotes.size) return
    Snapshot.withMutableSnapshot {
        seqNotes.clear()
        seqNotes.addAll(merged)
    }
}

/**
 * Sets note [index]'s versions to [bits], from its menu's OK; notes it now collides with in a
 * version it joined are resolved as a change of step resolves them.
 */
internal fun PatchModule.setNoteVersions(index: Int, bits: Int) {
    val note = seqNotes.getOrNull(index) ?: return
    if (bits == 0 || bits == note.versions) return
    val changed = seqNotes.toMutableList().also { it[index] = note.copy(versions = bits) }
    val tidy = mergeIdentical(resolveCollisions(changed))
    Snapshot.withMutableSnapshot {
        seqNotes.clear()
        seqNotes.addAll(tidy)
    }
}

// ---------------------------------------------------------------- the versions menu

/**
 * A Seq note's versions menu, open: which note, where the press was, and what is ticked -- "All",
 * which is exclusive, or some of the versions there are (Forrest, 2026-10-09). Nothing changes
 * until OK.
 */
internal data class NoteMenu(val index: Int, val anchor: Offset, val all: Boolean, val bits: Int) {
    /** The versions OK would give the note, or 0 while nothing is ticked. */
    fun chosen(every: Int): Int = if (all) every else bits

    companion object {
        fun of(module: PatchModule, index: Int, anchor: Offset): NoteMenu {
            val versions = module.seqNotes[index].versions
            val every = module.everyVersion
            return if (versions and every == every) NoteMenu(index, anchor, true, 0)
            else NoteMenu(index, anchor, false, versions)
        }
    }
}

/** What a tap in the versions menu lands on. */
internal sealed interface NoteMenuPick {
    data object All : NoteMenuPick
    data class Version(val n: Int) : NoteMenuPick
    data object Ok : NoteMenuPick
}

/** The versions menu laid out: a row of ticks, "All" first, and OK under them at the right. */
internal class NoteMenuLayout(val ground: Rect, val tiles: List<Pair<Rect, NoteMenuPick>>) {
    fun pickAt(at: Offset): NoteMenuPick? = tiles.firstOrNull { it.first.contains(at) }?.second
}

internal fun noteMenuLayout(body: Rect, d: Float, fontScale: Float, versions: Int, anchor: Offset): NoteMenuLayout {
    val text = fontScale.coerceAtLeast(1f)
    val tileH = maxOf(44f, 29f * text) * d
    val pad = 12f * d
    val gap = 6f * d
    val allW = 60f * text * d
    val tileW = 46f * text * d
    val okW = 64f * text * d
    val width = 2f * pad + allW + versions * (tileW + gap)
    val height = 2f * pad + 2f * tileH + gap
    val left = (anchor.x - width / 2f).coerceIn(body.left + 8f * d, (body.right - 8f * d - width).coerceAtLeast(body.left + 8f * d))
    val below = anchor.y + 16f * d
    val top = if (below + height <= body.bottom - 4f * d) below else (anchor.y - 16f * d - height).coerceAtLeast(body.top + 4f * d)
    val ground = Rect(left, top, left + width, top + height)
    val tiles = mutableListOf<Pair<Rect, NoteMenuPick>>()
    tiles += Rect(left + pad, top + pad, left + pad + allW, top + pad + tileH) to NoteMenuPick.All
    (1..versions).forEach { n ->
        val x = left + pad + allW + gap + (n - 1) * (tileW + gap)
        tiles += Rect(x, top + pad, x + tileW, top + pad + tileH) to NoteMenuPick.Version(n)
    }
    val okTop = top + pad + tileH + gap
    tiles += Rect(ground.right - pad - okW, okTop, ground.right - pad, okTop + tileH) to NoteMenuPick.Ok
    return NoteMenuLayout(ground, tiles)
}
