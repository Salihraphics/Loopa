# What's New screen

Content: `core/src/main/kotlin/com/loopa/core/release/WhatsNew.kt`
Screen: `app/src/main/kotlin/com/loopa/app/whatsnew/WhatsNewScreen.kt`

## Adding a release

Add one `ReleaseNotes` entry to `WhatsNew.releases`:

```kotlin
ReleaseNotes(
    versionName = "0.8.0",
    title = "…", subtitle = "…",
    hero = WhatsNewHero.GAMES,                 // illustration of the intro page
    features = listOf(
        WhatsNewFeature("Başlık", "Açıklama", FeatureArt.GAMES, Accent.MINT),
        WhatsNewFeature("…", "…", FeatureArt.CHAT, Accent.SKY, requires = ServerFeature.SOCIAL),
    ),
    ctaText = "Keşfetmeye başla",
)
```

No screen code is needed. List only features that really ship. A feature with `requires` is
hidden when the server does not have that backend capability.

## When the screen is shown

| Situation | Behaviour |
|---|---|
| Fresh install (not onboarded, no stored version) | Not shown. The version is stored at app start, before onboarding can finish. |
| Update from a version before 0.7 (onboarded, nothing stored) | The notes of the current version are shown once. |
| Same version again | Not shown. The decision ignores the `-debug` suffix. |
| Several versions skipped | Only the newest notes in (last seen, current] are shown. |
| A patch without its own notes | Nothing is shown. The version is stored. |
| Server cannot be reached | Postponed to the next start. Nothing is stored. |
| None of the features is available on this server | Not shown and not stored, so it appears once the backend is there. |

The stored value is `whats_new_seen` in the app's DataStore. It is written when the screen opens.
**Ayarlar → LOOPA hakkında → Yenilikler** opens the notes of the current version at any time.

## Motion

- The intro elements fade and lift in one after another.
- Pages scale and fade slightly while you swipe. "İleri" animates to the next page.
- The CTA on the last page breathes gently, and the illustrations move a little.

All of this is off when the system "remove animations" setting is on (`LoopaMotion.reduced`).
Infinite animations are not even created in that case.

Tests:
- `WhatsNewTest` (core): version logic, skipped versions, fresh install, server filter.
- `SocialUiTest`: paging, CTA, close, reduced motion, dark mode, tablet, landscape, 320 dp phone.
