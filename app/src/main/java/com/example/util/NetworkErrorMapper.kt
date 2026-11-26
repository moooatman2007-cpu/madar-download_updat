package com.example.util

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import java.util.concurrent.TimeoutException

fun Throwable.isOfflineOrNetworkError(): Boolean {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 8) {
        if (current is UnknownHostException ||
            current is ConnectException ||
            current is SocketTimeoutException ||
            current is SocketException ||
            current is TimeoutException ||
            current is UnresolvedAddressException ||
            current is IOException
        ) {
            return true
        }
        val text = "${current.message.orEmpty()} ${current.localizedMessage.orEmpty()}".lowercase()
        if (text.contains("unable to resolve host") ||
            text.contains("failed to connect") ||
            text.contains("no address associated with hostname") ||
            text.contains("eai_nodata") ||
            text.contains("econnrefused") ||
            text.contains("enetunreach") ||
            text.contains("network is unreachable") ||
            text.contains("network error") ||
            text.contains("offline") ||
            text.contains("timeout") ||
            text.contains("timed out") ||
            text.contains("connection reset") ||
            text.contains("connection closed") ||
            text.contains("placeholder.supabase.co")
        ) {
            return true
        }
        current = current.cause
        depth++
    }
    return false
}

fun Throwable.toArabicErrorMessage(): String {
    val messages = buildString {
        var current: Throwable? = this@toArabicErrorMessage
        var depth = 0
        while (current != null && depth < 8) {
            append(current.message.orEmpty()).append(' ')
            append(current.localizedMessage.orEmpty()).append(' ')
            current = current.cause
            depth++
        }
    }
    val fullText = messages.lowercase()
    val localized = this.localizedMessage ?: ""

    return when {
        hasCause<UnknownHostException>() ||
            hasCause<ConnectException>() ||
            hasCause<UnresolvedAddressException>() ||
            fullText.contains("unable to resolve host") ||
            fullText.contains("failed to connect") ||
            fullText.contains("no address associated with hostname") ||
            fullText.contains("network is unreachable") ||
            fullText.contains("offline") ||
            fullText.contains("placeholder.supabase.co") ->
            "لا يوجد اتصال بالإنترنت، يرجى التحقق من الشبكة والمحاولة مرة أخرى."

        hasCause<TimeoutException>() ||
            hasCause<SocketTimeoutException>() ||
            fullText.contains("timeout") ||
            fullText.contains("timed out") ->
            "استغرقت العملية وقتًا أطول من المتوقع، يرجى إعادة المحاولة."

        isOfflineOrNetworkError() ->
            "تعذر الاتصال بالسيرفر، يرجى التحقق من اتصال الإنترنت والمحاولة مرة أخرى."

        fullText.contains("invalid login credentials") ->
            "اسم المستخدم أو كلمة المرور غير صحيحة."

        fullText.contains("user already registered") ->
            "هذا البريد الإلكتروني مسجل بالفعل."

        else ->
            if (localized.isNotBlank() && !localized.contains("Exception") && !localized.contains("http") && !localized.contains("io.ktor")) {
                localized
            } else {
                "حدث خطأ أثناء الاتصال بالسيرفر، يرجى المحاولة لاحقًا."
            }
    }
}

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 8) {
        if (current is T) return true
        current = current.cause
        depth++
    }
    return false
}
