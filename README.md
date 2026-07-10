<p align="center">
  <img src="docs/images/mink-icon.png" alt="Mink" width="120">
</p>

# Mink

Mink is an Android app that shows you the device-fingerprinting surface your
phone quietly exposes, and gives you an on-device AI **Guardian** that watches
that surface with you. It reads real values from the same public Android APIs
any third-party app can call, and shows them to you raw.

Trackers don't need your name, email, or location to recognize you online. Each
reading isn't necessarily unique on its own, but together they form a
fingerprint that follows you across apps and websites. Mink makes that
fingerprint visible, explains why each reading matters, and lets a private,
local guardian help you reason about it.

Mink is the Android sibling of [Loupe](https://github.com/blokzdev/loupe) (iOS,
by Mysk). It replicates Loupe's depth and adds a local AI guardian powered by
[MiniCPM5-1B](https://github.com/blokzdev/MiniCPM), plus an optional floating
8-bit companion you can talk to.

## How signals are organized

Mink groups every reading into three tiers, reflecting the cost of access:

- **Passive** — visible to any app with no prompt at all (device model, build
  fingerprint, battery, display, sensors, locale, network, GPU, and more).
- **Needs Permission** — readings that trigger an Android runtime prompt
  (location, camera, contacts, photos, calendar, nearby Wi-Fi, activity).
- **Advanced** — clever side-channel uses of public APIs, such as the full list
  of installed apps, on-device account types, and a browser-style WebView
  canvas/WebGL fingerprint.

## The Guardian

Mink adds an on-device guardian that can observe, report, discuss, learn your
patterns, flag anomalies, and evolve with you. Over repeated sweeps it builds a
private, on-device **baseline** of your device's rhythms — which readings change
naturally, which have been rock-steady for weeks, and at what hours things
usually shift — storing only value hashes, never the raw values. That learning
lets it quiet the routine noise, sharpen the rare alarms, and tell you what it
has noticed. It runs **MiniCPM5-1B** locally through a `llama.cpp` JNI bridge,
with a tier system that adapts to your device:

- **Full** (8 GB+ RAM): MiniCPM5-1B Q8_0, hybrid thinking mode.
- **Lite** (4-6 GB): MiniCPM5-1B Q4_K_M, latency-tuned.
- **Minimal** (3 GB): Q4_K_M, short context, summaries only.
- **Rules only** (< 3 GB or unsupported): a deterministic engine, no model.

If the model is not downloaded or the device can't run it, the guardian degrades
gracefully to the rules engine. Nothing the guardian sees leaves your device.

## The Companion

You can enable an optional floating companion: a retro 8-bit blue Mink that
lives on top of your screen, reacts to what the guardian notices, and speaks up
with alerts, suggestions, and reminders. Tap it to talk; it draws its answers
from the same local guardian.

## Privacy

Everything Mink reads stays on your device. Values are shown raw, without
aggregation or hashing. The on-device guardian runs entirely locally. Nothing is
uploaded, synced, or shared unless you choose to export a report.

## Building

You'll need Android Studio (Ladybug or newer) and the Android SDK (compileSdk
35). Then:

1. Open the project and let Gradle sync.
2. Build and run on a device or emulator (minSdk 26).

The on-device model is optional and downloaded on demand from within the app.
The native `llama.cpp` bridge builds only when the sources are vendored; see
[`app/src/main/cpp/README.md`](app/src/main/cpp/README.md). Without it, the app
runs with the rules-based guardian.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full design.

## Credits

- **Loupe** (iOS), the app Mink achieves parity with, is by
  [Mysk](https://mysk.co) and released under the MIT License.
- **MiniCPM5-1B**, the on-device model, is by
  [OpenBMB](https://github.com/OpenBMB/MiniCPM).

## License

The source code is released under the [MIT License](LICENSE).
