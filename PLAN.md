# Plan

Features agreed on but not built yet, to go through one at a time. Each one gets its own
design pass before building. Highest priority first.

Already built: hiding apps, recency-weighted "Most used", web search when nothing matches,
renaming apps (searchable by both names), and the first quick tools: an inline calculator
and jumping to Settings pages.

## 1. More quick tools in search

Answers and actions straight from the search bar. The calculator and Settings pages are done;
these are the remaining candidates, each to be agreed on before building:
- **Unit and currency conversion:** `5 km in mi`, `100 usd in nok` (currency needs rates, so network access; maybe skip).
- **Timer / alarm:** `timer 10m`, `alarm 7:30` through `AlarmClock.ACTION_SET_TIMER` / `ACTION_SET_ALARM`
  (the SET_ALARM permission is already declared).
- **Dial / message a number:** a typed phone number offers "Call" / "Message".

Settled by the first tools: a tool's answer sits closest to the search bar (below app results),
and Go acts on it (the calculator copies its result).

## 2. Back up and restore launcher data

Groups, hidden apps, custom names and launch scores live only in SharedPreferences
(`launcher`, `launch_counts`, `launch_scores`), so reinstalling or getting a new phone loses them.

Options:
- **Android Auto Backup** (`allowBackup="true"` is already set): check what it actually backs up
  on the Samsung, and add explicit `fullBackupContent` / `dataExtractionRules` so these prefs are included.
- **Manual export/import** to a JSON file (Storage Access Framework), independent of Google backup.

Requirements: a failed or partial import must never wipe the existing data. Validate the whole
file first, and keep the current state if anything is off. App keys contain the user serial
(`#0`), which can differ on a new phone, so match on the component and fall back to the package name.

## 3. Haptic feedback

A short vibration that confirms gestures with no visual change, or none yet:
long-press (menu opening), swipe up/down/left/right firing, double-tap to lock.
Use `HapticFeedbackConstants` through `View.performHapticFeedback`, which follows the
system's touch-feedback setting and needs no permission. Decide which events get which strength.

## 4. Optional: user-defined gesture shortcuts

Example: a gesture opens a chosen app (camera, a specific chat…). Needs more thought first:
- Which gestures are still free? Up, down, left, right and double-tap are taken. Candidates:
  two-finger swipes, a swipe starting on the clock, long-press on the blank area, double-tap on a section tile.
- How the user assigns them (a settings screen, or long-press an app → "Open with gesture…").
- Discoverability without cluttering the minimal look.

## 5. Later: notification dots

A small dot on apps (and folders) with unread notifications. Needs a
`NotificationListenerService`, which the user switches on once, like the lock service.
Parked for now.
