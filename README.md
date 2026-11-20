# home-launcher

A minimal Android home screen: collapsible sections — **Favorites**,
**Most used**, your **groups**, and **Other apps (A–Z)** — as a plain list of
`[square icon] App name` rows over the wallpaper, with a full-width search bar at the bottom.

- Every section starts collapsed; tap a header (or its ⌄ arrow) to expand it.
- Type to filter. Search covers all apps, regardless of what's collapsed. Matching ignores case and accents and ranks: prefix > word prefix > initials
  (`gm` → Google Maps) > substring > subsequence. Ties go to the app you launch most.
  Press Go/Enter on the keyboard to open the top result.
- Search also finds things *inside* apps and shows them indented under the app they belong to
  (`dorian` → WhatsApp / Dorian, opening that chat):
  - **App shortcuts** (recent chats, "New incognito tab"…). Needs this to be the default home app.
  - **Contacts** (optional; asked for in the search results): every contact under the Contacts app,
    plus the actions apps attach to contacts (WhatsApp, Signal, Telegram…) under those apps.
  These only match on name/word prefixes, to keep a big contact list from flooding the results.
- Long-press an app to add or remove it from favorites, move it to a group (or a new one), open App info, or uninstall it.
  An app is in at most one group; grouped apps leave "Other apps" but can still be favorites.
- Long-press a group header to rename, reorder or delete it (its apps return to "Other apps").
- Pressing Home while the launcher is showing clears the search, collapses everything and scrolls to the top.
- Back never leaves the launcher.
- Work-profile apps are included and shown with the briefcase badge.
- Apps that are installed, removed or updated show up right away.
- "Most used" comes from launch counts the launcher records itself, so no special permission is needed.

## Layout

| File | Role |
|---|---|
| `app/src/main/java/dev/sondre/launcher/AppSearch.kt` | Pure ranking and sections logic (unit tested) |
| `.../AppRepository.kt` | App list (`LauncherApps`), launching, square icon rendering, change callbacks |
| `.../UsageStore.kt` | Favorites, groups and launch counts (SharedPreferences) |
| `.../SubItems.kt` | In-app search sources: app shortcuts and contacts, and launching them |
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
