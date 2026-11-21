package com.canvastv.dashboard

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollAction
import androidx.lifecycle.ViewModelProvider
import com.canvastv.dashboard.data.DashboardViewModel
import com.canvastv.dashboard.model.Dashboard
import com.canvastv.dashboard.model.FocusEffect
import com.canvastv.dashboard.model.FontChoice
import com.canvastv.dashboard.model.Page
import com.canvastv.dashboard.model.ThemeMode
import com.canvastv.dashboard.model.Tile
import com.canvastv.dashboard.model.TileType
import com.canvastv.dashboard.model.freeSpot
import com.canvastv.dashboard.ui.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real screens headlessly (Robolectric) to catch crashes before anything reaches the TV.
 * No network here, so online widgets show their loading or offline states.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(qualifiers = "w960dp-h540dp-land-xhdpi")
class ScreenSmokeTest {
    companion object {
        // Robolectric has no other launcher, so the app would think it's the home screen
        // and hand off to "home" on start. Runs before the rule launches the activity.
        init { com.canvastv.dashboard.ui.homeCheckOverride = { false } }
    }

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val vm get() = ViewModelProvider(rule.activity)[DashboardViewModel::class.java]

    private fun onMain(block: () -> Unit) { rule.runOnUiThread(block); rule.waitForIdle() }

    @Test fun `default layout renders with top bar`() {
        rule.waitForIdle()
        rule.onNodeWithText("Settings").assertExists()
        rule.onNodeWithText("Search").assertExists()
    }

    @Test fun `every preset renders`() {
        for (preset in listOf("Info Board", "Media Hub", "Minimal")) {
            onMain { vm.resetToPreset(preset) }
            rule.onNodeWithText("Settings").assertExists()
        }
        onMain { vm.resetToPreset("Info Board") }
        rule.onNodeWithText("Headlines").assertExists()
        rule.onNodeWithText("Set your city in Configure").assertExists()
    }

    @Test fun `every tile type renders unconfigured`() {
        onMain {
            var d = Dashboard(columns = 16, rows = 8, pages = listOf(Page(name = "All")))
            TileType.entries.forEach { t ->
                val p = d.pages[0]
                val spot = p.freeSpot(t.defaultW.coerceAtMost(4), t.defaultH.coerceAtMost(2), d)!!
                d = d.copy(pages = listOf(p.copy(tiles = p.tiles + Tile(type = t, x = spot.first, y = spot.second, w = t.defaultW.coerceAtMost(4), h = t.defaultH.coerceAtMost(2)))))
            }
            vm.replaceDashboard(d)
        }
        rule.onNodeWithText("Countdown").assertExists()
        rule.onNodeWithText("Press OK to test your connection").assertExists()
        rule.onNodeWithText("Set a web address in Configure").assertExists()
    }

    @Test fun `every look renders`() {
        for (mode in ThemeMode.entries) for (hc in listOf(false, true)) {
            onMain {
                val d = vm.dashboard
                vm.replaceDashboard(d.copy(theme = d.theme.copy(mode = mode, highContrast = hc,
                    focusEffect = FocusEffect.entries.random(), font = FontChoice.entries.random(), seasonal = true, gameDayColors = 2, gameDays = (1..7).toList())))
            }
            rule.onNodeWithText("Settings").assertExists()
        }
        onMain { vm.replaceDashboard(vm.dashboard.copy(lightweight = true)) }
        rule.onNodeWithText("Settings").assertExists()
    }

    @Test fun `settings opens and scrolls to the end`() {
        rule.onNodeWithText("Settings").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Mode").assertExists()
        rule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Close"))
        rule.onNodeWithText("Close").assertExists()
    }

    @Test fun `edit mode, add tile dialog and pin lock`() {
        onMain { vm.startEditing() }
        rule.onNodeWithText("+ Tile").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Add a tile").assertExists()
        // Lock with a PIN; leaving and re-entering edit mode now asks for it.
        onMain { vm.setPin("1234"); vm.stopEditing() }
        onMain { vm.startEditing() }
        rule.onNodeWithText("Enter PIN to edit").assertExists()
        onMain { assertTrue(vm.submitPin("0000") != null); assertTrue(vm.submitPin("1234") == null) }
        assertTrue(vm.editing)
        onMain { vm.stopEditing() }
        assertTrue(rule.onAllNodesWithText("Enter PIN to edit").fetchSemanticsNodes().isEmpty())
    }

    @Test fun `history keeps a version after editing`() {
        onMain { vm.startEditing(); vm.addTile(TileType.NOTE); vm.stopEditing() }
        assertTrue(vm.historyEntries().any { it.label == "Before edits" })
    }
}
