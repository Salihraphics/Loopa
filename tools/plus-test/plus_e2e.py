#!/usr/bin/env python3
"""LOOPA Plus / promo / admin / mode-gating end-to-end check against a LOCAL Supabase stack
(supabase CLI + Docker), through the real GoTrue, PostgREST, roles, grants and RLS.

Real Google sign-in cannot run here, so a Google link is simulated exactly as GoTrue stores it:
an auth.identities row (provider 'google', identity_data with email / email_verified) and
is_anonymous = false. Console steps (allowlist, activating the code) use psql as postgres, i.e.
what the dashboard SQL editor does. Everything created is removed at the end.

  python3 tools/plus-test/plus_e2e.py            # defaults: http://127.0.0.1:54321, db port 54322
Never point this at the hosted project: it writes to auth tables.
"""
import json, os, subprocess, sys, urllib.request, urllib.error, uuid

API = os.environ.get("LOOPA_LOCAL_API", "http://127.0.0.1:54321")
# Public demo anon key of every local Supabase CLI stack (not a secret, not the hosted key).
ANON = os.environ.get("LOOPA_LOCAL_ANON",
    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZS1kZW1vIiwicm9sZSI6ImFub24iLCJleHAiOjE5ODM4MTI5OTZ9."
    "CRXP1A7WOeoJeXxjNni43kdQwgnWNReilDMblYTn_I0")
if "127.0.0.1" not in API and "localhost" not in API:
    sys.exit("refusing to run against a non-local API")
DB = ["psql", "-h", "127.0.0.1", "-p", os.environ.get("LOOPA_LOCAL_DB_PORT", "54322"), "-U", "postgres",
      "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-qtA"]
ENV = dict(os.environ, PGPASSWORD=os.environ.get("PGPASSWORD", "postgres"))
TAG = uuid.uuid4().hex[:8]
results = []


def sql(q):
    return subprocess.run(DB + ["-c", q], env=ENV, check=True, capture_output=True, text=True).stdout.strip()


def http(method, path, token=None, body=None):
    req = urllib.request.Request(API + path, method=method,
                                 data=None if body is None else json.dumps(body).encode())
    req.add_header("apikey", ANON)
    req.add_header("Authorization", "Bearer " + (token or ANON))
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, raw


def rpc(token, fn, **args):
    return http("POST", "/rest/v1/rpc/" + fn, token, args)


def check(ok, what):
    results.append((bool(ok), what))
    print(("PASS " if ok else "FAIL ") + what)


def err(resp):
    return json.dumps(resp[1]) if resp[0] >= 400 else ""


def guest(name):
    s, b = http("POST", "/auth/v1/signup", body={})
    assert s == 200, (s, b)
    tok, uid = b["access_token"], b["user"]["id"]
    s, b = rpc(tok, "ensure_profile", p_username=name)
    assert s == 200, (s, b)
    return tok, uid


def link_google(uid, email):
    """What GoTrue writes after a successful manual linkIdentity(Google)."""
    sql(f"""insert into auth.identities(provider_id, user_id, identity_data, provider, created_at, updated_at)
            values ('g-{uid}', '{uid}', jsonb_build_object('sub','g-{uid}','email','{email}','email_verified',true),
                    'google', now(), now());
            update auth.users set is_anonymous = false, email = '{email}' where id = '{uid}';""")


users = []
try:
    # ---------- guest ----------
    g_tok, g_id = guest(f"Misafir{TAG[:4]}"); users.append(g_id)
    s, plan = rpc(g_tok, "get_my_plan")
    check(s == 200 and plan["plan"] == "free" and not plan["google_linked"], "guest: free, not linked")
    s, b = rpc(None, "get_my_plan")
    check(s >= 400, f"anon key without a user cannot call get_my_plan ({s})")

    # ---------- code is inactive until switched on ----------
    s, b = rpc(g_tok, "redeem_promo_code", p_code="LOPPAWEEK")
    check(s >= 400 and "PROMO_INVALID" in err((s, b)), "inactive LOPPAWEEK refused")
    sql("update public.promo_codes set active = true where code = 'LOPPAWEEK'")
    s, b = rpc(g_tok, "redeem_promo_code", p_code="LOPPAWEEK")
    check(s >= 400 and "GOOGLE_LINK_REQUIRED" in err((s, b)), "guest needs a Google link")

    # ---------- Google-linked ----------
    link_google(g_id, f"g{TAG}@loopa-test.invalid")
    s, plan = rpc(g_tok, "get_my_plan")
    check(s == 200 and plan["google_linked"], "linked account recognised")
    s, b = rpc(g_tok, "redeem_promo_code", p_code="LOPAWEEK")
    check(s >= 400 and "PROMO_INVALID" in err((s, b)), "misspelt code refused")
    s, r1 = rpc(g_tok, "redeem_promo_code", p_code="LOPPAWEEK")
    check(s == 200 and r1["status"] == "redeemed" and r1["days"] == 7, "LOPPAWEEK redeemed (7 days)")
    s, r2 = rpc(g_tok, "redeem_promo_code", p_code="LOPPAWEEK")
    check(s == 200 and r2["status"] == "already_redeemed" and r2["plus_until"] == r1["plus_until"], "second use: no second grant")
    s, plan = rpc(g_tok, "get_my_plan")
    check(plan["plan"] == "plus" and len(plan["entitlements"]) == 1, "plan is plus, one entitlement")

    # ---------- direct table access through PostgREST ----------
    s, rows = http("GET", "/rest/v1/entitlements?select=user_id", g_tok)
    check(s == 200 and len(rows) == 1 and rows[0]["user_id"] == g_id, "entitlements: only own rows readable")
    s, b = http("POST", "/rest/v1/entitlements", g_tok,
                {"user_id": g_id, "source": "admin", "starts_at": "2026-01-01T00:00:00Z"})
    check(s in (401, 403), f"insert into entitlements denied ({s})")
    s, b = http("GET", "/rest/v1/promo_codes?select=code", g_tok)
    check(s in (401, 403), f"promo_codes not readable ({s})")
    s, b = http("GET", "/rest/v1/admin_allowlist?select=email", g_tok)
    check(s in (401, 403), f"admin_allowlist not readable ({s})")
    s, b = http("PATCH", "/rest/v1/game_modes?id=eq.gizli_kelime", g_tok, {"status": "available"})
    check(s in (401, 403), f"game_modes not writable ({s})")
    s, b = rpc(g_tok, "_grant_plus", p_user=g_id, p_days=30, p_source="admin", p_ref=None, p_by=None)
    check(s >= 400, f"internal _grant_plus not callable ({s})")
    s, b = rpc(g_tok, "admin_grant_plus", p_user=g_id, p_days=30)
    check(s >= 400 and "NOT_ADMIN" in err((s, b)), "non-admin cannot use admin RPC")

    # ---------- admin via allowlist (console) ----------
    a_tok, a_id = guest(f"Admin{TAG[:4]}"); users.append(a_id)
    admin_mail = f"admin{TAG}@loopa-test.invalid"
    sql(f"select public._admin_allowlist_add('{admin_mail}', 'e2e')")
    s, plan = rpc(a_tok, "get_my_plan")
    check(plan["plan"] == "free", "allowlisted e-mail but no Google link: not admin")
    link_google(a_id, admin_mail)
    s, plan = rpc(a_tok, "get_my_plan")
    check(s == 200 and plan["plan"] == "admin", "verified Google e-mail on allowlist: admin")
    s, b = rpc(a_tok, "admin_user_overview", p_user=g_id)
    check(s == 200 and b["plan"] == "plus", "admin can inspect a user")

    # ---------- mode gating through the real API ----------
    s, cat = rpc(g_tok, "get_game_catalog")
    modes = {m["id"]: m for m in cat["modes"]}
    check(s == 200 and len(modes) == 10 and modes["isim_sehir"]["access"]["allowed"], "catalogue: 10 modes, İsim Şehir open")
    check(not cat["rotation"]["active"], "rotation not started (week 1 not fixed yet)")
    f_tok, f_id = guest(f"Free{TAG[:4]}"); users.append(f_id)
    s, room = rpc(f_tok, "create_room", p_is_public=False)
    rid = room["id"]
    s, st = rpc(f_tok, "get_room_state", p_room_id=rid)
    settings = st["room"]["settings"]
    check(settings.get("game_mode") == "isim_sehir", "new room is İsim Şehir")
    s, b = rpc(a_tok, "admin_set_mode_status", p_mode="gizli_kelime", p_status="available")
    check(s in (200, 204), "admin made gizli_kelime available")
    s, b = rpc(f_tok, "update_room_settings", p_room_id=rid, p_settings=dict(settings, game_mode="gizli_kelime"), p_is_public=False)
    check(s >= 400 and "PLUS_REQUIRED" in err((s, b)), "free host cannot pick a locked mode")
    old = {k: v for k, v in settings.items() if k != "game_mode"}
    s, b = rpc(f_tok, "update_room_settings", p_room_id=rid, p_settings=old, p_is_public=False)
    check(s == 200 and b["game_mode"] == "isim_sehir", "0.3.0-style settings (no game_mode) still accepted")
    s, b = rpc(g_tok, "create_room", p_is_public=False)
    prid = b["id"]
    s, b = rpc(g_tok, "update_room_settings", p_room_id=prid, p_settings=dict(settings, game_mode="gizli_kelime"), p_is_public=False)
    check(s == 200 and b["game_mode"] == "gizli_kelime", "Plus host may pick gizli_kelime")
    s, b = rpc(a_tok, "admin_set_mode_status", p_mode="gizli_kelime", p_status="available")
    rpc(f_tok, "close_room", p_room_id=rid); rpc(g_tok, "close_room", p_room_id=prid)
finally:
    ids = ",".join(f"'{u}'" for u in users)
    if ids:
        n = sql(f"select count(*) from public.promo_redemptions where user_id in ({ids})")
        sql(f"""update public.game_modes set status = 'available' where id = 'gizli_kelime';
                delete from public.admin_allowlist where email like '%{TAG}@loopa-test.invalid';
                delete from public.admin_audit_log where actor_id in ({ids}) or target_user in ({ids})
                    or details->>'email_hash' = md5('admin{TAG}@loopa-test.invalid');
                delete from public.promo_redemptions where user_id in ({ids});
                update public.promo_codes set active = false, redemptions = greatest(redemptions - {n}, 0) where code = 'LOPPAWEEK';
                delete from public.rooms where host_id in ({ids});
                delete from auth.users where id in ({ids});""")

failed = [w for ok, w in results if not ok]
print(f"\n{len(results) - len(failed)}/{len(results)} passed")
sys.exit(1 if failed else 0)
