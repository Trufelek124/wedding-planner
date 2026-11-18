# The Android app's release build

The phone app is built in two ways. The **debug** build is for trying things: it may talk plain http, offers the
development sign-in where the server does, and is signed with a key every Android toolchain makes for itself. The
**release** build is the one that goes on a phone to be used: it is made small, talks https and nothing else, and
is signed with a key that only the owner has.

The gate builds the release variant at every run, unsigned, to know that it can still be built. Signing happens
only on the owner's machine, with what this document sets up. The key and its passwords are never in the
repository, which is public.

## 1. Make the signing key (once)

On the Mac, somewhere outside the repository:

```bash
mkdir -p ~/keys
```

```bash
keytool -genkeypair -v -keystore ~/keys/wedding-planner-release.jks -alias wedding-planner -keyalg RSA -keysize 4096 -validity 10000
```

It asks for a password and for a name to put into the certificate. The name can be anything.

Keep the file and the password somewhere that survives the Mac. Android only lets a newer build replace the
installed app when both are signed with the same key: with another key the app has to be uninstalled first, and
whatever it had not sent to the server yet is lost with it.

## 2. Tell the build where the key is

Four values, all of them or none. In `~/.gradle/gradle.properties` on the Mac (this file is the Mac's own, not
the repository's):

```properties
wpReleaseStoreFile=/Users/<you>/keys/wedding-planner-release.jks
wpReleaseStorePassword=<the password>
wpReleaseKeyAlias=wedding-planner
wpReleaseKeyPassword=<the password>
```

The same four can come from the environment instead, as `WP_RELEASE_STORE_FILE`, `WP_RELEASE_STORE_PASSWORD`,
`WP_RELEASE_KEY_ALIAS` and `WP_RELEASE_KEY_PASSWORD`. With none of them the release build is made unsigned; with
only some of them the build stops and says which are missing.

## 3. Register the key for Google sign-in

Google only hands the app a proof of who signed in when it knows the app: by its id and by the fingerprint of
the key it is signed with.

Read the fingerprint:

```bash
keytool -list -v -keystore ~/keys/wedding-planner-release.jks -alias wedding-planner
```

Take the line that starts with `SHA1:`.

In the Google Cloud console, in the **same project** as the web client whose id the server has as
`GOOGLE_OAUTH_WEB_CLIENT_ID` (see `docs/deployment.md`): APIs & Services → Credentials → Create credentials →
OAuth client ID → Application type **Android**, with

| Field | Value |
|---|---|
| Package name | `io.github.trufelek124.weddingplanner` |
| SHA-1 certificate fingerprint | the fingerprint from above |

This Android client has no secret, and its id goes nowhere: that it exists is what lets the app ask. The proof the
app receives is made out to the web client, which is the one the server checks.

To try Google sign-in in a **debug** build as well, a second Android client is needed with the fingerprint of the
debug key (`keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`).

## 4. Build

The server's address is part of the app, fixed when it is built, and a release build needs the https one:

```bash
cd android && ./gradlew checkReleaseApk -PserverUrl=https://<the planner's address>
```

The last lines say what was made, for example `release app: app-release.apk, 5104 KiB, signed`. The app is
`android/app/build/outputs/apk/release/app-release.apk` (`app-release-unsigned.apk` when no key was given; that
one cannot be installed).

`checkReleaseApk` also looks into the app after it was made small: that what Android and the libraries find by
name is still in it, and that it neither allows plain http nor can be debugged.

To see which key signed it:

```bash
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs android/app/build/outputs/apk/release/app-release.apk
```

## 5. Install on a phone

With the phone on a cable and USB debugging on:

```bash
adb install -r android/app/build/outputs/apk/release/app-release.apk
```

Or send the file to the phone and open it there; Android asks once whether apps may be installed from that source.

A debug build that is already on the phone is signed with another key and has to go first:

```bash
adb uninstall io.github.trufelek124.weddingplanner
```

## When the app changes

Before a build is handed out, raise `versionCode` in `android/app/build.gradle.kts` by one and set `versionName`
to what the build is called. Android refuses to put an older `versionCode` over a newer one.

## What was and was not tried

Tried where the app is built, without a phone: the release variant builds; half a key and a plain-http address stop
the build with a sentence that says why; a build with a throwaway key is signed with exactly that key; the check
fails for an app that lacks a class or allows plain http.

Not tried anywhere yet, and the owner's to try (WP-TS-50): installing the signed build on a phone, starting it,
signing in with Google, and that the small build behaves like the debug one.
