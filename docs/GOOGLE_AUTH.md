# Google sign-in and guest → Google account linking

## How it works

| Action | Supabase call | User id |
|---|---|---|
| Misafir olarak devam et | `signInAnonymously()` | new anonymous user |
| Google ile giriş yap | `signInWith(Google)` (OAuth, PKCE) | the Google account's user |
| Misafir hesabını Google'a bağla | **`linkIdentity(Google)`** (manual identity linking) on the current session | **unchanged** |
| Google ile tekrar giriş (any device) | `signInWith(Google)` | same user as before |
| Çıkış | `signOut(SignOutScope.LOCAL)` | other devices stay signed in |

Linking adds a Google identity to the *existing* anonymous user (`auth.identities`), and
`auth.users.is_anonymous` becomes `false`. Nothing is copied or migrated: profile, XP, level,
avatar, unlocked cosmetics, statistics and match history are keyed by that user id and stay
attached. Jokers are per-match and are not affected either.

If the Google identity already belongs to another LOOPA account, Supabase refuses the link
(`identity_already_exists`). The app then shows a dialog. The guest account is **not** deleted
and **nothing is merged**. The player can stay a guest, or knowingly sign in to the other account,
in which case this device's guest progress is not transferred.

The browser flow returns to the app through the deep link **`loopa://auth-callback`**
(`AuthRepository.handleCallback`, called from `MainActivity.onCreate/onNewIntent`).
Sessions are persisted by supabase-kt's default Android session storage and refreshed automatically.

The app reads `/auth/v1/settings` and only enables the Google buttons when the provider is
enabled on the server; otherwise they show "yakında".

## Configuration checklist

### Already done on project `zxtrxbgphutuiizznakd` (via Management API)
- [x] Anonymous sign-ins: **on**
- [x] Manual identity linking (`security_manual_linking_enabled`): **on**
- [x] Redirect URL allow-list (`uri_allow_list`): **`loopa://auth-callback`**
- [x] Migration `20261108000003_account_status.sql` (RPC `get_account_status`)

### Needed from you
1. **Google Cloud project → APIs & Services → OAuth consent screen**: app name "LOOPA", support e-mail,
   scopes `openid`, `email`, `profile`. Publish it (or add testers while it is in "Testing").
2. **Credentials → Create OAuth client ID → type "Web application"**
   - Authorized redirect URI: **`https://zxtrxbgphutuiizznakd.supabase.co/auth/v1/callback`**
   - (No JavaScript origins are needed.)
   - This gives the **Web Client ID** and **Client Secret**.
3. **Supabase dashboard → Authentication → Sign In / Providers → Google**: enable, paste the
   Web Client ID and the Client Secret, save. The Client Secret lives **only** in Supabase;
   it is never put in the app, `local.properties` or git.

That is all the browser-based flow needs.

### Only for a future native (Credential Manager) Google button — not used today
An **Android** OAuth client in the same Google Cloud project, per signing key:

| Build | Package name | SHA-1 |
|---|---|---|
| debug (this build machine's `~/.android/debug.keystore`) | `com.loopa.game.debug` | `C5:AB:D5:C2:EB:31:D8:25:C8:68:F7:B6:B4:23:DA:81:42:0B:8D:F4` |
| release (your release keystore) | `com.loopa.game` | `keytool -list -v -keystore <release.jks> -alias <alias>` |

The debug keystore here belongs to the build machine. A different machine has a different
SHA-1. Use the release key's SHA-1 for the Play build.

## Testing

`core-data/src/test/.../AccountLinkingE2ETest.kt` runs the real `AuthRepository` against a real
Supabase Auth (GoTrue) server. Google's consent screen can't be scripted, so the local stack
enables Supabase's Keycloak OIDC provider pointed at `tools/auth-test/fake_oidc.py`. The
linking, PKCE exchange and conflict paths in Supabase are the same for every OAuth provider.

```bash
python3 tools/auth-test/fake_oidc.py 9999 &
# supabase/config.toml for the local test stack:
#   [auth] enable_manual_linking = true; additional_redirect_urls includes "loopa://auth-callback"
#   [auth.external.keycloak] enabled = true, client_id = "loopa-test", secret = "loopa-test-secret",
#                            url = "http://host.docker.internal:9999/realms/loopa"
supabase start
LOOPA_E2E_URL=http://127.0.0.1:54321 LOOPA_E2E_ANON_KEY=<local publishable key> LOOPA_E2E_OAUTH=keycloak \
  ./gradlew :core-data:testDebugUnitTest --tests '*AccountLinking*'
```

What remains to verify with real Google once the provider is configured: the consent screen and
the redirect into the installed app on a phone.
