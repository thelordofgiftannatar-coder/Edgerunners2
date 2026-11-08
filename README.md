# THE WITCHER — Android APK project

This wraps your existing THE WITCHER media-player interface in an Android WebView app and uses your supplied character artwork as the launcher icon.

## Build the APK using GitHub (no Android Studio required)

1. Create or open your GitHub repository.
2. Upload **the contents of this ZIP** to the repository's root. Keep the `.github/workflows/android-apk.yml` path intact.
3. Commit the files to the `main` branch.
4. Open the repository's **Actions** tab.
5. Select **Build THE WITCHER APK** and open the latest successful run.
6. Under **Artifacts**, download `THE-WITCHER-Android-APK`.
7. Extract the downloaded artifact ZIP. It contains `app-debug.apk`.
8. Transfer `app-debug.apk` to your Android phone and install it. If Android asks, allow installation from that source.

A push to `main` also starts the build automatically.

## Notes

- This workflow produces a debug APK for testing and personal installation, not a Play Store-signed release.
- The app name is THE WITCHER and the supplied artwork is its launcher icon.
- Use the app's add button to select media files. The Android file picker supports selecting multiple files. The browser-only folder picker feature is not available in this native WebView wrapper.
- Your media files are selected from your device; they are not bundled into the APK.

## v1.1 - remembers your library
- Files and folders you add are remembered between launches (the app keeps permission to read them), together with your playlists.
- Adding a file that is already in the library is skipped.
- Prefer adding whole folders (folder icon): one folder permission covers every song inside, and "Rescan saved folder" picks up new songs.
- Uninstall the old APK before installing this one (each GitHub build is signed with a different debug key).
