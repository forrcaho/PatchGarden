package io.github.forrcaho.patchgarden

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/*
 * Where the app's files live: one folder holding `soundfonts/`, `scales/`, `subpatches/` and
 * `recordings/`.
 *
 * Either a folder the user chose once through the system's folder picker -- as Pagan does,
 * so that another app can be pointed at the same SoundFonts and a computer can reach all of
 * it -- or, until they do, the app-specific folder under Android/data, which is where
 * everything lived before there was a choice. The libraries read through [Folder] and do not
 * know which; the patch itself stays in app-private storage either way, since it is the app's
 * working state rather than anything a person keeps.
 *
 * A chosen folder is a Storage Access Framework tree, so nothing here is a java.io.File: a
 * file is found by name among its folder's children and read and written through the
 * ContentResolver. The folders are small -- tens of files -- so a folder lists its children
 * when asked rather than keeping a live index.
 */

private const val TAG = "PatchFiles"

/** The folders inside the PatchGarden folder, by the name each has on disk. */
object Folders {
    const val SOUNDFONTS = "soundfonts"
    const val SCALES = "scales"
    const val SUBPATCHES = "subpatches"
    const val RECORDINGS = "recordings"

    /** What moving to a new folder carries across. Recordings only ever lived in a chosen one. */
    val MOVED = listOf(SOUNDFONTS, SCALES, SUBPATCHES)
}

/** One folder of files: read, written and listed by name, a name including its extension. */
interface Folder {
    /** Where it is, as a person would say it. */
    val label: String

    /** The files in it, by name. Never its folders. */
    fun list(): List<String>

    fun read(name: String): ByteArray? = try {
        input(name)?.use { it.readBytes() }
    } catch (e: Exception) {
        Log.w(TAG, "could not read $name", e)
        null
    }

    /** [name] to stream from, or null when it is not there. The caller closes it. */
    fun input(name: String): InputStream?

    /** Writes [bytes] as [name], replacing what is there. Returns whether it landed. */
    fun write(name: String, bytes: ByteArray): Boolean

    /** A new file called [name], or the one there emptied, to stream into. The caller closes it. */
    fun output(name: String, mime: String = "application/octet-stream"): OutputStream?

    /**
     * The same, as a descriptor native code can write into: a saved recording, which is written
     * in C++ for speed. The caller closes it.
     */
    fun descriptor(name: String, mime: String = "application/octet-stream"): ParcelFileDescriptor?

    fun delete(name: String): Boolean

    fun exists(name: String): Boolean = name in list()

    /** How long [name] is, in bytes, or -1 when it is not there. */
    fun length(name: String): Long
}

/** The PatchGarden folder, or app storage standing in for it. */
interface Home {
    /** Where it is, as a person would say it. */
    val label: String

    /** Whether this is a folder the user chose, rather than app storage. */
    val chosen: Boolean

    /** The folder called [name] inside it, made if it is not there; null if it cannot be. */
    fun folder(name: String): Folder?
}

// ---------------------------------------------------------------- app storage

class DirFolder(val dir: File) : Folder {
    override val label: String get() = dir.absolutePath

    override fun list(): List<String> =
        dir.listFiles { f -> f.isFile }.orEmpty().map { it.name }

    override fun input(name: String): InputStream? = try {
        File(dir, name).takeIf { it.isFile }?.inputStream()
    } catch (e: Exception) {
        Log.w(TAG, "could not read $name", e)
        null
    }

    override fun write(name: String, bytes: ByteArray): Boolean = try {
        dir.mkdirs()
        File(dir, name).writeBytes(bytes)
        true
    } catch (e: Exception) {
        Log.w(TAG, "could not write $name", e)
        false
    }

    override fun output(name: String, mime: String): OutputStream? = try {
        dir.mkdirs()
        File(dir, name).outputStream()
    } catch (e: Exception) {
        Log.w(TAG, "could not open $name", e)
        null
    }

    override fun descriptor(name: String, mime: String): ParcelFileDescriptor? = try {
        dir.mkdirs()
        ParcelFileDescriptor.open(
            File(dir, name),
            ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE,
        )
    } catch (e: Exception) {
        Log.w(TAG, "could not open $name", e)
        null
    }

    override fun delete(name: String): Boolean = try {
        File(dir, name).takeIf { it.isFile }?.delete() == true
    } catch (e: Exception) {
        Log.w(TAG, "could not delete $name", e)
        false
    }

    override fun exists(name: String): Boolean = File(dir, name).isFile

    override fun length(name: String): Long = File(dir, name).let { if (it.isFile) it.length() else -1L }
}

/** A directory standing in for the PatchGarden folder: app storage, or a test's temporary one. */
class DirHome(val base: File, override val chosen: Boolean = false) : Home {
    override val label: String get() = base.absolutePath

    override fun folder(name: String): Folder? = try {
        DirFolder(File(base, name).apply { mkdirs() })
    } catch (e: Exception) {
        Log.w(TAG, "no $name folder", e)
        null
    }

    companion object {
        /** The app-specific folder, where everything lived before a folder could be chosen. */
        fun appStorage(context: Context): DirHome =
            DirHome(context.getExternalFilesDir(null) ?: context.filesDir)
    }
}

// ---------------------------------------------------------------- a chosen folder

/**
 * A folder of a tree the user granted, addressed by its document id.
 *
 * Every lookup is a query of the folder's children. A provider will not overwrite by name --
 * creating "Bass.json" beside one makes "Bass (1).json" -- so a write finds the file first and
 * truncates it, and only creates one when there is none.
 */
class TreeFolder(
    private val resolver: ContentResolver,
    private val tree: Uri,
    private val documentId: String,
    override val label: String,
) : Folder {

    private class Child(val id: String, val name: String, val directory: Boolean, val size: Long)

    private fun children(): List<Child> = try {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val columns = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE,
        )
        resolver.query(uri, columns, null, null, null)?.use { c ->
            val out = mutableListOf<Child>()
            while (c.moveToNext()) {
                out += Child(
                    c.getString(0), c.getString(1) ?: "",
                    c.getString(2) == Document.MIME_TYPE_DIR,
                    if (c.isNull(3)) -1L else c.getLong(3),
                )
            }
            out
        }.orEmpty()
    } catch (e: Exception) {
        Log.w(TAG, "could not list $label", e)
        emptyList()
    }

    private fun uriOf(child: Child): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, child.id)

    private fun find(name: String): Child? = children().firstOrNull { !it.directory && it.name == name }

    override fun list(): List<String> = children().filter { !it.directory }.map { it.name }

    override fun input(name: String): InputStream? = try {
        find(name)?.let { resolver.openInputStream(uriOf(it)) }
    } catch (e: Exception) {
        Log.w(TAG, "could not read $name", e)
        null
    }

    override fun write(name: String, bytes: ByteArray): Boolean = try {
        output(name)?.use { it.write(bytes) } != null
    } catch (e: Exception) {
        Log.w(TAG, "could not write $name", e)
        false
    }

    /** [name]'s document, made if it is not there. */
    private fun documentFor(name: String, mime: String): Uri? =
        find(name)?.let { uriOf(it) }
            ?: DocumentsContract.createDocument(
                resolver, DocumentsContract.buildDocumentUriUsingTree(tree, documentId), mime, name,
            )

    override fun output(name: String, mime: String): OutputStream? = try {
        // "wt": truncated, so a shorter file written over a longer one leaves nothing behind.
        documentFor(name, mime)?.let { resolver.openOutputStream(it, "wt") }
    } catch (e: Exception) {
        Log.w(TAG, "could not open $name", e)
        null
    }

    override fun descriptor(name: String, mime: String): ParcelFileDescriptor? = try {
        documentFor(name, mime)?.let { resolver.openFileDescriptor(it, "wt") }
    } catch (e: Exception) {
        Log.w(TAG, "could not open $name", e)
        null
    }

    override fun delete(name: String): Boolean = try {
        find(name)?.let { DocumentsContract.deleteDocument(resolver, uriOf(it)) } == true
    } catch (e: Exception) {
        Log.w(TAG, "could not delete $name", e)
        false
    }

    override fun exists(name: String): Boolean = find(name) != null

    override fun length(name: String): Long = find(name)?.size ?: -1L

    /** The folder called [name] in this one, made if it is not there. */
    fun subfolder(name: String): TreeFolder? = try {
        val id = children().firstOrNull { it.directory && it.name == name }?.id
            ?: DocumentsContract.createDocument(
                resolver, DocumentsContract.buildDocumentUriUsingTree(tree, documentId),
                Document.MIME_TYPE_DIR, name,
            )?.let { DocumentsContract.getDocumentId(it) }
        id?.let { TreeFolder(resolver, tree, it, "$label/$name") }
    } catch (e: Exception) {
        Log.w(TAG, "no $name folder in $label", e)
        null
    }
}

/** The PatchGarden folder, a tree the user chose and granted. */
class TreeHome(resolver: ContentResolver, val tree: Uri) : Home {
    override val chosen: Boolean get() = true

    private val root = TreeFolder(
        resolver, tree, DocumentsContract.getTreeDocumentId(tree), describeTree(tree),
    )

    override val label: String get() = root.label

    override fun folder(name: String): Folder? = root.subfolder(name)
}

/**
 * A tree's place, as a person would say it: "primary:Music/PatchGarden" is "Music/PatchGarden",
 * and a card's volume id is kept, since two volumes can hold the same path.
 */
fun describeTree(tree: Uri): String = try {
    describeTreeId(DocumentsContract.getTreeDocumentId(tree))
} catch (e: Exception) {
    tree.toString()
}

/** [describeTree]'s rule, on the document id the provider writes. */
internal fun describeTreeId(id: String): String {
    val volume = id.substringBefore(':', "")
    val path = id.substringAfter(':', id)
    return when {
        volume == "primary" -> path.ifEmpty { "Internal storage" }
        volume.isEmpty() -> path
        else -> "$volume/$path"
    }
}

/** What the app remembers of its folder, in its own preferences. */
object FolderChoice {
    private const val PREFS = "files"
    private const val TREE = "tree"
    private const val ASKED = "asked"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The folder the user chose, if they chose one and the grant is still held -- a grant can
     * be revoked from the system's settings, and a folder the app cannot open is no folder.
     * App storage otherwise.
     */
    fun home(context: Context): Home {
        val tree = prefs(context).getString(TREE, null)?.toUri() ?: return DirHome.appStorage(context)
        val held = context.contentResolver.persistedUriPermissions.any {
            it.uri == tree && it.isReadPermission && it.isWritePermission
        }
        if (!held) {
            Log.w(TAG, "the grant for $tree is gone; using app storage")
            return DirHome.appStorage(context)
        }
        return TreeHome(context.contentResolver, tree)
    }

    /** Takes the grant for [tree] for good and makes it the folder. */
    fun choose(context: Context, tree: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(tree, flags)
        val previous = prefs(context).getString(TREE, null)?.toUri()
        prefs(context).edit {
            putString(TREE, tree.toString())
            putBoolean(ASKED, true)
        }
        // The old grant is let go: an app holding grants to folders it no longer uses is one
        // the system's list of them makes look careless.
        if (previous != null && previous != tree) {
            try {
                context.contentResolver.releasePersistableUriPermission(previous, flags)
            } catch (e: Exception) {
                Log.w(TAG, "could not release $previous", e)
            }
        }
    }

    /** Whether the first-launch offer has been answered, either way. */
    fun asked(context: Context): Boolean = prefs(context).getBoolean(ASKED, false)

    fun markAsked(context: Context) {
        prefs(context).edit { putBoolean(ASKED, true) }
    }
}

/**
 * What moving from one home to another would carry: each folder's files that are not already
 * at the destination. A file already there by that name is the destination's and is left alone
 * on both sides -- moving never replaces, and never deletes what it did not copy.
 */
fun movable(from: Home, to: Home): Map<String, List<String>> =
    Folders.MOVED.associateWith { sub ->
        val source = from.folder(sub)?.list().orEmpty()
        val there = to.folder(sub)?.list().orEmpty().toSet()
        source.filter { it !in there }
    }.filterValues { it.isNotEmpty() }

/**
 * Moves [files] (from [movable]) out of [from] and into [to], reporting each as it lands.
 *
 * Copy, check, then delete: an original is deleted only once its copy reads back the same
 * length, so a move cut short by a full card or a revoked grant leaves every file somewhere.
 * Streamed rather than read whole, since a SoundFont bank can be larger than the heap.
 * Returns how many moved.
 */
fun move(from: Home, to: Home, files: Map<String, List<String>>, progress: (Int) -> Unit = {}): Int {
    var moved = 0
    files.forEach { (sub, names) ->
        val source = from.folder(sub) ?: return@forEach
        val target = to.folder(sub) ?: return@forEach
        names.forEach { name ->
            val copied = try {
                source.input(name)?.use { input ->
                    target.output(name)?.use { output -> input.copyTo(output, 1 shl 16) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "could not copy $sub/$name", e)
                null
            }
            if (copied != null && copied == source.length(name) && target.length(name) == copied) {
                source.delete(name)
                moved++
            } else {
                Log.w(TAG, "left $sub/$name where it was")
            }
            progress(moved)
        }
    }
    return moved
}
