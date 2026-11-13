# RC6 verification report

## Story

A typed command or spoken request reaches one native Android task coordinator, dispatches only supported Android actions, and returns truthful progress and handoff states. Wake listening hands microphone ownership to Live and Stop releases it.

## Completed in this workspace

| Check | Result | Evidence / limit |
| --- | --- | --- |
| TypeScript | Passed | `npm run lint` |
| Repository regression command | Passed | 164 Node assertions passed, 2 skipped; Python ran 38 tests with 3 skipped. `npm test` includes lint. Skips concern absent assets/platform capabilities. |
| Native coordinator and wake phrase checks | Passed | 26 JVM assertions: serialization, preflight validation, duplicates, Stop, late callbacks, timeout, interrupted recovery, storage failure, stale responses, parameter bounds, wake/stop phrases. Actual Android services are not executed. |
| Mobile command parser | Passed | 5 Node test cases, including negation, exact message preservation, compound requests and numeric limits; included in repository results. |
| Browser task/voice flow | Passed | 12 Chromium checks of the real UI and shared WebAudio microphone pipeline. Android bridge and Gemini socket are fixtures. Evidence: `artifacts/mobile-tasks-browser.json` and `artifacts/android-task-ui.png`. |
| Production frontend/backend build | Passed | `VITE_MYRAA_PUBLIC=1 npm run build`; existing esbuild warnings about `import.meta` in CommonJS remain. |
| Android application compilation | Passed | JDK 21, platform 35; Java compilation and DEX generation. Deprecated API/Java 8 target warnings remain. |
| Android instrumentation sources | Compiled | Existing instrumentation sources compile against platform 35; they were not run. |
| APK packaging and signatures | Passed | Source-test APK packaged, zipaligned and verified with v2/v3 signatures. It contains a neutral avatar, no private models and no Gemini key. |

## Not verified

- Android emulator execution: setup was attempted, but the emulator exits with `Your device does not have enough disk space to run avd: myraa-test`. The environment also lacks KVM. No successful installation, native UI instrumentation, Android speech recognition or background-service run is claimed.
- Live Google inference and genuine Gemini audio conversations: no user Gemini credential was available. Browser fixtures test transport/tool response behavior, not model quality or account access.
- Real WhatsApp/Clock/Calendar/Maps behavior, contact permissions, vendor background restrictions, wake recognition, battery usage and Android version compatibility still require an actual phone.
- The supplied character artwork was absent; the existing character runtime is retained but this source-test APK uses its neutral fallback.

## Before using on a phone

Rebuild with the original signing identity to update an existing MYRAA installation without uninstalling it. On a test device, verify key/model access, microphone and notification denial/recovery, recipient ambiguity/cancellation, exact draft contents, calendar timezone preview, Clock handoff, background controls, wake/Live transitions, notification Stop, task timeout, and process-death recovery. Record real outcomes separately from fixture results.
