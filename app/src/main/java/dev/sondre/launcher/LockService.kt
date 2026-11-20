package dev.sondre.launcher

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Exists only to lock the screen (double-tap on the home screen).
 *
 * Android has no permission-free "lock now" for normal apps. The two options are a device admin
 * (`DevicePolicyManager.lockNow()`, which then demands the PIN instead of allowing fingerprint/face
 * unlock) or an accessibility service's GLOBAL_ACTION_LOCK_SCREEN, which locks exactly like the
 * power button. This is the latter. It reads no screen content and receives no events.
 * The user has to switch it on once in Settings → Accessibility.
 */
class LockService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        @Volatile
        private var instance: LockService? = null

        /** Locks the screen. Returns false if the service isn't switched on. */
        fun lock(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false

        /** Opens Settings → Accessibility so the user can switch the service on. */
        fun openSettings(context: Context) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: ActivityNotFoundException) {
            }
        }
    }
}
