package com.example.ui.more

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.model.Grade
import com.example.data.SupabaseClientProvider
import com.example.data.local.DatabaseProvider
import com.example.data.local.dao.OutboxDao
import com.example.data.local.entity.OutboxEntity
import com.example.data.repository.GradeRepository
import com.example.data.repository.NotificationPreferences
import com.example.data.repository.RepositoryProvider
import com.example.data.repository.SettingsRepository
import com.example.data.repository.TeacherRepository
import com.example.data.repository.ThemeMode
import com.example.data.sync.OutboxSyncScheduler
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class MoreUiState(
    val teacherName: String = "",
    val teacherPhone: String? = null,
    val avatarUrl: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val grades: List<Grade> = emptyList(),
    val showThemeDialog: Boolean = false,
    val showEditProfileDialog: Boolean = false,
    val showAboutDialog: Boolean = false,
    val showLogoutDialog: Boolean = false,
    val isLoggingOut: Boolean = false,
    val logoutError: String? = null,
    val isTestingWaha: Boolean = false,
    val wahaTestResult: String? = null,
    val showWahaPairingDialog: Boolean = false,
    val isStartingWaha: Boolean = false,
    val wahaSessionStatus: String? = null,
    val wahaQrBase64: String? = null,
    val wahaConnectedPhone: String? = null,
    val wahaPairingError: String? = null,
    val selectedPairingTab: String = "CODE", // Default is "CODE" for 1-phone optimization
    val pairingPhoneNumber: String = "",
    val pairingCode: String? = null,
    val isRequestingPairingCode: Boolean = false,
    val pairingCodeError: String? = null,
    val isPairingCodeExpired: Boolean = false,
    val isSyncingWahaConfig: Boolean = false,
    val wahaSyncSuccessMessage: String? = null,
    val wahaSyncErrorMessage: String? = null,
    val defaultMonthlyFee: Double = 0.0,
    val notificationPrefs: NotificationPreferences = NotificationPreferences(),
    val showNotificationsDialog: Boolean = false,
    val showLanguageDialog: Boolean = false,
    val showOutboxDialog: Boolean = false,
    val pendingOutboxCount: Int = 0,
    val failedOutboxCount: Int = 0,
    val unsyncedOutboxItems: List<OutboxEntity> = emptyList(),
    val isRetryingOutbox: Boolean = false,
    val outboxMessage: String? = null,
    val requireForceLogoutConfirm: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class MoreViewModel(
    private val settingsRepository: SettingsRepository = RepositoryProvider.settingsRepository,
    private val gradeRepository: GradeRepository = RepositoryProvider.gradeRepository,
    private val teacherRepository: TeacherRepository = RepositoryProvider.teacherRepository,
    private val okHttpClient: OkHttpClient = defaultOkHttpClient,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    private val runInitialCheck: Boolean = true,
    edgeFunctionUrl: String? = null,
    private val outboxDao: OutboxDao? = try { DatabaseProvider.getDatabase().outboxDao() } catch (_: Exception) { null }
) : ViewModel() {

    companion object {
        val defaultOkHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }

    private val _uiState = MutableStateFlow(MoreUiState())
    val uiState: StateFlow<MoreUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null
    private var pairingJob: Job? = null
    // In-memory only reference to pairing code copied to clipboard (never logged/persisted)
    private var lastCopiedPairingCode: String? = null

    private val wahaSessionUrl: String by lazy {
        edgeFunctionUrl?.takeIf { it.isNotBlank() }
            ?: SupabaseClientProvider.getEdgeFunctionUrl("waha-session")
    }

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            teacherRepository.fetchCurrentTeacher()
            (gradeRepository as? com.example.data.repository.SupabaseGradeRepository)?.fetchGrades()
            // Check status on load
            if (runInitialCheck) {
                fetchWahaStatus()
            }
        }

        viewModelScope.launch {
            combine(
                teacherRepository.getCurrentTeacher(),
                settingsRepository.getThemeMode(),
                gradeRepository.getGrades(),
                settingsRepository.getDefaultMonthlyFee(),
                settingsRepository.getNotificationPreferences()
            ) { teacher, theme, grades, defaultFee, notifPrefs ->
                val name = teacher?.fullName?.ifBlank { null } ?: teacher?.email ?: ""
                val phone = teacher?.phoneNumber?.ifBlank { null }
                _uiState.value.copy(
                    teacherName = name,
                    teacherPhone = phone,
                    avatarUrl = teacher?.avatarUrl,
                    themeMode = theme,
                    grades = grades.sortedBy { it.displayOrder },
                    defaultMonthlyFee = defaultFee,
                    notificationPrefs = notifPrefs
                )
            }.collect { newState ->
                _uiState.value = newState
            }
        }

        viewModelScope.launch {
            val dao = outboxDao ?: return@launch
            teacherRepository.getCurrentTeacher()
                .map { teacher ->
                    teacher?.id
                        ?: SupabaseClientProvider.mockTeacherId
                        ?: try { SupabaseClientProvider.client.auth.currentUserOrNull()?.id } catch (_: Exception) { null }
                }
                .distinctUntilChanged()
                .flatMapLatest { tId ->
                    if (tId.isNullOrBlank()) flowOf(emptyList())
                    else dao.observeUnsyncedOperationsForTeacher(tId)
                }
                .collect { ops ->
                    _uiState.update {
                        it.copy(
                            pendingOutboxCount = ops.count { op -> op.status == "PENDING" },
                            failedOutboxCount = ops.count { op -> op.status == "FAILED" },
                            unsyncedOutboxItems = ops
                        )
                    }
                }
        }
    }

    private suspend fun fetchWahaStatus() {
        try {
            val token = SupabaseClientProvider.client.auth.currentAccessTokenOrNull()
            val jsonObject = JSONObject().apply { put("action", "TEST_WAHA_CONNECTION") }
            val requestBody = jsonObject.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

            val requestBuilder = Request.Builder()
                .url(wahaSessionUrl)
                .post(requestBody)
            if (!token.isNullOrBlank()) requestBuilder.header("Authorization", "Bearer $token")

            val response = withContext(ioDispatcher) {
                okHttpClient.newCall(requestBuilder.build()).execute()
            }

            response.use { res ->
                if (res.isSuccessful) {
                    val resJson = JSONObject(res.body?.string() ?: "")
                    val isConnected = resJson.optBoolean("whatsapp_connected", false)
                    val status = resJson.optString("session_status", resJson.optString("status", ""))
                    val connectedPhone = resJson.optString("connected_phone", "")
                    _uiState.update {
                        it.copy(
                            wahaSessionStatus = if (isConnected && status.isBlank()) "CONNECTED" else status,
                            wahaConnectedPhone = if (connectedPhone.isNotBlank() && connectedPhone != "null") connectedPhone else null
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Silently fail if network is down on init
        }
    }

    fun openThemeDialog() {
        _uiState.update { it.copy(showThemeDialog = true) }
    }

    fun closeThemeDialog() {
        _uiState.update { it.copy(showThemeDialog = false) }
    }

    fun selectTheme(mode: ThemeMode) {
        viewModelScope.launch {
            settingsRepository.setThemeMode(mode)
            closeThemeDialog()
        }
    }

    fun openEditProfileDialog() {
        _uiState.update { it.copy(showEditProfileDialog = true) }
    }

    fun closeEditProfileDialog() {
        _uiState.update { it.copy(showEditProfileDialog = false) }
    }

    fun updateTeacherName(newName: String) {
        viewModelScope.launch {
            if (newName.isNotBlank()) {
                val currentTeacher = teacherRepository.fetchCurrentTeacher()
                teacherRepository.updateTeacherProfile(
                    fullName = newName.trim(),
                    phoneNumber = currentTeacher?.phoneNumber,
                    subject = currentTeacher?.subject,
                    centerName = currentTeacher?.centerName,
                    avatarUrl = currentTeacher?.avatarUrl
                )
            }
            closeEditProfileDialog()
        }
    }

    fun openAboutDialog() {
        _uiState.update { it.copy(showAboutDialog = true) }
    }

    fun closeAboutDialog() {
        _uiState.update { it.copy(showAboutDialog = false) }
    }

    fun openNotificationsDialog() {
        _uiState.update { it.copy(showNotificationsDialog = true) }
    }

    fun closeNotificationsDialog() {
        _uiState.update { it.copy(showNotificationsDialog = false) }
    }

    fun saveNotificationAndFeeSettings(prefs: NotificationPreferences, defaultFee: Double) {
        viewModelScope.launch {
            settingsRepository.setNotificationPreferences(prefs)
            settingsRepository.setDefaultMonthlyFee(defaultFee.coerceAtLeast(0.0))
            closeNotificationsDialog()
        }
    }

    fun openLanguageDialog() {
        _uiState.update { it.copy(showLanguageDialog = true) }
    }

    fun closeLanguageDialog() {
        _uiState.update { it.copy(showLanguageDialog = false) }
    }

    fun openOutboxDialog() {
        _uiState.update { it.copy(showOutboxDialog = true, outboxMessage = null) }
    }

    fun closeOutboxDialog() {
        _uiState.update { it.copy(showOutboxDialog = false, outboxMessage = null) }
    }

    fun retryPendingOutbox() {
        if (_uiState.value.isRetryingOutbox) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRetryingOutbox = true, outboxMessage = null) }
            try {
                val teacherId = com.example.data.auth.AccountSessionManager.getActiveTeacherId()
                if (teacherId.isNullOrBlank() || outboxDao == null) {
                    _uiState.update {
                        it.copy(
                            isRetryingOutbox = false,
                            outboxMessage = "تعذر الوصول إلى قائمة المزامنة المحلية."
                        )
                    }
                    return@launch
                }

                var resetCount = 0
                var permanentCount = 0
                withContext(ioDispatcher) {
                    val ops = outboxDao.getUnsyncedOperationsForTeacher(teacherId)
                    for (op in ops) {
                        if (op.status == "FAILED") {
                            val err = op.lastError?.lowercase().orEmpty()
                            val isPermanentConstraint = err.contains("violates foreign key") ||
                                err.contains("violates unique constraint") ||
                                err.contains("duplicate key") ||
                                err.contains("23503") ||
                                err.contains("23505") ||
                                err.contains("23502")
                            if (isPermanentConstraint) {
                                permanentCount++
                            } else {
                                outboxDao.updateOperation(op.copy(status = "PENDING", retryCount = 0))
                                resetCount++
                            }
                        }
                    }
                    OutboxSyncScheduler.retryNow()
                }

                val msg = when {
                    permanentCount > 0 && resetCount == 0 ->
                        "يوجد $permanentCount عملية متعثرة بسبب تعارض في البيانات (يرجى المراجعة قبل الإزالة)."
                    resetCount > 0 ->
                        "تمت إعادة جدولة $resetCount عملية للمزامنة الفورية عبر الشبكة."
                    else ->
                        "تم إرسال طلب المزامنة الفورية للعمليات المعلقة."
                }
                _uiState.update { it.copy(isRetryingOutbox = false, outboxMessage = msg) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isRetryingOutbox = false,
                        outboxMessage = e.localizedMessage ?: "تعذر إعادة جدولة المزامنة"
                    )
                }
            }
        }
    }

    fun openLogoutDialog() {
        _uiState.update {
            it.copy(
                showLogoutDialog = true,
                logoutError = null,
                requireForceLogoutConfirm = false
            )
        }
    }

    fun closeLogoutDialog() {
        _uiState.update {
            it.copy(
                showLogoutDialog = false,
                logoutError = null,
                requireForceLogoutConfirm = false
            )
        }
    }

    fun performLogout(onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoggingOut = true, logoutError = null) }
            val result = com.example.data.auth.AccountSessionManager.logout()
            if (result.isSuccess) {
                _uiState.update {
                    it.copy(
                        isLoggingOut = false,
                        showLogoutDialog = false,
                        requireForceLogoutConfirm = false
                    )
                }
                onSuccess()
            } else {
                val errorMsg = result.exceptionOrNull()?.localizedMessage
                    ?: "حدث خطأ أثناء تسجيل الخروج، يرجى المحاولة مرة أخرى."
                _uiState.update {
                    it.copy(
                        isLoggingOut = false,
                        logoutError = errorMsg,
                        requireForceLogoutConfirm = true
                    )
                }
            }
        }
    }

    fun confirmForceLogout(onSuccess: () -> Unit) {
        if (_uiState.value.isLoggingOut) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoggingOut = true, logoutError = null) }
            val result = com.example.data.auth.AccountSessionManager.logout(force = true)
            if (result.isSuccess) {
                _uiState.update {
                    it.copy(
                        isLoggingOut = false,
                        showLogoutDialog = false,
                        requireForceLogoutConfirm = false
                    )
                }
                onSuccess()
            } else {
                val errorMsg = result.exceptionOrNull()?.localizedMessage ?: "فشل تسجيل الخروج القسري."
                _uiState.update { it.copy(isLoggingOut = false, logoutError = errorMsg) }
            }
        }
    }

    fun testWahaConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(isTestingWaha = true, wahaTestResult = null) }
            try {
                val token = SupabaseClientProvider.client.auth.currentAccessTokenOrNull()
                if (token.isNullOrBlank()) {
                    _uiState.update {
                        it.copy(
                            isTestingWaha = false,
                            wahaTestResult = "انتهت جلسة تسجيل الدخول. يرجى تسجيل الدخول مرة أخرى."
                        )
                    }
                    return@launch
                }

                val jsonObject = JSONObject()
                jsonObject.put("action", "TEST_WAHA_CONNECTION")
                val bodyString = jsonObject.toString()

                val requestBody = bodyString.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

                val requestBuilder = Request.Builder()
                    .url(wahaSessionUrl)
                    .post(requestBody)
                    .header("Authorization", "Bearer $token")

                val startTime = System.currentTimeMillis()
                val response = withContext(ioDispatcher) {
                    okHttpClient.newCall(requestBuilder.build()).execute()
                }
                val latencyMs = System.currentTimeMillis() - startTime

                response.use { res ->
                    val code = res.code
                    val responseStr = res.body?.string() ?: ""
                    if (com.example.BuildConfig.DEBUG) android.util.Log.d("WahaTest", "Raw response: $responseStr (code: $code)")

                    var resJson: JSONObject? = null
                    try {
                        resJson = JSONObject(responseStr)
                    } catch (_: Exception) {
                        // JSON parsing failed
                    }

                    val reachable = if (resJson != null) {
                        resJson.optBoolean("reachable", true)
                    } else {
                        true
                    }

                    val statusVal = if (resJson != null && resJson.has("status") && !resJson.isNull("status")) {
                        resJson.optInt("status")
                    } else {
                        code
                    }

                    val success = if (resJson != null && resJson.has("success")) {
                        resJson.optBoolean("success", false)
                    } else {
                        res.isSuccessful
                    }

                    val jsonLatency = if (resJson != null && resJson.has("latency_ms")) {
                        resJson.optLong("latency_ms", latencyMs)
                    } else {
                        latencyMs
                    }

                    val sessionExists = if (resJson != null && resJson.has("session_exists")) {
                        resJson.optBoolean("session_exists", statusVal != 404)
                    } else {
                        statusVal != 404
                    }

                    val sessionStatus = resJson?.optString("session_status", if (statusVal == 404) "NOT_FOUND" else "UNKNOWN") ?: "UNKNOWN"

                    val isConnected = if (resJson != null && resJson.has("whatsapp_connected")) {
                        resJson.optBoolean("whatsapp_connected", false)
                    } else {
                        sessionStatus == "WORKING" || sessionStatus == "CONNECTED"
                    }

                    val resultText = buildString {
                        append("WhatsApp Server Connection Test\n\n")
                        append("reachable: $reachable\n")
                        append("session_exists: $sessionExists\n")
                        append("status: $statusVal\n")
                        append("session_status: $sessionStatus\n")
                        append("whatsapp_connected: $isConnected\n")
                        append("latency_ms: ${jsonLatency}ms\n")
                        append("success: $success")

                        if (!reachable) {
                            append("\n\nتعذر الوصول إلى خادم WAHA. يرجى التحقق من عمل السيرفر والمفتاح.")
                        } else if (!sessionExists) {
                            append("\n\nخادم WhatsApp متصل وجاهز للعمل ✓\n(الجلسة غير موجودة بعد - يمكنك الضغط على 'ربط WhatsApp' لبدء الربط).")
                        } else if (isConnected) {
                            append("\n\nجلسة WhatsApp متصلة وتعمل بنجاح ✓")
                        } else {
                            append("\n\nالخادم متصل، وحالة الجلسة: $sessionStatus")
                        }
                    }

                    _uiState.update {
                        it.copy(isTestingWaha = false, wahaTestResult = resultText)
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                _uiState.update {
                    it.copy(
                        isTestingWaha = false,
                        wahaTestResult = buildString {
                            append("WhatsApp Server Connection Test\n\n")
                            append("reachable: false\n")
                            append("status: 0\n")
                            append("latency_ms: 0ms\n")
                            append("success: false")
                        }
                    )
                }
            } catch (e: java.io.IOException) {
                _uiState.update {
                    it.copy(
                        isTestingWaha = false,
                        wahaTestResult = buildString {
                            append("WhatsApp Server Connection Test\n\n")
                            append("reachable: false\n")
                            append("status: 0\n")
                            append("latency_ms: 0ms\n")
                            append("success: false")
                        }
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isTestingWaha = false,
                        wahaTestResult = buildString {
                            append("WhatsApp Server Connection Test\n\n")
                            append("reachable: false\n")
                            append("status: 0\n")
                            append("latency_ms: 0ms\n")
                            append("success: false")
                        }
                    )
                }
            }
        }
    }

    fun dismissWahaTestResult() {
        _uiState.update { it.copy(wahaTestResult = null, isTestingWaha = false) }
    }

    fun openWahaPairingDialog() {
        val initialPhone = _uiState.value.teacherPhone ?: _uiState.value.pairingPhoneNumber
        _uiState.update {
            it.copy(
                showWahaPairingDialog = true,
                wahaPairingError = null,
                wahaSyncSuccessMessage = null,
                wahaSyncErrorMessage = null,
                selectedPairingTab = "CODE",
                pairingPhoneNumber = initialPhone,
                pairingCode = null,
                isRequestingPairingCode = false,
                pairingCodeError = null
            )
        }
        
        // Only start if not already connected/working
        val status = _uiState.value.wahaSessionStatus
        if (status != "CONNECTED" && status != "WORKING") {
            startWaha()
        }
    }

    fun syncWahaConfig() {
        if (_uiState.value.isSyncingWahaConfig) return // Prevent repeated clicks

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSyncingWahaConfig = true,
                    wahaSyncSuccessMessage = null,
                    wahaSyncErrorMessage = null
                )
            }

            try {
                val token = SupabaseClientProvider.client.auth.currentAccessTokenOrNull()

                val jsonObject = JSONObject()
                jsonObject.put("action", "START")
                val bodyString = jsonObject.toString()

                val requestBody = bodyString.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

                val requestBuilder = Request.Builder()
                    .url(wahaSessionUrl)
                    .post(requestBody)

                if (!token.isNullOrBlank()) {
                    requestBuilder.header("Authorization", "Bearer $token")
                }

                val response = withContext(ioDispatcher) {
                    okHttpClient.newCall(requestBuilder.build()).execute()
                }

                response.use { res ->
                    val responseStr = res.body?.string() ?: ""
                    val resJson = try {
                        if (responseStr.isNotBlank()) JSONObject(responseStr) else null
                    } catch (_: Exception) {
                        null
                    }

                    if (!res.isSuccessful) {
                        val errorMsg = resJson?.optString("message")?.takeIf { it.isNotBlank() }
                            ?: "فشل مزامنة إعدادات WhatsApp (رمز الخطأ: ${res.code})"
                        _uiState.update {
                            it.copy(
                                isSyncingWahaConfig = false,
                                wahaSyncErrorMessage = errorMsg
                            )
                        }
                        return@launch
                    }

                    val success = resJson?.optBoolean("success", false) ?: false
                    if (success) {
                        val status = resJson?.optString("session_status", resJson.optString("status", "")) ?: ""
                        val connectedPhone = resJson?.optString("connected_phone", "")

                        _uiState.update {
                            it.copy(
                                isSyncingWahaConfig = false,
                                wahaSyncSuccessMessage = "تمت مزامنة إعدادات WhatsApp بنجاح",
                                wahaSessionStatus = if (status.isNotBlank()) status else it.wahaSessionStatus,
                                wahaConnectedPhone = if (!connectedPhone.isNullOrBlank() && connectedPhone != "null") connectedPhone else it.wahaConnectedPhone
                            )
                        }
                    } else {
                        val errorMsg = resJson?.optString("message")?.takeIf { it.isNotBlank() }
                            ?: "تعذر مزامنة إعدادات WhatsApp"
                        _uiState.update {
                            it.copy(
                                isSyncingWahaConfig = false,
                                wahaSyncErrorMessage = errorMsg
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSyncingWahaConfig = false,
                        wahaSyncErrorMessage = e.localizedMessage ?: "حدث خطأ أثناء الاتصال بالخادم"
                    )
                }
            }
        }
    }

    fun dismissWahaSyncStatus() {
        _uiState.update {
            it.copy(
                wahaSyncSuccessMessage = null,
                wahaSyncErrorMessage = null
            )
        }
    }

    fun closeWahaPairingDialog() {
        stopWahaPolling()
        _uiState.update { it.copy(showWahaPairingDialog = false) }
    }

    fun startWaha() {
        viewModelScope.launch {
            _uiState.update { it.copy(isStartingWaha = true, wahaPairingError = null) }
            try {
                val token = SupabaseClientProvider.client.auth.currentAccessTokenOrNull()

                val jsonObject = JSONObject()
                jsonObject.put("action", "START")
                val bodyString = jsonObject.toString()

                val requestBody = bodyString.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

                val requestBuilder = Request.Builder()
                    .url(wahaSessionUrl)
                    .post(requestBody)

                if (!token.isNullOrBlank()) {
                    requestBuilder.header("Authorization", "Bearer $token")
                }

                val response = withContext(ioDispatcher) {
                    okHttpClient.newCall(requestBuilder.build()).execute()
                }

                response.use { res ->
                    if (!res.isSuccessful) {
                        _uiState.update {
                            it.copy(
                                isStartingWaha = false,
                                wahaPairingError = "تعذر الاتصال بخادم واتساب حالياً. يرجى التحقق من الاتصال بالإنترنت."
                            )
                        }
                        return@launch
                    }

                    val responseStr = res.body?.string() ?: ""
                    val resJson = JSONObject(responseStr)

                    val success = resJson.optBoolean("success", false)
                    if (!success) {
                        val msg = resJson.optString("message", "تعذر الاتصال بخدمة واتساب")
                        val isQrPending = msg.contains("not available yet", ignoreCase = true) ||
                                msg.contains("QR code is not available", ignoreCase = true) ||
                                msg.contains("جاري تجهيز", ignoreCase = true) ||
                                msg.contains("started but QR", ignoreCase = true)

                        if (isQrPending) {
                            // WAHA session is running and generating the QR code in background
                            _uiState.update {
                                it.copy(
                                    isStartingWaha = false,
                                    wahaSessionStatus = "SCAN_QR_CODE",
                                    wahaPairingError = null
                                )
                            }
                            startWahaPolling()
                            return@launch
                        }

                        _uiState.update {
                            it.copy(
                                isStartingWaha = false,
                                wahaPairingError = msg
                            )
                        }
                        return@launch
                    }

                    val status = resJson.optString("session_status", resJson.optString("status", ""))
                    val connectedPhone = resJson.optString("connected_phone", "")
                    val qrData = if (resJson.has("qr") && !resJson.isNull("qr")) {
                        val qrObj = resJson.optJSONObject("qr")
                        if (qrObj != null) {
                            qrObj.optString("data", qrObj.optString("qr", ""))
                        } else {
                            resJson.optString("qr", "")
                        }
                    } else null

                    _uiState.update {
                        it.copy(
                            isStartingWaha = false,
                            wahaSessionStatus = status,
                            wahaConnectedPhone = if (connectedPhone.isNotBlank() && connectedPhone != "null") connectedPhone else null,
                            wahaQrBase64 = if (!qrData.isNullOrBlank()) qrData else null
                        )
                    }

                    if (status == "CONNECTED" || status == "WORKING") {
                        stopWahaPolling()
                    } else if (status == "SCAN_QR_CODE" && _uiState.value.selectedPairingTab == "QR") {
                        startWahaPolling()
                    }
                }
            } catch (e: java.io.IOException) {
                _uiState.update {
                    it.copy(
                        isStartingWaha = false,
                        wahaPairingError = "تعذر الاتصال بالخادم. يرجى التحقق من اتصال الإنترنت."
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isStartingWaha = false,
                        wahaPairingError = "حدث خطأ غير متوقع: ${e.localizedMessage ?: "خطأ بالاتصال"}"
                    )
                }
            }
        }
    }

    private var pollingAttemptCount = 0

    fun startWahaPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob?.cancel()
        pollingAttemptCount = 0
        pollingJob = viewModelScope.launch {
            // Poll for up to 60 iterations (~2.5 minutes timeout)
            while (isActive && pollingAttemptCount < 60) {
                kotlinx.coroutines.delay(2500)
                pollingAttemptCount++
                pollWahaStatus()
            }
            if (pollingAttemptCount >= 60 && isActive) {
                val currentStatus = _uiState.value.wahaSessionStatus
                if (currentStatus != "CONNECTED" && currentStatus != "WORKING") {
                    _uiState.update {
                        it.copy(
                            isPairingCodeExpired = true,
                            pairingCodeError = "انتهت صلاحية الكود"
                        )
                    }
                    stopWahaPolling()
                }
            }
        }
    }

    fun checkStatusImmediately() {
        viewModelScope.launch {
            pollWahaStatus()
            val currentStatus = _uiState.value.wahaSessionStatus
            if (currentStatus != "CONNECTED" && currentStatus != "WORKING" && currentStatus != "FAILED") {
                if (pollingJob?.isActive != true) {
                    startWahaPolling()
                }
            }
        }
    }

    fun stopWahaPolling() {
        pollingJob?.cancel()
        pollingJob = null
        pollingAttemptCount = 0
    }

    fun recordPairingCodeCopied(code: String) {
        lastCopiedPairingCode = code
    }

    fun shouldClearClipboard(currentClipText: String?): Boolean {
        val target = lastCopiedPairingCode
        if (!target.isNullOrBlank() && currentClipText == target) {
            lastCopiedPairingCode = null
            return true
        }
        return false
    }

    fun requestNewPairingCode() {
        _uiState.update {
            it.copy(
                pairingCode = null,
                isPairingCodeExpired = false,
                pairingCodeError = null
            )
        }
        requestPairingCode()
    }

    private suspend fun pollWahaStatus() {
        try {
            val token = SupabaseClientProvider.client.auth.currentAccessTokenOrNull()

            val jsonObject = JSONObject()
            jsonObject.put("action", "START")
            val bodyString = jsonObject.toString()

            val requestBody = bodyString.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

            val requestBuilder = Request.Builder()
                .url(wahaSessionUrl)
                .post(requestBody)

            if (!token.isNullOrBlank()) {
                requestBuilder.header("Authorization", "Bearer $token")
            }

            val response = withContext(ioDispatcher) {
                okHttpClient.newCall(requestBuilder.build()).execute()
            }

            response.use { res ->
                if (res.isSuccessful) {
                    val responseStr = res.body?.string() ?: ""
                    val resJson = JSONObject(responseStr)

                    if (resJson.optBoolean("success", false)) {
                        val status = resJson.optString("session_status", resJson.optString("status", ""))
                        val connectedPhone = resJson.optString("connected_phone", "")
                        val qrData = if (resJson.has("qr") && !resJson.isNull("qr")) {
                            val qrObj = resJson.optJSONObject("qr")
                            if (qrObj != null) {
                                qrObj.optString("data", qrObj.optString("qr", ""))
                            } else {
                                resJson.optString("qr", "")
                            }
                        } else null

                        _uiState.update {
                            it.copy(
                                wahaSessionStatus = status,
                                wahaConnectedPhone = if (connectedPhone.isNotBlank() && connectedPhone != "null") connectedPhone else null,
                                wahaQrBase64 = if (!qrData.isNullOrBlank()) qrData else null
                            )
                        }

                        if (status == "CONNECTED" || status == "WORKING" || status == "FAILED") {
                            stopWahaPolling()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore temporary network glitches during polling
        }
    }

    fun logoutWaha() {
        viewModelScope.launch {
            _uiState.update { it.copy(isStartingWaha = true, wahaPairingError = null) }
            try {
                val token = SupabaseClientProvider.client.auth.currentAccessTokenOrNull()

                val jsonObject = JSONObject()
                jsonObject.put("action", "LOGOUT")
                val bodyString = jsonObject.toString()

                val requestBody = bodyString.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

                val requestBuilder = Request.Builder()
                    .url(wahaSessionUrl)
                    .post(requestBody)

                if (!token.isNullOrBlank()) {
                    requestBuilder.header("Authorization", "Bearer $token")
                }

                val response = withContext(ioDispatcher) {
                    okHttpClient.newCall(requestBuilder.build()).execute()
                }

                response.use { res ->
                    if (res.isSuccessful) {
                        val responseStr = res.body?.string() ?: ""
                        val resJson = JSONObject(responseStr)

                        if (resJson.optBoolean("success", false)) {
                            _uiState.update {
                                it.copy(
                                    isStartingWaha = false,
                                    wahaSessionStatus = "DISCONNECTED",
                                    wahaConnectedPhone = null,
                                    wahaQrBase64 = null
                                )
                            }
                            stopWahaPolling()
                            return@launch
                        }
                    }

                    _uiState.update {
                        it.copy(
                            isStartingWaha = false,
                            wahaPairingError = "تعذر قطع اتصال واتساب."
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isStartingWaha = false,
                        wahaPairingError = "حدث خطأ غير متوقع أثناء قطع الاتصال."
                    )
                }
            }
        }
    }

    fun selectPairingTab(tab: String) {
        _uiState.update { it.copy(selectedPairingTab = tab, pairingCodeError = null) }
    }

    fun onPairingPhoneNumberChange(num: String) {
        val normalized = com.example.util.PhoneUtil.normalizeDigits(num)
        val clean = normalized.replace(Regex("[^0-9]"), "")
        _uiState.update { it.copy(pairingPhoneNumber = clean, pairingCodeError = null) }
    }

    fun requestPairingCode() {
        if (_uiState.value.isRequestingPairingCode || pairingJob?.isActive == true) {
            return
        }

        val phoneNumber = _uiState.value.pairingPhoneNumber
        if (phoneNumber.isBlank()) {
            _uiState.update { it.copy(pairingCodeError = "يرجى إدخال رقم الهاتف أولاً.") }
            return
        }

        val formattedPhone = com.example.util.PhoneUtil.formatForWhatsApp(phoneNumber)
        if (formattedPhone.length < 8) {
            _uiState.update { it.copy(pairingCodeError = "رقم الهاتف غير صالح. يرجى إدخال رقم هاتف صحيح مع رمز الدولة (مثال: 201012345678).") }
            return
        }

        _uiState.update { it.copy(isRequestingPairingCode = true, pairingCodeError = null, pairingCode = null) }

        pairingJob = viewModelScope.launch {
            try {
                val token = SupabaseClientProvider.client.auth.currentAccessTokenOrNull()

                val jsonObject = JSONObject()
                jsonObject.put("action", "REQUEST_PAIRING_CODE")
                jsonObject.put("phoneNumber", formattedPhone)
                val bodyString = jsonObject.toString()

                val requestBody = bodyString.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

                val requestBuilder = Request.Builder()
                    .url(wahaSessionUrl)
                    .post(requestBody)

                if (!token.isNullOrBlank()) {
                    requestBuilder.header("Authorization", "Bearer $token")
                }

                val response = withContext(ioDispatcher) {
                    okHttpClient.newCall(requestBuilder.build()).execute()
                }

                response.use { res ->
                    val responseStr = res.body?.string() ?: ""
                    val resJson = try {
                        if (responseStr.isNotBlank()) JSONObject(responseStr) else null
                    } catch (_: Exception) {
                        null
                    }

                    if (!res.isSuccessful) {
                        val code = res.code
                        val serverMsg = resJson?.optString("message")?.takeIf { it.isNotBlank() }
                        val wahaResp = resJson?.optJSONObject("waha_response")
                        val wahaArrayMsg = wahaResp?.optJSONArray("message")?.let { arr ->
                            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }.joinToString(", ").takeIf { it.isNotBlank() }
                        }
                        val wahaExcMsg = wahaResp?.optJSONObject("exception")?.optString("message")?.takeIf { it.isNotBlank() }
                        val wahaDetail = wahaResp?.optString("detail")?.takeIf { it.isNotBlank() }
                        val wahaRawStr = if (wahaResp == null) resJson?.optString("waha_response")?.takeIf { it.isNotBlank() && it != "null" } else null

                        val wahaMsg = wahaResp?.optString("message")?.takeIf { it.isNotBlank() }
                            ?: wahaArrayMsg
                            ?: wahaExcMsg
                            ?: wahaDetail
                            ?: wahaResp?.optString("error")?.takeIf { it.isNotBlank() }
                            ?: wahaRawStr

                        val errorDescription = when {
                            !serverMsg.isNullOrBlank() -> serverMsg
                            !wahaMsg.isNullOrBlank() -> "خادم واتساب: $wahaMsg"
                            code == 401 -> "غير مصرح بالدخول. يرجى تسجيل الدخول مرة أخرى."
                            code == 404 -> "خادم واتساب غير متوفر حالياً."
                            code == 502 -> "تعذر التواصل مع خادم واتساب (502 Bad Gateway)."
                            code == 503 -> "خادم واتساب قيد التهيئة حالياً. يرجى إعادة المحاولة."
                            code == 504 -> "انتهت مهلة الاتصال بخادم واتساب."
                            else -> "فشل طلب كود الربط. رمز الخطأ: $code"
                        }

                        _uiState.update {
                            it.copy(
                                isRequestingPairingCode = false,
                                pairingCodeError = errorDescription
                            )
                        }
                        return@launch
                    }

                    val success = resJson?.optBoolean("success", false) ?: false
                    if (!success) {
                        val msg = resJson?.optString("message")?.takeIf { it.isNotBlank() } ?: "فشل جلب كود الربط"
                        _uiState.update {
                            it.copy(
                                isRequestingPairingCode = false,
                                pairingCodeError = msg
                            )
                        }
                        return@launch
                    }

                    // Check if the session was already connected
                    val alreadyConnected = resJson?.optBoolean("alreadyConnected", false) ?: false
                    val status = resJson?.optString("status", "") ?: ""
                    if (alreadyConnected || status == "CONNECTED" || status == "WORKING") {
                        val connectedPhone = resJson?.optString("connected_phone")?.takeIf { it.isNotBlank() && it != "null" }
                        stopWahaPolling()
                        _uiState.update {
                            it.copy(
                                isRequestingPairingCode = false,
                                pairingCode = null,
                                wahaSessionStatus = "CONNECTED",
                                wahaConnectedPhone = connectedPhone ?: it.wahaConnectedPhone,
                                pairingCodeError = null
                            )
                        }
                        return@launch
                    }

                    var code = resJson?.optString("code", "")?.trim() ?: ""
                    if (code.isEmpty()) {
                        code = resJson?.optJSONObject("data")?.optString("code", "")?.trim() ?: ""
                    }
                    if (code.isEmpty()) {
                        code = resJson?.optJSONObject("waha_response")?.optString("code", "")?.trim() ?: ""
                    }
                    if (code.isEmpty()) {
                        code = resJson?.optString("pairingCode", "")?.trim() ?: ""
                    }

                    if (code.isBlank()) {
                        _uiState.update {
                            it.copy(
                                isRequestingPairingCode = false,
                                pairingCode = null,
                                pairingCodeError = "لم يتم استلام كود ربط صالح من الخادم."
                            )
                        }
                        return@launch
                    }

                    lastCopiedPairingCode = code
                    _uiState.update {
                        it.copy(
                            isRequestingPairingCode = false,
                            pairingCode = code,
                            isPairingCodeExpired = false,
                            wahaSessionStatus = "SCAN_QR_CODE"
                        )
                    }

                    startWahaPolling()
                }
            } catch (e: java.io.IOException) {
                _uiState.update {
                    it.copy(
                        isRequestingPairingCode = false,
                        pairingCodeError = "فشل الاتصال بالخادم. يرجى التحقق من الاتصال بالإنترنت."
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isRequestingPairingCode = false,
                        pairingCodeError = "حدث خطأ غير متوقع: ${e.localizedMessage ?: "خطأ داخلي"}"
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pairingJob?.cancel()
        stopWahaPolling()
    }
}
