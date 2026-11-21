# Yomiage 読み上げ

Reads whatever is on your screen aloud with natural **Kokoro** voices, running entirely on the phone.

- **Read screen**: reads the app or web page you're looking at, from the top, and can keep going past the bottom of long pages.
- **Tap to start**: touch any sentence and it reads from there.
- **Player**: back/forward **2 s** and **5 s**, play/pause, and speed from 0.5× to 3× (presets plus fine ±0.05 steps). Speed changes instantly without changing pitch.
- **54 Kokoro voices**: American and British English, plus Japanese-accented, Spanish, French, Italian, Portuguese, Hindi and Mandarin voices, with previews.
- **Scan text in images**: on-device text recognition for pictures, PDFs and apps that don't share their text.
- **Read aloud** from the text-selection menu or the share sheet in any app.
- The paragraph being read is outlined on screen.

## Install

Each push builds an APK with GitHub Actions. Open **Releases**, tap the newest `Yomiage build N`, and download `Yomiage-N.apk`. Every build is signed with the same key, so new versions install over old ones and keep the voice pack and settings.

## First run

1. **Download the voices**: about 330 MB, once. Reading is offline after that.
2. **Turn on screen reading**: Settings → Accessibility → Installed apps → Yomiage → On.
   If the switch is greyed out: App info → ⋮ → **Allow restricted settings**, then try again.
3. **Keep it awake**: allow unrestricted battery so the phone doesn't stop it in the background.

Then tap the 読 bubble. Long-press it to go straight to tap mode. You can also assign Yomiage to the Accessibility button: one press reads the screen, the next pauses.

## How it works

- `service/ReaderService` is an accessibility service. It only looks at the screen when you ask it to read. `ScreenScanner` walks the app's accessibility tree, merges inline runs (links inside paragraphs, styled text) into paragraphs, and uses per-character positions to find the tapped sentence. `OcrScanner` uses an accessibility screenshot plus ML Kit when there's no text to read.
- `tts/KokoroEngine` runs Kokoro-82M (v1.0, 54 voices) through [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 1.13.8. The native libraries are downloaded by the build workflow, and `com/k2fsa/sherpa/onnx/Tts.kt` is vendored from the same version. The model loads on first use and unloads after 5 idle minutes.
- `reader/Reader` generates sentences on one thread, up to a minute ahead, and plays them on another through an `AudioTrack`. Because the audio is real samples, ±2/±5 s seeking works like a podcast player, and speed uses the platform's pitch-preserving time-stretch. The first sentences are split short so speech starts fast.

Voices: [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) by hexgrad, Apache 2.0.
