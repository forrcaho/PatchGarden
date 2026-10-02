package io.github.forrcaho.patchgarden

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Settings, and the other pages about the app rather than the patch: the first launch's offer
 * of a folder, moving files into one, and saving the recording.
 *
 * Composables over the canvas, like the licenses page and for the same reason -- they are
 * lists and buttons with text in them, which Compose lays out and the canvas would have to
 * be taught.
 */

/**
 * What the app around the canvas lends it: where its files are, and its recording. The
 * activity fills it in; a test or a preview leaves it as it is, with nowhere to save to.
 */
class AppControls(
    /** The PatchGarden folder, as a person would say where it is. */
    val folder: String = "",
    /** Whether that is a folder the user chose, rather than app storage. */
    val folderChosen: Boolean = false,
    val onChooseFolder: () -> Unit = {},
    /** The recording's length, in minutes; 0 is off. */
    val recordMinutes: Int = 0,
    val onRecordMinutes: (Int) -> Unit = {},
    /** The window as it stands, read afresh: how much there is to save. */
    val recording: () -> RecordingHeader? = { null },
    val onSaveRecording: suspend (BitDepth) -> SavedRecording = { SavedRecording.Failed },
    /** How far the save under way has got, from 0 to 1. */
    val saveProgress: () -> Float = { 0f },
)

/** How a save of the recording went. */
sealed interface SavedRecording {
    data class Saved(val name: String, val where: String) : SavedRecording
    /** The window holds only silence. */
    data object Silence : SavedRecording
    /** No folder is chosen, and a recording is not left in app storage. */
    data object NoFolder : SavedRecording
    data object Failed : SavedRecording
}

private val Title = TextStyle(color = Color(0xFFE6E9EF), fontSize = 18.sp, fontWeight = FontWeight.Medium)
private val Heading = TextStyle(color = Color(0xFF98A0AD), fontSize = 14.sp)
private val Body = TextStyle(color = Color(0xFFC9D0DA), fontSize = 15.sp)
private val Faint = TextStyle(color = Color(0xFF8A93A3), fontSize = 13.sp)

/**
 * A page over the canvas: a card, and a scrim that closes it when the page only shows
 * something. A page that asks a question -- [asks] -- is answered by its buttons or Back and
 * never by the scrim, which still takes the tap so it cannot reach the canvas: the folder offer
 * was answered "Not now" on the phone by the tap that woke its dimmed screen, and it is asked
 * only once.
 */
@Composable
private fun Page(onDone: () -> Unit, asks: Boolean = false, content: @Composable () -> Unit) {
    BackHandler(onBack = onDone)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .pointerInput(asks) { detectTapGestures { if (!asks) onDone() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 72.dp, vertical = 20.dp)
                .widthIn(max = 560.dp)
                .background(Color(0xFF1B1F26), RoundedCornerShape(12.dp))
                .border(1.5.dp, ChipEdge, RoundedCornerShape(12.dp))
                .pointerInput(Unit) { detectTapGestures { } }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) { content() }
    }
}

/** A button: its label on a colored ground. */
@Composable
private fun Button(label: String, color: Color, modifier: Modifier = Modifier, enabled: Boolean = true, onTap: () -> Unit) {
    Box(
        modifier
            .height(48.dp)
            .background(color.copy(alpha = if (enabled) 0.85f else 0.3f), RoundedCornerShape(10.dp))
            .pointerInput(label, enabled) { detectTapGestures { if (enabled) onTap() } }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = TextStyle(color = Color(0xFF12151A), fontSize = 16.sp, fontWeight = FontWeight.Medium))
    }
}

/** A row of choices, one lit, like the panel's chips. */
@Composable
private fun <T> Choices(options: List<T>, chosen: T, label: (T) -> String, accent: Color, onChoose: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            val on = option == chosen
            BasicText(
                label(option),
                style = TextStyle(
                    color = if (on) Color(0xFF14171C) else Color(0xFFC3CBD6),
                    fontSize = 15.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                ),
                modifier = Modifier
                    .background(if (on) accent else ChipFill, RoundedCornerShape(8.dp))
                    .border(1.5.dp, ChipEdge, RoundedCornerShape(8.dp))
                    .pointerInput(option) { detectTapGestures { onChoose(option) } }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

private val Accent = Color(0xFFB9C2CE)
private val Warn = Color(0xFFE07A6B)
private val Neutral = Color(0xFF8A93A3)

/** "7:32", or "0:04": how long a window is, the way a clock says it. */
internal fun minutesAndSeconds(seconds: Double): String {
    val whole = seconds.toLong().coerceAtLeast(0L)
    return "%d:%02d".format(whole / 60, whole % 60)
}

/** How much of the phone a window of [minutes] takes: float stereo at 48kHz, with the margin. */
internal fun recordingMegabytes(minutes: Int): Int =
    if (minutes <= 0) 0 else ((minutes * 60 + 30) * 48_000L * 8 / 1_000_000).toInt()

/**
 * The settings: where the files are, how long the recording is, and what this build is.
 *
 * Small on purpose -- the roadmap's list, and nothing more. A setting is a question the app
 * could not answer for itself; each of these is one.
 */
@Composable
internal fun SettingsOverlay(app: AppControls, onLicenses: () -> Unit, onDone: () -> Unit) {
    Page(onDone) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText("Settings", style = Title, modifier = Modifier.weight(1f))
            BasicText(
                "✕",
                style = TextStyle(color = Color(0xFFB7C0CE), fontSize = 22.sp),
                modifier = Modifier
                    .border(1.5.dp, ChipEdge, RoundedCornerShape(8.dp))
                    .pointerInput(Unit) { detectTapGestures { onDone() } }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }

        BasicText("Folder", style = Heading, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BasicText(if (app.folderChosen) app.folder else "App storage", style = Body)
                BasicText(
                    if (app.folderChosen) {
                        "SoundFonts, scales, saved subpatches and recordings"
                    } else {
                        "Only this app, and a computer over USB, can reach it"
                    },
                    style = Faint,
                )
            }
            Button(if (app.folderChosen) "Change…" else "Choose…", Accent, onTap = app.onChooseFolder)
        }

        BasicText("Recording", style = Heading, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
        BasicText("Always keeps the last", style = Body, modifier = Modifier.padding(bottom = 6.dp))
        Choices(
            RECORDING_MINUTES, app.recordMinutes,
            { if (it == 0) "Off" else "$it min" },
            Accent, app.onRecordMinutes,
        )
        BasicText(
            if (app.recordMinutes == 0) {
                "Nothing is recorded"
            } else {
                "of what plays, in about ${recordingMegabytes(app.recordMinutes)} MB of the phone. " +
                    "A new length starts over."
            },
            style = Faint,
            modifier = Modifier.padding(top = 6.dp),
        )

        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText("PatchGarden ${BuildConfig.VERSION_NAME}", style = Body, modifier = Modifier.weight(1f))
            Button("Licenses…", Accent, onTap = onLicenses)
        }
    }
}

/**
 * Saving the recording: how much the window holds, the bit depth, and Save. A saved one goes
 * in `recordings/` in the PatchGarden folder and nowhere else -- a recording left in app
 * storage is one a person cannot find -- so with no folder chosen, choosing one comes first.
 */
@Composable
internal fun SaveRecordingOverlay(app: AppControls, onSettings: () -> Unit, onDone: () -> Unit) {
    var depth by remember { mutableStateOf(BitDepth.PCM24) }
    var result by remember { mutableStateOf<SavedRecording?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Read afresh every second, since it grows while the page is open.
    val header by produceState(app.recording()) {
        while (true) {
            delay(1_000)
            value = withContext(Dispatchers.IO) { app.recording() }
        }
    }
    // Polled while a save runs, which is seconds, so the button counts rather than sitting there.
    val progress by produceState(0f, saving) {
        while (saving) {
            value = app.saveProgress()
            delay(100)
        }
    }
    // Not closed while it saves, by the scrim or by Back: the page is the only thing that says
    // when a save is done, and for two builds a tap away while one ran left nothing that would.
    Page(onDone = { if (!saving) onDone() }, asks = saving) {
        BasicText("Save the recording", style = Title, modifier = Modifier.padding(bottom = 10.dp))
        when {
            app.recordMinutes == 0 -> {
                BasicText("Recording is off.", style = Body)
                Button("Settings…", Accent, Modifier.padding(top = 12.dp), onTap = onSettings)
            }
            !app.folderChosen -> {
                BasicText(
                    "Recordings are saved in the PatchGarden folder, and none is chosen yet.",
                    style = Body,
                )
                Button("Choose folder…", Accent, Modifier.padding(top = 12.dp), onTap = app.onChooseFolder)
            }
            else -> {
                val held = header
                BasicText(
                    if (held == null || held.available == 0L) "Nothing is recorded yet."
                    else "${minutesAndSeconds(held.seconds)} is recorded. A save starts where its sound does.",
                    style = Body,
                )
                BasicText("Bit depth", style = Heading, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                Choices(BitDepth.entries.toList(), depth, { it.label }, Accent) { depth = it }
                Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        if (saving) "Saving… ${(progress * 100).toInt()}%" else "Save", Accent,
                        enabled = !saving && held != null && held.available > 0,
                    ) {
                        saving = true
                        result = null
                        scope.launch {
                            result = app.onSaveRecording(depth)
                            saving = false
                        }
                    }
                }
                result?.let {
                    BasicText(
                        when (it) {
                            is SavedRecording.Saved -> "Saved “${it.name}” in ${it.where}"
                            SavedRecording.Silence -> "There is only silence to save."
                            SavedRecording.NoFolder -> "Choose a folder first."
                            SavedRecording.Failed -> "It could not be saved."
                        },
                        style = if (it is SavedRecording.Saved) Body else Body.copy(color = Warn),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

/**
 * The first launch's offer: a folder of the user's choosing for everything a person keeps.
 * Asked once, and "Not now" is an answer -- Settings is where to change one's mind.
 */
@Composable
fun FolderOffer(onChoose: () -> Unit, onNotNow: () -> Unit) {
    Page(onNotNow, asks = true) {
        BasicText("A folder for PatchGarden", style = Title, modifier = Modifier.padding(bottom = 10.dp))
        BasicText(
            "SoundFonts, scales, saved subpatches and recordings can live in a folder you choose, " +
                "where other apps and your computer can reach them. Make a new one in the picker, or " +
                "choose one you already have.",
            style = Body,
        )
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button("Choose folder…", Accent, onTap = onChoose)
            Button("Not now", Neutral, onTap = onNotNow)
        }
    }
}

/** What a move would carry, said the way a person counts it: "3 SoundFonts and 20 scales". */
internal fun describeMove(files: Map<String, List<String>>): String {
    val parts = files.mapNotNull { (sub, names) ->
        val n = names.size
        when (sub) {
            Folders.SOUNDFONTS -> if (n == 1) "1 SoundFont" else "$n SoundFonts"
            Folders.SCALES -> if (n == 1) "1 scale" else "$n scales"
            Folders.SUBPATCHES -> if (n == 1) "1 saved subpatch" else "$n saved subpatches"
            else -> null
        }
    }
    return when (parts.size) {
        0 -> "nothing"
        1 -> parts[0]
        else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }
}

/** Where a home is, in a sentence: a chosen folder by its path, app storage by what it is. */
private fun placeOf(home: Home): String = if (home.chosen) home.label else "app storage"

/** State of a move the activity is offering or making. */
class MoveOffer(val from: Home, val to: Home, val files: Map<String, List<String>>) {
    val count: Int get() = files.values.sumOf { it.size }
}

/**
 * Offers to move what is where the files used to be into the folder just chosen, then moves
 * it with a count going up. "Leave them" leaves them where they were, reachable again by
 * choosing that folder; nothing is ever deleted that was not first copied and checked.
 */
@Composable
fun MoveOverlay(offer: MoveOffer, onFinished: () -> Unit) {
    var moved by remember { mutableIntStateOf(-1) }
    val scope = rememberCoroutineScope()
    Page(onDone = { if (moved < 0) onFinished() }, asks = true) {
        BasicText("Move your files?", style = Title, modifier = Modifier.padding(bottom = 10.dp))
        if (moved < 0) {
            BasicText(
                "Move ${describeMove(offer.files)} from ${placeOf(offer.from)} into ${placeOf(offer.to)}?",
                style = Body,
            )
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("Move", Accent) {
                    moved = 0
                    scope.launch {
                        withContext(Dispatchers.IO) { move(offer.from, offer.to, offer.files) { moved = it } }
                        onFinished()
                    }
                }
                Button("Leave them", Neutral, onTap = onFinished)
            }
        } else {
            BasicText("Moving… $moved of ${offer.count}", style = Body)
        }
    }
}
