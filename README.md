# lockscreen-translate

A native Android app that turns an English word/phrase into **authentic everyday Hong Kong
Cantonese + Mandarin**, full-screen in landscape — with the goal of one-gesture access even
when the phone is locked. A low-friction, native re-creation of the cloud "EN → 普粵 (everyday)"
web flow.

## `app/` — the Android app
Kotlin, minSdk 29 / targetSdk 34. **MVP (now):** a Settings screen (proxy URL + shared token)
and a landscape `TranslateActivity` — type English → the proxy streams the translation (SSE) →
a WebView renders **Mandarin on top, Cantonese on bottom** with two bundled OFL fonts that draw
jyutping/pinyin above each character (`VF-Canto`, `Hanzi-Pinyin`).

**Roadmap (incremental):** `showWhenLocked` launch over the keyguard · voice input
(`SpeechRecognizer`) · a **volume-key-chord AccessibilityService** that opens the translator over
the lock screen · a native pop-out fallback renderer (`Dict.kt`) if the color font can't render in
a device's WebView.

Install: grab the APK from the rolling **[lockscreen-translate-latest release](../../releases/tag/lockscreen-translate-latest)**
(built by CI), then in Settings set the proxy URL + token.

## `proxy/` — the backend
A single Python **Cloud Function** (`translate`) that holds the everyday system prompt, calls a
config-selectable **Vertex AI Model Garden** model, and streams the result back as SSE. Model is
an env var (`LT_MODEL`, currently `gemini-3.6-flash`); the model choice is benchmarked in a
separate repo, **[WandLZhang/language-benchmarks](https://github.com/WandLZhang/language-benchmarks)**.
GCP credentials stay server-side; the app authenticates with a shared token kept in Secret Manager.

Deploy: `cd proxy && LT_MODEL=<model> bash deploy.sh`.

## Notes
- No secrets in the repo. `app/signing/se.jks` is a **debug** keystore (intentionally committed so
  the rolling-release APK installs as an in-place upgrade).
- The model benchmark + its data live in the language-benchmarks repo, not here.
