# Coolapk Update Blocker (alpha)

Standalone LSPosed module for `com.coolapk.market`, initially analyzed against CoolApk 13.0.1.

## Current status
This is a diagnostic alpha, not yet a verified self-update blocker. It hooks OkHttp clients, logs Coolapk-owned host + path only (no query strings, headers, tokens, or request bodies), and supports exact-host + path-regex rules. The default rules are intentionally empty until the self-update endpoint is confirmed.

## GitHub Actions build
The repository workflow is `.github/workflows/coolapk-update-blocker.yml`. Pushes to branch `coolapk-update-blocker-alpha` that change this project start a build. The resulting APK is uploaded as a workflow artifact named `CoolapkUpdateBlocker-alpha-N`.

## Local build
JDK 17, Android SDK Platform 35, Gradle 8.9:

```sh
gradle assembleRelease
```

## Device test
Install the APK, enable it in LSPosed for the Coolapk scope only, force-stop Coolapk and launch it. Trigger its own version check and inspect:
`/sdcard/Android/data/com.coolapk.market/files/CoolapkUpdateBlocker.log`

Add only the confirmed self-update endpoint to `CoolapkUpdateBlocker.rules`; never block the whole API domain. Force-stop and restart Coolapk after editing the rules.

## Compatibility limits
Current interception covers OkHttp 3/4 clients visible to the app classloader. Cronet, WebView, native sockets, a future network-stack replacement, or dynamically loaded OkHttp may need additional adapters. Cross-version compatibility is based on network endpoint matching, not a guarantee against future server/protocol changes.
