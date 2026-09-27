package io.github.forrcaho.patchgarden

import android.util.Log
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import java.io.File

/*
 * The subpatch library: subpatches saved to files, and loaded back into any patch.
 *
 * A saved subpatch *is a patch file* -- one holding a single top-level subpatch and whatever is
 * inside it. That is the whole design. The alternative was a format of its own, and it
 * would have had its own module serialization, its own validation and its own refusal
 * rules, all of them a copy of the ones next door that a format change would then have to
 * be made in twice. Reading one back goes through `patchFromJson`, so a subpatch file gets the
 * same treatment a patch does: a type that no longer exists is skipped, a cable whose kinds
 * disagree refuses the whole file, and a version this build cannot read is refused rather
 * than migrated.
 *
 * Loading makes a copy, never a link. Forrest chose that on 2026-09-17: a linked instance
 * that changes in every patch when you edit the definition is the more powerful idea and
 * the more confusing one, and nothing here can yet show you where a definition is used.
 */

/** The file extension for a saved subpatch. Plain JSON, and a patch file if you rename it. */
private const val SUBPATCH_EXTENSION = ".json"
private const val TAG = "PatchSubpatches"

/**
 * This subpatch and everything inside it, as a patch holding nothing else.
 *
 * The copy is made into a throwaway patch first rather than written straight out, so that
 * exactly one routine knows how a subpatch is copied -- the same one that duplicates it.
 */
fun Patch.subpatchToJson(subpatch: PatchModule, name: String? = subpatch.name): String? {
    if (!subpatch.type.box) return null
    val lone = Patch()
    // [name] is what the saved copy is called, which is not always what the subpatch in the
    // patch is called: saving "Filt Osc" to the library as "Bass voice" must not rename
    // the one you are still playing.
    lone.adoptSubpatch(this, subpatch, Offset.Zero, TOP, name) ?: return null
    return lone.toJson()
}

/**
 * The whole patch as one saved subpatch, without touching the patch.
 *
 * Subpatching everything is what this means -- the cables into Out become the subpatch's outputs
 * and the ones out of In its inputs, which is exactly what [Patch.subpatch] does with any
 * selection -- and it is done to a *copy* read back from this patch's own file.
 *
 * **The file keeps what is around the box as well**: the cables from it to Out and from In to
 * it, the rails' knobs, the scales, the tempo and the patch's name. Loaded as a subpatch none of
 * that is read -- [loadSubpatch] adopts the one box and what is inside it -- but opened as a
 * patch ([openSaved]) it is the difference between the patch that was saved and a box wired
 * to nothing, silent, in the wrong tuning. The first version wrote the box alone, through
 * [subpatchToJson], and a saved patch could only ever come back as a subpatch.
 *
 * The first version subpatched the live patch, serialized, and unpacked again inside one
 * snapshot, on the reasoning that every step was silent to the engine. The engine agreed.
 * The file did not: unpacking re-adds the boundary's cables at the end of the list, so a
 * patch with a subpatch at its top level came back with its cables reordered, the autosave saw
 * a new file, and saving became an undo step that did nothing. Found on the phone; the demo
 * patch the test used happened to re-add its cables in the order they started in. Working
 * on a copy makes "the patch is untouched" true by construction rather than by care.
 */
fun Patch.patchToSubpatchJson(name: String): String? {
    val copy = patchFromJson(toJson()) ?: return null
    // Read back without the scale library, which would turn every tuning into the default;
    // the entries themselves are what the file needs, and they are already here.
    copy.scales = scales
    val ids = copy.modules.filter { !it.isPinned && it.parent == TOP }.map { it.id }.toSet()
    if (ids.isEmpty()) return null
    val subpatch = copy.makeSubpatch(ids) ?: return null
    subpatch.name = name
    copy.name = name
    return copy.toJson()
}

/**
 * Whether this patch is in the library as it stands: saved under its own name, and not
 * changed since. What Open asks before it replaces the patch.
 *
 * Asked of the library rather than remembered, so there is no second record to fall out of
 * step with the first: undoing back to what was saved makes it saved again, and deleting the
 * file makes it not. An empty patch has nothing to lose and counts as saved.
 */
fun Patch.isSavedIn(library: SubpatchLibrary?): Boolean {
    val now = patchToSubpatchJson(title) ?: return true
    return library?.read(title) == now
}

/**
 * The saved patch in [text] in place of this one, as one edit -- so one undo puts this one back.
 * Returns whether it did.
 *
 * A whole patch is saved as one plain subpatch wired to the rails, and opening one unpacks it,
 * which is exactly the inverse. Anything else in the library -- a poly voice, a subpatch saved
 * from inside a patch -- is opened as a patch holding that one box, since unpacking a poly
 * subpatch would make its one voice of everything into the whole patch. A file saved before
 * the whole patch was written around the box opens unwired: its cables to Out were never in it.
 */
fun Patch.openSaved(text: String, name: String, scales: ScaleLibrary = ScaleLibrary.of(null)): Boolean {
    val source = patchFromJson(text, scales) ?: return false
    val loose = source.modules.filter { !it.isPinned && it.parent == TOP }
    val box = loose.singleOrNull()?.takeIf { it.type.box } ?: run {
        Log.w(TAG, "not a saved subpatch: ${loose.size} modules at the top level")
        return false
    }
    if (box.type == Types.Subpatch) source.unpack(box)
    if (source.name == null) source.name = box.name ?: name
    Snapshot.withMutableSnapshot {
        replaceWith(source)
        scope = TOP
        modules.forEach { it.expanded = false }
    }
    return true
}

/**
 * The subpatch in a saved file, copied into this patch at [at], or null if the file does not
 * hold exactly one subpatch.
 *
 * The ids in the file are the ones it was saved with and are never reused here: [adoptSubpatch]
 * allocates fresh ones, so loading the same subpatch twice gives two independent subpatches.
 */
fun Patch.loadSubpatch(
    text: String,
    at: Offset,
    scales: ScaleLibrary = ScaleLibrary.of(null),
): PatchModule? {
    val source = patchFromJson(text, scales) ?: return null
    val loose = source.modules.filter { !it.isPinned && it.parent == TOP }
    val subpatch = loose.singleOrNull()?.takeIf { it.type.box } ?: run {
        Log.w(TAG, "not a saved subpatch: ${loose.size} modules at the top level")
        return null
    }
    return adoptSubpatch(source, subpatch, at, scopeOrTop)
}

/**
 * The saved subpatches, in `subpatches` in the PatchGarden folder.
 *
 * Beside the scales at Forrest's choice: a saved subpatch is something to copy off the phone,
 * mail to someone, or drop in by hand, and none of that is possible in app-private storage.
 * Through [Folder], so it is the same library in app storage or in a folder the user chose.
 */
class SubpatchLibrary(val folder: Folder?) {

    /** A library in a plain directory: app storage's, or a test's. */
    constructor(directory: File?) : this(directory?.let { DirFolder(it) })

    /** What is saved, by name, in the order a list should show them. */
    fun names(): List<String> =
        folder?.list().orEmpty()
            .filter { it.endsWith(SUBPATCH_EXTENSION, true) }
            .map { it.dropLast(SUBPATCH_EXTENSION.length) }
            .sortedBy { it.lowercase() }

    fun exists(name: String): Boolean = fileFor(name)?.let { folder?.exists(it) } == true

    fun read(name: String): String? = fileFor(name)?.let { folder?.read(it) }?.decodeToString()

    /**
     * Takes [name] out of the library, and says whether it went. Deleted rather than moved
     * aside: it is asked for by a tile on a menu of its own, never by a press, and the library
     * is not the patch -- nothing here is undone.
     */
    fun delete(name: String): Boolean = fileFor(name)?.let { folder?.delete(it) } == true

    /** Writes [json] under [name], replacing what is there. Returns whether it landed. */
    fun write(name: String, json: String): Boolean =
        fileFor(name)?.let { folder?.write(it, json.encodeToByteArray()) } == true

    /**
     * [name] if it is free, or the first "name 2", "name 3" that is.
     *
     * What "Keep both" saves under, so that saving twice under one name never quietly
     * replaces the first -- the same reason a refused patch file is moved aside rather
     * than written over.
     */
    fun freeName(name: String): String {
        if (!exists(name)) return name
        var n = 2
        while (exists("$name $n") && n < 100) n++
        return "$name $n"
    }

    private fun fileFor(name: String): String? =
        safeName(name).takeIf { it.isNotEmpty() && folder != null }?.let { it + SUBPATCH_EXTENSION }

    companion object {
        /**
         * A file name that cannot escape the folder or upset a file manager.
         *
         * The display name lives inside the file, on the subpatch itself, so this only has to
         * be a stable handle -- "Bass/Lead" saving as "Bass_Lead" loses nothing you can see.
         */
        fun safeName(name: String): String =
            name.map { if (it.isLetterOrDigit() || it in " _-") it else '_' }
                .joinToString("")
                .trim()
                .take(MAX_NAME)

        fun load(home: Home): SubpatchLibrary = SubpatchLibrary(home.folder(Folders.SUBPATCHES))
    }
}
