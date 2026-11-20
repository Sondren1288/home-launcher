package dev.sondre.launcher

import android.app.Activity
import android.content.pm.LauncherApps
import android.os.Bundle
import android.widget.Toast

/**
 * Receives "Add to home screen" requests (e.g. installing a web app from Firefox). Android
 * routes these to the default launcher; we accept straight away and the shortcut then shows up
 * in All apps like any other app. Invisible: it finishes immediately.
 */
class PinShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = getSystemService(LauncherApps::class.java).getPinItemRequest(intent)
        if (request != null &&
            request.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT &&
            request.isValid
        ) {
            val accepted = runCatching { request.accept() }.getOrDefault(false)
            val label = request.shortcutInfo?.shortLabel ?: "Shortcut"
            Toast.makeText(this, if (accepted) "Added $label" else "Couldn't add $label", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
