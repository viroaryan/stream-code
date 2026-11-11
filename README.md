<p align="center">
  <img src="assets/stream-logo.svg" width="96" alt="Stream logo">
</p>

<h1 align="center">Stream</h1>

<p align="center">
  Free, open-source YouTube client for Android. No ads, no tracking.<br>
  Made by <a href="https://github.com/viroaryan">Abhiraj Aryan</a> · Bihar, India
</p>

<p align="center">
  <a href="https://stream.arivna.com">Website</a> ·
  <a href="https://github.com/viroaryan/stream-releases/releases/latest">Download APK</a> ·
  <a href="https://stream.arivna.com/changelog">Changelog</a>
</p>

---

## Features

- Background play and Picture-in-Picture
- Video and audio downloads
- SponsorBlock and Return YouTube Dislike
- Subscriptions, playlists and history stored on your phone, no account needed
- Pure black theme with a rose-red accent
- In-app update notifications from [stream-releases](https://github.com/viroaryan/stream-releases)

## Download

Get the latest signed APK from the [website](https://stream.arivna.com) or from
[GitHub Releases](https://github.com/viroaryan/stream-releases/releases/latest).
Every release is signed with the same key, so updates install over the old version.

## Building

Requirements: JDK 17 and the Android SDK (platform 36).

```bash
./gradlew assembleDebug
```

Release builds are signed only when `updates/keys/keystore.properties` exists
(it is git-ignored and never committed). Without it, `assembleRelease` produces
an unsigned APK.

The in-app updater checks `viroaryan/stream-releases` by default. Point it at
another repo with `-PstreamGithubRepo=owner/name`.

## Credits and license

Stream is a fork of [Tubular](https://github.com/polymorphicshade/Tubular),
which is itself a fork of [NewPipe](https://github.com/TeamNewPipe/NewPipe).
Huge thanks to both projects and their contributors.

Stream is licensed under the [GNU General Public License v3.0 or later](LICENSE).

Stream is not affiliated with, endorsed by, or connected to YouTube or Google LLC.
