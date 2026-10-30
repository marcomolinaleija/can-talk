# Speech translation: use, limits and provider migration

Can Talk can translate speech continuously or translate the last spoken phrase
once. The active provider is **Google Translate web via the InstantTranslate method,
experimental and unofficial**. This test build bundles a web service credential
injected locally during compilation, not a personal Cloud API key. Translation is initially off and remote processing requires
explicit consent. App text, editing, clipboard, voice input and braille are not
changed. See [the analysis and NVDA comparison](translation-analysis.md) for rationale.

## Quick test

1. Open Can Talk settings → Controls → Translation. Read and accept **Allow online
   translation** only if you agree to send requested speech to Google.
2. Choose **Translate into** (system language by default) and optionally a fixed
   source language. Automatic source detection is the default.
3. In an external app, open the Can Talk menu → **Start continuous translation**.
   Navigate between items. Use **Stop continuous translation** to return to normal.
4. For one phrase, use **Translate last spoken phrase** from the menu, or assign a
   gesture in Gestures → Reading controls. Both actions are assignable; no existing
   assignment is overwritten.
5. Compare first visit and revisiting the same label. Test app exclusions, clear
   cache, offline fallback, fast swipes and stopping while a request is pending.

The settings switch controls the same service-session mode as the menu. Restarting,
disabling or suspending the reader resets continuous mode to off. Locking prevents
new translation and cancels pending preparation; unlocking does not itself disable
the session toggle. Translation-control announcements and Can Talk's own UI are
not translated, to avoid recursion and to keep controls usable.

## Privacy and failure behavior

| Boundary | Behavior |
| --- | --- |
| Remote opt-in | Both modes need consent; no silent switch to another provider. |
| Sensitive content | Password events, focused password fields, Android 14+ marked sensitive fields and excluded apps are rejected. Consent does **not** make all private text detectable: messages, names, notifications and financial text can still be sent. |
| Lock / pause / mute | No new uploads; completion rechecks policy. Stop, interruption and lifecycle shutdown cancel obsolete requests. |
| Origin | Source package from event metadata or focused/root node; unknown origin and this reader's own package are excluded. This is conservative and may leave some announcements untranslated. |
| Length | At most 16,000 UTF-16 characters per utterance. Longer continuous items remain original; one-shot reports the limit. |
| Network | HTTPS GET to `translate-pa.googleapis.com/v1/translate`, JSON/protobuf response, 1,200-character chunks with safe Unicode boundaries, 3-second connect/read timeouts, six-second overall deadline. Text and the credential are in the encrypted request query; never log URLs. Chunks are sequential and translation is all-or-original. |
| Error | Original speech continues; failures back off for 15 seconds. A spoken error is limited to once per minute. No retries, mirrors, captcha handling or alternate remote provider. |
| TTS | Target language applies to this utterance only. The installed TTS must support it; voice quality/model availability is a device test, not proved by unit tests. |
| Text offsets | Translated speech has no reliable correspondence to original node offsets, so original range-selection callbacks are disabled for translated items. Completion/continuous-reading callbacks remain active. |

The provider makes no HTTP calls until an eligible utterance reaches the speech
queue. It does not translate neighboring nodes or populate its cache from audio
prefetch. Rapid navigation invalidates older results, which cannot interrupt the
new item. Failure must never prevent using the untranslated reader.
If a privacy transition makes an item ineligible while waiting, it is cancelled
rather than speaking either the translation or the old private original.

## Cache controls

Default: an in-memory LRU partitioned by source application. **Shared across apps**
can reuse identical labels between apps; **No cache** disables result storage.
Both modes share the selected policy. There is no persistent utterance file,
database, cloud sync or restored private translation cache.

- Up to 500 entries and 512,000 UTF-16 characters (source plus translated text).
- Entries expire after 24 hours using a monotonic clock.
- Keys include provider/version, source and target languages, exact text and scope.
- Only successful translations are cached; no errors or partial chunk results.
- Clear the last external app or all entries from settings. In shared mode,
  clearing the app clears the shared cache.
- Language, consent, exclusion or cache-setting changes cancel speech preparation
  and clear results. Clearing advances a generation counter so an old request
  cannot refill the cache after the clear.
- Service shutdown clears all results. Identical in-flight requests are coalesced
  even when storage is off; this is temporary request sharing, not disk retention.

Android package visibility can limit the app-exclusion list. The last observed
external app and existing exclusions are retained in that list even if Android
does not return them in the installed-app query. No broad package visibility
permission was added.

## Architecture and change boundaries

| Layer | Owner | Contract |
| --- | --- | --- |
| Queue hook | `utils/.../output/SpeechTextTransformer.kt`, `SpeechControllerImpl.java` | Capture source/policy metadata; transform asynchronously; keep queue/group/start/completion semantics; invalidate stale results. No Google imports. |
| Per-item metadata | `FeedbackItem.java` | Original source, app, protected/applied state, one-shot and no-translation flags. Snapshot/deep copy retains association. |
| TTS target | `FailoverTextToSpeech.java` | Per-utterance forced locale overrides automatic language preference without mutating global settings. |
| Policy | `talkback/.../translation/TranslationController.kt` | Consent, app, lock, lifecycle, history, modes, failure announcements and preference changes. |
| Execution/cache | `TranslationEngine.kt`, `TranslationCache.kt` | Bounded jobs, cooperative cancellation, deduplication, deadline, cooldown, LRU/TTL and clear generation. No Android dependency. |
| Provider | `TranslationProvider.kt`, `GoogleInstantTranslationProvider.kt` | Plain text/languages in; plain text/detected source out. Provider owns encoding, transport, validation and chunking. The old `GoogleWebTranslationProvider.kt` is retained but is not an automatic fallback. |
| Configuration | `TranslationSettings.kt`, `TranslationFragment.kt` | Same device-protected preference storage as service; transient continuous toggle; static Google language catalog. |
| Commands | Gesture mapping/list/controller, context-menu processor and resources | Two actions, deferred menu execution and saved pre-menu history. |

Rollback boundary: remove translation UI/commands and the service's transformer
installation to restore ordinary speech without changing braille, navigation,
voice-input or AI-description behavior. Git delivery is separately authorized by
the user; rollback must preserve any later unrelated changes.

### Add another provider

1. Implement `TranslationProvider` in Kotlin. Give it a unique versioned `id`,
   `sendsTextOffDevice` and maximum input size. The engine calls its synchronous
   `translate` on a worker, **not** on the accessibility thread. Honor the
   cancellation token and close its active network/model operation on cancellation.
2. Register the adapter in `TranslationSettings.createProvider`. Add provider UI
   and its own language catalog/configuration. Replace the service-scoped engine
   atomically after cancelling old work, or require a reader restart; the current
   build has no selectable second provider and does not hot-swap engines.
3. Keep private credentials out of source/APK/logs. The current experimental
   InstantTranslate adapter is an explicit exception for its bundled web service
   credential; it is extractable from the APK and must never be a private key.
   Supply it through `CANTALK_TRANSLATION_INSTANT_KEY` only for the build process.
   An unset variable produces an unconfigured provider, not a silent fallback.
   Cloud and Gemini need their own
   credential, billing and consent flows; never reuse image-description credentials
   automatically. A user-owned key or authenticated backend needs a separate design.
4. ML Kit must declare local processing and model-download setup separately from
   per-text network consent. Preserve local-only behavior when offline; expose
   unsupported pairs/model readiness rather than silently switching to Google web.
5. Gemini should request translation only, not follow instructions contained in
   source text. Validate response size/format, latency and refusal/error behavior.
   Do not pass accessibility nodes, history, app data or prompts from unrelated AI
   features to the adapter.
6. Keep the provider tests, cache/version isolation tests and cancellation tests.
   Revisit the six-second interactive deadline for slow model setup rather than
   blocking speech while downloading or warming a model.

Future official-provider work should add typed error categories (quota, setup,
model unavailable), a provider-owned language catalog and a setup/readiness
screen. These are extension requirements, not advertised working features today.

## Build and proof

For the latest device iteration, the user explicitly requested **compilation and
ADB installation without tests or lint**. Only `:assemblePhoneDebug` is run;
previous test results do not validate the new InstantTranslate adapter. The two
translation actions now precede the settings footer, with Can Talk settings and
text-to-speech settings last. Live translation remains for user verification.

Use the repository's isolated Windows launcher:

```powershell
.\compilar.ps1 -Prueba -Tareas @('testPhoneDebugUnitTest', ':lintPhoneDebug', ':assemblePhoneDebug')
```

If Java cannot create temporary files in the virtualized AppData Gradle home,
the verified per-run override is:

```powershell
.\compilar.ps1 -Prueba -Tareas @('testPhoneDebugUnitTest', ':lintPhoneDebug', ':assemblePhoneDebug',
  '--gradle-user-home', 'C:/Users/angel_hmv3h04/can-talk-tools/gradle-user-home')
```

The override does not change project configuration or another application's Java
environment. APK output is `build/outputs/apk/phone/debug/cantalk-phone-debug.apk`;
test package is `can.cantalk.cantalktest`. Verify metadata/signature and record
the artifact hash after assembly. Do not infer installation or screen-reader
activation authorization from build authorization.

Unit tests cover fake-transport parsing/encoding/chunks, LRU/TTL, coalescing,
in-flight clear/cancel/backoff, privacy policy and real speech-queue preparation
with a fake TTS boundary. They do not prove audible output or successful Google
access. A development-network synthetic request returned HTTP 429. No Android
device was connected during the initial audit. User phone testing must cover:

- successful short and long live translations, correct voice and cached revisits;
- offline/429 fallback, quick focus movement, queue order and full-screen reading;
- one-shot original after continuous translation and after opening the menu;
- password fields, marked sensitive nodes, exclusions, lock, pause and mute;
- consent revoke, settings changes, restart reset and accessible preferences.

Final command results and artifact identity belong in the handoff, not an assumed
release claim. Do not widen `lint-baseline.xml` to accept new translation defects.
See [the current verification record](translation-verification.md) for the test
APK hash, passing suite, non-green lint gate and remaining live-device checks.
