package com.example.data.auth

import android.util.Log
import com.example.data.SupabaseClientProvider
import com.example.data.repository.RepositoryProvider
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates Online-Only account session teardown, session epoch tracking, and multi-tenant isolation.
 */
object AccountSessionManager {

    private const val TAG = "AccountSessionManager"
    private val mutex = Mutex()
    private val sessionEpoch = AtomicLong(0L)

    @Volatile
    private var activeTeacherId: String? = null

    @Volatile
    private var isTeardownInProgress = false

    fun currentSessionEpoch(): Long = sessionEpoch.get()

    fun setActiveTeacherId(teacherId: String?) {
        if (activeTeacherId != teacherId) {
            sessionEpoch.incrementAndGet()
        }
        activeTeacherId = teacherId
        if (com.example.BuildConfig.DEBUG) Log.d(TAG, "Active teacher set")
    }

    fun getActiveTeacherId(): String? {
        return activeTeacherId
            ?: SupabaseClientProvider.mockTeacherId
            ?: try { SupabaseClientProvider.client.auth.currentUserOrNull()?.id } catch (_: Exception) { null }
    }

    fun isSessionActive(teacherId: String?): Boolean {
        if (teacherId.isNullOrBlank() || isTeardownInProgress) return false

        val mockId = SupabaseClientProvider.mockTeacherId
        if (mockId != null) {
            return mockId == teacherId
        }

        val remoteId = try { SupabaseClientProvider.client.auth.currentUserOrNull()?.id } catch (_: Exception) { null }
        if (remoteId == null || remoteId != teacherId) {
            return false
        }

        val tracked = activeTeacherId
        return tracked == null || tracked == teacherId
    }

    /**
     * Shared Session Gate: rejects stale writes if the active teacher session changed or was torn down.
     */
    suspend fun <T> runIfSessionActive(
        teacherId: String,
        block: suspend () -> T
    ): T? {
        if (teacherId.isBlank()) return null
        return mutex.withLock {
            if (!isSessionActive(teacherId)) {
                Log.w(TAG, "runIfSessionActive: Session inactive or teardown in progress for teacher '$teacherId'")
                return@withLock null
            }
            block()
        }
    }

    /** Explicit sign-out: clears all in-memory session caches, bumps session epoch, and signs out remotely. */
    suspend fun logout(@Suppress("UNUSED_PARAMETER") force: Boolean = false): Result<Unit> =
        handleSessionTermination(remoteSignOut = true)

    /** Signs out remotely and clears in-memory session caches (used right after e-mail confirmation). */
    suspend fun endSessionKeepingLocalData(): Result<Unit> =
        handleSessionTermination(remoteSignOut = true)

    /** Involuntary end of session (expiry, revocation, spoofed deep link): clears all in-memory caches immediately. */
    suspend fun onSessionTerminated(): Result<Unit> =
        handleSessionTermination(remoteSignOut = false)

    private suspend fun handleSessionTermination(
        remoteSignOut: Boolean
    ): Result<Unit> = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            try {
                isTeardownInProgress = true
                Log.d(TAG, "Starting Online-Only session teardown (remoteSignOut=$remoteSignOut)")

                sessionEpoch.incrementAndGet()
                activeTeacherId = null
                SupabaseClientProvider.mockTeacherId = null

                try {
                    RepositoryProvider.clearAllSessionCaches()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clear repository in-memory caches", e)
                }

                if (remoteSignOut) {
                    try {
                        SupabaseClientProvider.client.auth.signOut()
                    } catch (e: Exception) {
                        Log.w(TAG, "Remote signOut failed or device offline. In-memory caches cleared.", e)
                    }
                }

                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Fatal error during account session teardown", e)
                Result.failure(e)
            } finally {
                isTeardownInProgress = false
            }
        }
    }
}
