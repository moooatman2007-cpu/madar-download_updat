package com.example

import androidx.lifecycle.SavedStateHandle
import com.example.core.model.Grade
import com.example.core.model.Group
import com.example.core.model.GroupDay
import com.example.core.model.Student
import com.example.core.model.Teacher
import com.example.data.SupabaseClientProvider
import com.example.data.auth.AccountSessionManager
import com.example.data.repository.GroupRemoteDataSource
import com.example.data.repository.MockGradeRepository
import com.example.data.repository.MockGroupRepository
import com.example.data.repository.MockStudentRepository
import com.example.data.repository.MockTeacherRepository
import com.example.data.repository.SupabaseGroupRepository
import com.example.ui.add_edit_student.AddEditStudentEvent
import com.example.ui.add_edit_student.AddEditStudentViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.UnknownHostException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AddEditStudentGroupSelectionTest {

    private val testDispatcher = StandardTestDispatcher()
    private val teacherT1 = Teacher(id = "teacher_1", fullName = "أحمد المدرس", email = "t1@example.com")

    private lateinit var teacherRepository: MockTeacherRepository
    private lateinit var gradeRepository: MockGradeRepository
    private lateinit var studentRepository: MockStudentRepository
    private lateinit var groupRepository: MockGroupRepository

    private fun createTestGroup(
        id: String,
        teacherId: String = "teacher_1",
        gradeId: String = "grade_1",
        name: String = "مجموعة تجريبية",
        active: Boolean = true,
        startTime: String = "14:00",
        endTime: String = "16:00"
    ): Group {
        return Group(
            id = id,
            teacherId = teacherId,
            gradeId = gradeId,
            name = name,
            active = active,
            startTime = startTime,
            endTime = endTime
        )
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        teacherRepository = MockTeacherRepository(initialTeacher = teacherT1)
        gradeRepository = MockGradeRepository()
        studentRepository = MockStudentRepository(gradeRepository = gradeRepository)
        groupRepository = MockGroupRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `1 loadGroupsForSelectedGrade loads active groups for the selected grade`() = runTest {
        val group1 = createTestGroup(id = "grp_1", teacherId = "teacher_1", gradeId = "grade_1", name = "مجموعة أ", active = true)
        val group2 = createTestGroup(id = "grp_2", teacherId = "teacher_1", gradeId = "grade_1", name = "مجموعة ب", active = true)
        val groupOtherGrade = createTestGroup(id = "grp_3", teacherId = "teacher_1", gradeId = "grade_2", name = "مجموعة ج", active = true)
        groupRepository.setGroups(listOf(group1, group2, groupOtherGrade))

        val savedStateHandle = SavedStateHandle(mapOf("gradeId" to "grade_1"))
        val viewModel = AddEditStudentViewModel(
            savedStateHandle = savedStateHandle,
            studentRepository = studentRepository,
            gradeRepository = gradeRepository,
            teacherRepository = teacherRepository,
            groupRepository = groupRepository
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("grade_1", state.selectedGradeId)
        assertEquals(2, state.groups.size)
        assertTrue(state.groups.any { it.id == "grp_1" })
        assertTrue(state.groups.any { it.id == "grp_2" })
        assertFalse(state.groups.any { it.id == "grp_3" })
    }

    @Test
    fun `2 groups filtered by teacher isolates groups from other teachers`() = runTest {
        val groupT1 = createTestGroup(id = "grp_t1", teacherId = "teacher_1", gradeId = "grade_1", name = "مجموعة مدرسي", active = true)
        val groupT2 = createTestGroup(id = "grp_t2", teacherId = "teacher_2", gradeId = "grade_1", name = "مجموعة مدرس آخر", active = true)
        groupRepository.setGroups(listOf(groupT1, groupT2))

        val savedStateHandle = SavedStateHandle(mapOf("gradeId" to "grade_1"))
        val viewModel = AddEditStudentViewModel(
            savedStateHandle = savedStateHandle,
            studentRepository = studentRepository,
            gradeRepository = gradeRepository,
            teacherRepository = teacherRepository,
            groupRepository = groupRepository
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val groups = viewModel.uiState.value.groups
        assertEquals(1, groups.size)
        assertEquals("grp_t1", groups.first().id)
    }

    @Test
    fun `3 groups filtered by grade isolates groups from other grades`() = runTest {
        val groupG1 = createTestGroup(id = "grp_g1", teacherId = "teacher_1", gradeId = "grade_1", name = "مجموعة أولى", active = true)
        val groupG2 = createTestGroup(id = "grp_g2", teacherId = "teacher_1", gradeId = "grade_2", name = "مجموعة ثانية", active = true)
        groupRepository.setGroups(listOf(groupG1, groupG2))

        val savedStateHandle = SavedStateHandle(mapOf("gradeId" to "grade_1"))
        val viewModel = AddEditStudentViewModel(
            savedStateHandle = savedStateHandle,
            studentRepository = studentRepository,
            gradeRepository = gradeRepository,
            teacherRepository = teacherRepository,
            groupRepository = groupRepository
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val groups = viewModel.uiState.value.groups
        assertEquals(1, groups.size)
        assertEquals("grp_g1", groups.first().id)
    }

    @Test
    fun `4 changing grade clears incompatible group`() = runTest {
        val groupG1 = createTestGroup(id = "grp_g1", teacherId = "teacher_1", gradeId = "grade_1", name = "مجموعة أولى", active = true)
        val groupG2 = createTestGroup(id = "grp_g2", teacherId = "teacher_1", gradeId = "grade_2", name = "مجموعة ثانية", active = true)
        groupRepository.setGroups(listOf(groupG1, groupG2))

        val savedStateHandle = SavedStateHandle(mapOf("gradeId" to "grade_1"))
        val viewModel = AddEditStudentViewModel(
            savedStateHandle = savedStateHandle,
            studentRepository = studentRepository,
            gradeRepository = gradeRepository,
            teacherRepository = teacherRepository,
            groupRepository = groupRepository
        )
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onGroupSelected("grp_g1")
        assertEquals("grp_g1", viewModel.uiState.value.selectedGroupId)

        viewModel.onGradeSelected("grade_2")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("grade_2", state.selectedGradeId)
        assertNull("Selected group should be cleared when grade changes", state.selectedGroupId)
        assertEquals(1, state.groups.size)
        assertEquals("grp_g2", state.groups.first().id)
    }

    @Test
    fun `5 valid group assignment succeeds`() = runTest {
        val group = createTestGroup(id = "grp_valid", teacherId = "teacher_1", gradeId = "grade_1", name = "نجوم المستقبل", active = true)
        groupRepository.setGroups(listOf(group))

        val viewModel = AddEditStudentViewModel(
            savedStateHandle = SavedStateHandle(mapOf("gradeId" to "grade_1")),
            studentRepository = studentRepository,
            gradeRepository = gradeRepository,
            teacherRepository = teacherRepository,
            groupRepository = groupRepository
        )
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onFullNameChange("محمد علي حسن")
        viewModel.onParentPhoneChange("01012345678")
        viewModel.onGroupSelected("grp_valid")

        viewModel.saveStudent()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.showSuccessDialog)
        assertNotNull(state.createdStudent)
        assertEquals("grp_valid", state.createdStudent?.groupId)
    }

    @Test
    fun `6 invalid teacher group rejected`() = runTest {
        val groupT2 = createTestGroup(id = "grp_t2", teacherId = "teacher_2", gradeId = "grade_1", name = "مجموعة مدرس آخر", active = true)
        groupRepository.setGroups(listOf(groupT2))

        val viewModel = AddEditStudentViewModel(
            savedStateHandle = SavedStateHandle(mapOf("gradeId" to "grade_1")),
            studentRepository = studentRepository,
            gradeRepository = gradeRepository,
            teacherRepository = teacherRepository,
            groupRepository = groupRepository
        )
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onFullNameChange("طالب غير صالح")
        viewModel.onParentPhoneChange("01012345678")
        viewModel.onGroupSelected("grp_t2")

        val events = mutableListOf<AddEditStudentEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.toList(events)
        }

        viewModel.saveStudent()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showSuccessDialog)
        assertTrue(events.any { it is AddEditStudentEvent.ShowError && it.message == "المجموعة غير متوافقة مع الصف الدراسي." })
        job.cancel()
    }

    @Test
    fun `7 invalid grade-group combination rejected`() = runTest {
        val groupG2 = createTestGroup(id = "grp_g2", teacherId = "teacher_1", gradeId = "grade_2", name = "مجموعة الصف الثاني", active = true)
        groupRepository.setGroups(listOf(groupG2))

        val viewModel = AddEditStudentViewModel(
            savedStateHandle = SavedStateHandle(mapOf("gradeId" to "grade_1")),
            studentRepository = studentRepository,
            gradeRepository = gradeRepository,
            teacherRepository = teacherRepository,
            groupRepository = groupRepository
        )
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.onFullNameChange("طالب صف أول")
        viewModel.onParentPhoneChange("01012345678")
        viewModel.onGroupSelected("grp_g2")

        val events = mutableListOf<AddEditStudentEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.toList(events)
        }

        viewModel.saveStudent()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showSuccessDialog)
        assertTrue(events.any { it is AddEditStudentEvent.ShowError && it.message == "المجموعة غير متوافقة مع الصف الدراسي." })
        job.cancel()
    }

    @Test
    fun `8 removing group works`() = runTest {
        val group = createTestGroup(id = "grp_1", teacherId = "teacher_1", gradeId = "grade_1", name = "مجموعة 1", active = true)
        groupRepository.setGroups(listOf(group))

        val addResult = studentRepository.addStudent(
            fullName = "ياسين أحمد",
            gradeId = "grade_1",
            parentPhone = "01012345678",
            hasWhatsApp = true,
            alternativePhone = null
        )
        val student = addResult.getOrThrow().data.copy(groupId = "grp_1", teacherId = "teacher_1")
        studentRepository.updateStudent(student)

        val viewModel = AddEditStudentViewModel(
            savedStateHandle = SavedStateHandle(mapOf("studentId" to student.studentId)),
            studentRepository = studentRepository,
            gradeRepository = gradeRepository,
            teacherRepository = teacherRepository,
            groupRepository = groupRepository
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("grp_1", viewModel.uiState.value.selectedGroupId)

        viewModel.onGroupSelected(null)
        assertNull(viewModel.uiState.value.selectedGroupId)

        val events = mutableListOf<AddEditStudentEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.toList(events)
        }

        viewModel.saveStudent()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(events.any { it is AddEditStudentEvent.StudentUpdated })
        val updated = studentRepository.getStudentById(student.studentId)
        assertNull(updated?.groupId)
        job.cancel()
    }

    @Test
    fun `9 inactive current group remains visible on edit`() = runTest {
        val inactiveCurrentGroup = createTestGroup(id = "grp_inactive_1", teacherId = "teacher_1", gradeId = "grade_1", name = "مجموعة قديمة مغلقة", active = false)
        val inactiveOtherGroup = createTestGroup(id = "grp_inactive_2", teacherId = "teacher_1", gradeId = "grade_1", name = "مجموعة قديمة أخرى", active = false)
        val activeGroup = createTestGroup(id = "grp_active", teacherId = "teacher_1", gradeId = "grade_1", name = "مجموعة نشطة", active = true)
        groupRepository.setGroups(listOf(inactiveCurrentGroup, inactiveOtherGroup, activeGroup))

        val addResult = studentRepository.addStudent(
            fullName = "خالد سامي",
            gradeId = "grade_1",
            parentPhone = "01012345678",
            hasWhatsApp = true,
            alternativePhone = null
        )
        val student = addResult.getOrThrow().data.copy(groupId = "grp_inactive_1", teacherId = "teacher_1")
        studentRepository.updateStudent(student)

        val viewModel = AddEditStudentViewModel(
            savedStateHandle = SavedStateHandle(mapOf("studentId" to student.studentId)),
            studentRepository = studentRepository,
            gradeRepository = gradeRepository,
            teacherRepository = teacherRepository,
            groupRepository = groupRepository
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("grp_inactive_1", state.selectedGroupId)
        assertTrue(state.groups.any { it.id == "grp_inactive_1" })
        assertTrue(state.groups.any { it.id == "grp_active" })
        assertFalse(state.groups.any { it.id == "grp_inactive_2" })
    }

    @Test
    fun `10 no groups - student can still be saved`() = runTest {
        groupRepository.setGroups(emptyList())

        val viewModel = AddEditStudentViewModel(
            savedStateHandle = SavedStateHandle(mapOf("gradeId" to "grade_1")),
            studentRepository = studentRepository,
            gradeRepository = gradeRepository,
            teacherRepository = teacherRepository,
            groupRepository = groupRepository
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.groups.isEmpty())
        assertNull(state.selectedGroupId)

        viewModel.onFullNameChange("طالب بدون مجموعة")
        viewModel.onParentPhoneChange("01012345678")

        viewModel.saveStudent()
        testDispatcher.scheduler.advanceUntilIdle()

        val finalState = viewModel.uiState.value
        assertTrue(finalState.showSuccessDialog)
        assertNotNull(finalState.createdStudent)
        assertNull(finalState.createdStudent?.groupId)
    }

    @Test
    fun `11 offline student group assignment fails without mutating memory`() = runBlocking {
        SupabaseClientProvider.mockTeacherId = "teacher_1"
        AccountSessionManager.setActiveTeacherId("teacher_1")

        try {
            val offlineRemote = object : GroupRemoteDataSource {
                override suspend fun fetchGroups(teacherId: String) = throw UnknownHostException("offline")
                override suspend fun fetchGroupDays(teacherId: String) = throw UnknownHostException("offline")
                override suspend fun fetchGroupById(teacherId: String, groupId: String) = throw UnknownHostException("offline")
                override suspend fun fetchGroupDaysForGroup(teacherId: String, groupId: String) = throw UnknownHostException("offline")
                override suspend fun createGroup(group: Group) = throw UnknownHostException("offline")
                override suspend fun updateGroup(group: Group) = throw UnknownHostException("offline")
                override suspend fun setGroupActive(teacherId: String, groupId: String, active: Boolean) = throw UnknownHostException("offline")
                override suspend fun hasStudentsInGroup(teacherId: String, groupId: String) = false
                override suspend fun hasAttendanceInGroup(teacherId: String, groupId: String) = false
                override suspend fun deleteGroupDays(teacherId: String, groupId: String) {}
                override suspend fun insertGroupDays(days: List<GroupDay>) {}
                override suspend fun deleteGroupRow(teacherId: String, groupId: String) {}
                override suspend fun fetchStudentById(teacherId: String, studentId: String) = throw UnknownHostException("offline")
                override suspend fun updateStudentGroup(teacherId: String, studentId: String, groupId: String?) = throw UnknownHostException("offline")
            }

            var callbackCalled = false
            val supabaseGroupRepo = SupabaseGroupRepository(
                remoteDataSource = offlineRemote,
                teacherIdProvider = { "teacher_1" },
                sessionActiveChecker = { true },
                onStudentUpdated = { callbackCalled = true }
            )

            val assignResult = supabaseGroupRepo.assignStudentToGroup("teacher_1", "s_offline_1", "grp_1")
            assertTrue(assignResult.isFailure)
            assertFalse(callbackCalled)
            assertTrue(supabaseGroupRepo.observeGroups("teacher_1").first().isEmpty())
        } finally {
            AccountSessionManager.setActiveTeacherId(null)
            SupabaseClientProvider.mockTeacherId = null
        }
    }

    @Test
    fun `12 online group assignment updates server directly without local cache`() = runBlocking {
        SupabaseClientProvider.mockTeacherId = "teacher_1"
        AccountSessionManager.setActiveTeacherId("teacher_1")

        try {
            val group = createTestGroup(id = "grp_1", teacherId = "teacher_1", gradeId = "grade_1", name = "المتفوقين", active = true)
            var student = Student(
                studentId = "s_online_1",
                studentCode = "ST-10002",
                fullName = "إسلام صبحي",
                gradeId = "grade_1",
                parentPhone = "01012345678",
                teacherId = "teacher_1"
            )
            var updatedOnServer: Student? = null

            val onlineRemote = object : GroupRemoteDataSource {
                override suspend fun fetchGroups(teacherId: String) = listOf(group)
                override suspend fun fetchGroupDays(teacherId: String) = emptyList<GroupDay>()
                override suspend fun fetchGroupById(teacherId: String, groupId: String) = if (groupId == "grp_1") group else null
                override suspend fun fetchGroupDaysForGroup(teacherId: String, groupId: String) = emptyList<GroupDay>()
                override suspend fun createGroup(group: Group) = group
                override suspend fun updateGroup(group: Group) = group
                override suspend fun setGroupActive(teacherId: String, groupId: String, active: Boolean) = group.copy(active = active)
                override suspend fun hasStudentsInGroup(teacherId: String, groupId: String) = false
                override suspend fun hasAttendanceInGroup(teacherId: String, groupId: String) = false
                override suspend fun deleteGroupDays(teacherId: String, groupId: String) {}
                override suspend fun insertGroupDays(days: List<GroupDay>) {}
                override suspend fun deleteGroupRow(teacherId: String, groupId: String) {}
                override suspend fun fetchStudentById(teacherId: String, studentId: String) = if (studentId == student.studentId) student else null
                override suspend fun updateStudentGroup(teacherId: String, studentId: String, groupId: String?): Student {
                    student = student.copy(groupId = groupId)
                    updatedOnServer = student
                    return student
                }
            }

            var callbackStudent: Student? = null
            val supabaseGroupRepo = SupabaseGroupRepository(
                remoteDataSource = onlineRemote,
                teacherIdProvider = { "teacher_1" },
                sessionActiveChecker = { true },
                onStudentUpdated = { callbackStudent = it }
            )

            val res = supabaseGroupRepo.assignStudentToGroup("teacher_1", "s_online_1", "grp_1")
            assertTrue(res.isSuccess)
            assertEquals("grp_1", updatedOnServer?.groupId)
            assertEquals("grp_1", callbackStudent?.groupId)
            assertFalse(updatedOnServer!!.studentCode.contains("TMP"))
        } finally {
            AccountSessionManager.setActiveTeacherId(null)
            SupabaseClientProvider.mockTeacherId = null
        }
    }

    @Test
    fun `13 editing existing student via initialize preserves student ID and executes update instead of insert`() = runTest {
        val existing = studentRepository.getStudentById("s_01")
        assertNotNull(existing)

        val viewModel = AddEditStudentViewModel(
            savedStateHandle = null,
            studentRepository = studentRepository,
            gradeRepository = gradeRepository,
            teacherRepository = teacherRepository,
            groupRepository = groupRepository
        )

        viewModel.initialize("s_01", null)
        testDispatcher.scheduler.advanceUntilIdle()

        val stateBeforeSave = viewModel.uiState.value
        assertTrue("Must be in edit mode", stateBeforeSave.isEditMode)
        assertEquals("s_01", stateBeforeSave.studentId)
        assertEquals(existing!!.fullName, stateBeforeSave.fullName)
        assertEquals(existing.studentCode, stateBeforeSave.existingStudentCode)

        viewModel.onFullNameChange("طالب بعد التعديل")
        viewModel.saveStudent()
        testDispatcher.scheduler.advanceUntilIdle()

        val updated = studentRepository.getStudentById("s_01")
        assertNotNull(updated)
        assertEquals("s_01", updated!!.studentId)
        assertEquals("طالب بعد التعديل", updated.fullName)
        assertEquals(existing.studentCode, updated.studentCode)
    }
}
