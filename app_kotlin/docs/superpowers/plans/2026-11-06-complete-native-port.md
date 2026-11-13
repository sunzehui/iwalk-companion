# Complete native Kotlin port Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. Continue in the existing clean `integration/kotlin-port` checkout; it already isolates the Kotlin rewrite from Flutter.

**Goal:** Complete the existing native app's settings, calibration, diagnostics, guarded experimental lock and local export flows, then deliver a verified release APK.

**Architecture:** Retain `:app → :core → :protocol`. The existing DeviceSessionController owns one session; persistent user settings remain separate from demo calibration. Capture session evidence under one lock before serializing or writing files. Only LockControl's fixed boolean operation may issue a lock command.

**Tech Stack:** Existing pinned Kotlin/Compose/Gradle versions; JUnit and Robolectric; Android FileProvider.

## Baseline and scope

- [x] Restore existing completed work: `git merge --ff-only ticket/04-evidence-export` (includes ticket 03).
- [x] Baseline: `./gradlew --offline :protocol:test :core:test :app:testDebugUnitTest :app:assembleRelease`.
- [x] Complete ticket 07 settings and ticket 09 experimental lock from the existing Oct 29 spec.
- [x] Document actual delivery rather than claim parity with unavailable v0.8.6 sources.
- [x] Run release acceptance possible without a device; record ADB device absence.

## Task 1: Session integrity and lock policy

Files: core/session/SessionManager.kt, core/session/LockPolicy.kt, core/model/Calibration.kt, corresponding JVM tests.

- [x] Add failing tests for atomic capture (frame/raw/event counts), retained receive timestamps on calibration replay, offset overflow, and lock guards (disabled, disconnected, unsupported, moving, stationary, demo).
- [x] Add `capture()` returning a single immutable snapshot, keep original receive times during replay, bounds-check offsets without integer overflow; implement pure lock guard returning a user-visible reason or null.
- [x] Run `./gradlew --offline :core:test` and inspect test XML.

## Task 2: Controller settings and actions

Files: app/ui/device/DeviceSessionController.kt, app/data/PreferencesStore.kt, app/di/AppContainer.kt, controller JVM tests.

- [x] Add failing tests: invalid JSON preserves stored calibration, valid import replays a live session, demo stays isolated, clear persists, lock preference survives controller recreation, concurrent lock calls write once, failure records unconfirmed state, exported command records and export capture stay consistent.
- [x] Add settings StateFlow, calibration import/clear, guarded single-flight lock request and records; use atomic capture and IO dispatcher for export.
- [x] Run targeted controller tests before UI integration.

## Task 3: Complete visible flows

Files: app/ui/settings/{SettingsTabBody,CalibrationImportDialog,DiagnosticsScreen}.kt, app/ui/device/LockSlider.kt, app/ui/IwApp.kt, app/ui/secondary/SecondaryPage.kt, app/data/ExportSharing.kt, AndroidManifest.xml, res/xml/export_paths.xml.

- [x] Add Compose tests for invalid import feedback, risk confirmation/cancel, diagnostics field states and slider reset/single callback.
- [x] Wire settings, calibration import/clear, diagnostics, export/share, persistent risk-confirmed switch, guarded lock slider and records into existing screens. Keep static preview inert.
- [x] Run `:app:testDebugUnitTest` and resolve any regressions.

## Task 4: Delivery

Files: app/build.gradle.kts, README.md, docs acceptance record.

- [x] Run all JVM tests, lint, `assembleRelease`, `assembleDebugAndroidTest`.
- [x] Inspect APK signatures, SDK/permissions/ABI and byte count; compute SHA-256 and copy artifact to workspace downloads.
- [x] If ADB remains empty, explicitly mark install, startup measurement and real BLE as unverified.

验收记录：`docs/acceptance-2026-11-11.md`；交付说明：`README.md`。
