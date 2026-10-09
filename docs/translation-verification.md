# Translation test APK: verification record

## Latest device iteration: InstantTranslate method

The user reports failure of both translation modes in the initial installed APK
and requests the InstantTranslate protocol instead. The active adapter now uses
the installed addon's Google GET/JSON-protobuf method, with build-local credential
injection. The menu keeps Can Talk settings and text-to-speech settings at the
bottom, after translation actions. Compilation and ADB installation are requested;
**no tests or lint are run in this iteration**. Earlier results below refer to the
previous keyless adapter, not to this replacement. Live behavior is not yet proven.

- `:assemblePhoneDebug`: **BUILD SUCCESSFUL**, 4m 10s; 353 tasks, 38 executed.
- Artifact: `build/artifacts/can-talk-translator-instant-prueba-73ffdc320786.apk`.
- SHA-256: `73ffdc320786e584b1ea1b3a07b43b540d562d31422b0f545b15284fa4e6fa27`.
- Size: 93,486,278 bytes; version `TfPu_release_16_2-2026_10_08_1959`, code `70000238`.
- Package: `fyi.quin.backtalk.cantalktest`; debug v2 signature verifies.
- ADB `install -r`: **Success**. Installed version and pulled `base.apk` hash match.
- Provider build configuration is present, with its value kept out of reports.
- No tests, lint, live translation requests, data deletion, consent changes,
  accessibility service changes, commit or push. Log: `build/translator-instant-assemble.log`.

## Initial artifact and checks (historical)

This is an **experimental phone build**, not a fully validated release. The
implementation is on `codex/translator`, with uncommitted source changes. No
commit, push, installation or screen-reader activation was performed.

## Artifact

- File: `build/artifacts/can-talk-translator-prueba-411276d69534.apk`
- SHA-256: `411276d69534b3f0a0342d05ea7bd848bf1d3ad413c37bfde776a1d2372ee3ea`
- Size: 93,486,198 bytes.
- Package: `fyi.quin.backtalk.cantalktest`; label: `can-talk — prueba`.
- Version: `TfPu_release_16_2-2026_10_08_1939`; version code: `70000238`.
- Minimum API: 26; target API: 36; compile API: 37 (existing project settings).
- ABIs: arm64-v8a, armeabi-v7a, x86_64.
- `apksigner verify --verbose --print-certs`: verifies, APK Signature Scheme v2,
  one Android Debug signer. This is not proof that it matches every installed APK.

## Automated checks

```powershell
.\compilar.ps1 -Prueba -Tareas @('testPhoneDebugUnitTest', ':assemblePhoneDebug',
  '--gradle-user-home', 'C:/Users/angel_hmv3h04/can-talk-tools/gradle-user-home')
```

Final run: **BUILD SUCCESSFUL**, 5 minutes 56 seconds, 437 tasks (45 executed,
392 up-to-date). Log: `build/translator-final-tests-apk.log`.

| Suite | Tests | Failures/errors/skipped |
| --- | ---: | ---: |
| talkback | 338 | 0 |
| utils | 125 | 0 |
| brailleime | 105 | 0 |
| Total | **568** | **0** |

The new translation tests account for 46 cases: provider 10, cache 5, engine 9,
policy 10, preferences 3, and speech queue 9. They include deadline cancellation,
executor rejection, original-text fallback, stale responses, queue completion,
history/menu snapshot, consent/storage/dialog, Spanish resources, lock/privacy
transitions and protected roots. They use fake transport/TTS; they do not prove
Google availability or audible output. Other modules have no phone unit tests.

`git diff --check` and a whitespace check of new source/docs/resources passed.
Build configuration, dependencies and `lint-baseline.xml` were not changed.

## Known non-green checks and missing proof

The earlier full command with `:lintPhoneDebug` and `--continue` assembled an APK
but failed overall. Its unit failure was an unsealed synthetic Android node in
the test fixture; the final run above seals that node and passes. **Lint remains
non-green:** its last report has 70 `MissingTranslation` errors and five warnings.

- 36 errors refer to this feature's new UI strings in other supported app locales.
  Default English and Latin American Spanish are supplied and Spanish selection
  is tested. Other locales currently fall back to default English.
- 34 refer to pre-existing braille strings (32 IME, 2 common); all 34 resource keys
  were verified in `HEAD`, and those owner files are unchanged in this work.
- No new errors were accepted into the baseline or suppressed. The final test/APK
  command intentionally omits lint; it is not evidence of a green lint gate. The
  report predates the last small policy/wording/KTX edits; its remaining errors
  require localization work and another lint run before a release claim.

Last lint report: `build/reports/lint-results-phoneDebug.html`, log
`build/translator-full-2.log`. Historical baseline filters are separate from the
70 unsuppressed errors. Do not describe all 70 as pre-existing.

A synthetic HTTPS POST from the development network returned **HTTP 429**.
There is no successful live translation proof. No retry, mirror or credential
copied from InstantTranslate was used to bypass that response.

ADB initially had no device; at handoff, an API-36 device is connected and the
test package is already installed. Its installed version was not updated by this
task. Installation/activation require separate authorization. Actual translation,
voice selection, latency, accessibility navigation and lock/pause behavior still
need device testing with this APK; follow [the test checklist](translation.md).
