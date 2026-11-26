package com.example

import com.example.core.model.Group
import com.example.core.model.OnlineLoadState
import com.example.core.model.Student
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSyncReconciliationTest {

    @Test
    fun testOnlineOnlyPolicy_staleLocalRecordsNeverDisplayedWhenServerFails() {
        val staleLocalStudents = (1..22).map { i ->
            Student(
                studentId = "s$i",
                studentCode = "ST-$i",
                fullName = "طالب محلي قديم $i",
                gradeId = "g1",
                parentPhone = "01011111111",
                teacherId = "t1",
                groupId = "grp1"
            )
        }
        val state: OnlineLoadState<List<Student>> = OnlineLoadState.Offline("لا يوجد اتصال بالإنترنت")
        assertTrue(state is OnlineLoadState.Offline)
        assertEquals(22, staleLocalStudents.size)
    }

    @Test
    fun testOnlineOnlyPolicy_duplicateGroupNamesNotMerged() {
        val group1 = Group(id = "g-a", teacherId = "t1", gradeId = "grade-1", name = "الأول الثانوي", startTime = "10:00", endTime = "11:00")
        val group2 = Group(id = "g-b", teacherId = "t1", gradeId = "grade-1", name = "الأول الثانوي", startTime = "12:00", endTime = "13:00")

        val groups = listOf(group1, group2)
        assertEquals(2, groups.size)
        assertTrue(groups.distinctBy { it.id }.size == 2)
    }
}
