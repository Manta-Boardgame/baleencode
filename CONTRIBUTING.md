# Contributing

Thanks for looking. This is a small project maintained in spare time, so replies can take a few days.

## Run it

```bash
npm install
npm test        # 36 tests; no network, no API key, no cost
npm start       # http://localhost:4141 (Windows)
```

Android: see the README (`npm install`, then `cd android && ./gradlew assembleDebug`).

## What a good change looks like

- **Safety first.** Limits (folders, providers, approvals, cost) are enforced in code, not by prompts. A change that adds an
  ability should add its limit and a test in `tests/agent.test.mjs` that fails if the limit is removed.
- **The provider allowlist is not up for casual change.** Adding a provider needs evidence (zero data retention, no training,
  headquarters) recorded in `docs/PROVIDERS.md`.
- **Windows and Android stay in step.** The Android loop lives in `public/agent-android.js` (and the native side in
  `android/`); behaviour such as approval levels and `/goal` should match.
- **Keep the page text in English.** Other languages go in a language pack (see `public/i18n.js`); `lang-ja.js` is an example
  that this repository does not ship.

## Security problems

Please report them privately: see [SECURITY.md](SECURITY.md).
