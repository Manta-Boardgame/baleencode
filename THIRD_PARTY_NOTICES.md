# Third-party notices

DeepSeek Agent is built on, bundles, or calls the following. Each remains under its own licence or terms. This list is
meant to be accurate rather than legal advice; check the linked sources for the authoritative text.

## Code that ends up in the app

| Component | Used for | Licence | Where it appears |
|---|---|---|---|
| [pdf.js](https://github.com/mozilla/pdf.js) (`pdfjs-dist` 4.10.38) | Reading PDFs in the page and in the Windows server | Apache-2.0 | Installed by `npm install` (the only runtime npm dependency); copied into `public/vendor/` and the Android assets by `scripts/copy-vendor.mjs`; its `LICENSE` ships inside the package |
| [AndroidX WebKit](https://developer.android.com/jetpack/androidx/releases/webkit) (`androidx.webkit:webkit` 1.12.1) | Serving the app's own files to the Android WebView | Apache-2.0 | Android app |
| [Google ML Kit](https://developers.google.com/ml-kit/vision/text-recognition/v2) text recognition, Japanese (`com.google.mlkit:text-recognition-japanese` 16.0.1) | On-device OCR on Android | Proprietary SDK, used under [Google's ML Kit terms](https://developers.google.com/ml-kit/terms) — not open source | Android app (it is most of the APK's size) |
| [Node.js](https://nodejs.org/) | Runs the local server on Windows | MIT (Node.js also bundles other components, see its [LICENSE](https://github.com/nodejs/node/blob/main/LICENSE)) | **Not in this repository.** Only in the optional ready-to-run Windows package, as `runtime/node.exe` |

Everything else is this project's own code. No analytics, telemetry or remote code is included.

## Windows features that are part of the operating system

The Windows build uses what Windows already provides — Windows OCR, the Windows PDF renderer and Windows UI Automation —
through PowerShell scripts in `scripts/windows/`. Nothing of Microsoft's is redistributed.

## Services and models (called over the network, not distributed)

| | Notes |
|---|---|
| [Vercel AI Gateway](https://vercel.com/ai-gateway) | The API the app talks to, with the user's own key. Subject to Vercel's terms. |
| DeepSeek V4.1 Flash (`deepseek/deepseek-v4.1-flash-1017`) | Open-weights model by DeepSeek. The weights are **not** included; the publisher's licence governs them. |
| Xiaomi MiMo-V2.6-Flash (`xiaomi/mimo-v2.6-flash`) | Vision model used only in the browser "screenshots" mode. Not included; the publisher's licence governs it. |
| Inference providers (Together AI, Fireworks AI, Baseten, DeepInfra, Relace) | Run the models. See [docs/PROVIDERS.md](docs/PROVIDERS.md). |

## Names

"DeepSeek", "Vercel", "Xiaomi", "Google", "Android", "Windows" and the other product names belong to their owners. This is an
unofficial community project and is not affiliated with or endorsed by any of them. The whale icon is original artwork
made for this project; it is not DeepSeek's logo.
