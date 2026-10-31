# CAN Talk

This repository is the CAN Talk fork, with Android application ID `can.cantalk`.
The independent test app uses `can.cantalk.cantalktest`. This release targets
phones. Wear OS sources are retained with their build variants disabled by default.
Official release builds
require a private signing key supplied outside Git. The selected candidate is
`1.0.0-rc.1`; a maintainer builds and signs locally, with a second collaborator
keeping a recovery copy. Development builds still use debug keys and a dated
`-dev-` version suffix. See [COMPILACION.md](COMPILACION.md) for
build instructions and [LANZAMIENTO.md](LANZAMIENTO.md) for release preparation.
The upstream documentation below describes Backtalk; CAN Talk's updater only
checks this fork's repository.

## Upstream Backtalk documentation

Backtalk is a fork of [Google's TalkBack](https://github.com/google/talkback), the screen reader for blind and visually-impaired users of Android. It adds fixes and features on top of Google's source releases. It is not affiliated with Google.

## Goals

*   Make Backtalk faster and more responsive for people who use their phone quickly.
*   Bring back useful behavior from older TalkBack and other screen readers.
*   Keep the build working on Windows, Linux, and macOS.
*   Move the code to Kotlin over time. New code is written in Kotlin. Google's Java files are converted only when they already need large changes, so that new Google releases stay easy to merge.
*   Stay close to Google's releases, and merge each new one.

## Differences from TalkBack

Backtalk speaks sooner after each swipe and screen change, and can play its sounds and speech through a low-latency audio path. It adds a two-finger rotor, a circle menu, Pause Backtalk, direct touch for audio games, sound themes with control sounds in 3D, a vibration for every sound, a status gesture, and braille keyboard dots that follow how you hold the device. It also changes some defaults and fixes problems in TalkBack. For the full list, see [Differences from TalkBack](differences.md).

## Third-party data

The emoji names are from the [Unicode CLDR](https://cldr.unicode.org/) and the Unicode emoji data, copyright Unicode, Inc., used under the Unicode License v3, which is in `utils/src/main/assets/emoji_names/LICENSE`.

## Build

Para Can Talk, consulta la [guía de compilación en español](COMPILACION.md): requisitos, Windows, Linux/macOS, APK independiente de prueba e instalación por ADB.

You need JDK 17 or newer, the Android SDK with platform 37, and NDK 27.3.13750724. The Gradle wrapper downloads the correct Gradle version, so you do not need to install Gradle.

### Linux or macOS

Set `ANDROID_SDK` to your SDK path, then run `./build.sh`. This builds the phone and watch APKs, and keeps anything else that's in `local.properties`, such as a Gemini API key.

### Windows

Set `ANDROID_HOME` to your SDK path, then run:

```
.\gradlew.bat assemblePhoneDebug
```

### Dependencies

Library and plugin versions are in `gradle/libs.versions.toml`. Renovate opens pull requests to update them each week, and GitHub Actions builds each pull request.

### Image and screen descriptions with ChatGPT

CAN Talk supports "Sign in with ChatGPT" to describe images and screens using your personal ChatGPT subscription (such as ChatGPT Plus or Pro).

1. Open CAN Talk settings, then **Automatic descriptions**, then **ChatGPT account**.
2. Tap **Sign in with ChatGPT** to authorize in your browser.
3. Once authorized, image and screen descriptions and follow-up questions will use GPT-4o or GPT-4o mini directly from your plan, without needing separate API keys. Alternatively, you can configure a manual OpenAI API key (`sk-...`).

### Image descriptions with Gemini

Google's source release does not include the Gemini settings, so **Describe image** only reads text in images, and **Describe screen** does not work, unless you add your own Gemini API key. Backtalk adds its own support for Describe screen, including follow-up questions. To add a key:

1.  Open Backtalk settings, then **Automatic descriptions**, then **Gemini API key**.
2.  Choose **Get a key** to open [Google AI Studio](https://aistudio.google.com/apikey) and create a key.
3.  Paste the key into the field, and choose **Save**. It works at once.

To remove the key, clear the field and save.

If you build Backtalk yourself, you can also build a key into the APK instead. Add `gemini.api.key=YOUR_KEY` to `local.properties` in the project folder, then build and install Backtalk again. A key entered in settings takes the place of the built-in key. Git ignores `local.properties`, so your key is not committed, but do not share an APK that contains your key. To use a different model, add `gemini.model=MODEL_NAME`. The default is `gemini-flash-latest`.

Images and screenshots that you describe are sent to Google. On the free tier, Google can use this data to improve its products.

### On-device AI

If you do not want to use an API key, or you have hit the free tier limit, Backtalk can describe images and screens with a Gemma 4 model that runs on your phone. Nothing is sent to Google or anyone else when you use it.

1.  Open Backtalk settings, then **Automatic descriptions**, then **On-device AI**.
2.  Choose a model. **Gemma 4 E2B** is the one to start with: 2.6 GB, and it needs a phone with about 6 GB of memory. **Gemma 4 E4B** is 3.7 GB, gives better answers, and needs about 8 GB. The list also has other small vision models from the [LiteRT community](https://huggingface.co/litert-community), from 0.4 GB up, so that you can try them. Those are marked experimental: they are community conversions that the Backtalk developers have not tried, and some may not work or may follow the screen description format badly. The list only shows models that your phone has enough memory for, plus any you already have. If your phone does not have enough memory for Gemma 4 E2B, Backtalk picks the largest model that fits.
3.  Choose **Download model**. It downloads once from [Hugging Face](https://huggingface.co/litert-community) and carries on where it stopped if the connection drops. A notification shows the progress. Backtalk checks the file against a known SHA-256 hash and deletes it if it does not match. Or choose **Use a model file from storage** to use a `.litertlm` file that you downloaded yourself, such as `gemma-4-E2B-it.litertlm` from `litert-community/gemma-4-E2B-it-litert-lm`. Backtalk works out which model it is.
4.  Turn on **Use on-device AI**.

You still need to turn on Gemini support in the Gemini settings, which switches on Describe image and Describe screen. After that, they use the model on your phone. Turn **Use on-device AI** off to go back to the Gemini API.

Answers take several seconds and use battery, more than the cloud on a mid-range phone. The model loads on the first request and unloads after two idle minutes to free memory. The model runs in its own process, so if the phone runs out of memory, Android stops the model and not Backtalk, and Backtalk says so. Before it loads a model, Backtalk checks that the phone has at least as much free memory as the model's size, and if not, it says there is not enough free memory and does not load it. To test with a model that is too big for your phone, turn on **Ignore on-device AI memory limits** in **Advanced settings** > **Developer settings**. It lists every model, lets you choose any downloaded one, and skips the free memory check. If answers fail or the phone slows down, try the other model, or turn **Use the GPU** on or off. On-device AI needs a 64-bit ARM phone and adds about 22 MB to the app.

## Install

Install the APK on your device with adb.

On Windows, `.\deploy.ps1` builds the APK and installs it with adb. This script needs PowerShell 7. To install the last build without building again, use `-SkipBuild`.

CAN Talk installs as `can.cantalk`, with separate settings from Google's TalkBack and Backtalk. The independent test variant uses `can.cantalk.cantalktest`. This release builds only the phone APK; Wear OS is deferred.

### Earlier CAN Talk builds

Earlier CAN Talk builds used `fyi.quin.backtalk` or `fyi.quin.backtalk.cantalktest`. A build with the new ID installs alongside them rather than updating them. Enable CAN Talk, its braille keyboard and accessibility shortcuts separately. Settings are not copied automatically; export a settings backup from the previous app and import it in the new one if needed. Backtalk's historical automatic hand-over does not run under CAN Talk's new ID.

## Updates

CAN Talk checks the [dev release in marcomolinaleija/can-talk](https://github.com/marcomolinaleija/can-talk/releases/tag/dev) for the phone APK `cantalk.apk` and only offers a build whose commit count is higher than the installed build. Automatic publication of development APKs is disabled while the first official release and its signing key are prepared. The manual **Release candidate** workflow builds the signed phone artifact from `master`; it does not publish a GitHub release. No watch APK is built or distributed for this release.

To check now, go to **Check for updates** in CAN Talk settings. Automatic checks run shortly after the screen reader starts and about once a day while it runs. Turn off **Automatically check for updates** to disable them. An available update shows a notification with its changes; tap it to download and open Android's installation prompt. The first time, Android asks you to allow CAN Talk to install apps.

The connection is prepared for this repository. The app includes no GitHub credentials: checks query GitHub's public API directly. An accessible public release is required for automatic update downloads. CAN Talk never falls back to Backtalk's releases.

Android only installs an update with the same package ID and signing key. Official release APKs require CAN Talk's private production key; the shared debug key is only for development. The independent `-Prueba` app has a different ID and no corresponding release asset, so update settings and automatic checks are disabled for that variant. See [COMPILACION.md](COMPILACION.md) for building and signing.

## Run

After you install CAN Talk, go to **Settings > Accessibility**. CAN Talk is listed as **can-talk** and is off by default. Turn off Google's TalkBack or other screen readers first, then turn on CAN Talk.

## Android TV

CAN Talk retains the Android TV support from TalkBack, and the same phone APK installs on Android TV and Google TV. It has been tested on Android TV emulators.

1.  Turn on developer options and network debugging on the TV, then connect to it with `adb connect` and the TV's IP address.
2.  Install the APK with `adb install cantalk.apk`.
3.  Turn on CAN Talk in the TV's accessibility settings. On most Android TVs, this is **Settings** > **Device Preferences** > **Accessibility**.

To open CAN Talk settings, choose **CAN Talk settings** in the apps on the TV home screen. TVs often don't show notifications, so to update, go to **Check for updates** in CAN Talk settings. Pause is not available on TVs, because a TV remote often has no way to resume it.

## Debug tools

Debug builds include tools to find lag:

*   The `BacktalkStall` logcat tag logs each time the main thread is blocked for more than 100 ms, with the code that was running.
*   The `BacktalkGesture` logcat tag logs touch state changes and each gesture that Backtalk detects.
*   Event processing has trace sections, which show in [Perfetto](https://perfetto.dev) system traces.
