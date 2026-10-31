package dev.lanpad

import dev.lanpad.network.Phase
import dev.lanpad.network.StartupConnection
import org.junit.Assert.assertEquals
import org.junit.Test

class StartupConnectionTest {
    @Test fun waitsForVisibleReadyDiscoveryThenConnectsOnlyOnce() {
        val startup = StartupConnection()
        assertEquals(StartupConnection.Action.NONE, startup.decide(false, true, Phase.DISCONNECTED, true, true))
        assertEquals(StartupConnection.Action.NONE, startup.decide(true, false, Phase.DISCONNECTED, true, true))
        assertEquals(StartupConnection.Action.NONE, startup.decide(true, true, Phase.DISCONNECTED, false, true))
        assertEquals(StartupConnection.Action.CONNECT, startup.decide(true, true, Phase.DISCONNECTED, true, true))
        assertEquals(StartupConnection.Action.NONE, startup.decide(true, true, Phase.DISCONNECTED, true, true))
    }

    @Test fun newOrExpiredDevicePromptsOnceAndDoesNotConnectWithoutConsent() {
        val startup = StartupConnection()
        assertEquals(StartupConnection.Action.PROMPT, startup.decide(true, true, Phase.DISCONNECTED, true, false))
        assertEquals(StartupConnection.Action.NONE, startup.decide(true, true, Phase.DISCONNECTED, true, false))
    }

    @Test fun explicitStopSuppressesAutomaticConnectAndPrompt() {
        val startup = StartupConnection()
        startup.handled()
        assertEquals(StartupConnection.Action.NONE, startup.decide(true, true, Phase.DISCONNECTED, true, true))
        assertEquals(StartupConnection.Action.NONE, startup.decide(true, true, Phase.DISCONNECTED, true, false))
    }

    @Test fun existingBackgroundSessionIsNotReplacedAndDoesNotAutoRestartAfterStopping() {
        val startup = StartupConnection()
        assertEquals(StartupConnection.Action.NONE, startup.decide(true, true, Phase.CONNECTED, true, true))
        assertEquals(StartupConnection.Action.NONE, startup.decide(true, true, Phase.DISCONNECTED, true, true))
    }

    @Test fun rememberedManualAddressCanConnectWithoutDiscovery() {
        assertEquals(StartupConnection.Action.CONNECT,
            StartupConnection().decide(true, true, Phase.DISCONNECTED, true, true))
        assertEquals(StartupConnection.Action.PROMPT,
            StartupConnection().decide(true, true, Phase.DISCONNECTED, true, false))
    }

    @Test fun aFreshLaunchCanRetryAPreviousFailureButDoesNotLoopAfterANewFailure() {
        val startup = StartupConnection()
        assertEquals(StartupConnection.Action.CONNECT, startup.decide(true, true, Phase.ERROR, true, true))
        assertEquals(StartupConnection.Action.NONE, startup.decide(true, true, Phase.ERROR, true, true))
    }
}
