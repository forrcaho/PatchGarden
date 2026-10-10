package io.github.forrcaho.patchgarden

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// What the sequencers on the canvas are doing, so an Arranger can be followed without opening
// anything (Forrest, 2026-10-05). A Seq says the version it is playing and is dimmed while
// silent; every sequencer has a playhead along its foot; a closed Arranger says its scene, how far
// into the section it is, and how far through the song. Polled from the engine once a frame for
// the modules on screen -- the same published positions an open panel reads.

/** How SECTION_STEP_STRIDE packs an Arranger's position; mirrors ArrangerNode::kStepStride. */
internal const val SECTION_STEP_STRIDE = 4096

/**
 * What a module on the canvas is doing, as the engine last said: its [position] (-1 for none),
 * and the version it is playing when something drives that knob -- null when nothing does, and
 * the knob is then the answer.
 */
internal data class Activity(val position: Int, val version: Int? = null)

/** A module the canvas polls, and its version knob's index when something drives it, or -1. */
internal data class Watch(val id: Long, val versionKnob: Int)

/** Whether the engine reports where this module has got to. */
internal val PatchModule.reportsPosition: Boolean
    get() = type.grid == GridKind.SEQUENCE || type.grid == GridKind.NOTES || type.grid == GridKind.PATTERN ||
        type.grid == GridKind.LEVELS || type.grid == GridKind.SONG

/** Whether something is patched into [module]'s version knob, so only the engine knows its value. */
internal fun Patch.versionDriven(module: PatchModule): Boolean {
    val knob = module.type.versionParam
    return knob >= 0 && connections.any { it.to == PortRef(module.id, PortDirection.MOD, knob) }
}

/** The version [module] is playing: the engine's, when driven; its knob's otherwise; null for none. */
internal fun PatchModule.playingVersion(activity: Activity?): Int? {
    val knob = type.versionParam
    if (knob < 0) return null
    return activity?.version ?: params.getOrElse(knob) { 1f }.roundToInt()
}

/** How many steps a sequencer loops over: its first knob, for all four that have one. */
private val PatchModule.loopSteps: Int
    get() = if (type.grid == GridKind.SONG) 0 else params.getOrElse(0) { 1f }.roundToInt().coerceAtLeast(1)

/** The section an Arranger's [position] names, or -1. */
internal fun sectionOfPosition(position: Int): Int = if (position < 0) -1 else position / SECTION_STEP_STRIDE

/** Where in the song a closed Arranger is: what its body says. */
internal data class ArrangerWhere(
    val scene: String,
    /** "bar 2 of 4", or "step 2 of 4" at an interval other than a bar. */
    val within: String,
    /** How far through the song, 0 to 1, counting the step playing. */
    val song: Float,
)

/** Where [module] is, at [position] in its song [version]; null when it is stopped or ended. */
internal fun PatchModule.arrangerWhere(position: Int, version: Int?): ArrangerWhere? {
    if (position < 0 || version == null || version <= 0) return null
    val sections = songs.getOrNull(version - 1)?.sections ?: return null
    val at = position / SECTION_STEP_STRIDE
    val step = position % SECTION_STEP_STRIDE
    val section = sections.getOrNull(at) ?: return null
    val total = sections.sumOf { it.steps }.coerceAtLeast(1)
    val before = sections.take(at).sumOf { it.steps }
    val unit = if (interval == Interval(1, 1, bars = true)) "bar" else "step"
    return ArrangerWhere(
        scene = scenes.getOrNull(section.scene)?.name ?: "?",
        within = "$unit ${step + 1} of ${section.steps}",
        song = ((before + step + 1) / total.toFloat()).coerceIn(0f, 1f),
    )
}

private val ActivityLabelStyle = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Color(0xFFE4E7EC))
private val ActivityQuietStyle = TextStyle(fontSize = 10.sp, color = Color(0xFF8A93A3))
private val ActivitySceneStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE4E7EC))
private val ActivitySmallStyle = TextStyle(fontSize = 8.sp, color = Color(0xFF9AA3B0))

/**
 * What [module] is doing, drawn over its body in [rect]: a Seq's version and, while it sounds, a
 * playhead along its foot -- the module dimmed while it is silent; any other sequencer's playhead;
 * an Arranger's scene, step and song. Text only where labels are drawn at this zoom.
 */
internal fun DrawScope.drawActivity(
    module: PatchModule,
    rect: Rect,
    unit: Float,
    activity: Activity?,
    measurer: TextMeasurer,
    showLabels: Boolean,
    alpha: Float,
) {
    val body = Rect(
        rect.left, rect.top + PatchModule.HEADER * unit,
        rect.right, rect.top + (PatchModule.HEADER + module.portsBody) * unit,
    )
    val inset = PatchModule.LABEL_INSET * unit
    val accent = module.type.accent
    val position = activity?.position ?: -1

    fun bar(fraction: Float) {
        val track = Rect(body.left + inset, body.bottom - 6f * unit, body.right - inset, body.bottom - 3.5f * unit)
        drawRect(Color(0xFF2B323C).copy(alpha = alpha), track.topLeft, track.size)
        drawRect(accent.copy(alpha = alpha), track.topLeft, Size(track.width * fraction.coerceIn(0f, 1f), track.height))
    }

    if (module.type.grid == GridKind.SONG) {
        val where = module.arrangerWhere(position, module.playingVersion(activity))
        if (where == null) {
            if (showLabels) {
                val text = measurer.measure("stopped", ActivityQuietStyle)
                drawText(text, alpha = alpha, topLeft = Offset(body.left + inset, body.top + 6f * unit))
            }
            return
        }
        if (showLabels) {
            val scene = measurer.measure(where.scene, ActivitySceneStyle)
            drawText(scene, alpha = alpha, topLeft = Offset(body.left + inset, body.top + 6f * unit))
            val within = measurer.measure(where.within, ActivitySmallStyle)
            drawText(within, alpha = alpha, topLeft = Offset(body.left + inset, body.top + 8f * unit + scene.size.height))
        }
        bar(where.song)
        return
    }

    val version = module.playingVersion(activity)
    if (version == 0) {
        // Silent: the module dimmed, and said so, with no playhead to follow.
        drawRoundRect(
            ModuleFill.copy(alpha = 0.55f * alpha), rect.topLeft, rect.size,
            CornerRadius(PatchModule.CORNER * unit, PatchModule.CORNER * unit),
        )
        if (showLabels) {
            val text = measurer.measure("silent", ActivityQuietStyle)
            drawText(text, alpha = alpha, topLeft = Offset(body.left + inset, body.top + (PatchModule.PORT_PITCH * unit - text.size.height) / 2f))
        }
        return
    }
    // The version, where there is a choice of them: a Seq with one version and nothing driving it
    // is always playing it, and saying "v1" would be saying nothing.
    if (showLabels && version != null && (activity?.version != null || module.versionCount > 1)) {
        val text = measurer.measure("v$version", ActivityLabelStyle)
        drawText(text, alpha = alpha, topLeft = Offset(body.left + inset, body.top + (PatchModule.PORT_PITCH * unit - text.size.height) / 2f))
    }
    if (position >= 0) bar((position + 1) / module.loopSteps.toFloat())
}
