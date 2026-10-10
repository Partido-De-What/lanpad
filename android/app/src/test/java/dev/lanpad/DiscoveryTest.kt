package dev.lanpad

import dev.lanpad.network.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiscoveryTest {
    @Test fun pinIsExactlyFiveAsciiDigitsAndRetainsLeadingZeros() {
        assertTrue(Pairing.validPin("00042"))
        listOf("1234", "123456", "１２３４５", "12a45", " 12345").forEach { assertFalse(Pairing.validPin(it)) }
        val message = JSONObject(Pairing.message("00042"))
        assertEquals("00042", message.getString("pin"))
        assertEquals(2, message.getInt("version"))
        // keys() is available in both Android's JSONObject and the JVM test library.
        assertEquals(setOf("type", "pin", "version"), message.keys().asSequence().toSet())
    }

    @Test fun unsupportedOrPublicAdvertisementsAreIgnored() {
        assertNull(discoveredReceiver("Computer", listOf("192.168.1.2"), 8765, "1"))
        assertNull(discoveredReceiver("Computer", listOf("8.8.8.8", "127.0.0.1"), 8765, "2"))
        assertNull(discoveredReceiver("Computer", listOf("192.168.1.2"), 0, "2"))
    }

    @Test fun discoveryUsesAnAdvertisedLanAddressAndCustomPort() {
        val receiver = discoveredReceiver("Computer", listOf("::1", "192.168.1.2"), 9876, "2")!!
        assertEquals(Endpoint("192.168.1.2", 9876), receiver.endpoint)
    }

    @Test fun oneComputerIsAutomaticAndMultipleComputersNeedAChoice() {
        val first = DiscoveredReceiver("First", Endpoint("192.168.1.2", 8765))
        val second = DiscoveredReceiver("Second", Endpoint("192.168.1.3", 8765))
        assertEquals(first, chooseReceiver(listOf(first), null))
        assertNull(chooseReceiver(listOf(first, second), null))
        assertEquals(second, chooseReceiver(listOf(first, second), "Second"))
        assertNull(chooseReceiver(emptyList(), "Second"))
    }
}
