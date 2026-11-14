# LOOPA design system (0.6.0)

Everything visual lives in `core-ui`. Feature screens use these pieces instead of hard-coded
values. The colour palette, logo and app icon are unchanged since 0.1. Only how the palette is
used has changed.

## Tokens: `core-ui/.../theme`

| File | Contents |
|---|---|
| `Color.kt` → `LoopaPalette` | Pastel brand palette: Lavender, Mint, Peach, Butter, Sky, Cream, Ink, plus `*Deep` variants and the Night colours. **Do not change it.** |
| `Theme.kt` → `LoopaColors` (`Loopa.colors`) | Semantic roles for light and dark: `background`, `card`, `cardHigh`, `outline`, `text`, `textSoft`, `accentText` (Lavender-deep in light mode, Lavender in dark mode: readable link/action text). |
| `Type.kt` | Baloo 2 for display and title styles, Nunito for body and label styles (both SIL OFL). |
| `DesignTokens.kt` | `Spacing` (xxs 2 … xxl 32, `gutter` 20, `section` 24), `Radii` (sm 12, md 18, lg 24, xl 30, pill), `Borders` (hairline, strong), `Elevation` (flat by default), `MinTouch` = 48 dp, `Motion` (FAST 140 / MEDIUM 240 / SLOW 420 ms, `standard`, `bouncy`, `press` specs). |
| `LocalReducedMotion` / `LoopaMotion.reduced` | `true` when the system animator scale is 0 ("remove animations"). Every 0.6 animation checks it and becomes instant. |

## Components: `core-ui/.../components`

| Component | Use |
|---|---|
| `LoopaSurface` | Default container of the 0.6 screens: flat card with a hairline outline, optional `onClick` (slight press scale, tap sound) and a `selected` outline. |
| `SectionHeader` | Section title with an optional text action ("Tümü"). |
| `ListRow` | Menu and settings row: icon tile, title, subtitle, trailing slot or chevron. At least 56 dp tall. |
| `IconAction` | Round icon button with a 48 dp touch target, a required content description and an optional dot. |
| `LoopaBottomNav` / `LoopaNavRail` | Primary navigation built from `NavItem`s. The rail is used when the window is at least 720 dp wide (tablets, landscape). Destinations have `Role.Tab` and tags `nav_<KEY>`. |
| `ComingSoonTag` | The honest "Yakında" label for planned features. Rows with it do nothing when tapped. |
| `GameArtwork` | Flat geometric/editorial illustration per game, drawn on a Canvas from palette colours only (no bitmaps). A new game needs one `when` branch. |
| `FeaturedGame`, `GameCard`, `StatusBadge`, `MetaPill` | Catalogue pieces shared by Home and Oyunlar. Status (free, weekly free, Plus, coming soon) always comes from `GameCards.status`, i.e. from the server catalogue. |
| `LoopaButton`, `LoopaTextField`, `LoopaChip`, `LoopaCard`, `Pill`, `StatTile`, `XpBar`, `LevelBadge`, `PlanBadge`, `ErrorBanner` | Existing components, kept. `LoopaChip` is now a selectable tab (`Role.Tab`), at least 40 dp tall, and never wraps its label. |

## Navigation

There are four tabs (`Tab` in `app/.../AppNavHost.kt`): **Ana Sayfa, Oyunlar, Sosyal, Profil**.
Settings, Avatar, Shop, Plus and Public rooms are pushed routes. Settings opens from the Profile
header (gear) or from the Profile menu. Tab state is kept per tab with a `SaveableStateHolder`.
On phones the bottom bar hides while the keyboard is open.

## Catalogue data

`GameModes` (core) holds each game's `category` (`GameCategory`), `orientation` (`GameOrientation`:
PORTRAIT / LANDSCAPE / FLEXIBLE, all FLEXIBLE today, nothing is forced), `addedIn` and `sameRoom`.
`GameModes.categories` only lists categories that at least one game uses. `GameModes.search` is
Turkish-aware (İ/ı). Recently played games come from the server's `get_my_mode_stats.last_played_at`.

## Rules

- Only real data. If the backend has no feature, the UI says "Yakında" or shows an empty state.
- Text on pastel surfaces is `Ink`. On the app background use `Loopa.colors.text` / `textSoft`.
- Touch targets are at least 48 dp. Icons that carry meaning have a content description.
- Gameplay screens keep their own layouts. The redesign touches them only for keyboard space
  (İsim Şehir: compact header while typing).
