# Changelog

All notable changes are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/), and the project uses [Semantic Versioning](https://semver.org/).

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
