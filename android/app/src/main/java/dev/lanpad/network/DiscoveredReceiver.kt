package dev.lanpad.network

data class DiscoveredReceiver(val name: String, val endpoint: Endpoint)

data class DiscoveryState(
    val receivers: List<DiscoveredReceiver> = emptyList(),
    val searching: Boolean = false,
    val message: String = "",
)

/** Discovery is a hint; reject unsupported versions and non-LAN addresses before connecting. */
fun discoveredReceiver(name: String, addresses: List<String>, port: Int, version: String?): DiscoveredReceiver? {
    if (version != Pairing.VERSION.toString() || name.isBlank()) return null
    val endpoint = addresses.firstNotNullOfOrNull { address ->
        runCatching { Endpoint.parse(address, port.toString()) }.getOrNull()
    } ?: return null
    return DiscoveredReceiver(name.take(80), endpoint)
}

/** Choose the remembered computer or the sole result; multiple unknown computers require a choice. */
fun chooseReceiver(receivers: List<DiscoveredReceiver>, preferred: String?): DiscoveredReceiver? =
    receivers.firstOrNull { it.name == preferred } ?: receivers.singleOrNull()
