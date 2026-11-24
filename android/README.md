# xrnkey for Android

The keyboard: Telex through the shared core (`../core`), with accent restoration and the
English/Vietnamese choice from the language models, an English keyboard, and a Japanese one
(romaji or the 12-key flick pad to kana, kana to kanji with Mozc's dictionary). It asks for no
permissions, and has no network. The feature spec is [docs/feature-spec.md](docs/feature-spec.md).

## Build and test

`local.properties` needs the SDK and the system CMake (the core needs CMake 3.27 or newer):

```
sdk.dir=/path/to/Android/Sdk
cmake.dir=/usr
```

```bash
./gradlew :app:testDebugUnitTest      # JVM tests, against the engine built for this machine
./gradlew :app:assembleDebug          # arm64-v8a
./gradlew :app:assembleRelease        # needs the trained models and the Japanese dictionary
```

- **Models.** The trained models (`vi-phone.xklm`, `en.xklm`; `../data/lm/README.md`) are
  bundled into the APK from `~/.cache/xrnkey-lm/models`, or from `-Pxrnkey.models=<dir>`.
  - A debug build without them bundles the small test models.
  - A release build without them fails.
  - They are stored uncompressed and memory-mapped in place.
- **Japanese dictionary.** `ja.xkdic` (`../data/ja/README.md`) comes from the same folder, the
  same way: a debug build without it bundles the made-up test dictionary, a release build fails.
- **Tests.** The JVM tests run the real core through the JNI bridge, built for this machine by
  `host/CMakeLists.txt` (the `buildHost` task, which needs a C++ compiler, CMake, Ninja, Python 3
  and a JDK).
- **Screenshots.** `-Pxrnkey.shots=<dir>` saves the keyboard's screenshots there (Robolectric,
  native graphics).
- **Signing.** A release build is signed with the key that `../../xrn-signing.properties`
  describes, when that file is there. A worktree's release build comes out unsigned.

## Releases and the hub app

The keyboard can't update itself, so the owner's hub app does it: `scripts/release.py` publishes a
release to the hub's server, and the hub app shows **Keyboard update ready** and installs it on a
tap (docs/feature-spec.md, "Updates").

```bash
python3 scripts/release.py --check   # build and check, upload nothing
python3 scripts/release.py           # build, check and publish
python3 scripts/test_release.py      # what stops it
```

It runs from the main checkout, on `main`, with nothing uncommitted, and bumps nothing: bump
`versionCode` and `versionName` in `app/build.gradle.kts` in a commit of their own first. It reads
`~/.config/xrnkey/release.json`:

```json
{ "server": "https://<host>/<hub path>", "ssh": ["<ssh host>"], "signer": "<SHA-256>" }
```

`signer` is the installed keyboard's signing certificate, as `apksigner verify --print-certs` prints
it; an APK signed any other way stops there. `"dir"` names the folder on the server, under the ssh
login's home, if it isn't `xrnhub/data/update/xrnkey`.

The hub app also syncs the personal words with the laptop, while Settings → Personal words →
**Sync through xrnhub** is on (off at first). It reads and patches them through
`sync/WordsProvider.kt`, behind the keyboard's own signature permission (docs/feature-spec.md, "Sync").

## Credits

MiSans (Xiaomi) for every label: `app/src/main/res/font/misans_vf.ttf`. The Japanese dictionary
is derived from Mozc's (BSD 3-Clause, with the IPAdic notices): `../data/ja/NOTICE`.
