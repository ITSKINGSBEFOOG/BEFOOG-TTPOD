# BEFOOG TTPOD — Android music player

This is an Android source project for BEFOOG TTPOD. It is designed to be built in the cloud, so the user's Windows 7 32-bit laptop does not need a modern Android Studio installation.

## Implemented in this milestone
- BEFOOG TTPOD branding
- Android MediaStore local music scan and global song search
- Basic ExoPlayer playback controls
- Create and persist named playlists on the device
- Add library songs to playlists
- A separate search box inside every playlist; each box filters only that playlist's own songs
- Independent Shake to Next and Shake to Previous ON/OFF switches
- Basic shuffle and repeat controls
- GitHub Actions workflow to build a debug APK on push or manually

## Still required before calling this a finished player
- Foreground media playback service and Android media notification/lock-screen controls
- Sleep timer
- More thorough queue behavior and playback-state handling
- Persist shake settings and playback modes
- Build and install testing on the target Infinix / Android 15 device
- Validate Gradle build in GitHub Actions

## Cloud build steps
1. Create a public GitHub repository named `BEFOOG-TTPOD` and initialize it with a README.
2. Upload the contents of this project folder to the repository root (not the containing folder itself).
3. Open the repository's **Actions** tab and enable workflows if GitHub asks.
4. Select **Build BEFOOG TTPOD APK** and press **Run workflow**, or push a commit to `main`.
5. When the workflow succeeds, open its run and download the `BEFOOG-TTPOD-debug-apk` artifact.

The APK is a debug build for testing, not a Play Store release. Do not install it until the build succeeds. The package ID is `com.itskings.ttpodreborn`.
