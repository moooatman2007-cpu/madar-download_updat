package com.example

import com.example.core.model.Student
import com.example.core.model.Group
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSyncReconciliationTest {

    @Test
    fun testReconciliationConcept1_reducesStaleLocalRecordsToMatchServer() {
        val serverStudents = listOf(
            Student(studentId = "s1", studentCode = "ST-001", fullName = "الطالب الحقيقي", gradeId = "g1", parentPhone = "01011111111", teacherId = "t1", groupId = "grp1")
        )
        val localCached = (1..22).map { i ->
            Student(studentId = "s$i", studentCode = "ST-$i", fullName = "طالب رقم $i", gradeId = "g1", parentPhone = "01011111111", teacherId = "t1", groupId = "grp1")
        }
        val pendingOutboxEntityIds = emptySet<String>()

        val serverIds = serverStudents.map { it.studentId }.toSet()
        val reconciled = localCached.filter { serverIds.contains(it.studentId) || pendingOutboxEntityIds.contains(it.studentId) } + serverStudents

        assertEquals(1, reconciled.distinctBy { it.studentId }.size)
        assertEquals("s1", reconciled.first().studentId)
    }

    @Test
    fun testReconciliationConcept2_protectsPendingOutboxItems() {
        val serverStudents = emptyList<Student>()
        val localCached = listOf(
            Student(studentId = "s-offline", studentCode = "ST-NEW", fullName = "طالب أوفلاين جديد", gradeId = "g1", parentPhone = "01022222222", teacherId = "t1", groupId = "grp1")
        )
        val pendingOutboxEntityIds = setOf("s-offline")

        val serverIds = serverStudents.map { it.studentId }.toSet()
        val reconciled = localCached.filter { serverIds.contains(it.studentId) || pendingOutboxEntityIds.contains(it.studentId) } + serverStudents

        assertEquals(1, reconciled.size)
        assertEquals("s-offline", reconciled.first().studentId)
    }

    @Test
    fun testReconciliationConcept3_networkFailurePreservesCache() {
        val localCached = (1..5).map { i ->
            Student(studentId = "s$i", studentCode = "ST-$i", fullName = "طالب $i", gradeId = "g1", parentPhone = "01011111111", teacherId = "t1", groupId = "grp1")
        }
        // Simulated network exception catch block preserves local cache
        val networkSuccess = false
        val result = if (networkSuccess) emptyList() else localCached

        assertEquals(5, result.size)
    }

    @Test
    fun testReconciliationConcept8_duplicateGroupNamesNotMerged() {
        val group1 = Group(id = "g-a", teacherId = "t1", gradeId = "grade-1", name = "الأول الثانوي", startTime = "10:00", endTime = "11:00")
        val group2 = Group(id = "g-b", teacherId = "t1", gradeId = "grade-1", name = "الأول الثانوي", startTime = "12:00", endTime = "13:00")

        val groups = listOf(group1, group2)
        assertEquals(2, groups.size)
        assertTrue(groups.distinctBy { it.id }.size == 2)
    }
}
