# Selective upstream integration — 2026-10-10

Compatible upstream changes are integrated selectively into CAN Talk on
`codex/desarrollo`. This is not a wholesale upstream merge or a release. Scripting
and voice profiles are intentionally excluded. Build and device behavior remain
unverified by the agent. The user subsequently reported that everything works
and authorized commit and push; see the delivery follow-up below.

## Review baseline

| Snapshot | Commit |
|---|---|
| CAN Talk before integration | `045b5e87cc3e349f13d5c75d742c18ccaa567f8b` |
| Common ancestor | `4b7020c09e01883955233eab631f838dd6be1384` |
| Audited upstream master | `ef18fc2fcc74cd38d427f53f72866503f493cae6` |

The selection takes changes from 46 of the 50 upstream commits after the common
ancestor. Some commits are adapted or only partially included. During implementation,
HEAD and the index were unchanged, and no commits or pushes were performed.
Build/deployment scripts and CI configuration are unchanged.

## Review by behavior

| Area | Incorporated behavior | Main owners / upstream reference |
|---|---|---|
| Speech completion | Ignore a second completion after an interrupt | `utils/.../output/SpeechControllerImpl.java`; [93795f4b](https://github.com/trypsynth/backtalk/commit/93795f4b) |
| Gesture stability | Motion events, state changes and timeouts share the main-thread queue | `TalkBackService.java`, `TouchInteractionMonitor.java`; [c8a43e13](https://github.com/trypsynth/backtalk/commit/c8a43e13) |
| Caption queues | Monotonic timeout scheduling, one completion per request, exact queue removal and late-result cancellation | `actor/ImageCaptioner.java`, `imagecaption/Request*.java`; [f16ed839](https://github.com/trypsynth/backtalk/commit/f16ed839) |
| Brightness | Clamp invalid brightness values before conversion | `selector/ScreenBrightness.kt`; [596360de](https://github.com/trypsynth/backtalk/commit/596360de) |
| Audio while recording | Use conventional speech/sound playback when the microphone is in use | `utils/.../output/FailoverTextToSpeech.java`, `FeedbackController.java`, `LowLatencyAudio.kt`; [4762c983](https://github.com/trypsynth/backtalk/commit/4762c983) |
| Navigation performance | Retain traversal cache, reduce app queries and wait for scroll results before searching headings/links/controls | `focusmanagement/TraversalTreeCache.kt`, navigation/compositor owners; [2c5a4713](https://github.com/trypsynth/backtalk/commit/2c5a4713), [616baf60](https://github.com/trypsynth/backtalk/commit/616baf60), [58da2c60](https://github.com/trypsynth/backtalk/commit/58da2c60) |
| Item details and tables | All six name/type/state orders, accessible reordering, table header order and coordinate switches | `preference/base/ItemDetailsOrderFragment.kt`, compositor and verbosity resources |
| Keyboard | Configurable physical-key interruption, Ctrl pause, web-arrow handling, focus-speech deduplication and lift-to-type except the action key | Keyboard/input/focus owners; the action-key exception recognizes Gboard, not every keyboard |
| Hints and tutorials | Use assigned gestures/keymaps and correct reading-control action names | `gesture/GestureHints.java`, `trainingcommon/TutorialShortcuts.java`; retain the existing TTS-engine switch |
| Other compatible fixes | Navigation-button speech, composing-underline suppression, notification title deduplication, separate pause channel, Samsung watch content and optional Vibration Watch gestures, dim-screen gap | Direct touch, text formatting, notifications, gesture and dim-screen owners |
| Maintenance | Settings relocation, obsolete help/update-notice removal, compatible Czech resources and Kotlin/checker annotation patch versions | Preference resources, `differences.md`, `gradle/libs.versions.toml` |

Performance improvements are code-level imports. No CAN Talk latency or query-count
benchmark was performed.

## Fork-specific adaptations

- Preserve translation providers/hooks, Bluetooth routing/device rules, call speech
  and sound gain, Braille changes, updater policy and encrypted selective backups.
- Combine the microphone fallback with CAN Talk's existing call-volume scaling;
  do not replace either path with the upstream-only implementation.
- Apply 200 ms focus delay and low-latency audio as defaults only when no saved
  choice exists. Omit upstream's `moveToFasterDefaults` migration: saved settings
  are not rewritten. Wear keeps low-latency audio off by default.
- Keep the backup settings entry and disabled upstream updater controls.
- Add Spanish and Latin American Spanish resources for the new UI. Update the
  Spanish AI consent wording instead of falling back to English.
- Extend `backup/BackupPreferenceTypes.kt` for six new settings so restore on an
  empty installation uses the owners' boolean/string contracts. Keyboard
  interruption mode is a stored string, not an integer. Add a regression test.
- Preserve the fork lint baseline and incorporate only the relevant upstream
  missing-translation entries. No lint run has verified the resulting baseline.

## Intentionally not imported

| Exclusion | Reason |
|---|---|
| `bfb4b565` / PR 111 | Voice profiles excluded by the user |
| `a4752a21` / PR 62 and `ef18fc2f` / PR 148 | Scripting excluded by the user, including its resources and dependencies |
| `b9576f16` / PR 146 | Temporary targeted CI workflow would replace the fork's build/test/publish workflow |
| CI changes from `b4ad3474` / PR 143 | Preserve the fork workflow |
| Whole upstream lint baseline | Avoid overwriting unrelated fork suppressions |
| Historical on-device Gemma document deletions | Preserve existing project artifacts |
| Faster-defaults preference migration | Do not overwrite saved choices |

The included upstream commit selection, in chronological order:

```text
4987e516 93795f4b dd3e2dd1 98631b37 855851a7 e211fc3d 50a41841 d984cac8
a93a9e01 cff9b21b 8219b85b 29d15cfd b05bb937 2b86892b daeea525 5d030819
4311c3f8 4762c983 c7740403 2c5a4713 27dd1d18 596360de 1f75cf83 164299d1
29d6f13a 7a6945aa 420829ae 616baf60 43db7dbf 79a21765 1087a3c7 c99063bd
e4b231fa a6b17c59 149289fe 9222c486 902bf7c1 30407ef9 58da2c60 7053b1ac
78211c55 a4818c6c c8a43e13 f16ed839 c32bda23 b4ad3474
```

## Validation and delivery boundaries

Completed static checks:

- `git diff --check`.
- Parse changed XML and the compositor's comment-bearing JSON.
- Check resource duplicates and newly introduced resource references.
- Check for merge markers, excluded source/resource functionality and references
  to removed upstream methods/constants.
- Check retention of nontrivial fork-added source lines in overlapping files and
  unchanged protected Braille, backup, updater and delivery files, except the
  deliberate backup type-map extension.
- Confirm unchanged HEAD and empty staging index.

These checks do not type-check Java/Kotlin or prove runtime behavior. Regression
tests are included but were not executed. No Gradle tests, lint, app build,
installation or deployment was performed by the agent. Commit and push were
authorized afterward.

### Delivery follow-up

After reporting their own compilation was in progress and receiving the manual
test checklist, the user reported "todo está bien" and authorized commit and push.
This is user-reported acceptance, not an independently observed build log or a
case-by-case test result. No additional build, automated tests or lint were run
for the Git handoff. Scripting and voice profiles remain excluded; publication of
a release or binaries is outside this authorization.

Before a release, obtain authorization for compilation/validation and verify:

- Gesture timing under main-thread load and normal touch exploration.
- Speech interruption/pause, translation, Bluetooth and call-volume behavior.
- OCR/captions with timeout, cancellation, a late result and a subsequent request.
- Table speech, accessible item-order actions and both keyboard typing modes.
- Selective backup round trips with new settings and existing Braille preferences.
- Upgrade with saved focus delay and low-latency choices; first-install defaults.

**Known limit:** local AI OOM/timeout cancellation in `GeminiActor` can still leave
a description unfinished. The caption-queue changes do not establish that every
local AI failure is recovered.

Rollback/review units are speech/audio, gesture scheduling, navigation caching,
caption queues, reading/keyboard/settings and resources, and backup type-map
compatibility. Keep each behavior's tests/resources with its owning unit. The
snapshot is a single selective integration unit; do not revert unrelated fork work when
reviewing or backing out a unit.
