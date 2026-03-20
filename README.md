# nexawal-android

`nexawal-android` is the Android version of the NexaWal Monero wallet, built on top of `monero-oxide` and the shared wallet core.

- Android app: this repository
- iOS app: [nexawal](https://github.com/cacaosteve/nexawal)
- Rust wallet core / Swift package: [WalletCoreFFI](https://github.com/cacaosteve/WalletCoreFFI)
- Monero library work: [monero-oxide](https://github.com/cacaosteve/monero-oxide)

## Screenshots

| Wallet | Receive |
| --- | --- |
| ![Android wallet](docs/screenshots/android1.png) | ![Android receive](docs/screenshots/android2.png) |

| Send | Settings |
| --- | --- |
| ![Android send](docs/screenshots/android3.png) | ![Android settings](docs/screenshots/android4.png) |

## Notes

- Single-wallet Monero app
- Uses a native wallet core built from `monero-oxide`
- Android consumes the shared wallet core through native `.so` integration
- Syncs against standard Monero nodes, including local or remote nodes
