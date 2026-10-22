# Changelog

All notable changes are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/), and the project uses [Semantic Versioning](https://semver.org/).

## [1.2.0] - 2026-10-23

### Changed
- New layout in the spirit of Claude Code and Codex: the conversation fills the screen in a wide, readable column; the settings
  (mode, folder, approval, web search, cost cap) are chips in the composer card; on phones the less used ones fold behind ⚙.
- Replies are shown as formatted Markdown (headings, lists, tables, code, links). The renderer escapes everything first and
  builds only a fixed set of tags; links open only for http(s) addresses.
- Tool activity is one compact line per action; details open on click.

### Added
- Live status while the model works: elapsed time, step, and input / output tokens (the model's own counts, added up per
  step) in the Agent tab; elapsed time and an estimate of the tokens so far while a chat reply streams, with the real counts
  under the reply afterwards.

## [1.1.0] - 2026-10-22

### Added
- **Web search** for the agent (Windows and Android), off until you turn it on. AI Gateway runs Parallel's search (US) and
  gives the results to the model; only the search words go to Parallel. About $0.005 per search. The provider allowlist for the
  model itself is unchanged. Chosen after comparing Parallel, Perplexity and Exa on the same questions: all three were
  accurate, and Parallel usually needed one search per answer instead of two.
- Android: **Phone apps · screenshots** mode. The vision model taps, long-presses, swipes and types at screen positions through
  the accessibility gesture interface; points outside the chosen apps are refused, risky or password controls ask first.
- Android: **browser modes** (text and screenshots) in a panel inside the app: a separate web view with no bridge to the app,
  https only. This is also how the Android agent searches the web.

## [1.0.1] - 2026-10-22

### Changed
- Safety wording: risky actions "ask first" instead of "always ask". They are recognised from lists of known patterns,
  which is a safeguard, not a guarantee; the app, README and security notes now say so.
- A no-warranty notice in the app (API key screen) and in the Autonomous confirmation; a Disclaimer section in the README.

## [1.0.0] - 2026-10-22

First public release.

### Added
- Chat and **Agent** for DeepSeek V4.1 Flash on Windows and Android, through Vercel AI Gateway with a fixed allowlist of five US providers (fail-closed; a reply from any other provider stops the run).
- Agent modes: read-only folder, read & write folder, full access (Windows), browser (text or screenshots), desktop apps (Windows UI Automation), phone apps (Android accessibility).
- Reading PDF, Word, Excel and PowerPoint files, with on-device OCR.
- Three approval levels (ask every time, auto-accept edits, autonomous) and an always-ask list that holds at every level.
- `/goal` for long unattended runs, plus `/plan`, `/permissions`, `/compact`, `/cost`, `/status`, `/clear`, `/help`.
- Per-request cost cap, automatic waiting on rate limits, usage and free-credit display.
- Threat model, security policy, provider documentation, 21 automated safety tests.
- Prebuilt Windows package (Node.js included) and signed Android APKs on the Releases page.
