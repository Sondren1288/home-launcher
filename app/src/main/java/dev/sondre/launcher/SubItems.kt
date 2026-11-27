package dev.sondre.launcher

import android.Manifest
import android.accounts.AccountManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.ContactsContract
import android.util.Log

/** What a search sub-result opens when tapped. */
sealed interface SubTarget {
    /** An app shortcut, e.g. a WhatsApp chat or "New incognito tab". */
    data class Shortcut(val info: ShortcutInfo) : SubTarget

    /** A contact card in the Contacts app. */
    data class ContactCard(val contactId: Long, val lookupKey: String) : SubTarget

    /** An app-specific contact action, e.g. "Message +47…" in WhatsApp. */
    data class ContactAction(val dataId: Long, val mimeType: String, val packageName: String) : SubTarget

    /** A Settings screen, opened by its intent action. */
    data class Settings(val action: String) : SubTarget
}

/** A sub-result before it's been attached to an [AppEntry]. */
data class RawSubItem(
    val id: String,
    val label: String,
    val packageName: String,
    val user: UserHandle,
    /** The specific launcher activity it belongs to, if known. */
    val component: ComponentName?,
    val target: SubTarget,
    /** Extra words that find it besides [label] (settings pages: "wlan" → Wi-Fi). */
    val keywords: List<String> = emptyList(),
)

/** A sub-result shown indented beneath the app it belongs to. */
data class SubEntry(
    val id: String,
    val label: String,
    val ownerKey: String,
    val target: SubTarget,
    val keywords: List<String> = emptyList(),
)

/** Attach each item to the app it belongs to; items whose app isn't in [apps] are dropped. */
fun attachToApps(items: List<RawSubItem>, apps: List<AppEntry>): List<SubEntry> {
    // Pinned shortcuts share their publisher's component; never attach children to them.
    val real = apps.filterNot { it.isShortcut }
    val byComponent = real.associateBy { it.component to it.user }
    val byPackage = real.groupBy { it.component.packageName to it.user }
    return items.mapNotNull { item ->
        val owner = item.component?.let { byComponent[it to item.user] }
            ?: byPackage[item.packageName to item.user]?.firstOrNull()
        owner?.let { SubEntry(item.id, item.label, it.key, item.target, item.keywords) }
    }
}

/** Loads app shortcuts and contacts for search, and launches them. */
class SubItemSource(private val context: Context) {

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val pm = context.packageManager

    fun hasContactsPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Shortcuts are only readable while we're the default home app. Blocking. */
    fun loadShortcuts(): List<RawSubItem> {
        if (!runCatching { launcherApps.hasShortcutHostPermission() }.getOrDefault(false)) return emptyList()
        var flags = LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
            LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
        // Pinned shortcuts are left out on purpose: they're listed as apps of their own.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Conversation shortcuts kept around by the system (recent chats).
            flags = flags or LauncherApps.ShortcutQuery.FLAG_MATCH_CACHED
        }
        val query = LauncherApps.ShortcutQuery().setQueryFlags(flags)
        return userManager.userProfiles.flatMap { user ->
            val serial = userManager.getSerialNumberForUser(user)
            // Throws while e.g. a work profile is locked; just skip that profile.
            val shortcuts = runCatching { launcherApps.getShortcuts(query, user) }
                .onFailure { Log.w(TAG, "getShortcuts failed for $user", it) }
                .getOrNull().orEmpty()
            shortcuts.filter { it.isEnabled }.mapNotNull { s ->
                val label = (s.shortLabel ?: s.longLabel)?.toString()?.trim()
                if (label.isNullOrEmpty()) return@mapNotNull null
                RawSubItem("sc:$serial/${s.`package`}/${s.id}", label, s.`package`, user, s.activity, SubTarget.Shortcut(s))
            }
        }.distinctBy { it.id } // a shortcut can be both dynamic and pinned
    }

    /**
     * Every contact (under the Contacts app), plus the app actions other apps attach to
     * contacts (WhatsApp, Signal, Telegram…) under those apps. Blocking.
     */
    fun loadContacts(): List<RawSubItem> {
        if (!hasContactsPermission()) return emptyList()
        return runCatching { contactCards() + contactActions() }
            .onFailure { Log.w(TAG, "loading contacts failed", it) }
            .getOrDefault(emptyList())
    }

    private fun contactCards(): List<RawSubItem> {
        val contactsPkg = contactsAppPackage() ?: return emptyList()
        val me = Process.myUserHandle()
        val out = mutableListOf<RawSubItem>()
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(2)?.trim()
                if (name.isNullOrEmpty()) continue
                out += RawSubItem("ct:$id", name, contactsPkg, me, null, SubTarget.ContactCard(id, c.getString(1).orEmpty()))
            }
        }
        return out
    }

    private fun contactActions(): List<RawSubItem> {
        // Account type (e.g. "com.whatsapp") -> the package that owns it.
        val accountOwner = AccountManager.get(context).authenticatorTypes.associate { it.type to it.packageName }
        val contactsPkg = contactsAppPackage()
        val handles = HashMap<Pair<String, String>, Boolean>()
        val me = Process.myUserHandle()

        data class Row(val dataId: Long, val contactId: Long, val name: String, val mime: String, val pkg: String)
        val rows = mutableListOf<Row>()
        val placeholders = STANDARD_MIMETYPES.joinToString(",") { "?" }
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data._ID,
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Data.MIMETYPE,
                ContactsContract.RawContacts.ACCOUNT_TYPE,
            ),
            "${ContactsContract.Data.MIMETYPE} NOT IN ($placeholders)",
            STANDARD_MIMETYPES.toTypedArray(),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(2)?.trim()
                val mime = c.getString(3) ?: continue
                val pkg = accountOwner[c.getString(4) ?: continue] ?: continue
                if (name.isNullOrEmpty() || pkg == contactsPkg) continue
                // Only keep actions the owning app can actually open.
                val ok = handles.getOrPut(pkg to mime) { canHandle(pkg, mime) }
                if (ok) rows += Row(c.getLong(0), c.getLong(1), name, mime, pkg)
            }
        }
        // One row per contact per app: prefer chat/profile over voice/video call.
        return rows.groupBy { it.contactId to it.pkg }.values.map { group ->
            val r = group.minWith(compareBy({ actionRank(it.mime) }, { it.dataId }))
            RawSubItem("ca:${r.pkg}/${r.contactId}", r.name, r.pkg, me, null, SubTarget.ContactAction(r.dataId, r.mime, r.pkg))
        }
    }

    private fun actionRank(mime: String): Int {
        val m = mime.lowercase()
        return when {
            "call" in m || "voip" in m || "video" in m -> 1
            else -> 0
        }
    }

    private fun canHandle(pkg: String, mime: String): Boolean {
        val probe = Intent(Intent.ACTION_VIEW)
            .setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, 1), mime)
            .setPackage(pkg)
        return pm.queryIntentActivities(probe, 0).isNotEmpty()
    }

    /**
     * The [SettingsPages] this phone can open, under the Settings app. Pages handled by another
     * system app (default apps → permission controller) still sit under Settings. Blocking.
     */
    fun loadSettingsPages(): List<RawSubItem> {
        val settingsPkg = pm.resolveActivity(Intent(android.provider.Settings.ACTION_SETTINGS), 0)
            ?.activityInfo?.packageName ?: return emptyList()
        val me = Process.myUserHandle()
        return SettingsPages.all
            .filter { pm.resolveActivity(Intent(it.action), 0) != null }
            .map { RawSubItem("set:${it.id}", it.label, settingsPkg, me, null, SubTarget.Settings(it.action), it.keywords) }
    }

    /** The default Contacts app, which owns plain contact results. */
    private fun contactsAppPackage(): String? {
        val intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS)
        val resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        // "android" means the chooser: there's no single default, so take the first candidate.
        return resolved?.takeIf { it != "android" }
            ?: pm.queryIntentActivities(intent, 0).firstOrNull()?.activityInfo?.packageName
    }

    /** Returns false if it couldn't be opened. */
    fun launch(target: SubTarget): Boolean = try {
        when (target) {
            is SubTarget.Shortcut -> launcherApps.startShortcut(target.info, null, null)
            is SubTarget.ContactCard -> context.startActivity(
                Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.getLookupUri(target.contactId, target.lookupKey))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            is SubTarget.ContactAction -> context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, target.dataId), target.mimeType)
                    .setPackage(target.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            is SubTarget.Settings -> context.startActivity(
                Intent(target.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: IllegalStateException) { // shortcut no longer exists / user locked
        false
    } catch (e: SecurityException) {
        false
    }

    private companion object {
        const val TAG = "SubItemSource"

        /** Built-in contact data kinds; anything else was added by an app. */
        val STANDARD_MIMETYPES = listOf(
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE,
            "vnd.android.cursor.item/im", // CommonDataKinds.Im (deprecated constant)
            ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE,
            "vnd.android.cursor.item/sip_address", // CommonDataKinds.SipAddress (deprecated constant)
            ContactsContract.CommonDataKinds.Identity.CONTENT_ITEM_TYPE,
        )
    }
}
