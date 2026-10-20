package dev.lanpad

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
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
        compose.onNodeWithText("Right").performSemanticsAction(SemanticsActions.OnClick) { it() }
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

    @Test fun keyboardModeSendsTextKeysAndConfiguredI3Shortcuts() {
        val input = mutableListOf<Command>()
        compose.setContent {
            LanPadTheme {
                LanPadScreen(PadState(settingsReady = true, link = LinkState(Phase.CONNECTED)),
                    PadActions(input = { input.add(it) }))
            }
        }
        compose.onNodeWithContentDescription("Switch to keyboard").performClick()
        compose.onNodeWithText("Text to send").performTextInput("Caffè")
        compose.onNodeWithText("Send text").performClick()
        compose.onNodeWithText("Enter ↵").performScrollTo().performClick()
        assertEquals(listOf(Command.Text("Caffè"), Command.Key("enter")), input)
        compose.onNodeWithText("i3 shortcuts").performClick()
        compose.onNodeWithText("Terminal").performScrollTo().performClick()
        assertEquals(Command.Key("enter", listOf("super")), input.last())
        compose.onNodeWithContentDescription("Switch to mouse").performClick()
        compose.onNodeWithText("Left").assertIsDisplayed()
    }

    @Test fun disconnectedKeyboardCannotSendInput() {
        compose.setContent { LanPadTheme { LanPadScreen(PadState(settingsReady = true), PadActions()) } }
        compose.onNodeWithContentDescription("Switch to keyboard").performClick()
        compose.onNodeWithText("Text to send").performTextInput("hello")
        compose.onNodeWithText("Send text").assertIsNotEnabled()
        compose.onNodeWithText("Enter ↵").assertIsNotEnabled()
        compose.onNodeWithText("i3 shortcuts").performClick()
        compose.onNodeWithText("Terminal").assertIsNotEnabled()
    }

    @Test fun modifiersRemainSelectedAcrossRepeatedKeyPresses() {
        val input = mutableListOf<Command>()
        compose.setContent {
            LanPadTheme {
                LanPadScreen(PadState(settingsReady = true, link = LinkState(Phase.CONNECTED)),
                    PadActions(input = { input.add(it) }))
            }
        }
        compose.onNodeWithContentDescription("Switch to keyboard").performClick()
        compose.onNodeWithText("Ctrl").performScrollTo().performClick()
        compose.onNodeWithText("↓").performScrollTo().performClick().performClick()
        assertEquals(listOf(Command.Key("down", listOf("ctrl")), Command.Key("down", listOf("ctrl"))), input)
        compose.onNodeWithText("Ctrl").assertIsSelected()
        compose.onNodeWithText("Clear modifiers").performScrollTo().performClick()
        compose.onNodeWithText("↓").performScrollTo().performClick()
        assertEquals(Command.Key("down"), input.last())
    }

    @Test fun rememberedComputerCanConnectWithAnEmptyPin() {
        compose.setContent {
            LanPadTheme {
                LanPadScreen(PadState(settingsReady = true, canResume = true,
                    trustExpiresAt = System.currentTimeMillis() / 1000 + 90L * 86400,
                    discovery = DiscoveryState(receivers = listOf(
                        DiscoveredReceiver("Remembered computer", Endpoint("192.168.1.2", 8765))))), PadActions())
            }
        }
        compose.onNodeWithText("Connect your computer").performClick()
        compose.onNodeWithText("Reconnect").assertIsEnabled()
        compose.onNodeWithText("Pairing PIN").assertDoesNotExist()
        compose.onNodeWithText("Auto-connect valid for 90 days").assertIsDisplayed()
        compose.onNodeWithText("Pair again with a PIN").performScrollTo().performClick()
        compose.onNodeWithText("Pairing PIN").assertIsDisplayed()
    }

    @Test fun connectedSheetShowsComputerAndRemainingTrustInsteadOfPinForm() {
        val expires = System.currentTimeMillis() / 1000 + 43L * 86400
        compose.setContent {
            LanPadTheme {
                LanPadScreen(PadState(settingsReady = true,
                    settings = dev.lanpad.data.PadSettings(host = "192.168.1.2", receiverName = "Workstation"),
                    link = LinkState(Phase.CONNECTED, trustExpiresAt = expires)), PadActions())
            }
        }
        compose.onNodeWithContentDescription("Connection settings").performClick()
        compose.onNodeWithText("You're connected").assertIsDisplayed()
        compose.onNodeWithText("Workstation").assertIsDisplayed()
        compose.onNodeWithText("Auto-connect valid for 43 days").assertIsDisplayed()
        compose.onNodeWithText("Pairing PIN").assertDoesNotExist()
        compose.onNodeWithText("Disconnect").assertIsEnabled()
    }
}
