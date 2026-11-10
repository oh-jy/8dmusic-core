# Handoff: Exit Dialog, Nav Order And FM Position

- Time: 2026-11-05 09:34:50 +08:00
- Thread: `01a1a94f-0b7a-7cc2-9600-0b32c6b9753a`
- Scope: exit confirmation dialog copy, top navigation order, and reopening the FM list at the active station

## Requested Changes

1. Exit dialog title: `请确认`.
2. Exit dialog body: `请确认，选择退出播放或转入后台`.
3. Exit dialog actions, left to right: `后台`, `取消`, `退出`.
4. Top navigation order: show `曲库` before `FM`.
5. When FM is active and the FM list is reopened, locate the current station:
   - Catalog stations restore the matching region, city, and type filters, show the existing `LIVE` active-row state, and scroll that row into position.
   - A custom stream expands the existing stream URL / microphone section because custom streams do not have a catalog station row.

## Files Changed In This Handoff

- `app/src/main/kotlin/j5i/MainActivity.kt`
  - `showExitDialog()` now uses the requested title, body, and background action label.
  - `navbar()` now creates and adds `onlineButton` before `fmButton`.
  - `showSourceSheet(false)` calls `syncStationFilterToCurrent()` before populating the FM sheet.
  - `syncStationFilterToCurrent()` restores filters that contain the active catalog station.
  - `renderStations()` keeps a reference to the active row and scrolls the station `ScrollView` to it.
  - `addStationBrowser()` expands the custom-stream editor when the active source is the built-in custom station id.
  - Existing click behavior is unchanged:
    - `后台` dismisses the dialog and calls `moveTaskToBack(true)`.
    - `取消` only dismisses the dialog.
    - `退出` dismisses the dialog, calls `shutdownForExit()`, then calls `finishAndRemoveTask()`.
    - `曲库` still calls `showSourceSheet(true)`.
    - `FM` still calls `showSourceSheet(false)`.
    - Station rows still call `playRadio(station)` and dismiss the source sheet.
- `app/build.gradle.kts`
  - `versionName` bumped from `2.7.1` to `2.7.2` per the project's parallel-fix version rule.
  - `versionCode` generation was not manually changed.

## Build And Verification

- No Gradle build, unit test, install, or device verification was run, because another parallel session owns the combined build.
- Static checks performed:
  - Located the exit dialog and navigation code with `rg`.
  - Reviewed the scoped diff with `git diff`.
  - Ran `git diff --check` successfully.

## Recommended Combined Verification

1. Build after all parallel sessions have merged their edits.
2. Open the app and confirm the top order is `曲库`, then `FM`, subject to each button's existing unlock visibility.
3. Trigger the back/exit dialog and verify:
   - Title: `请确认`.
   - Body: `请确认，选择退出播放或转入后台`.
   - Actions: `后台`, `取消`, `退出`.
4. Manually verify the three actions still match their original behavior.
5. Play a catalog FM station, reopen the FM sheet, and verify:
   - The filter chips match the active station.
   - The active row shows `LIVE`.
   - The list opens scrolled to that row rather than the top.
6. Play a custom stream, reopen the FM sheet, and verify the stream URL / microphone section is expanded and contains the saved URL.

## Parallel Working Tree Notes

- Other parallel-session changes were present and left untouched:
  - `app/src/main/kotlin/j5i/audio/BiliMusic.kt`
  - `app/src/main/kotlin/j5i/audio/OnlineMusic.kt`
  - `app/src/main/kotlin/j5i/data/MusicStore.kt`
  - `app/src/main/kotlin/j5i/ui/PlaylistDialog.kt`
  - Additional `MainActivity.kt` playlist/online-music hunks outside this handoff's edited regions.
- If another parallel session also bumps `versionName`, keep only one coordinated final patch increment.
