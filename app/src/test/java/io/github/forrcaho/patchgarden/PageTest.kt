package io.github.forrcaho.patchgarden

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The pages over the canvas, and which of them a tap outside the card closes.
 *
 * Found on the phone, 2026-09-27: the first launch's folder offer was up on a dimmed screen, the
 * tap that woke the screen landed on the scrim, and the scrim answered "Not now" -- for an offer
 * made once. A page that asks something is answered by its buttons; a page that only shows
 * something still closes on a tap away, as every page here always has.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w986dp-h443dp-land-390dpi")
class PageTest {
    @get:Rule val compose = createComposeRule()

    /** Near a corner of the screen: the scrim, well clear of any card. */
    private fun tapAway() {
        compose.onRoot().performTouchInput { click(Offset(20f, height - 20f)) }
        compose.waitForIdle()
    }

    @Test
    fun `a tap on the scrim does not answer the folder offer, and its buttons do`() {
        var chose = 0
        var notNow = 0
        compose.setContent { FolderOffer(onChoose = { chose++ }, onNotNow = { notNow++ }) }
        tapAway()
        assertEquals("the tap that wakes the screen is not an answer", 0, notNow)
        compose.onAllNodesWithText("Not now").onFirst().performClick()
        assertEquals(1, notNow)
        compose.onAllNodesWithText("Choose folder…").onFirst().performClick()
        assertEquals(1, chose)
    }

    @Test
    fun `nor the offer to move files`() {
        var finished = 0
        val home = DirHome(java.io.File(System.getProperty("java.io.tmpdir")!!))
        val offer = MoveOffer(home, home, mapOf(Folders.SCALES to listOf("A.scl")))
        compose.setContent { MoveOverlay(offer) { finished++ } }
        tapAway()
        assertEquals("away is not Leave them", 0, finished)
        compose.onAllNodesWithText("Leave them").onFirst().performClick()
        assertEquals(1, finished)
    }

    @Test
    fun `a page that only shows something still closes on a tap away`() {
        var done = 0
        compose.setContent { SettingsOverlay(AppControls(), onLicenses = {}) { done++ } }
        tapAway()
        assertEquals(1, done)
    }
}
