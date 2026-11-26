package com.example.data.repository

import android.content.Context

object RepositoryProvider {
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val teacherRepository: TeacherRepository by lazy {
        SupabaseTeacherRepository()
    }

    val gradeRepository: GradeRepository by lazy {
        SupabaseGradeRepository()
    }

    val studentRepository: StudentRepository by lazy {
        SupabaseStudentRepository()
    }

    val settingsRepository: SettingsRepository by lazy {
        try {
            val ctx = appContext ?: throw IllegalStateException("RepositoryProvider not initialized with Context.")
            DataStoreSettingsRepository(ctx)
        } catch (_: Throwable) {
            MockSettingsRepository()
        }
    }

    val attendanceRepository: AttendanceRepository by lazy {
        SupabaseAttendanceRepository()
    }

    val recitationRepository: RecitationRepository by lazy {
        SupabaseRecitationRepository()
    }

    val examRepository: ExamRepository by lazy {
        SupabaseExamRepository()
    }

    val monthlyReportRepository: MonthlyReportRepository by lazy {
        SupabaseMonthlyReportRepository()
    }

    val homeworkRepository: HomeworkRepository by lazy {
        SupabaseHomeworkRepository()
    }

    val groupRepository: GroupRepository by lazy {
        SupabaseGroupRepository(
            onStudentUpdated = { updatedStudent ->
                (studentRepository as? SupabaseStudentRepository)?.updateStudentInMemory(updatedStudent)
            }
        )
    }

    val paymentRepository: PaymentRepository by lazy {
        SupabasePaymentRepository()
    }

    val notificationEventRepository: NotificationEventRepository by lazy {
        SupabaseNotificationEventRepository()
    }

    val subscriptionRepository: SubscriptionRepository by lazy {
        SupabaseSubscriptionRepository()
    }

    fun clearAllSessionCaches() {
        (teacherRepository as? SupabaseTeacherRepository)?.clearCache()
        (gradeRepository as? SupabaseGradeRepository)?.clearCache()
        (studentRepository as? SupabaseStudentRepository)?.clearCache()
        (groupRepository as? SupabaseGroupRepository)?.clearCache()
        (attendanceRepository as? SupabaseAttendanceRepository)?.clearCache()
        (examRepository as? SupabaseExamRepository)?.clearCache()
        (recitationRepository as? SupabaseRecitationRepository)?.clearCache()
        (homeworkRepository as? SupabaseHomeworkRepository)?.clearCache()
        (paymentRepository as? SupabasePaymentRepository)?.clearCache()
        (monthlyReportRepository as? SupabaseMonthlyReportRepository)?.clearCache()
    }
}
