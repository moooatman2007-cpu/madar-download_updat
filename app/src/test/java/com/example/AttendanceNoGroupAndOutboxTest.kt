package com.example

import com.example.core.model.Attendance
import com.example.core.model.AttendanceStatus
import com.example.core.model.BatchSyncStatus
import com.example.core.model.UpsertAttendanceRequest
import com.example.data.repository.AttendanceRemoteDataSource
import com.example.data.repository.MockAttendanceRepository
import com.example.data.repository.SupabaseAttendanceRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class AttendanceNoGroupAndOutboxTest {

    @Test
    fun testNoGroupInsertAndUpdate_singleRecordMaintained() = runTest {
        val repo = MockAttendanceRepository()
        val studentId = "std_nogroup_1"
        val date = "2026-09-27"

        val res1 = repo.recordOrUpdateAttendance(studentId, date, AttendanceStatus.PRESENT, "Note 1", null)
        assertTrue(res1.isSuccess)
        val att1 = res1.getOrThrow().data
        assertEquals(AttendanceStatus.PRESENT, att1.status)
        assertNull(att1.groupId)

        val res2 = repo.recordOrUpdateAttendance(studentId, date, AttendanceStatus.ABSENT, "Note 2", null)
        assertTrue(res2.isSuccess)
        val att2 = res2.getOrThrow().data
        assertEquals(AttendanceStatus.ABSENT, att2.status)

        val allForStudent = repo.getAttendanceForStudent(studentId).first()
        assertEquals(1, allForStudent.size)
        val record = repo.getAttendanceByDate(studentId, date)
        assertNotNull(record)
        assertEquals(AttendanceStatus.ABSENT, record?.status)
    }

    @Test
    fun testWithGroupInsertUpdateAndDifferentGroup() = runTest {
        val repo = MockAttendanceRepository()
        val studentId = "std_group_1"
        val date = "2026-09-27"
        val groupA = "grp_A"
        val groupB = "grp_B"

        val r1 = repo.recordOrUpdateAttendance(studentId, date, AttendanceStatus.PRESENT, "Group A", groupA)
        assertTrue(r1.isSuccess)
        assertEquals(groupA, r1.getOrThrow().data.groupId)

        val r2 = repo.recordOrUpdateAttendance(studentId, date, AttendanceStatus.LATE, "Group A Updated", groupA)
        assertTrue(r2.isSuccess)
        assertEquals(AttendanceStatus.LATE, r2.getOrThrow().data.status)

        val r3 = repo.recordOrUpdateAttendance(studentId, date, AttendanceStatus.PRESENT, "Group B", groupB)
        assertTrue(r3.isSuccess)
        assertEquals(groupB, r3.getOrThrow().data.groupId)
    }

    @Test
    fun testOutboxSyncNoGroupAndRetrySafety() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        var shouldFailOnce = true
        val capturedRequests = mutableListOf<UpsertAttendanceRequest>()

        val fakeRemote = object : AttendanceRemoteDataSource {
            private val stored = linkedMapOf<String, Attendance>()
            override suspend fun fetchAttendanceForStudent(teacherId: String, studentId: String) =
                stored.values.filter { it.teacherId == teacherId && it.studentId == studentId }
            override suspend fun fetchAttendanceForStudentByMonth(teacherId: String, studentId: String, startDate: String, endDate: String) = emptyList<Attendance>()
            override suspend fun fetchAllAttendanceForTeacher(teacherId: String) = stored.values.filter { it.teacherId == teacherId }
            override suspend fun fetchTodayCountByStatus(teacherId: String, date: String, status: AttendanceStatus) = 0
            override suspend fun fetchStudentGroupId(teacherId: String, studentId: String): String? = null
            override suspend fun upsertAttendance(request: UpsertAttendanceRequest): Attendance {
                if (shouldFailOnce) {
                    shouldFailOnce = false
                    throw IOException("Transient 503 error")
                }
                capturedRequests.add(request)
                val key = "${request.studentId}_${request.date}"
                val saved = Attendance(
                    attendanceId = key,
                    studentId = request.studentId,
                    teacherId = request.teacherId,
                    groupId = request.groupId,
                    date = request.date,
                    status = AttendanceStatus.fromString(request.status),
                    note = request.note
                )
                stored[key] = saved
                return saved
            }
            override suspend fun upsertBatchAttendance(requests: List<UpsertAttendanceRequest>) = emptyList<Attendance>()
            override suspend fun deleteAttendanceByStudentAndDate(teacherId: String, studentId: String, date: String) {}
            override suspend fun deleteAttendanceById(teacherId: String, attendanceId: String) {}
            override suspend fun fetchAttendanceByDate(teacherId: String, studentId: String, date: String) =
                stored["${studentId}_$date"]
            override suspend fun fetchAttendanceForDate(teacherId: String, date: String) = emptyList<Attendance>()
        }

        val repo = SupabaseAttendanceRepository(
            remoteDataSource = fakeRemote,
            teacherIdProvider = { "teacher_1" },
            sessionActiveChecker = { true },
            ioDispatcher = dispatcher
        )

        val firstAttempt = repo.recordOrUpdateAttendance(
            studentId = "std_og_1",
            date = "2026-09-27",
            status = AttendanceStatus.PRESENT,
            note = "No Group Direct Online",
            groupId = null
        )
        assertTrue(firstAttempt.isFailure)
        assertTrue(repo.getAllAttendanceForTeacher().first().isEmpty())

        val retryAttempt = repo.recordOrUpdateAttendance(
            studentId = "std_og_1",
            date = "2026-09-27",
            status = AttendanceStatus.PRESENT,
            note = "No Group Direct Online",
            groupId = null
        )
        assertTrue(retryAttempt.isSuccess)
        val syncResult = retryAttempt.getOrThrow()
        assertEquals(BatchSyncStatus.SAVED_TO_CLOUD, syncResult.syncStatus)
        assertNull(syncResult.data.groupId)
        assertEquals(1, capturedRequests.size)
        assertNull(capturedRequests[0].groupId)
        assertEquals(1, repo.getAllAttendanceForTeacher().first().size)
    }
}
