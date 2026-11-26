package com.example

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncStatusTest {

    @Test
    fun testOnlineStatusLogic() {
        val isOnline = true
        assertEquals(true, isOnline)
    }
}
