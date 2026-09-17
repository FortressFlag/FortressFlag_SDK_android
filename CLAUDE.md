# FortressFlag_SDK_android — Agent & Contributor Guide

> **This repo inherits the FortressFlag founding principles.** The canonical, source-of-truth
> document lives in the backend repo (`FortressFlag_Backend/CLAUDE.md`, the founding document).
> Read it before making architectural or design decisions.
>
> ADR-nnnn refers to FortressFlag's internal architecture decision records. The public contract
> every SDK implements is `FortressFlag_Standards`; decision records are not published.
>
> When anything here conflicts with the founding document, the founding document wins.
> Priority order when in doubt: **Security → Compliance → Efficiency → Cost.**

## This repo

The **Android client SDK** — FortressFlag's second client SDK (backend ADR-0013). It embeds in
a customer's app, resolves flag values for a single device context, and must never take that
app down. It implements the contracts published in
[`FortressFlag_Standards`](https://github.com/FortressFlag/FortressFlag_Standards)
(`contracts/contract-v1.md`, `contract-v2.md`, `device-identity.md`) — owned by
`FortressFlag_Backend`, changed only via ADRs there.

## Non-negotiable rules (see founding doc for the full set)

### Fail-safe evaluation (Founding §8.1, §8.4)

- The SDK **never throws to the caller** and **never crashes the host app.** A flagging outage
  must be invisible to the end user's core experience. `!!` is Kotlin's `try!` — CI greps
  library sources for it and fails the build.
- **Fallback cascade, in this exact order:**
  1. **Last recorded value** — persist the most recent known-good value per flag to durable
     device storage and return it on any fetch failure (offline, timeout, cold start).
  2. **`false`** — if no value was ever recorded for a flag, return `false` (features ship
     gated off). A developer-supplied default substitutes for this step only; the last
     recorded value always beats it.
- The local cache is a **correctness feature, not an optimization** — it must survive app
  restarts, and expiry governs freshness, never validity (the cache loads with expiry
  unenforced; see the contract's "expiry asymmetry").

### Data minimization (Founding §2.1, §7.3)

- The SDK **must not download the full ruleset** or any other device's data. It receives only
  the values for its own device context — one endpoint, `GET /v1/client/flags` (backend
  ADR-0004). Do not add endpoints, streaming, or ruleset access; the one-endpoint shape is
  architecture, not an omission.
- No secrets or PII in anything the SDK stores or transmits beyond what the customer
  configures.

### Device identity = billing (Founding §6.1)

The canonical contract is
**`FortressFlag_Standards/contracts/device-identity.md`** — this repo implements it: `dev_`
(or `sim_` when the emulator heuristic fires) + unpadded base64url of 16 bytes from
`SecureRandom`, held in Keystore-encrypted app-private storage, validation accepting both
prefixes, mint-then-adopt-on-conflict, never derived from `ANDROID_ID` or any hardware
identifier. Emulator detection is **best-effort heuristics** here (compile-time-exact on iOS);
a wrong heuristic is a billing skew, not an outage.

Two Android honesty notes, stated in the docs rather than papered over:

- **Identity is per app in v0.1.** App-family sharing (signature-gated ContentProvider with
  automatic discovery — the mechanism is decided in ADR-0013) is a follow-up. Until it ships,
  one physical device running several of a customer's apps bills as up to that many devices.
- **Identity does not survive uninstall.** Keystore-backed app data is wiped on uninstall
  (unlike the iOS keychain). Reinstall = new identity = a new billable device. A platform
  fact; documenting it beats pretending.

## Workflow

- Default branch: `development`. Changes go via PR with review; squash merge, linear history
  (Founding §7.5). CI is the merge gate — we cannot recall a shipped SDK.
- **Commits and PRs are authored as FortressFlag, never a personal identity.** Local commits
  carry `FortressFlag <noreply@fortressflag.com>` (a gitconfig include scoped to the
  maintainer's FortressFlag clones); PRs are opened and merged via the `fortressflag` GitHub App, because GitHub
  authors a squash commit as the PR opener's account regardless of branch authorship.
- **The public SDK API and the consumed contract are backward-compatibility sacred** (Founding
  §5, §8.3) — never break a shipped SDK.
- Runtime dependencies: **kotlinx-coroutines only** (ADR-0013). Anything further — a crypto
  library, a JSON library — is a supply-chain decision the maintainer owns; ask, don't add.
- Local gate: `./gradlew build lint test` (needs JDK 17 and the Android SDK; CI runs the same
  plus instrumented Keystore tests on an emulator).
