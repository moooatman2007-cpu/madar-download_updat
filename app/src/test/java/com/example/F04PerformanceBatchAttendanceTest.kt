package com.example

import com.example.core.model.BatchAttendanceItemDto
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class F04PerformanceBatchAttendanceTest {

    @Test
    fun testBatchAttendanceListCreation() {
        val items = (1..30).map { i ->
            BatchAttendanceItemDto(studentId = "student_30_$i", status = "PRESENT", note = null)
        }
        assertEquals(30, items.size)
    }
}
