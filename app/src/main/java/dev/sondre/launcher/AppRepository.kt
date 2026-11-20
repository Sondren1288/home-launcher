package dev.sondre.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.text.Collator
import java.util.concurrent.ConcurrentHashMap

data class AppEntry(
    /** Stable id: component (or pinned shortcut id) + user serial, so work-profile copies are distinct. */
    val key: String,
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    /**
     * Set for pinned shortcuts shown as apps, e.g. web apps added from Firefox with
     * "Add to home screen". [component] is then the publishing app's activity.
     */
    val shortcut: ShortcutInfo? = null,
    /**
     * Another home app (e.g. One UI Home), launched explicitly without changing the default
     * launcher. Only shown in search results, never in the sections.
     */
    val isOtherHome: Boolean = false,
) {
    val isShortcut get() = shortcut != null
}

class AppRepository(private val context: Context) {

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val infos = ConcurrentHashMap<String, LauncherActivityInfo>()
    private val icons = LruCache<String, ImageBitmap>(400)

    /**
     * All launchable activities across the user's profiles (incl. work profile), plus the
     * shortcuts pinned to this launcher (web apps), A–Z.
     */
    fun loadApps(): List<AppEntry> {
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        val result = mutableListOf<AppEntry>()
        infos.clear()
        for (user in userManager.userProfiles) {
            val serial = userManager.getSerialNumberForUser(user)
            for (info in launcherApps.getActivityList(null, user)) {
                if (info.componentName.packageName == context.packageName) continue
                val key = "${info.componentName.flattenToString()}#$serial"
                infos[key] = info
                result += AppEntry(key, info.label.toString(), info.componentName, user)
            }
            result += pinnedShortcuts(user, serial)
        }
        result += otherHomes()
        return result.sortedWith(compareBy(collator) { it.label })
    }

    /**
     * The phone's other home apps (One UI Home on Samsung), so search can open them as a
     * visit: pressing Home comes back here. Skips Settings' FallbackHome (boot placeholder).
     */
    private fun otherHomes(): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val me = Process.myUserHandle()
        val serial = userManager.getSerialNumberForUser(me)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo }
            .filter { it.packageName != context.packageName && it.packageName != "com.android.settings" }
            .map { info ->
                val component = ComponentName(info.packageName, info.name)
                AppEntry(
                    key = "home:${component.flattenToString()}#$serial",
                    label = info.loadLabel(pm).toString(),
                    component = component,
                    user = me,
                    isOtherHome = true,
                )
            }
    }

    /** Shortcuts pinned to *this* launcher. Android doesn't let us see other launchers' pins. */
    private fun pinnedShortcuts(user: UserHandle, serial: Long): List<AppEntry> {
        if (!hasShortcutAccess()) return emptyList()
        val query = LauncherApps.ShortcutQuery().setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        val shortcuts = runCatching { launcherApps.getShortcuts(query, user) }
            .onFailure { Log.w(TAG, "pinned shortcuts failed for $user", it) }
            .getOrNull().orEmpty()
        return shortcuts.filter { it.isEnabled && it.isPinned }.mapNotNull { s ->
            val label = (s.shortLabel ?: s.longLabel)?.toString()?.trim()
            if (label.isNullOrEmpty()) return@mapNotNull null
            AppEntry(
                key = "pin:${s.`package`}/${s.id}#$serial",
                label = label,
                component = s.activity ?: ComponentName(s.`package`, ""),
                user = user,
                shortcut = s,
            )
        }
    }

    private fun hasShortcutAccess() = runCatching { launcherApps.hasShortcutHostPermission() }.getOrDefault(false)

    fun launch(app: AppEntry, sourceBounds: Rect? = null) {
        val s = app.shortcut
        if (s != null) {
            launcherApps.startShortcut(s, sourceBounds, null)
        } else if (app.isOtherHome) {
            // Not a LAUNCHER activity, so LauncherApps won't start it; an explicit HOME intent will.
            context.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setComponent(app.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } else {
            launcherApps.startMainActivity(app.component, app.user, sourceBounds, null)
        }
    }

    /** Unpin a shortcut entry (it disappears from the launcher; the publishing app is untouched). */
    fun removeShortcut(app: AppEntry) {
        val s = app.shortcut ?: return
        val query = LauncherApps.ShortcutQuery()
            .setPackage(s.`package`)
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        val keep = launcherApps.getShortcuts(query, app.user).orEmpty()
            .filter { it.isPinned && it.id != s.id }
            .map { it.id }
        // pinShortcuts replaces this launcher's whole pinned set for the package.
        launcherApps.pinShortcuts(s.`package`, keep, app.user)
    }

    fun openAppInfo(app: AppEntry) {
        if (app.isShortcut || app.isOtherHome) {
            // Shortcut: no app of its own, so show the publishing app (e.g. Firefox) instead.
            // Other home app: not a LAUNCHER activity, which startAppDetailsActivity requires.
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.component.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } else {
            launcherApps.startAppDetailsActivity(app.component, app.user, null, null)
        }
    }

    fun uninstall(app: AppEntry) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.component.packageName, null))
            .putExtra(Intent.EXTRA_USER, app.user)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun cachedIcon(app: AppEntry, sizePx: Int): ImageBitmap? = icons.get("${app.key}@$sizePx")

    /** Blocking; call off the main thread. */
    fun loadIcon(app: AppEntry, sizePx: Int): ImageBitmap? {
        val cacheKey = "${app.key}@$sizePx"
        icons.get(cacheKey)?.let { return it }
        val raw = app.shortcut?.let { s ->
            runCatching { launcherApps.getShortcutIconDrawable(s, context.resources.displayMetrics.densityDpi) }.getOrNull()
        } ?: infos[app.key]?.getIcon(0)
            ?: if (app.isOtherHome) runCatching { context.packageManager.getActivityIcon(app.component) }.getOrNull() else null
        if (raw == null) return null
        var bitmap = renderSquare(raw, sizePx)
        if (app.user != Process.myUserHandle()) {
            // Add the briefcase badge for work-profile apps.
            val badged = context.packageManager.getUserBadgedIcon(BitmapDrawable(context.resources, bitmap), app.user)
            bitmap = badged.toBitmap(sizePx, sizePx)
        }
        return bitmap.asImageBitmap().also { icons.put(cacheKey, it) }
    }

    /**
     * Render an icon as a full-bleed square, ignoring the OEM mask shape.
     * Adaptive icon layers are 108dp with the centre 72dp visible, so each layer is drawn at
     * 1.5x the target size, centred, and the overflow gets clipped by the bitmap edge.
     */
    private fun renderSquare(drawable: Drawable, sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (drawable is AdaptiveIconDrawable) {
            val inset = sizePx / 4
            val bounds = Rect(-inset, -inset, sizePx + inset, sizePx + inset)
            for (layer in listOfNotNull(drawable.background, drawable.foreground)) {
                layer.bounds = bounds
                layer.draw(canvas)
            }
        } else {
            // Legacy icons already have their own shape; draw them as-is.
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(canvas)
        }
        return bitmap
    }

    /**
     * Calls [onChange] on the main thread whenever apps are installed, removed or updated,
     * and [onShortcutsChanged] when an app publishes or removes shortcuts.
     */
    fun registerCallback(onChange: () -> Unit, onShortcutsChanged: () -> Unit): LauncherApps.Callback {
        val callback = object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) = changed()
            override fun onPackageAdded(packageName: String, user: UserHandle) = changed()
            override fun onPackageChanged(packageName: String, user: UserHandle) = changed()
            override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed()
            override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed()
            override fun onShortcutsChanged(packageName: String, shortcuts: MutableList<ShortcutInfo>, user: UserHandle) =
                onShortcutsChanged()

            private fun changed() {
                icons.evictAll() // an update may have changed the icon
                onChange()
            }
        }
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        return callback
    }

    fun unregisterCallback(callback: LauncherApps.Callback) = launcherApps.unregisterCallback(callback)

    private companion object {
        const val TAG = "AppRepository"
    }
}
