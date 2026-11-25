package com.example.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.midarSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "midar_settings"
)

class DataStoreSettingsRepository(
    context: Context
) : SettingsRepository {

    private val dataStore = context.applicationContext.midarSettingsDataStore

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val TEACHER_NAME = stringPreferencesKey("teacher_name")
        val DEFAULT_MONTHLY_FEE = doublePreferencesKey("default_monthly_fee")
        val ATTENDANCE_REMINDERS = booleanPreferencesKey("pref_attendance_reminders")
        val ABSENCE_ALERTS = booleanPreferencesKey("pref_absence_alerts")
        val MONTHLY_REPORT_ALERTS = booleanPreferencesKey("pref_monthly_report_alerts")
    }

    private val safeData: Flow<Preferences> = dataStore.data.catch { exception ->
        if (exception is IOException) {
            emit(emptyPreferences())
        } else {
            throw exception
        }
    }

    override fun getThemeMode(): Flow<ThemeMode> = safeData.map { prefs ->
        val raw = prefs[Keys.THEME_MODE]
        ThemeMode.entries.firstOrNull { it.name == raw } ?: ThemeMode.SYSTEM
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = mode.name
        }
    }

    override fun getTeacherName(): Flow<String> = safeData.map { prefs ->
        prefs[Keys.TEACHER_NAME] ?: "الأستاذ"
    }

    override suspend fun setTeacherName(name: String) {
        dataStore.edit { prefs ->
            prefs[Keys.TEACHER_NAME] = name.trim()
        }
    }

    override fun getDefaultMonthlyFee(): Flow<Double> = safeData.map { prefs ->
        (prefs[Keys.DEFAULT_MONTHLY_FEE] ?: 0.0).coerceAtLeast(0.0)
    }

    override suspend fun setDefaultMonthlyFee(fee: Double) {
        dataStore.edit { prefs ->
            prefs[Keys.DEFAULT_MONTHLY_FEE] = fee.coerceAtLeast(0.0)
        }
    }

    override fun getNotificationPreferences(): Flow<NotificationPreferences> = safeData.map { prefs ->
        NotificationPreferences(
            attendanceRemindersEnabled = prefs[Keys.ATTENDANCE_REMINDERS] ?: true,
            absenceAlertsEnabled = prefs[Keys.ABSENCE_ALERTS] ?: true,
            monthlyReportAlertsEnabled = prefs[Keys.MONTHLY_REPORT_ALERTS] ?: true
        )
    }

    override suspend fun setNotificationPreferences(prefs: NotificationPreferences) {
        dataStore.edit { mutablePrefs ->
            mutablePrefs[Keys.ATTENDANCE_REMINDERS] = prefs.attendanceRemindersEnabled
            mutablePrefs[Keys.ABSENCE_ALERTS] = prefs.absenceAlertsEnabled
            mutablePrefs[Keys.MONTHLY_REPORT_ALERTS] = prefs.monthlyReportAlertsEnabled
        }
    }
}
