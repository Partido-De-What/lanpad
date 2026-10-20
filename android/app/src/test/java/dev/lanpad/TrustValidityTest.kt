package dev.lanpad

import dev.lanpad.data.trustDaysRemaining
import org.junit.Assert.assertEquals
import org.junit.Test

class TrustValidityTest {
    @Test fun remainingDaysDecreaseWithTimeAndExpiredTrustShowsZero() {
        val expiry = 90L * 86_400
        assertEquals(90L, trustDaysRemaining(expiry, 0))
        assertEquals(89L, trustDaysRemaining(expiry, 86_400))
        assertEquals(1L, trustDaysRemaining(expiry, expiry - 1))
        assertEquals(0L, trustDaysRemaining(expiry, expiry))
        assertEquals(0L, trustDaysRemaining(expiry, expiry + 1))
    }
}
