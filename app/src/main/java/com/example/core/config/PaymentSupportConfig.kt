package com.example.core.config

import com.example.util.PhoneUtil

/**
 * Central configuration for subscription transfer number and admin WhatsApp contact.
 * Kept in a single source of truth so numbers are never duplicated across screens.
 */
object PaymentSupportConfig {
    const val TRANSFER_NUMBER: String = "01040129414"

    val WHATSAPP_TARGET_E164_NO_PLUS: String
        get() = PhoneUtil.formatForWhatsApp(TRANSFER_NUMBER)

    val WHATSAPP_TARGET_JID: String
        get() = "${WHATSAPP_TARGET_E164_NO_PLUS}@s.whatsapp.net"
}
