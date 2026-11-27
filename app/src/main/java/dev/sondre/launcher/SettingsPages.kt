package dev.sondre.launcher

/**
 * Settings screens that search can jump straight to. They show up as sub-results under the
 * Settings app: typing "wifi" (or "wlan", "trådløst"…) offers Settings › Wi-Fi.
 *
 * [keywords] are extra words that find the page, in English and Norwegian, since the phone's
 * own Settings use Norwegian names. Pages the phone can't open are dropped when loading.
 */
data class SettingsPage(
    val id: String,
    val label: String,
    /** The intent action that opens the page. */
    val action: String,
    val keywords: List<String> = emptyList(),
)

object SettingsPages {
    private const val S = "android.settings."

    val all = listOf(
        SettingsPage("wifi", "Wi-Fi", S + "WIFI_SETTINGS", listOf("wifi", "wlan", "trådløst", "nettverk", "internett", "network")),
        SettingsPage("bluetooth", "Bluetooth", S + "BLUETOOTH_SETTINGS", listOf("bt", "headset", "hodetelefoner", "headphones")),
        SettingsPage("connections", "Connections", S + "WIRELESS_SETTINGS", listOf("tilkoblinger", "mobilnett", "mobile network", "sim", "nettverk", "network")),
        SettingsPage("hotspot", "Hotspot and tethering", S + "TETHER_SETTINGS", listOf("tilkoblingspunkt", "tjoring", "deling", "hotspot")),
        SettingsPage("data-usage", "Data usage", S + "DATA_USAGE_SETTINGS", listOf("databruk", "mobildata", "mobile data")),
        SettingsPage("airplane", "Airplane mode", S + "AIRPLANE_MODE_SETTINGS", listOf("flymodus", "flight mode")),
        SettingsPage("nfc", "NFC and payments", S + "NFC_SETTINGS", listOf("betaling", "payment", "kontaktløs", "contactless")),
        SettingsPage("vpn", "VPN", S + "VPN_SETTINGS"),
        SettingsPage("cast", "Cast / Smart View", S + "CAST_SETTINGS", listOf("caste", "smart view", "skjermdeling", "chromecast")),
        SettingsPage("display", "Display", S + "DISPLAY_SETTINGS", listOf("skjerm", "lysstyrke", "brightness", "skjermtid", "screen timeout", "font", "skrift")),
        SettingsPage("dark", "Dark mode", S + "DARK_THEME_SETTINGS", listOf("mørk modus", "mørkt tema", "dark theme")),
        SettingsPage("eye-comfort", "Eye comfort / night light", S + "NIGHT_DISPLAY_SETTINGS", listOf("øyekomfort", "nattlys", "blått lys", "blue light")),
        SettingsPage("wallpaper", "Wallpaper", "android.intent.action.SET_WALLPAPER", listOf("bakgrunn", "bakgrunnsbilde", "background")),
        SettingsPage("sound", "Sound", S + "SOUND_SETTINGS", listOf("lyd", "volum", "volume", "ringetone", "ringtone", "vibrasjon", "vibration")),
        SettingsPage("notifications", "Notifications", S + "NOTIFICATION_SETTINGS", listOf("varsler", "varslinger")),
        SettingsPage("app-notifications", "App notifications", S + "ALL_APPS_NOTIFICATION_SETTINGS", listOf("appvarsler", "varsler", "notifications")),
        SettingsPage("dnd", "Do not disturb", S + "ZEN_MODE_SETTINGS", listOf("ikke forstyrr", "dnd", "stille", "silent", "modes", "moduser")),
        SettingsPage("battery", "Battery", "android.intent.action.POWER_USAGE_SUMMARY", listOf("batteri", "strøm", "power", "lading", "charging")),
        SettingsPage("battery-saver", "Battery saver", S + "BATTERY_SAVER_SETTINGS", listOf("strømsparing", "batterisparing", "power saving")),
        SettingsPage("storage", "Storage", S + "INTERNAL_STORAGE_SETTINGS", listOf("lagring", "plass", "space", "minne")),
        SettingsPage("apps", "Apps", S + "APPLICATION_SETTINGS", listOf("apper", "programmer", "applications")),
        SettingsPage("default-apps", "Default apps", S + "MANAGE_DEFAULT_APPS_SETTINGS", listOf("standardapper", "standard", "browser", "nettleser")),
        SettingsPage("home-app", "Home app", S + "HOME_SETTINGS", listOf("startskjerm", "launcher", "hjem", "home screen")),
        SettingsPage("location", "Location", S + "LOCATION_SOURCE_SETTINGS", listOf("posisjon", "plassering", "gps")),
        SettingsPage("security", "Security and privacy", S + "SECURITY_SETTINGS", listOf("sikkerhet", "personvern", "lås", "lock", "fingeravtrykk", "fingerprint", "face", "ansikt", "pin", "passord", "password")),
        SettingsPage("privacy", "Privacy", S + "PRIVACY_SETTINGS", listOf("personvern", "tillatelser", "permissions")),
        SettingsPage("accounts", "Accounts and sync", S + "SYNC_SETTINGS", listOf("kontoer", "konto", "synkronisering", "google")),
        SettingsPage("accessibility", "Accessibility", S + "ACCESSIBILITY_SETTINGS", listOf("tilgjengelighet")),
        SettingsPage("language", "Language", S + "LOCALE_SETTINGS", listOf("språk", "locale")),
        SettingsPage("keyboard", "Keyboard", S + "INPUT_METHOD_SETTINGS", listOf("tastatur", "input")),
        SettingsPage("date-time", "Date and time", S + "DATE_SETTINGS", listOf("dato", "tid", "klokke", "time", "tidssone", "timezone")),
        SettingsPage("about", "About phone", S + "DEVICE_INFO_SETTINGS", listOf("om telefonen", "telefoninfo", "device info", "software", "programvare", "imei")),
        SettingsPage("developer", "Developer options", S + "APPLICATION_DEVELOPMENT_SETTINGS", listOf("utvikleralternativer", "utvikler", "usb debugging", "usb-feilsøking", "adb")),
    )
}
