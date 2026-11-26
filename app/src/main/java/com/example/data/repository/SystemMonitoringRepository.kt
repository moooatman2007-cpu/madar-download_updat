package com.example.data.repository

import com.example.core.model.SystemMonitoringAlert
import com.example.data.SupabaseClientProvider
import com.example.data.auth.AccountSessionManager
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface SystemMonitoringRepository {
    suspend fun getOpenAlerts(): Result<List<SystemMonitoringAlert>>
}

class SupabaseSystemMonitoringRepository(
    private val client: SupabaseClient = SupabaseClientProvider.client,
    private val teacherIdProvider: () -> String? = {
        SupabaseClientProvider.mockTeacherId
            ?: try { client.auth.currentUserOrNull()?.id } catch (_: Exception) { null }
    },
    private val sessionActiveChecker: (String) -> Boolean = { teacherId ->
        AccountSessionManager.isSessionActive(teacherId)
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : SystemMonitoringRepository {

    override suspend fun getOpenAlerts(): Result<List<SystemMonitoringAlert>> = withContext(ioDispatcher) {
        val teacherId = teacherIdProvider()?.takeIf { it.isNotBlank() && sessionActiveChecker(it) }
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى تسجيل الدخول أولاً."))

        try {
            val alerts = client.postgrest["system_monitoring_alerts"]
                .select {
                    filter {
                        eq("status", "OPEN")
                    }
                    order("created_at", order = Order.DESCENDING)
                }
                .decodeList<SystemMonitoringAlert>()

            if (!sessionActiveChecker(teacherId) || teacherIdProvider() != teacherId) {
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء جلب التنبيهات."))
            }

            Result.success(alerts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
