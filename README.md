# home-launcher

A minimal Android home screen: search bar on top, then **Favorites**, **Most used**, and
**All apps (A–Z)** as a plain list of `[square icon] App name` rows over the wallpaper.

- Type to filter. Matching ignores case and accents and ranks: prefix > word prefix > initials
  (`gm` → Google Maps) > substring > subsequence. Ties go to the app you launch most.
  Press Go/Enter on the keyboard to open the top result.
- Long-press an app to add or remove it from favorites, open App info, or uninstall it.
- Pressing Home while the launcher is showing clears the search and scrolls to the top.
- Back never leaves the launcher.
- Work-profile apps are included and shown with the briefcase badge.
- Apps that are installed, removed or updated show up right away.
- "Most used" comes from launch counts the launcher records itself, so no special permission is needed.

## Layout

| File | Role |
|---|---|
| `app/src/main/java/dev/sondre/launcher/AppSearch.kt` | Pure ranking and sections logic (unit tested) |
| `.../AppRepository.kt` | App list (`LauncherApps`), launching, square icon rendering, change callbacks |
| `.../UsageStore.kt` | Favorites and launch counts (SharedPreferences) |
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
