package com.example

import com.example.core.model.Student
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SupabaseDtoRoundTripTest {

    @Test
    fun testStudentDtoJsonRoundTrip() {
        val student = Student(
            studentId = "s_test_1",
            studentCode = "ST-1001",
            fullName = "محمد أحمد",
            gradeId = "g_1",
            parentPhone = "01012345678",
            hasWhatsApp = true,
            teacherId = "teacher_1"
        )
        val jsonString = Json.encodeToString(Student.serializer(), student)
        assertNotNull(jsonString)
        val decoded = Json.decodeFromString(Student.serializer(), jsonString)
        assertEquals(student.studentId, decoded.studentId)
        assertEquals(student.fullName, decoded.fullName)
        assertEquals(student.studentCode, decoded.studentCode)
    }
}
