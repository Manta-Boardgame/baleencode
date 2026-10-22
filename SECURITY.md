# Security policy

BaleenCode lets an AI model read files, drive a browser and operate apps on your computer or phone. That makes
security the main thing to get right, so this page says plainly what is protected, what is not, and how to report a problem.

## Reporting a vulnerability

Please **do not open a public issue** for a security problem. Use GitHub's private reporting instead:
**Security tab → "Report a vulnerability"** on this repository.

This is a small community project maintained in spare time: there is no service-level agreement, but reports are read,
and I aim to acknowledge one within about a week and to say what I will do about it.

Include: what you did, what you expected, what happened, the platform (Windows/Android) and the commit or version.
A short proof of concept (a file, a page, a prompt) helps a lot.

## Supported versions

Only the latest commit on `main`. There are no versioned releases yet.

## What counts as a vulnerability here

In scope — the **code-enforced limits** (they must hold even if the AI is fooled or misbehaves):

- escaping the chosen folder (`..`, absolute paths, symlinks/junctions, alternate data streams);
- performing a write, delete, command, click or typing without the approval the mode requires, or bypassing the
  "ask first" cases (secrets, passwords, risky commands, purchase/delete buttons) when they are recognised;
- reaching a provider outside the allowlist, or the allowlist being widened by anything other than editing the source;
- operating a window or app that the user did not choose (Windows UI Automation mode, Android accessibility mode);
- leaking the API key, or reading it from another site / another local program through this app's own server;
- the local server (`127.0.0.1:4141`) accepting requests it should refuse (cross-site requests, DNS rebinding);
- parser problems with files the app opens (PDF, Word, PowerPoint, Excel, images): crashes, unbounded memory, code execution.

Out of scope:

- the AI saying something wrong, or being talked into *proposing* a harmful action — what matters is whether the code lets
  it happen without your approval (see [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md));
- consequences of choosing the **Autonomous** approval level, which exists to remove confirmations (the ask-first list still asks);
- an attacker who already controls your computer or phone, or who can read your user profile;
- the security of Vercel, the model providers, Edge/Chrome, Android or Windows themselves.

## Known limitations (please read before trusting it with anything important)

- **Prompt injection cannot be fully prevented.** Text in a file, web page or app screen can try to steer the model. The
  system prompt tells the model to treat such text as data, and the tests show it does so in practice, but that is a
  behaviour of the model, not a guarantee. The real protection is that dangerous actions are limited and need your approval.
- **Your API key is stored on the device without encryption** (browser storage on Windows, app storage on Android).
- **What the AI reads is sent to the approved providers** (through Vercel). That includes file contents, page text and
  screenshots the assistant looks at. Choose folders, windows and apps with that in mind.
- **The Autonomous level removes the confirmations** for edits, deletes (to the trash), commands, clicks and typing; only the ask-first
  list remains. `/goal` runs can last hours unattended: give them a folder you can check, and set a Vercel budget.
- **Windows "Full access" runs PowerShell as you.** Approve commands only if you understand them.
- The Android accessibility service can read the screen of the apps you chose. Banking and other secure screens are
  usually blocked by Android itself.
- The local server trusts any program running as you on the same PC (it cannot tell them apart from the page).

More detail, including how each control is tested: [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md) and
[docs/TESTING.md](docs/TESTING.md).
