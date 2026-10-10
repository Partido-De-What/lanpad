package dev.lanpad.network

/** Validate a numeric private IPv4 endpoint without triggering DNS on the UI thread. */
data class Endpoint(val host: String, val port: Int) {
    val url: String get() = "ws://$host:$port"

    companion object {
        fun parse(host: String, port: String): Endpoint {
            val clean = host.trim()
            val parts = clean.split('.').map { it.toIntOrNull() }
            require(parts.size == 4 && parts.all { it != null && it in 0..255 }) {
                "Enter the computer's private IPv4 address, for example 192.168.1.20."
            }
            val (a, b, c, d) = parts.map { requireNotNull(it) }
            require(a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)) {
                "Use a private address on the same network as your phone."
            }
            val number = port.toIntOrNull()
            require(number != null && number in 1..65535) { "Port must be between 1 and 65535." }
            return Endpoint("$a.$b.$c.$d", number)
        }
    }
}
