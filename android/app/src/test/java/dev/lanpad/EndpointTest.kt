package dev.lanpad

import dev.lanpad.network.Endpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EndpointTest {
    @Test fun privateAddressesProduceAnExplicitWebSocketEndpoint() {
        for (address in listOf("10.0.0.2", "172.16.0.2", "192.168.1.20")) {
            assertEquals("ws://$address:8765", Endpoint.parse(address, "8765").url)
        }
    }

    @Test fun publicMalformedAndLoopbackAddressesAreRejected() {
        for (address in listOf("8.8.8.8", "localhost", "127.0.0.1", "0.0.0.0", "192.168.1.999", "172.32.0.1")) {
            assertThrows(IllegalArgumentException::class.java) { Endpoint.parse(address, "8765") }
        }
    }

    @Test fun portsMustBeValid() {
        for (port in listOf("0", "65536", "text")) {
            assertThrows(IllegalArgumentException::class.java) { Endpoint.parse("192.168.1.20", port) }
        }
    }
}
