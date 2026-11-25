package com.example.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

data class NotificationPreferences(
    val attendanceRemindersEnabled: Boolean = true,
    val absenceAlertsEnabled: Boolean = true,
    val monthlyReportAlertsEnabled: Boolean = true
)

interface SettingsRepository {
    fun getThemeMode(): Flow<ThemeMode>
    suspend fun setThemeMode(mode: ThemeMode)
    fun getTeacherName(): Flow<String>
    suspend fun setTeacherName(name: String)

    fun getDefaultMonthlyFee(): Flow<Double> = flowOf(0.0)
    suspend fun setDefaultMonthlyFee(fee: Double) {}

    fun getNotificationPreferences(): Flow<NotificationPreferences> = flowOf(NotificationPreferences())
    suspend fun setNotificationPreferences(prefs: NotificationPreferences) {}
}
