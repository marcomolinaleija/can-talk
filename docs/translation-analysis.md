# Speech translation: integration analysis

Status: implementation on `codex/translator`, October 8, 2026. After both modes
failed on the phone, the user requested the installed InstantTranslate protocol
for this test build, replacing the initial keyless Google web adapter. The
service credential is injected from the local addon into the build environment,
not committed or logged; it is necessarily bundled in the experimental APK.
Cloud, ML Kit and Gemini are future
adapters, not fallback services or working options in this build. This is ordinary
feature work, not an SDD transaction. See [the usage and maintenance guide](translation.md).

## Scope and current state

The working tree was clean on `codex/desarrollo` at `39e1eb3e` before creating
`codex/translator`. The repository contains 4,654 tracked files. This review maps
the modules and examines translation-related owner files; it is not a claim that
every line or every unrelated feature has been audited.

| Area | Responsibility and relevance |
| --- | --- |
| Root application | Packages the phone/wear variants; test package is `can.cantalk.cantalktest`. |
| `talkback` | Accessibility service, event interpretation, compositor, feedback pipeline, actors, navigation, menus, gestures, preferences, AI descriptions, and updates. Owns translation policy and UI. |
| `utils` | Shared speech processing, queue, history, TTS, language handling, sounds, haptics, event and node utilities. Needs a provider-neutral speech integration boundary. |
| `braille` | IME, display, interfaces, translation tables and native liblouis. Braille translation is a different feature and must not be replaced by natural-language translation. |
| `material` | Accessible preference widgets and phone/wear themes. Reuse existing controls. |
| Build/tooling | Gradle wrapper, shared configuration, Windows isolated environment, lint baseline, JUnit/Robolectric tests and resource localization. Preserve the configured environment. |

No repository `AGENTS.md` files were found. New implementation should use Kotlin,
consistent with the README. New user-visible strings need default resources and
Latin American Spanish equivalents, following `LOCALIZACION.md`.

## Verified speech boundaries

`TalkBackService` constructs the pipeline, actors and `SpeechControllerImpl`.
`Actors` routes `Feedback.Speech` to the controller. Other components also use the
controller, so translating only in the gesture handler or in `Actors` would not
cover all speech.

The central `SpeechControllerImpl.speak(...)` converts content-description and
braille spans, processes emoji, builds feedback fragments, then applies queue
semantics. A provider-neutral asynchronous hook captures the source before emoji
processing and prepares translation only when the queued item is about to start,
with policy implemented in `talkback`, not a Google dependency in `utils`.

Important invariants:

- Speech history is updated when a feedback item starts. One-shot translation
  must refer to the last started utterance, not the latest request or focused node.
- Menus already snapshot the last utterance for repeat/copy commands. Menu speech
  must not overwrite the item a translation command intends to use.
- Queue mode, group interruption, start/completion actions and continuous-reading
  callbacks are functional contracts. Delayed translations must preserve them.
- `EarlyFocusSpeech` suppresses duplicate focus events; `PreparedFocusSpeech`
  prepares neighboring announcements. Neither is a translation cache.
- `SpeechCacheController` and the TTS cache hold synthesized speech. They must not
  prefetch remote translations for nodes the user has not asked to hear.
- `LanguageSwitch` can override locale spans based on the selected voice and
  automatic-switch preferences. A translation's destination must be spoken in
  its destination language without permanently changing normal TTS preferences.
- `FLAG_NO_HISTORY` is not a reliable sensitive-data classification. Password
  event rules and sensitive nodes require explicit exclusions before translation
  and before caching, including the one-shot path.

## Provider comparison and selected approach

| Option | Credentials and network | Tradeoff |
| --- | --- | --- |
| Google Cloud Translation Basic v2 | User-managed API credential; text sent to Google; usage-based billing. | Documented official API. Never bundle a shared secret or silently enable billing. |
| Google Translate web endpoint | Usually no API key; text sent to Google. | Used by some NVDA add-ons, but not a supported public integration contract. Can change or be blocked. Must be labeled experimental with bounded fallback. |
| Google ML Kit on-device translation | Language-model downloads, then local processing; no Cloud API credential. | Uses Google Translate offline models. More private and no per-text network latency, but language coverage and translation quality differ from online translation. |

Do not silently substitute one option for another or fall back to a different
remote provider. The cloud implementation should not reuse the Gemini image
description key: these are separate products and credential configurations.

## Implemented feature contract

1. A dedicated Translation preference screen: provider information, destination
   language (system by default), source auto-detection where supported, privacy
   notice, cache controls, and provider-specific setup.
2. Two independently assignable gesture actions: toggle continuous translation
   and translate the last spoken utterance. Do not replace existing assignments.
3. A Can Talk menu toggle with an explicit enabled/disabled announcement. Keep
   translation-control messages out of translation to avoid recursion.
4. Translation disabled initially. Remote processing requires an understandable
   in-app opt-in. Service restart must not silently resume uploading speech.
5. Store source/result association separately from the spoken translation; never
   repeatedly translate a translation or lose the original text on failure.
6. Keep braille output, text editing, accessibility nodes and clipboard unchanged.
   This feature translates speech output, not the application UI or user input.

### Responsiveness and failures

Never block the accessibility main thread. Navigation should cancel or invalidate
obsolete work; an older response must not cut off a newer focus announcement.
Queued reading must remain ordered rather than dropping every pending utterance.
Stop, pause, mute, provider/language changes, service shutdown and lock-screen
transitions need explicit cancellation and privacy handling.

Use bounded requests, deadlines and input lengths. Coalesce identical in-flight
requests, handle Unicode safely, and avoid retry storms on connectivity failure,
rate limiting or unsupported languages. Fail back to the original utterance,
with a non-repeating accessible error indication. Do not log speech, translated
content, API keys or raw response bodies.

### Cache policy

Separate translation results from synthesized audio. Default to a bounded,
memory-only LRU cache partitioned by source application; offer optional global
sharing for repeated labels and a way to disable caching. Keys must include
provider/version, source and destination language, text and application scope.
Do not normalize away punctuation or case that could change meaning.

Provide clear-current-app and clear-all controls, entry limits and expiration.
Cache only successful results. Sensitive text is never cached. Persistent cache,
if included, needs explicit opt-in, secure storage, no Android backup, and a clear
retention policy; do not write private utterances to plaintext JSON files.
Clearing must also prevent an older in-flight request from refilling the cache.

## Verification boundaries

- Provider tests: request encoding, response parsing, Unicode, empty/malformed
  responses, unsupported languages, timeouts and HTTP errors, with fake transport.
- Coordinator tests: stale results, stop/pause/mute/shutdown, queue ordering,
  callback completion, one-shot history, no recursion, and original-text fallback.
- Cache tests: app/global isolation, provider/language separation, LRU/TTL,
  coalescing, sensitive exclusions and clearing during requests.
- Integration/resource tests: menu and gesture actions, accessible preferences,
  localization and target-language TTS behavior.
- Full phone unit tests, `:lintPhoneDebug`, then `:assemblePhoneDebug`, using
  `compilar.ps1 -Prueba` with the existing isolated environment. Do not regenerate
  or widen the lint baseline to hide new defects.
- Inspect the APK package/signature and provide the exact artifact path/hash.
  Installation and enabling/replacing an active screen reader require separate
  authorization. ADB currently lists no connected devices; real-device speech,
  latency and accessibility validation remain unverified.

## Research references

- [Google Cloud Translation v2 REST contract](https://docs.cloud.google.com/translate/docs/reference/rest/v2/translate)
- [Google Cloud Translation pricing](https://cloud.google.com/products/translate/pricing)
- [Google ML Kit translation and limitations](https://developers.google.com/ml-kit/language/translation)
- [Google ML Kit Android integration](https://developers.google.com/ml-kit/language/translation/android)
- [NVDA Translate: speech interception, app caches and privacy](https://github.com/yplassiard/nvda-translate)
- [NVDA Polyglot: replaceable providers, continuous and last-speech translation](https://github.com/cary-rowen/polyglot)

These NVDA projects are comparison evidence, not dependencies to copy into the
Android service. Their privacy and latency limitations motivate explicit consent,
bounded caching and cancellation in Can Talk.

## Installed InstantTranslate comparison

The user's installed `instantTranslate` is version **5.1.1** (its manifest), under
`C:\Users\angel_hmv3h04\AppData\Roaming\nvda\addons\instantTranslate`.
It was inspected read-only; no addon setting, cache or source was changed.

| Implementation | Transport and cache | Assessment for this project |
| --- | --- | --- |
| Older NVDA Translate (`mtranslate/core.py`) | Extracts the mobile website's HTML `result-container`; per-app JSON caches. | Layout-dependent parsing and persistent private text are undesirable defaults. |
| Polyglot (`services/engines/google.py`) | Keyless `translate_a/single`, POST form text, JSON segments, replaceable engine. | Better parsing boundary than HTML scraping, but still unofficial. This informed the initial adapter, now replaced for the device trial. No mirrors are used. |
| Installed InstantTranslate 5.1.1 (`translators/google.py`) | Internal `translate-pa.googleapis.com/v1/translate`, JSON/protobuf response with an embedded service credential. Not HTML scraping. | Now selected explicitly by the user for a device trial. Independently implemented adapter; credential injected locally, not tracked. Still not a supported public contract. |

In the installed addon, `translators/base.py` and `providers.py` separate provider
selection. `httpClient.py` defaults to a 30-second timeout. `__init__.py` records
the last spoken text, uses worker threads, and caches 100 translations with a key
including provider, source, target, text and swap direction. The continuous speech
path waits for the worker with `join()`: using a thread alone does not make that
speech hook non-blocking. Can Talk instead waits asynchronously inside its queue,
with interrupt invalidation and a six-second total deadline. InstantTranslate's
`gt_langs.json` is a language-catalog cache (24-hour refresh), not a disk cache of
private utterances. These findings apply to the inspected installation, not every
past or future release. Its manifest links to [InstantTranslate's source](https://github.com/addonFactory/instantTranslate).

**Decision:** JSON avoids brittle HTML extraction; scraping is not the better
default. Neither unofficial transport offers the reliability of a documented API.
A synthetic POST from this development environment returned **HTTP 429**. There
is no successful live translation proof yet. The implementation falls back to
the original text and backs off rather than bypassing rate limits, captcha or
provider access controls. A phone-network test remains essential before release.
