# RC5 asset restoration verification

## Result

A private restored RC8 APK was built successfully with the original RC5 avatar and icon. No Android application Java/TypeScript runtime source was changed for this restoration.

| Check | Result |
| --- | --- |
| Original PMX, texture mapping and nine textures | All 11 character files byte-identical to the original RC5 APK |
| Original icon | 1024 × 1024 RGBA pixels identical to RC5 |
| Personality | Byte-identical to RC5 |
| RC8 native code | `classes.dex` byte-identical to the published RC8 test APK |
| RC8 frontend | Bundled JavaScript, CSS and mobile HTML byte-identical to the published RC8 test APK |
| Application manifest | Byte-identical to the published RC8 test APK; package and version retained |
| APK build/signature | Successful Android35/JDK21 build; v2/v3 signature verification passed |
| Real restored model browser checks | 19 checks passed: rendering, 293 bones, 135 physics nodes, texture loading, no placeholder, Wave/Sit/Stand/Smile controls, portrait controls, RC8 workspace, wrist motion, seated leg geometry, standing geometry and finite/healthy simulation |
| Recovery safety checks | 4 passed: exact/idempotent restoration, conflict preservation, traversal rejection and incomplete-archive rejection |
| Repository regression command | Passed: 164 Node assertions; Python 38 tests with 3 platform skips; 2 Node skips |
| Existing native task/wake checks | 26 passed |
| Existing screen pipeline source checks | 62 passed |
| Existing voice/task browser checks | 12 passed with fixture provider/native bridge; real browser microphone/audio engine |
| Existing Screen Companion browser checks | 26 passed with synthetic screenshots/native events |

The real-model browser checks used actual recovered PMX/textures, the real Three.js renderer and production animation/physics code. The six geometry checks skip intermediate GPU draws but update the real rig/physics and render the final poses. They are not mock model responses. Native UI defaults were read-only browser adapters, and no Gemini key was used.

Software WebGL was slow in this environment; no mobile FPS, battery or hardware performance guarantee follows from these checks. The optional full character matrix was not completed. Android emulator/physical-device avatar execution and live Gemini voice remain NOT TESTED for this restored build. Audio-driven lip-sync is retained in the unchanged runtime but was not verified with a genuine Gemini conversation.

## Signing limitation

The restored APK reuses this workspace's development signing key, matching the previously delivered RC8 test build. Its certificate differs from the original RC5 APK. The original private signing key cannot be recovered from an APK; it is still required to update an original RC5 installation without uninstalling it. No settings, history or API key were intentionally cleared or reset.

## Private assets

The private restored APK and owner source archive contain the recovered model/artwork for private owner testing, as restricted by the original third-party notices. They are supplied separately and are not uploaded to the public repository. The public repository contains recovery/build tools, instructions and source tests. Its neutral source-test APK is still the asset-free public variant.
