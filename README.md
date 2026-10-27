# Reels Guard

Android app that keeps you out of Instagram Reels, YouTube Shorts and TikTok.
You can allow yourself a few minutes a day, with a countdown notification while you watch.
It can also limit how far you scroll the Instagram feed.
No internet permission, no dependencies.

Download it from [Releases](../../releases).

## How it works

You choose what to block and how many minutes a day you get, up to 2 hours.
Only time spent in Reels, Shorts or TikTok counts, and a notification shows how much is left.
Making things stricter always works right away. Loosening them depends on the mode:

- **With a password**: loosening anything needs the password.
- **Without a password** (if you don't trust yourself): loosening only takes effect the next day.
  You set tomorrow's minutes today; if you don't, you get 30.

## Setup

1. Install the APK from [Releases](../../releases).
   Play Protect will probably block it, because the app uses Accessibility.
   Pause Play Protect while installing, or install with `adb install`.
2. Set a password (or let a friend set it), or set it up without one.
3. Android 13+: App info -> three dots -> *Allow restricted settings*.
4. Turn on Reels Guard under Accessibility.
5. Turn on what you want blocked and set your daily minutes.
   *Lock Settings screens* stops the app from being switched off or uninstalled.

To remove it, switch *Protection* off, apply, then uninstall.
Without a password, the switch-off takes effect the next day.

## Limits

Safe mode, `adb`, a factory reset or Instagram in a browser all get around it.
So does moving the date forward to get the next day's minutes early.
Instagram and YouTube can break the detection with any update.
If that happens, dump the screen with `adb shell uiautomator dump` and update the view IDs in `Guard.kt`.

## Building

    ./gradlew assembleDebug

`local.properties` can bake a preset or master (recovery) password into your own builds, see `tools/master_hash.py`.
`-Pshared` leaves both out, release builds from CI always use it.

Pushing a `v1.2.3` tag builds a signed APK and drafts a GitHub release.
It needs the `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` repository secrets.

## License

MIT, see [LICENSE](LICENSE).
