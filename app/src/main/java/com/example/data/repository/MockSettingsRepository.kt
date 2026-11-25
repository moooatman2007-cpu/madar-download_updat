package com.example.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MockSettingsRepository : SettingsRepository {
    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    private val _teacherName = MutableStateFlow("أستاذ محمد أحمد")
    private val _defaultMonthlyFee = MutableStateFlow(0.0)
    private val _notificationPreferences = MutableStateFlow(NotificationPreferences())

    override fun getThemeMode(): Flow<ThemeMode> = _themeMode.asStateFlow()

    override suspend fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
    }

    override fun getTeacherName(): Flow<String> = _teacherName.asStateFlow()

    override suspend fun setTeacherName(name: String) {
        _teacherName.value = name
    }

    override fun getDefaultMonthlyFee(): Flow<Double> = _defaultMonthlyFee.asStateFlow()

    override suspend fun setDefaultMonthlyFee(fee: Double) {
        _defaultMonthlyFee.value = fee.coerceAtLeast(0.0)
    }

    override fun getNotificationPreferences(): Flow<NotificationPreferences> = _notificationPreferences.asStateFlow()

    override suspend fun setNotificationPreferences(prefs: NotificationPreferences) {
        _notificationPreferences.value = prefs
    }
}
