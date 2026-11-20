package dev.sondre.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
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
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.text.Collator
import java.util.concurrent.ConcurrentHashMap

data class AppEntry(
    /** Stable id: component + user serial, so work-profile copies are distinct. */
    val key: String,
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
)

class AppRepository(private val context: Context) {

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val infos = ConcurrentHashMap<String, LauncherActivityInfo>()
    private val icons = LruCache<String, ImageBitmap>(400)

    /** All launchable activities across the user's profiles (incl. work profile), A–Z. */
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
        }
        return result.sortedWith(compareBy(collator) { it.label })
    }

    fun launch(app: AppEntry, sourceBounds: Rect? = null) {
        launcherApps.startMainActivity(app.component, app.user, sourceBounds, null)
    }

    fun openAppInfo(app: AppEntry) {
        launcherApps.startAppDetailsActivity(app.component, app.user, null, null)
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
        val info = infos[app.key] ?: return null
        val raw = info.getIcon(0) ?: return null
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

    /** Calls [onChange] on the main thread whenever apps are installed, removed or updated. */
    fun registerCallback(onChange: () -> Unit): LauncherApps.Callback {
        val callback = object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) = changed()
            override fun onPackageAdded(packageName: String, user: UserHandle) = changed()
            override fun onPackageChanged(packageName: String, user: UserHandle) = changed()
            override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed()
            override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed()

            private fun changed() {
                icons.evictAll() // an update may have changed the icon
                onChange()
            }
        }
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        return callback
    }

    fun unregisterCallback(callback: LauncherApps.Callback) = launcherApps.unregisterCallback(callback)
}
