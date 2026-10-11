# Security Policy

Discount Hunter is an offline-first Android app: it talks to public storefront APIs, shows you
prices, and keeps everything else on your device. It has no accounts, no server, no analytics, and
no third-party tracking. That makes the attack surface small — but this document exists so that if
you find a real problem, there is a safe way to report it.

## Supported versions

| Version | Supported |
|---------|-----------|
| 1.0.x (current release) | ✅ |
| Anything older | ❌ |

You are running the latest build from the
[Releases page](https://github.com/JuggyBob/DiscountHunterAndroid/releases/latest). Security fixes
land in the next release.

## What the app does and does not do

**It does:**

- Fetch prices and media over HTTPS from Steam, GOG, Epic and `frankfurter.app` (exchange rates).
- Cache responses and recorded prices **on your device only**.
- Let you export a diagnostics log that you explicitly choose to share.

**It does not:**

- Collect any personal data, telemetry, analytics, or crash reports to a server.
- Require an account, an email address, or any API key.
- Send your wishlist, search history, or settings anywhere.
- Request any dangerous Android permission. (It needs internet access, and optionally
  `QUERY_ALL_PACKAGES` only to detect which store apps are installed so it can offer a deep link
  instead of a browser.)

## Reporting a vulnerability

**Please do not open a public issue for a security vulnerability.** Public issues are visible to
everyone, including people who would exploit a bug before it is patched.

Report it privately via **GitHub Security Advisories**:

> On this repository: **Security** tab → **Report a vulnerability**

That opens a private thread visible only to you and the maintainers. Please include:

- What the vulnerability is and what an attacker gains.
- Steps to reproduce, or a proof of concept.
- The app version (Settings → About) and your Android version.
- Whether the issue requires a malicious network, a malicious server, or only user action.

If you are not able to use GitHub Security Advisories, open a regular issue that says only
"Please contact me privately about a security matter" with no technical detail, and we will arrange
a private channel.

### What to expect

- **Acknowledgement within 3 days.**
- **An assessment within 10 days** — whether we consider it a real issue, and a rough severity.
- **A fix and a release** for confirmed issues, ideally within 30 days. We will credit you in the
  release notes unless you would rather we did not.

Please give us a reasonable amount of time to ship a fix before disclosing publicly. We will
coordinate disclosure with you.

## Threat model

This is what we consider in scope, and what we defend against:

**We defend against:**
- Malicious or compromised remote content (a hostile store API response) causing a crash, code
  execution, or reading data the app should not touch.
- Cache poisoning — one game's cached data being served for a different game.
- Local data leakage — another app on the device reading the user's wishlist or cached pages.
- Transport security — we require HTTPS for all network calls and do not disable certificate
  validation.

**We consider out of scope (by design, not by accident):**
- The app is unsigned and distributed as a plain APK, so it is not on Google Play. Anyone can
  repackage or modify an APK they already have. There is no anti-tamper mechanism and we do not
  claim one.
- Rooting a device, and anything that requires it.
- Denial of service by a store's API being down.
- Insecure configurations the user explicitly makes themselves.
- GOG, Steam or Epic serving a wrong price. That is a store data issue, not a vulnerability, though
  it is a bug we would like to hear about via the normal issue tracker.

## Data handling

All data lives in the app's private storage under `data/data/com.discounthunter.android`, readable
only by the app. Deleting the app deletes it. "Settings → Storage → Delete all app data" wipes
everything, including the wishlist, cached pages, recorded price history and your settings.

The **Export logs** feature writes a file to `cache/exports/` and hands it to the Android share
sheet. It is not uploaded anywhere by the app; where it goes after that is up to you and whatever
app you share it to. Review it before sending — it contains your device model and OS version.

## Cryptography

The app does not implement its own cryptography and does not store secrets or credentials. It has
no API keys because every service it uses is public and unauthenticated.

---

If you find something, thank you. Reporting a vulnerability is genuinely useful, and we would much
rather hear about it from you than read about it.
