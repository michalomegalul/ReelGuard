# Reels Guard

Android app that keeps you out of Instagram Reels, YouTube Shorts and TikTok.
You can allow yourself a few minutes a day, with a countdown notification while you watch.
It can also limit how far you scroll the Instagram feed.
No internet permission, no dependencies.

Download it from [Releases](../../releases).

## How it works

Someone you trust sets the password.
Making things stricter is always free, loosening them needs the password.
No one to hold the password? Set it up without one: then loosening only takes effect the next day.

## Setup

1. Install the APK from [Releases](../../releases).
2. Set the password, or let a friend set it.
3. Android 13+: App info -> three dots -> *Allow restricted settings*.
4. Turn on Reels Guard under Accessibility.
5. Turn on what you want blocked and set your daily minutes.
   *Lock Settings screens* stops the app from being switched off or uninstalled.

To remove it, switch *Protection* off, apply, then uninstall.

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
Don't lose the keystore, updates won't install without it.

## License

MIT, see [LICENSE](LICENSE).
