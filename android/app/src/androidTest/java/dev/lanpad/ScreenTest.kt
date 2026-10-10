package dev.lanpad

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.lanpad.input.Command
import dev.lanpad.network.DiscoveredReceiver
import dev.lanpad.network.DiscoveryState
import dev.lanpad.network.Endpoint
import dev.lanpad.network.LinkState
import dev.lanpad.network.Phase
import dev.lanpad.ui.LanPadScreen
import dev.lanpad.ui.LanPadTheme
import dev.lanpad.ui.PadActions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun disconnectedScreenShowsSetupAndDisablesMouseButtons() {
        compose.setContent { LanPadTheme { LanPadScreen(PadState(settingsReady = true), PadActions()) } }
        compose.onNodeWithText("Left").assertIsNotEnabled()
        compose.onNodeWithText("Connect your computer").performClick()
        compose.onNodeWithText("Pairing PIN").assertIsDisplayed()
        compose.onNodeWithText("Connect").assertIsNotEnabled()
    }

    @Test fun accessibleButtonClickAndStopSendTheExpectedActions() {
        val input = mutableListOf<Command>()
        var stopped = false
        compose.setContent {
            LanPadTheme {
                LanPadScreen(PadState(settingsReady = true, link = LinkState(Phase.CONNECTED)),
                    PadActions(input = { input.add(it) }, disconnect = { stopped = true }))
            }
        }
        compose.onNodeWithText("Right").performClick()
        assertEquals(listOf(Command.Click("right")), input)
        compose.onNodeWithText("Stop").performClick()
        assertEquals(true, stopped)
        compose.onNodeWithContentDescription("Connection settings").assertIsDisplayed()
    }

    @Test fun discoveredComputerNeedsOnlyAPinWithoutAddressFields() {
        val receiver = DiscoveredReceiver("LanPad computer", Endpoint("192.168.1.2", 9876))
        compose.setContent {
            LanPadTheme {
                LanPadScreen(PadState(settingsReady = true,
                    discovery = DiscoveryState(receivers = listOf(receiver))), PadActions())
            }
        }
        compose.onNodeWithText("Connect your computer").performClick()
        compose.onNodeWithText("LanPad computer").assertIsDisplayed()
        compose.onNodeWithText("Selected").assertIsDisplayed()
        compose.onNodeWithText("Pairing PIN").assertIsDisplayed()
        compose.onNodeWithText("Computer IP").assertDoesNotExist()
    }
}
