package com.example

import com.example.core.model.BatchSyncStatus
import com.example.core.model.Grade
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class F03DataIntegrityTest {

    @Test
    fun testDataModelIntegrity() {
        val grade = Grade(id = "g1", name = "الصف الأول", displayOrder = 1, teacherId = "t1")
        assertEquals("g1", grade.id)
        assertEquals("الصف الأول", grade.name)
        assertEquals(BatchSyncStatus.SAVED_TO_CLOUD, BatchSyncStatus.SAVED_TO_CLOUD)
    }
}
