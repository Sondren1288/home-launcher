package dev.sondre.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log

/**
 * Pulls down the notification shade.
 *
 * There's no public API for this; launchers (Nova, Lawnchair, Niagara…) call the hidden
 * `StatusBarManager.expandNotificationsPanel()` by reflection, which needs the normal
 * (install-time) EXPAND_STATUS_BAR permission. Returns false if the platform refused.
 */
@SuppressLint("WrongConstant")
fun expandNotifications(context: Context): Boolean = try {
    val sbm = context.getSystemService("statusbar") ?: error("no statusbar service")
    sbm.javaClass.getMethod("expandNotificationsPanel").invoke(sbm)
    true
} catch (e: Exception) {
    Log.w("Notifications", "couldn't expand the notification shade", e)
    false
}
