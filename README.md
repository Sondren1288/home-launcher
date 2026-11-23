# home-launcher

A minimal Android home screen: collapsible sections — **Folders** (your groups), **Most used** and
**All apps (A–Z)** — as a plain list of
`[square icon] App name` rows over the wallpaper, with a full-width search bar at the bottom.

- Every section starts collapsed and shows only a dark, sharp-cornered tile with a chevron (›) and an icon:
  📁 Folders, 🕓 Most used, ⊞ All apps. Tap it to expand and see the title.
  Swiping up on the home screen opens Folders and Most used; swiping down pulls down the
  notification shade (both only while nothing is open). Back closes whatever is open.
  Swiping right (anywhere, when not searching) opens every section and every folder; swiping left
  closes the open folders and All apps (back to the swipe-up state), and a second swipe left closes everything.
  All apps has a small `B ──── B` divider before each new letter.
  Folders and Most used are always shown, even when empty.
  The wallpaper is shown as-is until a section is expanded or the search is in use, then it's darkened.
- Tapping anywhere outside the search bar, or hiding the keyboard, ends the search focus (no blinking cursor).
- A large clock (JetBrains Mono Bold) with the date sits to the right of the collapsed section tiles; tap it to open
  the alarms in your clock app. It's drawn behind the list and fades out when a section opens or you search.
- Type to filter. Results grow upwards from the search bar, best match at the bottom (next to your thumb).
  Search covers all apps, regardless of what's collapsed. Matching ignores case and accents and ranks: prefix > word prefix > initials
  (`gm` → Google Maps) > substring > subsequence. Ties go to the app you launch most.
  Press Go/Enter on the keyboard to open the top result.
- Search also finds things *inside* apps and shows them indented under the app they belong to
  (a contact's name → WhatsApp / that contact, opening the chat):
  - **App shortcuts** (recent chats, "New incognito tab"…). Needs this to be the default home app.
  - **Contacts** (optional; asked for in the search results): every contact under the Contacts app,
    plus the actions apps attach to contacts (WhatsApp, Signal, Telegram…) under those apps.
  These only match on name/word prefixes, to keep a big contact list from flooding the results.
- Long-press an app to move it to a group (or a new one), open App info, or uninstall it.
  An app is in at most one group, and stays in All apps as well.
- Groups are the folders in the Folders section: tap to open (apps shown indented), long-press to rename,
  reorder or delete (the apps themselves are untouched).
- Long-press menus and dialogs are wide (90% of the screen) with sharp corners.
- Pressing Home while the launcher is showing clears the search, collapses everything and scrolls to the top.
- Back never leaves the launcher.
- **Double-tap the empty area** (below the tiles/clock, above the search bar, nothing open) to lock the phone.
  Needs "Home: double-tap to lock" switched on once under Settings → Accessibility → Installed apps
  (an accessibility service whose only job is the lock action; fingerprint/face unlock keep working).
  The first double-tap without it shows a dialog that links there.
- **Web apps / "Add to home screen"** (e.g. Firefox): the launcher accepts these pin requests and lists
  them in All apps like any app (groups and search work). Long-press → "Remove from launcher".
  Android only shows a launcher the shortcuts pinned *to it*, so ones added while another launcher was
  the default have to be added again from the browser.
- Work-profile apps are included and shown with the briefcase badge.
- Apps that are installed, removed or updated show up right away.
- "Most used" comes from launch counts the launcher records itself, so no special permission is needed.

## Layout

| File | Role |
|---|---|
| `app/src/main/java/dev/sondre/launcher/AppSearch.kt` | Pure ranking and sections logic (unit tested) |
| `.../AppRepository.kt` | App list (`LauncherApps`), launching, square icon rendering, change callbacks |
| `.../UsageStore.kt` | Groups and launch counts (SharedPreferences) |
| `.../SubItems.kt` | In-app search sources: app shortcuts and contacts, and launching them |
| `.../LockService.kt` | Accessibility service used only to lock the screen (double-tap) |
| `.../Clock.kt` | Home-screen clock (minute ticks, locale date) and opening the alarms list |
| `.../LauncherViewModel.kt` | Combines the above into `UiState` |
| `.../LauncherScreen.kt` | Compose UI. Tweak sizes and colors in `Style` at the top |
| `.../MainActivity.kt` | HOME activity, edge-to-edge, Home-press reset |

## Build and install

`flake.nix` provides JDK 17, the Android SDK (platform 35, build-tools 34.0.0) and `adb`.
The Nix store is read-only, so if you bump `compileSdk` or the Android Gradle plugin, update
`platformVersion` / `buildToolsVersion` in `flake.nix` to match.

```sh
nix develop                  # enter the dev shell
./gradlew test               # unit tests
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug       # with a phone connected over adb (USB debugging on)
```

Then press Home on the phone and choose **Home**, or go to Settings → Apps → Default apps → Home app.
If you need to get out, switch back to the stock launcher in the same settings screen.
