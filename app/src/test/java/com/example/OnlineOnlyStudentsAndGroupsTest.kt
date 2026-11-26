package com.example

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.model.BatchSyncStatus
import com.example.core.model.EducationalStages
import com.example.core.model.Grade
import com.example.core.model.Group
import com.example.core.model.GroupDay
import com.example.core.model.OnlineLoadState
import com.example.core.model.Student
import com.example.core.model.SupabaseStudentInsertDto
import com.example.core.model.Teacher
import com.example.core.model.UpdateStudentOnlineDto
import com.example.data.SupabaseClientProvider
import com.example.data.auth.AccountSessionManager
import com.example.data.local.AppDatabase
import com.example.data.local.DatabaseProvider
import com.example.data.local.entity.GroupDayEntity
import com.example.data.local.entity.GroupEntity
import com.example.data.local.entity.StudentEntity
import com.example.data.repository.GradeRepository
import com.example.data.repository.GroupDeletionBlockedException
import com.example.data.repository.GroupRemoteDataSource
import com.example.data.repository.MockGradeRepository
import com.example.data.repository.MockTeacherRepository
import com.example.data.repository.StudentRemoteDataSource
import com.example.data.repository.SupabaseGroupRepository
import com.example.data.repository.SupabaseStudentRepository
import com.example.ui.add_edit_student.AddEditStudentEvent
import com.example.ui.add_edit_student.AddEditStudentViewModel
import com.example.ui.groups.GroupsViewModel
import com.example.ui.students.StudentListViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
import java.io.IOException
import java.net.UnknownHostException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OnlineOnlyStudentsAndGroupsTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private val testDispatcher = UnconfinedTestDispatcher()

    private class FakeBackend {
        val students = linkedMapOf<String, Student>()
        val groups = linkedMapOf<String, Group>()
        val groupDays = mutableListOf<GroupDay>()
        val attendanceGroupIds = mutableSetOf<String>()
        var nextStudentCodeNumber = 1

        var failFetchStudents: Throwable? = null
        var failFetchStudentById: Throwable? = null
        var failInsertStudent: Throwable? = null
        var failUpdateStudent: Throwable? = null
        var failDeleteStudent: Throwable? = null

        var failFetchGroups: Throwable? = null
        var failCreateGroup: Throwable? = null
        var failUpdateGroup: Throwable? = null
        var failSetGroupActive: Throwable? = null
        var failDeleteGroupRow: Throwable? = null
        var failInsertGroupDays: Throwable? = null
        var failAssignStudent: Throwable? = null

        var deleteGroupDaysCalls = 0

        val studentRemote = object : StudentRemoteDataSource {
            override suspend fun fetchStudents(teacherId: String): List<Student> {
                failFetchStudents?.let { throw it }
                return students.values.filter { it.teacherId == teacherId && it.deletedAt == null }
            }

            override suspend fun fetchStudentById(teacherId: String, studentId: String): Student? {
                failFetchStudentById?.let { throw it }
                return students[studentId]?.takeIf { it.teacherId == teacherId && it.deletedAt == null }
            }

            override suspend fun fetchStudentByCode(teacherId: String, studentCode: String): Student? {
                failFetchStudents?.let { throw it }
                return students.values.firstOrNull {
                    it.teacherId == teacherId && it.studentCode == studentCode && it.deletedAt == null
                }
            }

            override suspend fun insertStudent(dto: SupabaseStudentInsertDto): Student {
                failInsertStudent?.let { throw it }
                val created = Student(
                    studentId = dto.id,
                    studentCode = "ST-%05d".format(nextStudentCodeNumber++),
                    fullName = dto.fullName,
                    gradeId = dto.gradeId,
                    parentPhone = dto.parentPhone,
                    hasWhatsApp = dto.hasWhatsApp,
                    alternativePhone = dto.alternativePhone,
                    teacherId = dto.teacherId,
                    groupId = null
                )
                students[created.studentId] = created
                return created
            }

            override suspend fun updateStudent(
                teacherId: String,
                studentId: String,
                request: UpdateStudentOnlineDto
            ): Student {
                failUpdateStudent?.let { throw it }
                val current = students[studentId]?.takeIf { it.teacherId == teacherId && it.deletedAt == null }
                    ?: throw NoSuchElementException("Student not found")
                val updated = current.copy(
                    fullName = request.fullName,
                    gradeId = request.gradeId,
                    parentPhone = request.parentPhone,
                    hasWhatsApp = request.hasWhatsApp,
                    alternativePhone = request.alternativePhone,
                    groupId = request.groupId
                )
                students[studentId] = updated
                return updated
            }

            override suspend fun softDeleteStudent(teacherId: String, studentId: String, deletedAtIso: String) {
                failDeleteStudent?.let { throw it }
                val current = students[studentId]?.takeIf { it.teacherId == teacherId } ?: return
                students[studentId] = current.copy(deletedAt = deletedAtIso)
            }
        }

        val groupRemote = object : GroupRemoteDataSource {
            override suspend fun fetchGroups(teacherId: String): List<Group> {
                failFetchGroups?.let { throw it }
                return groups.values.filter { it.teacherId == teacherId }
            }

            override suspend fun fetchGroupDays(teacherId: String): List<GroupDay> {
                failFetchGroups?.let { throw it }
                return groupDays.filter { it.teacherId == teacherId }
            }

            override suspend fun fetchGroupById(teacherId: String, groupId: String): Group? {
                failFetchGroups?.let { throw it }
                return groups[groupId]?.takeIf { it.teacherId == teacherId }
            }

            override suspend fun fetchGroupDaysForGroup(teacherId: String, groupId: String): List<GroupDay> {
                return groupDays.filter { it.teacherId == teacherId && it.groupId == groupId }
            }

            override suspend fun createGroup(group: Group): Group {
                failCreateGroup?.let { throw it }
                groups[group.id] = group
                return group
            }

            override suspend fun updateGroup(group: Group): Group {
                failUpdateGroup?.let { throw it }
                val current = groups[group.id]?.takeIf { it.teacherId == group.teacherId }
                    ?: throw NoSuchElementException("Group not found")
                val updated = current.copy(
                    name = group.name,
                    gradeId = group.gradeId,
                    active = group.active,
                    startTime = group.startTime,
                    endTime = group.endTime,
                    capacity = group.capacity,
                    location = group.location
                )
                groups[group.id] = updated
                return updated
            }

            override suspend fun setGroupActive(teacherId: String, groupId: String, active: Boolean): Group? {
                failSetGroupActive?.let { throw it }
                val current = groups[groupId]?.takeIf { it.teacherId == teacherId } ?: return null
                val updated = current.copy(active = active)
                groups[groupId] = updated
                return updated
            }

            override suspend fun hasStudentsInGroup(teacherId: String, groupId: String): Boolean {
                return students.values.any { it.teacherId == teacherId && it.groupId == groupId }
            }

            override suspend fun hasAttendanceInGroup(teacherId: String, groupId: String): Boolean {
                return attendanceGroupIds.contains("$teacherId:$groupId")
            }

            override suspend fun deleteGroupDays(teacherId: String, groupId: String) {
                deleteGroupDaysCalls++
                groupDays.removeAll { it.teacherId == teacherId && it.groupId == groupId }
            }

            override suspend fun insertGroupDays(days: List<GroupDay>) {
                failInsertGroupDays?.let { throw it }
                groupDays.addAll(days)
            }

            override suspend fun deleteGroupRow(teacherId: String, groupId: String) {
                failDeleteGroupRow?.let { throw it }
                groups.remove(groupId)
            }

            override suspend fun fetchStudentById(teacherId: String, studentId: String): Student? {
                failAssignStudent?.let { throw it }
                return students[studentId]?.takeIf { it.teacherId == teacherId && it.deletedAt == null }
            }

            override suspend fun updateStudentGroup(teacherId: String, studentId: String, groupId: String?): Student {
                failAssignStudent?.let { throw it }
                val current = students[studentId]?.takeIf { it.teacherId == teacherId && it.deletedAt == null }
                    ?: throw NoSuchElementException("Student not found")
                val updated = current.copy(groupId = groupId)
                students[studentId] = updated
                return updated
            }
        }
    }

    private var activeTeacherId: String? = "teacher_A"
    private lateinit var backend: FakeBackend
    private lateinit var studentRepo: SupabaseStudentRepository
    private lateinit var groupRepo: SupabaseGroupRepository
    private lateinit var gradeRepo: GradeRepository
    private lateinit var teacherRepo: MockTeacherRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        DatabaseProvider.setDatabase(db, context)

        activeTeacherId = "teacher_A"
        SupabaseClientProvider.mockTeacherId = activeTeacherId
        AccountSessionManager.setActiveTeacherId(activeTeacherId)

        backend = FakeBackend()
        studentRepo = SupabaseStudentRepository(
            remoteDataSource = backend.studentRemote,
            teacherIdProvider = { activeTeacherId },
            sessionActiveChecker = { id -> activeTeacherId != null && activeTeacherId == id },
            ioDispatcher = testDispatcher
        )
        groupRepo = SupabaseGroupRepository(
            remoteDataSource = backend.groupRemote,
            teacherIdProvider = { activeTeacherId },
            sessionActiveChecker = { id -> activeTeacherId != null && activeTeacherId == id },
            onStudentUpdated = { studentRepo.updateStudentInMemory(it) },
            ioDispatcher = testDispatcher
        )
        gradeRepo = MockGradeRepository(EducationalStages.PREPARATORY)
        teacherRepo = MockTeacherRepository(
            Teacher(
                id = "teacher_A",
                email = "teacherA@midar.app",
                fullName = "أحمد محمد",
                educationalStage = EducationalStages.PREPARATORY
            )
        )
    }

    @After
    fun tearDown() {
        AccountSessionManager.setActiveTeacherId(null)
        SupabaseClientProvider.mockTeacherId = null
        db.close()
        DatabaseProvider.resetForTesting()
        Dispatchers.resetMain()
    }

    // 1. جلب الطلاب مباشرة من Supabase
    @Test
    fun scenario01_fetchStudentsDirectlyFromSupabase() = runTest {
        backend.students["s1"] = Student(
            studentId = "s1",
            studentCode = "ST-00101",
            fullName = "عبدالله علي",
            gradeId = "grade_prep_1",
            parentPhone = "01012345678",
            teacherId = "teacher_A",
            groupId = "grp_1"
        )

        val vm = StudentListViewModel(
            studentRepository = studentRepo,
            gradeRepository = gradeRepo,
            defaultDispatcher = testDispatcher
        )
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.loadState is OnlineLoadState.Success)
        assertEquals(1, state.students.size)
        assertEquals("عبدالله علي", state.students[0].fullName)
        assertEquals("ST-00101", state.students[0].studentCode)
        assertEquals("grp_1", state.students[0].groupId)
    }

    // 2. عدم عرض بيانات Room القديمة
    @Test
    fun scenario02_staleRoomDataNeverDisplayed() = runTest {
        db.studentDao().upsertStudent(
            StudentEntity(
                studentId = "stale_room_s1",
                studentCode = "ST-OLD",
                fullName = "طالب كاش قديم",
                gradeId = "grade_prep_1",
                parentPhone = "01099999999",
                teacherId = "teacher_A"
            )
        )
        db.groupDao().insertGroup(
            GroupEntity(
                id = "stale_room_g1",
                teacherId = "teacher_A",
                gradeId = "grade_prep_1",
                name = "مجموعة كاش قديمة",
                startTime = "10:00",
                endTime = "11:00"
            )
        )

        backend.failFetchStudents = UnknownHostException("Unable to resolve host")
        backend.failFetchGroups = UnknownHostException("Unable to resolve host")

        val studentsVm = StudentListViewModel(
            studentRepository = studentRepo,
            gradeRepository = gradeRepo,
            defaultDispatcher = testDispatcher
        )
        val groupsVm = GroupsViewModel(
            groupRepository = groupRepo,
            gradeRepository = gradeRepo,
            teacherRepository = teacherRepo,
            studentRepository = studentRepo
        )
        advanceUntilIdle()

        assertTrue(studentsVm.uiState.value.students.isEmpty())
        assertTrue(studentsVm.uiState.value.loadState is OnlineLoadState.Offline)
        assertTrue(groupsVm.uiState.value.groups.isEmpty())
        assertTrue(groupsVm.uiState.value.loadState is OnlineLoadState.Offline)
    }

    // 3. إضافة وتعديل وحذف طالب + مجموعات مباشرة بدون ST-TMP-*
    @Test
    fun scenario03_addEditDeleteStudentAndGroupsOnlineWithRealServerCode() = runTest {
        val addVm = AddEditStudentViewModel(
            studentRepository = studentRepo,
            gradeRepository = gradeRepo,
            teacherRepository = teacherRepo,
            groupRepository = groupRepo
        )
        advanceUntilIdle()

        addVm.onFullNameChange("يوسف خالد")
        addVm.onGradeSelected("grade_prep_1")
        addVm.onParentPhoneChange("01011112222")
        addVm.saveStudent()
        advanceUntilIdle()

        val created = addVm.uiState.value.createdStudent
        assertNotNull(created)
        assertEquals("ST-00001", created!!.studentCode)
        assertFalse(created.studentCode.contains("TMP"))
        assertTrue(addVm.uiState.value.showSuccessDialog)

        // Update directly on Supabase
        val updatedRes = studentRepo.updateStudent(created.copy(fullName = "يوسف خالد حسن"))
        assertTrue(updatedRes.isSuccess)
        assertEquals("يوسف خالد حسن", backend.students[created.studentId]?.fullName)

        // Soft-delete directly on Supabase
        val delRes = studentRepo.deleteStudent(created.studentId)
        assertTrue(delRes.isSuccess)
        assertNotNull(backend.students[created.studentId]?.deletedAt)
        assertTrue(studentRepo.getStudents().first().isEmpty())

        // Group create, edit, deactivate
        val groupsVm = GroupsViewModel(
            groupRepository = groupRepo,
            gradeRepository = gradeRepo,
            teacherRepository = teacherRepo,
            studentRepository = studentRepo
        )
        advanceUntilIdle()

        groupsVm.openAddDialog()
        groupsVm.onFormNameChange("مجموعة الفجر")
        groupsVm.onFormGradeChange("grade_prep_1")
        groupsVm.onFormDayToggle("الاثنين")
        groupsVm.onFormStartTimeChange("14:00")
        groupsVm.onFormEndTimeChange("15:30")
        groupsVm.saveGroup()
        advanceUntilIdle()

        assertEquals(1, backend.groups.size)
        val createdGroup = backend.groups.values.first()
        assertEquals("مجموعة الفجر", createdGroup.name)
        assertEquals(listOf("السبت", "الاثنين"), backend.groupDays.filter { it.groupId == createdGroup.id }.map { it.dayOfWeek })

        groupsVm.toggleGroupActive(createdGroup)
        advanceUntilIdle()
        assertFalse(backend.groups[createdGroup.id]!!.active)
    }

    // 4. ربط الطالب بمجموعة مباشرة
    @Test
    fun scenario04_assignStudentToGroupUpdatesServerDirectly() = runTest {
        backend.groups["g_alpha"] = Group(
            id = "g_alpha",
            teacherId = "teacher_A",
            gradeId = "grade_prep_1",
            name = "مجموعة النور",
            active = true,
            startTime = "16:00",
            endTime = "17:00"
        )
        groupRepo.refreshGroups("teacher_A")

        val addVm = AddEditStudentViewModel(
            studentRepository = studentRepo,
            gradeRepository = gradeRepo,
            teacherRepository = teacherRepo,
            groupRepository = groupRepo
        )
        advanceUntilIdle()

        addVm.onFullNameChange("زياد طارق")
        addVm.onGradeSelected("grade_prep_1")
        addVm.onParentPhoneChange("01022223333")
        addVm.onGroupSelected("g_alpha")
        addVm.saveStudent()
        advanceUntilIdle()

        val savedStudent = addVm.uiState.value.createdStudent
        assertNotNull(savedStudent)
        assertEquals("g_alpha", savedStudent?.groupId)
        assertEquals("g_alpha", backend.students[savedStudent?.studentId]?.groupId)
        assertEquals("g_alpha", studentRepo.getStudentById(savedStudent!!.studentId)?.groupId)
    }

    // 5. فشل العملية عند عدم وجود إنترنت دون تحديث الحالة كنجاح
    @Test
    fun scenario05_networkFailureDoesNotMarkUiOrMemoryAsSucceeded() = runTest(testDispatcher) {
        val addVm = AddEditStudentViewModel(
            studentRepository = studentRepo,
            gradeRepository = gradeRepo,
            teacherRepository = teacherRepo,
            groupRepository = groupRepo
        )
        advanceUntilIdle()

        val events = mutableListOf<AddEditStudentEvent>()
        val collectJob = launch(testDispatcher) { addVm.events.collect { events.add(it) } }

        backend.failInsertStudent = UnknownHostException("Unable to resolve host")
        addVm.onFullNameChange("طالب بدون نت")
        addVm.onGradeSelected("grade_prep_1")
        addVm.onParentPhoneChange("01044445555")
        addVm.saveStudent()
        advanceUntilIdle()

        assertFalse(addVm.uiState.value.showSuccessDialog)
        assertNull(addVm.uiState.value.createdStudent)
        assertTrue(events.any { it is AddEditStudentEvent.ShowError })
        assertTrue(backend.students.isEmpty())
        assertTrue(studentRepo.getStudents().first().isEmpty())
        collectJob.cancel()

        // Group creation failure must also leave GroupsViewModel empty & dialog open with error
        val groupsVm = GroupsViewModel(
            groupRepository = groupRepo,
            gradeRepository = gradeRepo,
            teacherRepository = teacherRepo,
            studentRepository = studentRepo
        )
        advanceUntilIdle()
        backend.failCreateGroup = UnknownHostException("offline")

        groupsVm.openAddDialog()
        groupsVm.onFormNameChange("مجموعة أوفلاين")
        groupsVm.onFormGradeChange("grade_prep_1")
        groupsVm.onFormDayToggle("الأحد")
        groupsVm.saveGroup()
        advanceUntilIdle()

        assertTrue(groupsVm.uiState.value.showAddEditDialog)
        assertNotNull(groupsVm.uiState.value.formError)
        assertTrue(backend.groups.isEmpty())
    }

    // 6. إعادة المحاولة Retry
    @Test
    fun scenario06_retryRecoversFromOfflineToSuccess() = runTest {
        backend.failFetchStudents = UnknownHostException("Unable to resolve host")
        backend.failFetchGroups = UnknownHostException("Unable to resolve host")

        val studentsVm = StudentListViewModel(
            studentRepository = studentRepo,
            gradeRepository = gradeRepo,
            defaultDispatcher = testDispatcher
        )
        val groupsVm = GroupsViewModel(
            groupRepository = groupRepo,
            gradeRepository = gradeRepo,
            teacherRepository = teacherRepo,
            studentRepository = studentRepo
        )
        advanceUntilIdle()

        assertTrue(studentsVm.uiState.value.loadState is OnlineLoadState.Offline)
        assertTrue(groupsVm.uiState.value.loadState is OnlineLoadState.Offline)

        // Restore connection and populate server data
        backend.failFetchStudents = null
        backend.failFetchGroups = null
        backend.students["s_ret"] = Student(
            studentId = "s_ret",
            studentCode = "ST-00077",
            fullName = "عمر مجدي",
            gradeId = "grade_prep_1",
            parentPhone = "01055556666",
            teacherId = "teacher_A"
        )
        backend.groups["g_ret"] = Group(
            id = "g_ret",
            teacherId = "teacher_A",
            gradeId = "grade_prep_1",
            name = "مجموعة العصر",
            active = true,
            startTime = "15:00",
            endTime = "16:00"
        )

        studentsVm.retry()
        groupsVm.retry()
        advanceUntilIdle()

        assertTrue(studentsVm.uiState.value.loadState is OnlineLoadState.Success)
        assertEquals(1, studentsVm.uiState.value.students.size)
        assertEquals("عمر مجدي", studentsVm.uiState.value.students[0].fullName)

        assertTrue(groupsVm.uiState.value.loadState is OnlineLoadState.Success)
        assertEquals(1, groupsVm.uiState.value.groups.size)
        assertEquals("مجموعة العصر", groupsVm.uiState.value.groups[0].name)
    }

    // 7. منع عرض بيانات مدرس آخر
    @Test
    fun scenario07_teacherTenantIsolationAndClearOnSwitch() = runTest {
        backend.students["sA"] = Student(
            studentId = "sA",
            studentCode = "ST-00001",
            fullName = "طالب المعلم أ",
            gradeId = "grade_prep_1",
            parentPhone = "01011111111",
            teacherId = "teacher_A"
        )
        backend.students["sB"] = Student(
            studentId = "sB",
            studentCode = "ST-00001",
            fullName = "طالب المعلم ب",
            gradeId = "grade_prep_1",
            parentPhone = "01022222222",
            teacherId = "teacher_B"
        )
        backend.groups["gA"] = Group(
            id = "gA",
            teacherId = "teacher_A",
            gradeId = "grade_prep_1",
            name = "مجموعة أ",
            startTime = "10:00",
            endTime = "11:00"
        )
        backend.groups["gB"] = Group(
            id = "gB",
            teacherId = "teacher_B",
            gradeId = "grade_prep_1",
            name = "مجموعة ب",
            startTime = "12:00",
            endTime = "13:00"
        )

        assertTrue(studentRepo.refreshStudents().isSuccess)
        assertTrue(groupRepo.refreshGroups("teacher_A").isSuccess)
        assertEquals(listOf("sA"), studentRepo.getStudents().first().map { it.studentId })
        assertEquals(listOf("gA"), groupRepo.observeGroups("teacher_A").first().map { it.id })
        assertTrue(groupRepo.observeGroups("teacher_B").first().isEmpty())

        // Switch active teacher to B -> caches cleared, only B data returned
        studentRepo.clearCache()
        groupRepo.clearCache()
        activeTeacherId = "teacher_B"
        SupabaseClientProvider.mockTeacherId = "teacher_B"
        AccountSessionManager.setActiveTeacherId("teacher_B")

        assertTrue(studentRepo.getStudents().first().isEmpty())
        assertTrue(studentRepo.refreshStudents().isSuccess)
        assertTrue(groupRepo.refreshGroups("teacher_B").isSuccess)
        assertEquals(listOf("sB"), studentRepo.getStudents().first().map { it.studentId })
        assertEquals(listOf("gB"), groupRepo.observeGroups("teacher_B").first().map { it.id })
    }

    // 8. الحفاظ على group_id وعلاقات الطلاب وعدم دمج مجموعتين متشابهتي الاسم
    @Test
    fun scenario08_preservesGroupIdAndDoesNotMergeDuplicateNamedGroups() = runTest {
        backend.groups["grp_1"] = Group(
            id = "grp_1",
            teacherId = "teacher_A",
            gradeId = "grade_prep_1",
            name = "مجموعة المتفوقين",
            startTime = "13:00",
            endTime = "14:00"
        )
        backend.groups["grp_2"] = Group(
            id = "grp_2",
            teacherId = "teacher_A",
            gradeId = "grade_prep_1",
            name = "مجموعة المتفوقين",
            startTime = "17:00",
            endTime = "18:00"
        )
        backend.students["s_keep"] = Student(
            studentId = "s_keep",
            studentCode = "ST-00050",
            fullName = "خالد مصطفى",
            gradeId = "grade_prep_1",
            parentPhone = "01077778888",
            teacherId = "teacher_A",
            groupId = "grp_2"
        )

        groupRepo.refreshGroups("teacher_A")
        studentRepo.refreshStudents()

        val groups = groupRepo.observeGroups("teacher_A").first()
        assertEquals(2, groups.size)
        assertEquals(setOf("grp_1", "grp_2"), groups.map { it.id }.toSet())

        // Update student name without losing groupId
        val existing = studentRepo.getStudentById("s_keep")!!
        val updated = studentRepo.updateStudent(existing.copy(fullName = "خالد مصطفى محمود")).getOrThrow()
        assertEquals("grp_2", updated.groupId)
        assertEquals("grp_2", backend.students["s_keep"]?.groupId)
    }

    // 9. الحذف الآمن للمجموعة (عدم حذف group_days عند ارتباط طلاب أو حضور، واقتراح التعطيل)
    @Test
    fun scenario09_safeGroupDeletionBlocksLinkedGroupWithoutDeletingGroupDaysAndOffersDeactivation() = runTest {
        val linkedGroup = Group(
            id = "grp_linked",
            teacherId = "teacher_A",
            gradeId = "grade_prep_1",
            name = "مجموعة مرتبطة",
            active = true,
            startTime = "15:00",
            endTime = "16:00"
        )
        val unlinkedGroup = Group(
            id = "grp_unlinked",
            teacherId = "teacher_A",
            gradeId = "grade_prep_1",
            name = "مجموعة فارغة",
            active = true,
            startTime = "18:00",
            endTime = "19:00"
        )
        backend.groups[linkedGroup.id] = linkedGroup
        backend.groups[unlinkedGroup.id] = unlinkedGroup
        backend.groupDays.add(
            GroupDay(teacherId = "teacher_A", groupId = "grp_linked", dayOfWeek = "الاثنين")
        )
        backend.groupDays.add(
            GroupDay(teacherId = "teacher_A", groupId = "grp_unlinked", dayOfWeek = "الثلاثاء")
        )
        backend.students["s_linked"] = Student(
            studentId = "s_linked",
            studentCode = "ST-00010",
            fullName = "سعيد حسن",
            gradeId = "grade_prep_1",
            parentPhone = "01012121212",
            teacherId = "teacher_A",
            groupId = "grp_linked"
        )

        val groupsVm = GroupsViewModel(
            groupRepository = groupRepo,
            gradeRepository = gradeRepo,
            teacherRepository = teacherRepo,
            studentRepository = studentRepo
        )
        advanceUntilIdle()

        // Attempt delete linked group via ViewModel -> blocked BEFORE touching group_days
        groupsVm.requestDeleteGroup(linkedGroup)
        groupsVm.confirmDeleteGroup()
        advanceUntilIdle()

        assertEquals(0, backend.deleteGroupDaysCalls)
        assertEquals(1, backend.groupDays.count { it.groupId == "grp_linked" })
        assertNotNull(backend.groups["grp_linked"])
        assertEquals("grp_linked", backend.students["s_linked"]?.groupId)
        assertEquals("grp_linked", groupsVm.uiState.value.groupBlockedToDeactivate?.id)

        // Confirm deactivation -> active becomes false while preserving students & days
        groupsVm.confirmDeactivateBlockedGroup()
        advanceUntilIdle()
        assertFalse(backend.groups["grp_linked"]!!.active)
        assertEquals("grp_linked", backend.students["s_linked"]?.groupId)
        assertEquals(1, backend.groupDays.count { it.groupId == "grp_linked" })
        assertNull(groupsVm.uiState.value.groupBlockedToDeactivate)

        // Attendance-only link also blocks deletion without touching group_days
        backend.attendanceGroupIds.add("teacher_A:grp_unlinked")
        val attBlockedRes = groupRepo.deleteGroup("teacher_A", "grp_unlinked")
        assertTrue(attBlockedRes.exceptionOrNull() is GroupDeletionBlockedException)
        assertEquals(0, backend.deleteGroupDaysCalls)
        assertEquals(1, backend.groupDays.count { it.groupId == "grp_unlinked" })

        // Unlinked group deletes cleanly
        backend.attendanceGroupIds.remove("teacher_A:grp_unlinked")
        val cleanDelRes = groupRepo.deleteGroup("teacher_A", "grp_unlinked")
        assertTrue(cleanDelRes.isSuccess)
        assertNull(backend.groups["grp_unlinked"])
        assertEquals(0, backend.groupDays.count { it.groupId == "grp_unlinked" })
    }

    // 10. عدم حفظ البيانات محليًا بشكل دائم في Room أو Outbox
    @Test
    fun scenario10_noPermanentLocalPersistenceInRoomOrOutbox() = runTest {
        val addRes = studentRepo.addStudent("مازن فؤاد", "grade_prep_1", "01066667777", true, null)
        assertTrue(addRes.isSuccess)
        val student = addRes.getOrThrow().data

        val grpRes = groupRepo.createGroup(
            Group(
                id = "grp_online_only",
                teacherId = "teacher_A",
                gradeId = "grade_prep_1",
                name = "مجموعة أونلاين",
                startTime = "14:00",
                endTime = "15:00"
            )
        )
        assertTrue(grpRes.isSuccess)
        assertTrue(groupRepo.replaceGroupDays("teacher_A", "grp_online_only", listOf("السبت", "الثلاثاء")).isSuccess)
        assertTrue(groupRepo.assignStudentToGroup("teacher_A", student.studentId, "grp_online_only").isSuccess)
        assertTrue(studentRepo.refreshStudents().isSuccess)
        assertTrue(groupRepo.refreshGroups("teacher_A").isSuccess)

        assertTrue(db.studentDao().getAllStudentsSync("teacher_A").isEmpty())
        assertTrue(db.groupDao().getGroupsSync("teacher_A").isEmpty())
        assertTrue(db.groupDayDao().getGroupDaysByTeacher("teacher_A").isEmpty())
        assertTrue(db.outboxDao().getPendingOperations().isEmpty())
        assertTrue(db.outboxDao().getPendingOperationsForTeacher("teacher_A").isEmpty())
    }
}
