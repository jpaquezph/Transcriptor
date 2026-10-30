package com.transcriptor.app.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Android no deja que una app entre a los chats de otra. Lo que sí se puede es abrir WhatsApp para
 * compartir desde el chat, o abrir el selector del sistema en su carpeta de notas de voz.
 */
object WhatsApp {
    private const val STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val PACKAGE = "com.whatsapp"
    private const val BUSINESS_PACKAGE = "com.whatsapp.w4b"
    private const val VOICE_NOTES = "primary:Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes"
    private const val BUSINESS_VOICE_NOTES =
        "primary:Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Voice Notes"

    fun launchIntent(packageManager: PackageManager): Intent? =
        packageManager.getLaunchIntentForPackage(PACKAGE)
            ?: packageManager.getLaunchIntentForPackage(BUSINESS_PACKAGE)

    fun voiceNotesUri(packageManager: PackageManager): Uri {
        val business = packageManager.getLaunchIntentForPackage(PACKAGE) == null &&
            packageManager.getLaunchIntentForPackage(BUSINESS_PACKAGE) != null
        val folder = if (business) BUSINESS_VOICE_NOTES else VOICE_NOTES
        return DocumentsContract.buildDocumentUri(STORAGE_AUTHORITY, folder)
    }
}
