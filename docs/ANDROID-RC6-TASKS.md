# Android RC6 — task assistant

RC6 extends the supplied RC5 Java/TypeScript application. It does not require a Windows PC, a new framework, or a cloud backend. Gemini remains the only AI provider.

## Try it

Enter your own Gemini key in the app's Settings; never put it in chat. Select text and Live models available to your Google project and use **Check key & model**.

- `Open Chrome` and `set a timer for 5 minutes` use the native offline command path.
- `Open Maps then set a timer for 10 minutes` uses Gemini planning with a maximum of eight supported steps.
- `Message Rahul on WhatsApp: I'll be ten minutes late.` resolves contacts locally and opens a draft. You review it and tap Send.
- Ask for a calendar event with its title, start and end times. MYRAA shows the interpreted date and device timezone before opening a Calendar draft. You save it there.

Supported actions: app opening, web/YouTube search, WhatsApp drafts, map directions, Clock alarm/timer setup, and calendar drafts. Opening a web search does not read or summarize its results. Generic app-screen reading, tapping, automated sending, purchases and PC control are not part of this release.

The task panel shows each step and the actual native outcome. Requests execute one at a time. After an external draft or setup screen, return to MYRAA and tap **Continue**. Multi-step app launches also pause to avoid immediately covering the screen you just opened. Continue acknowledges the handoff; it does not verify that another app sent or saved anything. **Stop** cancels planning, pending native actions, voice and wake listening. An exact spoken Stop/cancel command also stops work when Live transcription arrives; the notification Stop remains available without Gemini.

Tasks expire after five minutes, including review time. The last 20 tasks are kept in private app storage. After process death, tasks are marked interrupted; **Skip uncertain step & continue** skips an action that might already have run. Submit a new request if you want to retry that action. No task or listening session starts automatically on reboot.

## Wake listening

Tap **Enable listening**, allow microphone/notification access, then say **Hey MYRAA** or **Hey Myra**. This is opt-in recognition through the installed Android speech service. On-device recognition is preferred on Android 12+ when available; other speech providers may use the network. Sessions expire after 30 minutes and stop after repeated recognizer errors. Idle Live conversations return to wake listening after 60 seconds; explicitly ending a conversation also returns to wake listening.

Wake recognition pauses while Live owns the microphone and while Android TTS speaks. Say **Stop listening** during wake listening to end it. Enable floating voice controls to permit supported background app switching. Android and vendor restrictions can still require returning to MYRAA. This is not a guaranteed always-on wake-word service; real-phone validation is required.

## Build and install

Prerequisites: Node/npm, JDK 21, Android SDK platform 35 and build-tools 35.0.0.

```bash
npm ci
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk-21
bash scripts/build-android.sh
```

Windows can use the existing `scripts/build-android.ps1` with its SDK/JDK parameters. Both scripts preserve an existing `android/local-debug.keystore` and include owner-provided `assets/characters/evelyn` if present. Without those assets, the source build displays a neutral M placeholder and keeps voice/tasks usable. The source archive supplied for this task contained no character assets.

**The downloadable source-test APK uses a newly generated development signing key. It cannot update an existing MYRAA installation signed with another key. To preserve installed settings, keys and history, rebuild the source using your original `android/local-debug.keystore` or original release signing identity. Do not uninstall your existing app just to install this test APK.**

The Android package stays `com.myraa.mobile`; versionCode is 6, versionName is `1.0.0-rc.6`. No credentials or private owner assets are bundled.

## Interfaces and internals

- `MobileTaskCoordinator` owns native plan validation, serialized dispatch, persistent history, timeouts, continuation and cancellation. It persists before dispatching an action; storage failures block further actions.
- `MobileActionSpec` validates every planned step before the first side effect, and validates direct actions again at dispatch.
- `MyraaNative.chat(text,id)` plans typed requests; ordinary conversation or missing details returns a reply with no actions. Local parser shortcuts call `mobileAction(id,name,args)` through the same coordinator.
- `taskTool(id,args)` supports `start`, `status`, `continue`, and `cancel`; `mobile_task` exposes it to Live alongside the expanded `mobile_action`. Start returns an asynchronous task status, never a completion claim. Ask for status to hear the latest result.
- `getTask()`, `continueTask()`, and `setListening(enabled)` support native task and listening controls. `mobile-task` and `wake-state` events drive the UI. API keys remain inside native encrypted storage.
- Existing state fields and settings are retained; task state is stored under the additive `tasks.v1` preference. The old text-chat prohibition on all app actions is replaced with the actual supported capabilities.

## Checks

```bash
npm test
bash scripts/test-android-native.sh
# Browser checks need Playwright installed in a test environment and Chromium:
PLAYWRIGHT_MODULE=/path/to/playwright MYRAA_TEST_URL=http://127.0.0.1:5178/mobile.html node tests/mobile-tasks-browser.cjs
```

The native test script uses a checksum-pinned JSON dependency from Maven Central and Android platform types. It exercises the production coordinator/validator and wake phrase matching on the JVM; it does not simulate successful Android platform execution.

See `ANDROID-RC6-VERIFICATION.md` for completed checks and device limitations.
