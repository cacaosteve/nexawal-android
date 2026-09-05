# Changelog

All notable changes to **NexaWal Android** are documented here.
Release tags use `v<versionName>` (e.g. `v1.0.0`).

## [1.0.1] — 2026-09-04

Correctness and recovery update for the first public release.

### Fixed
- Preserve wallet history and balance across interrupted or failed refreshes
- Join an in-flight refresh instead of starting a competing refresh job
- Reject ambiguous or over-precision Monero URI amounts
- Update WalletCore for reorg recovery, cache identity checks, exact fees, and timestamps

### Build / FOSS
- Pin the WalletCore source submodule to the audited 0.1.7 release revision
- Keep the default F-Droid / Wallet Scrutiny path rebuilding native code from source

## [1.0.0] — 2026-08-12

First public packaging cut for Play / F-Droid / Wallet Scrutiny.

### Features
- Single-wallet Monero create / import with seed backup gate
- Sync against configurable Monero daemon RPC (default `https://rpc.nexatrode.com`)
- Clearnet / I2P / hybrid routing
- Receive (QR, payment URI, subaddresses) and send with fee preview
- Optional biometrics / screen-lock for unlock and send
- Techno Theme toggle
- Optional fiat estimates (off by default)

### Build / FOSS
- MIT licensed application source
- No Google Play services / ML Kit (ZXing + CameraX for QR)
- Native core rebuilt from source when `NEXAWAL_BUILD_NATIVE_FROM_SOURCE=1`
- Reproducible-build notes for Wallet Scrutiny: `docs/REPRODUCIBLE_BUILD.md`
- F-Droid packaging notes + fdroiddata draft: `docs/FDROID.md`

### Native
- Shared `MoneroWalletCoreFFI` submodule (MIT)
- Android one-ahead bulk fetch/scan overlap
- 16 KB page-aligned native libraries
