# SwipeYT

Tinder-style music discovery for Android on top of your YouTube Music account.

- A card shows the cover, title and artist of a track while a short preview plays.
- Swipe right (or tap the heart) to like — the track is added to the **Swipe YT** playlist in your
  YouTube Music library. Swipe left to skip. The next track starts immediately.
- The **Swipe YT** tab shows the playlist from your account; tracks can be previewed, removed, or
  opened in YouTube Music.
- Recommendations are computed on the device from YouTube Music home/mixes/radio/genre feeds and
  re-ranked with a Thompson-sampling bandit over your local likes/dislikes.
- Material 3 Expressive UI (Compose).

Everything runs on the phone: no backend, no API keys. The app talks to the same private
InnerTube endpoints the YouTube Music web client uses, authenticated with the cookies from a
normal Google sign-in inside an in-app WebView. Credentials never leave the device.

## Build

Requirements: JDK 17, Android SDK with platform 36 (`sdk.dir` in `local.properties` or
`ANDROID_HOME`).

```bash
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Project layout

```
app/src/main/java/com/swipeyt
├── data/auth        AuthStore – cookie/visitor/datasync persisted in SharedPreferences
├── data/innertube   InnerTube client (WEB_REMIX for account ops, ANDROID_VR/IOS for streams), parsers
├── data/local       UserStore – swipes + settings as a JSON file
├── data/repo        PlaylistRepository – find/create "Swipe YT", add/remove/list
├── domain           Track model, Recommender (candidate generation + bandit ranking)
├── player           PreviewPlayer – ExoPlayer preview clips with prefetch and stream fallback
└── ui               Compose screens: login, onboarding, discover deck, playlist, settings
```

## Notes

- YouTube Music has no public API; InnerTube request shapes are reverse-engineered and may
  change. Client versions live in `data/innertube/YtClient.kt`.
- Material 3 Expressive components are only public in `material3` 1.5.0 alphas; the version is
  pinned in `gradle/libs.versions.toml` (alpha19+ require compileSdk 37 / AGP 9.1).
