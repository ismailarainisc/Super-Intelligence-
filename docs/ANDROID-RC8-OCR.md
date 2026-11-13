# RC8 optional on-device OCR assessment

Status: **NOT IMPLEMENTED / NOT TESTED**. Screen Companion does not claim that it has read any text locally. `ScreenPrivacy.ocrStatus()` explicitly reports that no on-device OCR engine is bundled. Gemini visual explanations come from approved screenshot image requests, not a fabricated OCR stage.

## Compatibility checked

Google's current [ML Kit Text Recognition v2 Android setup](https://developers.google.com/ml-kit/vision/text-recognition/v2/android) was inspected during this implementation. Its detailed setup currently specifies Android API 23 or above, so MYRAA's API 26 minimum is compatible. The setup lists bundled `com.google.mlkit:text-recognition:16.0.1` for Latin script and separate Chinese, Devanagari, Japanese and Korean libraries. Devanagari could support an explicitly installed Hindi OCR module; no Arabic/Urdu OCR support is claimed. Urdu response translation through Gemini is a separate capability and does not establish Urdu OCR support.

The actual [Google Maven POM for the bundled Latin module](https://dl.google.com/dl/android/maven2/com/google/mlkit/text-recognition/16.0.1/text-recognition-16.0.1.pom) was fetched and inspected. It is an Android AAR, with direct dependencies on:

- `com.google.android.gms:play-services-base:18.5.0` (AAR)
- `com.google.android.gms:play-services-basement:18.4.0` (AAR)
- `com.google.android.gms:play-services-mlkit-text-recognition:19.0.1` (AAR)
- `com.google.mlkit:common:18.11.0` (AAR)
- `com.google.mlkit:text-recognition-bundled-common:17.0.0` (AAR)

These dependencies themselves have additional dependencies; listing these five is not a claim that they form the complete resolved runtime graph.

## Why it is not bundled here

MYRAA's established Windows and Linux build scripts intentionally use `javac` against `android.jar`, package the resulting classes into one JAR, run D8 on that JAR, and link only local resources/assets through AAPT2. They contain no Maven/Gradle dependency resolution, AAR extraction, transitive classpath assembly, manifest merging, or third-party Android resource/native-library packaging stages. Adding one ML Kit Java import would therefore not produce a genuine installable working OCR engine.

A reliable integration would require an explicit extension of both established build paths to resolve and checksum-pin the full Android dependency graph; merge applicable manifests and resources; package any required native libraries/models; include the classes in both javac and D8; close the recognizer correctly; and run real Android OCR extraction tests for each claimed script. That integration was not performed or represented as working in RC8. No dummy OCR result or OCR extraction success test substitutes for it.

## Privacy implications

Every outgoing frame requires explicit review. Without OCR, MYRAA cannot automatically inspect the screenshot for sensitive text. The review UI must prominently explain this, require the user to inspect the exact preview, and reject any frame marked sensitive. A local best-effort text policy rejects sensitive questions and any future OCR text supplied to it. The provider output filter discards apparent password values, authentication codes, or long financial identifiers before UI/voice delivery. These safeguards are best effort and do not prove an image safe; banking, sign-in, password, authentication and other sensitive screens should not be approved.
