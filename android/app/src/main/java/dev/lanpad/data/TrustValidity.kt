package dev.lanpad.data

/** Display whole remaining days rounded up; expiry is authoritative on the receiver. */
fun trustDaysRemaining(expiresAt: Long, now: Long = System.currentTimeMillis() / 1000): Long {
    val seconds = (expiresAt - now).coerceAtLeast(0)
    return seconds / 86_400 + if (seconds % 86_400 == 0L) 0 else 1
}
