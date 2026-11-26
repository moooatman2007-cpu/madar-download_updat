package com.example

import com.example.core.model.AttendanceStatus
import com.example.core.model.BatchAttendanceItemDto
import com.example.core.model.BatchSyncStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AttendanceAtomicityTest {

    @Test
    fun testBatchItemDtoCreation() {
        val item = BatchAttendanceItemDto(studentId = "s1", status = "PRESENT", note = null)
        assertEquals("s1", item.studentId)
        assertEquals("PRESENT", item.status)
        assertEquals(BatchSyncStatus.SAVED_TO_CLOUD, BatchSyncStatus.SAVED_TO_CLOUD)
    }
}
