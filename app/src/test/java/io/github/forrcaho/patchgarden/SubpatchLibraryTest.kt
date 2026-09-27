package io.github.forrcaho.patchgarden

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Saving a subpatch and loading it back into another patch.
 *
 * A saved subpatch is a patch file holding one subpatch, so the interesting claims are not about
 * JSON -- that path is the patch's own and already tested -- but about what survives the
 * round trip, and about the copy being a copy: fresh ids, independent of the original and
 * of every other copy of it.
 */
class SubpatchLibraryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun saved(): Pair<SubpatchFixture, String> {
        val f = SubpatchFixture()
        val subpatch = f.patch.makeSubpatch(setOf(f.osc.id, f.filter.id))!!
        subpatch.name = "Filt Osc"
        f.patch.enterScope(subpatch.id)
        f.patch.promote(f.filter, 0)
        f.patch.scope = TOP
        f.osc.setParam(0, 0.25f)
        return f to f.patch.subpatchToJson(subpatch)!!
    }

    @Test
    fun `a saved subpatch comes back with its modules, cables, ports, knobs and names`() {
        val (f, json) = saved()
        val original = f.patch.modules.first { it.type == Types.Subpatch }

        val patch = Patch()
        val loaded = patch.loadSubpatch(json, Offset(120f, 80f))!!

        assertEquals("Filt Osc", loaded.name)
        assertEquals(Offset(120f, 80f), loaded.position)
        assertEquals(TOP, loaded.parent)
        assertEquals(
            original.ports(PortDirection.INPUT).map { it.name to it.kind },
            loaded.ports(PortDirection.INPUT).map { it.name to it.kind },
        )
        assertEquals(
            original.ports(PortDirection.OUTPUT).map { it.name to it.kind },
            loaded.ports(PortDirection.OUTPUT).map { it.name to it.kind },
        )

        // What is inside, by type and by knob, and the cables between them.
        val inside = patch.modules.filter { it.parent == loaded.id && !it.isPinned }
        assertEquals(setOf("Osc", "Filter"), inside.map { it.type.name }.toSet())
        assertEquals(0.25f, inside.first { it.type == Types.Osc }.params[0], 0.001f)
        val filter = inside.first { it.type == Types.Filter }
        assertTrue("the filter's exposed cutoff came too", 0 in filter.modRanges)
        assertEquals(
            "the promoted knob points at the copy",
            listOf(ParamRef(filter.id, 0)),
            loaded.subpatchPorts?.promoted?.toList(),
        )
        assertEquals(
            "the cable inside is the same cable",
            1,
            patch.connections.count { it.from.moduleId == inside.first { m -> m.type == Types.Osc }.id },
        )
    }

    @Test
    fun `loading twice gives two subpatches that share nothing`() {
        val (_, json) = saved()
        val patch = Patch()
        val first = patch.loadSubpatch(json, Offset.Zero)!!
        val second = patch.loadSubpatch(json, Offset(200f, 0f))!!

        assertNotEquals(first.id, second.id)
        val insideFirst = patch.descendants(first.id)
        val insideSecond = patch.descendants(second.id)
        assertTrue("no module is in both", insideFirst.intersect(insideSecond).isEmpty())

        // Turning one subpatch's knob leaves the other where it was.
        val filterOf = { g: PatchModule ->
            patch.modules.first { it.parent == g.id && it.type == Types.Filter }
        }
        filterOf(first).setParam(1, 0.9f)
        assertNotEquals(0.9f, filterOf(second).params[1])
        // And their promoted knobs point at their own insides.
        assertEquals(listOf(ParamRef(filterOf(first).id, 0)), first.subpatchPorts?.promoted?.toList())
        assertEquals(listOf(ParamRef(filterOf(second).id, 0)), second.subpatchPorts?.promoted?.toList())
    }

    @Test
    fun `a subpatch loads into the scope being looked at`() {
        val (_, json) = saved()
        val patch = Patch()
        val outer = patch.makeSubpatch(setOf(patch.add(Types.Mix, Offset.Zero)!!.id))!!
        patch.enterScope(outer.id)

        val loaded = patch.loadSubpatch(json, Offset.Zero)!!
        assertEquals("it lands where you are looking", outer.id, loaded.parent)
        assertTrue("and it is a subpatch inside a subpatch", patch.descendants(outer.id).contains(loaded.id))
    }

    @Test
    fun `a file that is not one subpatch is refused`() {
        val patch = Patch()
        assertNull("a whole patch is not a subpatch", patch.loadSubpatch(fixturePatch().toJson(), Offset.Zero))
        assertNull("nor is nonsense", patch.loadSubpatch("{\"version\":7}", Offset.Zero))
        assertNull("nor is a version this build cannot read", patch.loadSubpatch("{\"version\":2}", Offset.Zero))

        // A subpatch and something else beside it is not a saved subpatch either, even though the
        // subpatch is the first thing in the file: the rest of the patch would be lost quietly.
        val mixed = Patch()
        val subpatch = mixed.makeSubpatch(setOf(mixed.add(Types.Osc, Offset.Zero)!!.id))!!
        val stray = mixed.add(Types.Mix, Offset(400f, 0f))!!
        assertTrue(
            "the subpatch has to come first, or this proves nothing",
            mixed.free.indexOfFirst { it.id == subpatch.id } < mixed.free.indexOfFirst { it.id == stray.id },
        )
        assertNull("a subpatch with company is refused", patch.loadSubpatch(mixed.toJson(), Offset.Zero))

        assertTrue("and nothing of it landed", patch.free.isEmpty())
    }

    @Test
    fun `the whole patch saves as one subpatch and leaves the patch alone`() {
        val patch = fixturePatch()
        val before = patch.toJson()

        val json = patch.patchToSubpatchJson("Whole thing")!!
        assertEquals("the patch is exactly as it was", before, patch.toJson())

        val into = Patch()
        val loaded = into.loadSubpatch(json, Offset.Zero)!!
        assertEquals("Whole thing", loaded.name)
        // What the patch sent to Out becomes what the subpatch sends out.
        assertTrue("it has something to say", loaded.ports(PortDirection.OUTPUT).isNotEmpty())
        assertEquals(
            "everything that was at the patch's top level is inside it",
            patch.free.count { it.parent == TOP },
            into.modules.count { it.parent == loaded.id && !it.isPinned },
        )
    }

    /**
     * Found on the phone, 2026-09-18: a patch with a subpatch at its top level, and cables
     * crossing into it, came back from "Save patch" with the same cables in a different
     * order. The engine heard nothing -- it diffs sets -- but the autosave saw a new file,
     * so the save became an undo step that did nothing. The demo patch above could not show
     * it: its cables happen to be re-added in the order they started in.
     */
    @Test
    fun `saving the whole patch leaves a patch with subpatches byte for byte alone`() {
        val f = SubpatchFixture()
        f.patch.makeSubpatch(setOf(f.osc.id, f.filter.id))
        val before = f.patch.toJson()

        assertNotNull(f.patch.patchToSubpatchJson("Whole thing"))
        assertEquals(before, f.patch.toJson())
    }

    @Test
    fun `the library keeps files beside the scales, and never replaces one silently`() {
        val library = SubpatchLibrary(folder.newFolder("subpatches"))
        val (_, json) = saved()

        assertTrue(library.names().isEmpty())
        assertTrue(library.write("Filt Osc", json))
        assertEquals(listOf("Filt Osc"), library.names())
        assertTrue(library.exists("Filt Osc"))
        assertNotNull(library.read("Filt Osc"))
        assertNull(library.read("nothing of that name"))

        // "Keep both" never writes over what is there.
        assertEquals("Filt Osc 2", library.freeName("Filt Osc"))
        library.write("Filt Osc 2", json)
        assertEquals("Filt Osc 3", library.freeName("Filt Osc"))
        assertEquals("a free name is returned as it is", "Bass", library.freeName("Bass"))

        // Sorted for the list, regardless of case.
        library.write("apple", json)
        assertEquals(listOf("apple", "Filt Osc", "Filt Osc 2"), library.names())
    }

    @Test
    fun `a name that would escape the folder cannot`() {
        assertEquals("Bass_Lead", SubpatchLibrary.safeName("Bass/Lead"))
        // A dot is not kept either, which is what makes ".." impossible rather than handled.
        SubpatchLibrary.safeName("../../etc/passwd").let { safe ->
            assertFalse(safe, safe.contains('/'))
            assertFalse(safe, safe.contains(".."))
        }
        assertEquals("", SubpatchLibrary.safeName("  "))
        assertTrue(SubpatchLibrary.safeName("x".repeat(80)).length <= MAX_NAME)

        val library = SubpatchLibrary(folder.newFolder("safe"))
        // A blank name has nothing to save under. "///" is not blank -- it saves as "___",
        // an odd name for something the user typed, but its own file and nobody else's.
        assertFalse("a blank name saves nowhere", library.write("   ", "{}"))
        assertTrue(library.write("///", "{}"))
        assertEquals(listOf("___"), library.names())
    }

    @Test
    fun `a saved subpatch can be deleted, and only once`() {
        val library = SubpatchLibrary(folder.newFolder("deleting"))
        val (_, json) = saved()
        library.write("Filt Osc", json)
        library.write("Bass", json)
        assertTrue(library.delete("Filt Osc"))
        assertEquals(listOf("Bass"), library.names())
        assertFalse("gone is gone", library.delete("Filt Osc"))
        assertFalse("and a name that was never there is not an error", library.delete("Nothing"))
    }

    // ------------------------------------------------------------------ opening a saved patch

    /**
     * A tuning that is not the default, from a folder as the app's are: a scale a file names
     * and the library cannot find comes back as the default, so a test in the default tuning
     * could not tell a saved scale from a lost one.
     */
    private val tunings: ScaleLibrary by lazy {
        val dir = folder.newFolder("scales")
        java.io.File(dir, "Pentatonic.scl").writeText("! Pentatonic.scl\nPentatonic\n 5\n 200.0\n 400.0\n 700.0\n 900.0\n 2/1\n")
        // And the default, as the app's folder always has: without it the library's first
        // scale is its default, and a lost Pentatonic would come back as Pentatonic.
        java.io.File(dir, "12-TET.scl").writeText(
            "! 12-TET.scl\n12-TET\n 12\n" + (1..11).joinToString("") { " ${it * 100}.0\n" } + " 2/1\n",
        )
        ScaleLibrary.of(dir).also { check(it.default.name == Scale.Chromatic.name) }
    }

    /** The fixture, off its defaults in everything a patch has besides its modules. */
    private fun whole(): SubpatchFixture = SubpatchFixture().apply {
        patch.tempo = 90f
        patch.beatsPerBar = 3
        patch.scales = listOf(ScaleEntry(tunings.byName("Pentatonic")!!, bars = 2, rootCents = 200f))
        patch.module(OUT_ID)!!.setParam(0, 0.5f)
        patch.name = "Groove"
    }

    /**
     * What Open is for: the patch that was saved, back -- sounding, since its cables to Out
     * are what make it sound, and in its own tempo and tuning. The first whole-patch file held
     * the box alone, and this would have come back silent and in C.
     */
    @Test
    fun `a saved patch opens as the patch it was, wired to Out, in its tempo and tuning`() {
        val f = whole()
        val json = f.patch.patchToSubpatchJson("Groove")!!

        val into = Patch().apply { add(Types.Pluck, Offset.Zero) }
        assertTrue(into.openSaved(json, "Groove", tunings))

        assertEquals("Groove", into.name)
        assertEquals(90f, into.tempo)
        assertEquals(3, into.beatsPerBar)
        assertEquals("Pentatonic", into.scales.single().scale.name)
        assertEquals(200f, into.scales.single().rootCents)
        assertEquals(2, into.scales.single().bars)
        assertEquals(0.5f, into.module(OUT_ID)!!.params[0])
        assertTrue("what was here is gone", into.modules.none { it.type == Types.Pluck })
        assertTrue("and no box is left around what was saved", into.modules.none { it.type.box })
        assertEquals(
            "it sounds as it did: the engine would be sent the same cables",
            f.patch.engineConnections().toSet(),
            into.engineConnections().toSet(),
        )
        assertEquals(TOP, into.scope)
    }

    /**
     * Open asks the library rather than keeping a flag, so this is the whole of what "saved"
     * means: the file under the patch's name is what saving would write now.
     */
    @Test
    fun `a patch is saved until it changes, and saved again when it changes back`() {
        val library = SubpatchLibrary(folder.newFolder("saved"))
        assertTrue("an empty patch has nothing to lose", Patch().isSavedIn(library))

        val f = whole()
        assertFalse("never saved", f.patch.isSavedIn(library))
        library.write("Groove", f.patch.patchToSubpatchJson("Groove")!!)
        assertTrue(f.patch.isSavedIn(library))

        f.osc.setParam(0, 2f)
        assertFalse("a knob moved", f.patch.isSavedIn(library))
        f.osc.setParam(0, Types.Osc.params[0].default)
        assertTrue("and moved back", f.patch.isSavedIn(library))

        f.patch.name = "Other"
        assertFalse("under another name there is nothing saved", f.patch.isSavedIn(library))
        assertFalse("and none at all", f.patch.isSavedIn(null))
    }

    /**
     * Opened and saved are the same patch. Without it, opening a patch and then opening another
     * would ask to save the first -- which nobody changed. It holds only because subpatching
     * the opened patch again makes the box, its ports and its cables exactly as they were made
     * the first time; this is the test that says so, with a box inside the patch as well.
     */
    @Test
    fun `a patch just opened is saved as it stands`() {
        val library = SubpatchLibrary(folder.newFolder("round"))
        val f = whole()
        f.patch.makeSubpatch(setOf(f.lfo.id, f.env.id))!!.name = "Mods"
        library.write("Groove", f.patch.patchToSubpatchJson("Groove")!!)

        val into = Patch()
        assertTrue(into.openSaved(library.read("Groove")!!, "Groove", tunings))
        assertTrue(into.isSavedIn(library))
        assertEquals("the box inside is still a box", "Mods", into.modules.single { it.type.box }.name)
    }

    /** A poly voice is a box, not a patch: unpacking it would make one voice of everything. */
    @Test
    fun `a saved poly voice opens as a patch holding the voice`() {
        val voice = Patch()
        val osc = voice.add(Types.Osc, Offset.Zero)!!
        val poly = voice.makeSubpatch(setOf(osc.id), Types.Poly)!!
        poly.name = "Bell"
        val json = voice.subpatchToJson(poly)!!

        val into = Patch()
        assertTrue(into.openSaved(json, "Bell"))
        val box = into.modules.single { it.parent == TOP && !it.isPinned }
        assertEquals(Types.Poly, box.type)
        assertEquals("Bell", into.name)
    }

    @Test
    fun `a file that is not one saved subpatch opens nothing and changes nothing`() {
        val into = whole().patch
        val before = into.toJson()
        assertFalse(into.openSaved("{not json", "Broken"))
        assertFalse(into.openSaved(Patch().apply { add(Types.Osc, Offset.Zero) }.toJson(), "Loose"))
        assertEquals(before, into.toJson())
    }
}
