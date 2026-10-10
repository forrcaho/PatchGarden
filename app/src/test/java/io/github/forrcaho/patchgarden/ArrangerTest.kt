package io.github.forrcaho.patchgarden

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Arranger, roadmap item 5: scenes shared by every song, songs as versions, lanes as jacks.
 * The engine's half is in node_test; this is the model, the file and what crosses.
 */
class ArrangerTest {

    private fun out(m: PatchModule, i: Int = 0) = PortRef(m.id, PortDirection.OUTPUT, i)
    private fun knob(m: PatchModule, i: Int) = PortRef(m.id, PortDirection.MOD, i)

    @Test
    fun `a new Arranger is four lanes, one scene and one song of it, a bar a step`() {
        val patch = Patch()
        val arranger = patch.add(Types.Arranger, Offset.Zero)!!
        assertEquals(4, arranger.laneCount)
        assertEquals(4, arranger.ports(PortDirection.OUTPUT).size)
        assertEquals(listOf("A"), arranger.scenes.map { it.name })
        assertEquals(listOf(Song(listOf(Section(0, 4)))), arranger.songs.toList())
        assertEquals(Interval(1, 1, bars = true), arranger.interval)
        assertEquals("4 bars", arranger.sectionLength(4))
        assertEquals("it sends modulation, so it is filed under Mod", Category.MOD, Types.Arranger.category)
        assertFalse("it loops unless told to stop", arranger.stopsAtEnd)
    }

    @Test
    fun `a new section is a new scene, and a branch of one is its prime`() {
        val patch = Patch()
        val arranger = patch.add(Types.Arranger, Offset.Zero)!!
        arranger.setCell(0, 1, 0.9f)
        assertTrue(arranger.appendSection())
        assertEquals(listOf("A", "B"), arranger.scenes.map { it.name })
        assertEquals("B starts from A's values", 0.9f, arranger.scenes[1].levels[1])
        assertEquals(Section(1, 4), arranger.shownSections[1])

        assertTrue(arranger.branchScene(1))
        assertEquals("B'", arranger.scenes.last().name.replace('′', '\''))
        assertEquals("the section plays its new scene", 2, arranger.shownSections[1].scene)
        assertTrue(arranger.branchScene(1))
        assertEquals("B''", arranger.scenes.last().name.replace("″", "''"))

        // Editing a scene edits every section that plays it.
        arranger.setSectionScene(0, 1)
        arranger.setCell(1, 0, 0.1f)
        assertEquals(1, arranger.shownSections[0].scene)
        assertEquals(0.1f, arranger.scenes[arranger.shownSections[0].scene].levels[0])

        assertFalse("a song keeps one section", run { arranger.deleteSection(0); arranger.deleteSection(0); arranger.deleteSection(0) })
        assertEquals(1, arranger.shownSections.size)
    }

    @Test
    fun `its versions are songs over the same scenes, and the knob follows a deletion down`() {
        val patch = Patch()
        val arranger = patch.add(Types.Arranger, Offset.Zero)!!
        arranger.appendSection()
        assertTrue(arranger.addVersion())
        assertEquals(2, arranger.versionCount)
        assertEquals("shown at once", 2, arranger.shownVersion)
        assertEquals("a copy of the song shown", arranger.songs[0], arranger.songs[1])
        arranger.setSectionSteps(0, 8)
        assertEquals("an edit is to the song shown", 4, arranger.songs[0].sections[0].steps)
        assertEquals(8, arranger.songs[1].sections[0].steps)

        arranger.setParam(Types.Arranger.versionParam, 2f)
        assertTrue(arranger.deleteVersion(1))
        assertEquals(1, arranger.songs.size)
        assertEquals("song 2 is song 1 now", 8, arranger.songs[0].sections[0].steps)
        assertEquals("and the knob playing it followed it down", 1f, arranger.params[Types.Arranger.versionParam])
        assertFalse("the last song cannot go", arranger.deleteVersion(1))
    }

    @Test
    fun `a lane removed takes its cables, and the cables after it move up a jack`() {
        val patch = Patch()
        val arranger = patch.add(Types.Arranger, Offset.Zero)!!
        val seq = patch.add(Types.Seq, Offset(200f, 0f))!!
        val filter = patch.add(Types.Filter, Offset(200f, 200f))!!
        assertTrue(patch.expose(seq, Types.Seq.versionParam, ModRange(0f, 2f)))
        assertTrue(patch.expose(filter, 0, ModRange(200f, 4000f)))
        assertTrue(patch.connect(out(arranger, 1), knob(seq, Types.Seq.versionParam)))
        assertTrue(patch.connect(out(arranger, 2), knob(filter, 0)))
        arranger.setCell(0, 2, 0.75f)

        assertTrue(patch.removeLane(arranger, 1))
        assertEquals(3, arranger.laneCount)
        assertFalse("its own cable went", patch.connections.any { it.to.moduleId == seq.id })
        assertTrue("the next one is on the jack above", Connection(out(arranger, 1), knob(filter, 0)) in patch.connections)
        assertEquals("and its column of every scene came with it", 0.75f, arranger.scenes[0].levels[1])
        assertEquals(ParamRow(filter, 0), patch.laneTarget(arranger, 1))

        assertTrue(arranger.addLane())
        assertEquals("a lane added is a jack under the others", 4, arranger.ports(PortDirection.OUTPUT).size)
    }

    /**
     * Four sections and the "+" fit together, as on the phone at font scale 1.5: the "+" is narrow,
     * and the first build, which laid out only whole columns, scrolled it out of view at exactly
     * four -- seen on the phone, where the gesture tests' wider screen never reached that width.
     */
    @Test
    fun `the plus after the last section is in view beside four sections where a fifth would not fit`() {
        val patch = Patch()
        val arranger = patch.add(Types.Arranger, Offset.Zero)!!
        repeat(3) { arranger.appendSection() }
        // At d = 1 and text 1: names 96, columns 76, the "+" 40 -- room for four and the "+", not five.
        val area = androidx.compose.ui.geometry.Rect(0f, 0f, 96f + 4 * 76f + 40f + 2f, 300f)
        val table = arrangerTable(area, 1f, 1f, arranger)
        assertEquals(4, table.heads.size)
        assertEquals("nothing to scroll", 0, table.maxSectionScroll)
        assertNotNull("and the plus in view", table.plus)
        assertTrue("inside the table", table.plus!!.right <= area.right)

        arranger.appendSection()
        val longer = arrangerTable(area, 1f, 1f, arranger)
        assertTrue("a fifth section scrolls", longer.maxSectionScroll > 0)
        arranger.sectionScroll = longer.maxSectionScroll
        assertNotNull("and scrolled to the end the plus is there again", arrangerTable(area, 1f, 1f, arranger).plus)
    }

    /**
     * The Arranger's limits and the packing of its position cross the boundary as numbers on
     * both sides; a disagreement drops slots or misreads where it is, silently. Read out of the
     * header, as the node types and slot kinds are.
     */
    @Test
    fun `the Arranger's limits and position packing are the engine's`() {
        val header = java.io.File("src/main/cpp/nodes.h").readText()
            .substringAfter("class ArrangerNode").substringBefore("ArrangerNode();")
        fun engine(name: String) =
            Regex("""$name\s*=\s*(\d+)""").find(header)!!.groupValues[1].toInt()
        assertEquals(LANES_MAX, engine("kLanes"))
        assertEquals(MAX_SCENES, engine("kMaxScenes"))
        assertEquals(MAX_SECTIONS, engine("kMaxSections"))
        assertEquals(MAX_SONGS, engine("kMaxSongs"))
        assertEquals(SECTION_STEP_STRIDE, engine("kStepStride"))
        assertTrue("a section's longest length fits in the stride", MAX_SECTION_STEPS < SECTION_STEP_STRIDE)
    }

    /** What a closed Arranger says: the scene, the bar into the section, and how far into the song. */
    @Test
    fun `a closed Arranger says its scene, its bar and how far through the song`() {
        val patch = Patch()
        val arranger = patch.add(Types.Arranger, Offset.Zero)!!
        arranger.setSectionSteps(0, 2)
        arranger.appendSection()
        arranger.setSectionSteps(1, 1)
        val playing = arranger.arrangerWhere(0 * SECTION_STEP_STRIDE + 1, 1)!!
        assertEquals("A", playing.scene)
        assertEquals("bar 2 of 2", playing.within)
        assertEquals("two of the song's three bars", 2f / 3f, playing.song, 1e-6f)
        assertEquals("B", arranger.arrangerWhere(1 * SECTION_STEP_STRIDE, 1)!!.scene)
        assertEquals("stopped or ended says nothing", null, arranger.arrangerWhere(-1, 1))
        assertEquals("nor does version 0", null, arranger.arrangerWhere(0, 0))
        arranger.setParam(Types.Arranger.intervalParam, Interval(1, 2).code.toFloat())
        assertEquals("at an interval other than a bar it counts steps", "step 2 of 2",
            arranger.arrangerWhere(1, 1)!!.within)
        assertEquals(1, sectionOfPosition(SECTION_STEP_STRIDE + 3))
        assertEquals(-1, sectionOfPosition(-1))
    }

    /** A Seq's version is the engine's while something drives the knob, and the knob's otherwise. */
    @Test
    fun `the version a Seq plays is the engine's when driven and its knob's when not`() {
        val patch = Patch()
        val arranger = patch.add(Types.Arranger, Offset.Zero)!!
        val seq = patch.add(Types.Seq, Offset(200f, 0f))!!
        assertFalse(patch.versionDriven(seq))
        assertEquals(1, seq.playingVersion(Activity(3)))
        seq.setParam(Types.Seq.versionParam, 0f)
        assertEquals(0, seq.playingVersion(null))
        assertTrue(patch.expose(seq, Types.Seq.versionParam, ModRange(0f, 2f)))
        assertTrue(patch.connect(out(arranger), knob(seq, Types.Seq.versionParam)))
        assertTrue(patch.versionDriven(seq))
        assertEquals(2, seq.playingVersion(Activity(3, version = 2)))
        assertEquals("a filter has no version", null, patch.add(Types.Filter, Offset.Zero)!!.playingVersion(null))
    }

    @Test
    fun `a lane is named by its knob and says a Seq's versions as off and v1`() {
        val patch = Patch()
        val arranger = patch.add(Types.Arranger, Offset.Zero)!!
        val seq = patch.add(Types.Seq, Offset(200f, 0f))!!
        assertEquals("lane 1" to "", patch.laneName(arranger, 0))
        assertTrue(patch.expose(seq, Types.Seq.versionParam, ModRange(0f, 2f)))
        assertTrue(patch.connect(out(arranger, 0), knob(seq, Types.Seq.versionParam)))
        assertEquals(seq.title to "version", patch.laneName(arranger, 0))
        val options = patch.laneOptions(arranger, 0)
        assertNotNull(options)
        assertEquals(listOf("off", "v1", "v2"), options!!.map { it.second })
        assertEquals(listOf(0f, 0.5f, 1f), options.map { it.first })
        assertEquals("a lane with nothing patched takes a slider", null, patch.laneOptions(arranger, 1))
    }

    @Test
    fun `scenes and songs are saved, read back byte for byte, and copied`() {
        val patch = Patch()
        val arranger = patch.add(Types.Arranger, Offset.Zero)!!
        arranger.setCell(0, 3, 0.25f)
        arranger.appendSection()
        arranger.branchScene(1)
        arranger.setSectionSteps(1, 2)
        arranger.addVersion()
        arranger.addLane()
        arranger.toggleEnd()
        val json = patch.toJson()
        val back = patchFromJson(json)!!
        val read = back.modules.single { it.type == Types.Arranger }
        assertEquals(arranger.scenes.toList(), read.scenes.toList())
        assertEquals(arranger.songs.toList(), read.songs.toList())
        assertEquals(5, read.laneCount)
        assertTrue(read.stopsAtEnd)
        assertEquals("the round trip is byte-identical, which undo depends on", json, back.toJson())

        val copy = PatchModule(99, Types.Arranger, Offset.Zero).also { it.copyGridFrom(arranger) }
        assertEquals(arranger.scenes.toList(), copy.scenes.toList())
        assertEquals(arranger.songs.toList(), copy.songs.toList())
    }

    /**
     * Cells go as segment slots at scene * 8 + lane, a level each; sections as section slots at
     * song * 64 + section, every song followed by an empty one that ends it -- every song there
     * could be, so one deleted is empty in the engine rather than what was last there.
     */
    @Test
    fun `what crosses is every cell and every song with its end, and an edit is one slot`() {
        val patch = Patch()
        val arranger = patch.add(Types.Arranger, Offset.Zero)!!
        val cells = mutableListOf<Pair<Int, Float>>()
        val sections = mutableListOf<Triple<Int, Int, Int>>()
        val commands = object : GraphCommands by NoCommands {
            override fun setSegment(id: Long, slot: Int, time: Float, level: Float, curve: Float, sustain: Boolean) {
                cells += slot to level
            }
            override fun setSection(id: Long, slot: Int, scene: Int, length: Int) {
                sections += Triple(slot, scene, length)
            }
        }
        val sync = GraphSync(commands)
        sync.sync(patch)
        assertEquals("one scene of eight lanes", 8, cells.size)
        assertTrue(Triple(0, 0, 4) in sections)
        assertTrue("song 1 ends after its one section", Triple(1, 0, 0) in sections)
        assertTrue("and every other song is empty", (1 until MAX_SONGS).all { Triple(it * MAX_SECTIONS, 0, 0) in sections })

        cells.clear()
        sections.clear()
        arranger.setCell(0, 2, 0.3f)
        sync.sync(patch)
        assertEquals(listOf(2 to 0.3f), cells)
        assertTrue(sections.isEmpty())

        arranger.appendSection()
        cells.clear()
        sections.clear()
        sync.sync(patch)
        assertEquals("the new scene's eight cells", (8 until 16).toList(), cells.map { it.first })
        assertEquals(listOf(Triple(1, 1, 4), Triple(2, 0, 0)), sections)
    }
}
