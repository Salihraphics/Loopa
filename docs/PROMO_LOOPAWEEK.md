# "LOOPAWEEK" promo code: "Kod geçersiz ya da şu anda kullanılamıyor"

Status: documented only. Nothing was changed in 0.7.0, as requested.

## Most likely cause: the code on the server is spelled differently

The code on the hosted server is **`LOPPAWEEK`** (double **P**), not `LOOPAWEEK` (double **O**).

A read-only check of the hosted database on 2026-11-14 returned one row:

| Field | Value |
|---|---|
| code | `LOPPAWEEK` |
| plus_days | 7 |
| active | true |
| valid_from / valid_until | none |
| max_redemptions | none |
| redemptions | 0 |
| requires_google | true |

`redeem_promo_code` trims the input and compares it case-insensitively. The spelling itself must
be exact on purpose, so typos cannot be probed, and an unknown code returns `LOOPA:PROMO_INVALID`.
The app shows that as "Kod geçersiz ya da şu anda kullanılamıyor. Yazımı kontrol et."

So typing **LOOPAWEEK** always fails, and **LOPPAWEEK** should work for a Google-linked account.
The spelling has been `LOPPAWEEK` since `20261110000002_promo_codes.sql` (the header comment and
the seed row), and the tests use it too.

## Other ways the same message can appear

`PROMO_INVALID` is used on purpose for all of these:
- an unknown code;
- an inactive code;
- a code outside its date window;
- input that is not 4–24 characters of A–Z or 0–9 (for example `LOOPA WEEK` with a space).

Other cases have their own messages:

| Case | Error | What the app shows |
|---|---|---|
| Guest account (no Google) | `GOOGLE_LINK_REQUIRED` | its own message |
| Cap reached | `PROMO_EXHAUSTED` | its own message |
| Already redeemed by this account | not an error | status `already_redeemed` |

## Code path

1. UI: `feature-settings/.../MembershipSection.kt`, the promo field in Ayarlar → Üyelik.
2. Client: `MembershipRepository.redeem()` in `core-data/.../PlusRepositories.kt` trims the input
   and calls RPC `redeem_promo_code(p_code)`.
3. Server: `public.redeem_promo_code` in `supabase/migrations/20261110000002_promo_codes.sql`.
   - Uses `promo_codes` (not readable by clients) and `promo_redemptions` (unique per code and
     user).
   - Creates the entitlement through `_grant_plus`.
4. Error mapping: `LoopaError.PROMO_INVALID` in `core/.../GameLogic.kt`.

## Options (not done; needs a decision)

- **A.** Tell users the real spelling, `LOPPAWEEK`. No change needed.
- **B.** Add `LOOPAWEEK` as a second code with the same settings, using `admin_set_promo_code`
  from the admin account. Each code is once per account, so one account could then redeem both.
  If that matters, both codes need a shared redemption check.
- **C.** Rename the code. A data change on the hosted database, which needs your approval.
