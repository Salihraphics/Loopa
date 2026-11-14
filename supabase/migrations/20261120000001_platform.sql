-- =====================================================================
-- LOOPA 0.8.0 — platform: economy, progression, quests, badges, leaderboards,
-- persistent teams, game preferences and the room setup flow.
--
-- Principles (same as the rest of the backend):
--  * Clients read their own rows (RLS) or call SECURITY DEFINER RPCs.
--    Nothing here is writable by clients directly.
--  * Every reward is computed on the server, once per (user, source) through
--    idempotency keys; balances never go negative (check constraint + row lock).
--  * Match rewards are computed when a match's rewards are processed
--    (matches.rewards_processed false -> true happens exactly once).
--  * All amounts live in economy_config and can be changed by an admin
--    without an app update.
--
-- Changes to existing objects (all additive):
--  * xp_transactions.reason: new reasons allowed (streak_bonus, quest, tournament, achievement).
--  * game_modes: player limits widened to 2..20, new column tags.
--  * cosmetic_items: new columns price_coins / is_premium, new slot name_effect.
--  * match_players: new columns team_side, team_id.
--  * unlock_cosmetic: refuses items that have a coin price (they must be bought).
--  * update_room_settings / set_ready / start_match: setup flow (configured flag,
--    per-game options, up to 20 players). Old clients keep working: rooms without
--    the flag behave exactly as before.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Configuration
-- ---------------------------------------------------------------------
create table if not exists public.economy_config (
    key        text primary key check (key ~ '^[a-z_]{3,48}$'),
    value      jsonb not null,
    updated_at timestamptz not null default now()
);

insert into public.economy_config(key, value) values
 ('match_rewards', '{
    "duel":     {"participation": 250, "win": 200, "min_seconds": 90},
    "word":     {"participation": 300, "win": 250, "min_seconds": 120},
    "team":     {"participation": 350, "win": 300, "min_seconds": 120},
    "ffa":      {"participation": 300, "win": 300, "min_seconds": 120},
    "strategy": {"participation": 500, "win": 400, "min_seconds": 300}
  }'),
 ('game_reward_class', '{
    "isim_sehir": "word", "kelime_zinciri": "word", "anlat_bakalim": "team", "gizli_kelime": "team",
    "bilgi_arenasi": "ffa", "iki_dogru_bir_yalan": "ffa", "en_muhtemel_kim": "ffa", "sessiz_sinema": "ffa",
    "ciz_tahmin": "ffa", "cizim_telefonu": "ffa",
    "tavla": "duel", "yirmi_bir": "duel", "amiral_batti": "duel", "kizma_birader": "ffa",
    "renk_kartlari": "ffa", "okey": "ffa", "milyoner": "strategy", "kurt_adam": "team", "kralliklar": "strategy"
  }'),
 ('streak_individual', '[[1,1.00],[3,1.10],[5,1.20],[8,1.30],[11,1.40],[16,1.50]]'),
 ('streak_team',       '[[1,1.00],[3,1.15],[5,1.30],[8,1.45],[11,1.60]]'),
 ('streak_xp',         '{"apply_to_xp": true}'),
 ('anti_farm', '{"daily_rewarded_matches": 40, "same_opponents_daily": 6}'),
 ('prestige',  '{"level_required": 100, "levels_per_prestige": 25}'),
 ('tournament_rewards', '{"champion": {"coins": 1500, "xp": 300, "reputation": 50},
                          "finalist": {"coins": 500,  "xp": 120, "reputation": 20},
                          "min_participants": 4, "daily_rewarded_tournaments": 2}'),
 ('reputation', '{"badge_rare": 5, "badge_epic": 15, "badge_legendary": 40}'),
 ('wheel', '{"like_weight": 0.6, "dislike_weight": 0.7, "min_weight": 0.05}')
on conflict (key) do nothing;

create or replace function public._cfg(p_key text)
returns jsonb language sql stable security definer set search_path = public, pg_temp as $$
    select coalesce((select value from public.economy_config where key = p_key), '{}'::jsonb);
$$;

-- Calendar periods in Türkiye time: days start at 00:00, weeks on Monday 00:00.
create or replace function public._day_key(p_at timestamptz default now())
returns text language sql stable as $$ select 'd:' || to_char(p_at at time zone 'Europe/Istanbul', 'YYYY-MM-DD'); $$;
create or replace function public._week_key(p_at timestamptz default now())
returns text language sql stable as $$ select 'w:' || to_char(p_at at time zone 'Europe/Istanbul', 'IYYY-"W"IW'); $$;
create or replace function public._week_start(p_at timestamptz default now())
returns timestamptz language sql stable as $$
    select (date_trunc('week', p_at at time zone 'Europe/Istanbul')) at time zone 'Europe/Istanbul';
$$;
create or replace function public._day_start(p_at timestamptz default now())
returns timestamptz language sql stable as $$
    select (date_trunc('day', p_at at time zone 'Europe/Istanbul')) at time zone 'Europe/Istanbul';
$$;

-- ---------------------------------------------------------------------
-- Existing tables, extended (additive)
-- ---------------------------------------------------------------------
alter table public.xp_transactions drop constraint if exists xp_transactions_reason_check;
alter table public.xp_transactions add constraint xp_transactions_reason_check check (reason in
    ('match_complete','round_win','match_winner','match_second','match_third',
     'streak_bonus','quest','tournament','achievement'));
create index if not exists xp_transactions_time_idx on public.xp_transactions(created_at);

alter table public.game_modes drop constraint if exists game_modes_min_players_check;
alter table public.game_modes drop constraint if exists game_modes_max_players_check;
alter table public.game_modes add constraint game_modes_min_players_check check (min_players between 2 and 20);
alter table public.game_modes add constraint game_modes_max_players_check check (max_players between 2 and 20);
alter table public.game_modes add column if not exists tags text[] not null default '{}';

update public.game_modes g set tags = v.t from (values
 ('isim_sehir', '{word,ffa}'::text[]), ('gizli_kelime', '{word,team,social}'), ('kelime_zinciri', '{word,duel}'),
 ('bilgi_arenasi', '{quiz,ffa}'), ('iki_dogru_bir_yalan', '{social,ffa}'), ('ciz_tahmin', '{drawing,ffa}'),
 ('en_muhtemel_kim', '{social,ffa}'), ('anlat_bakalim', '{word,team,social}'), ('cizim_telefonu', '{drawing,ffa}'),
 ('sessiz_sinema', '{social,team}')) v(id, t) where g.id = v.id;

alter table public.cosmetic_items add column if not exists price_coins int check (price_coins is null or price_coins > 0);
alter table public.cosmetic_items add column if not exists is_premium boolean not null default false;
alter table public.cosmetic_items drop constraint if exists cosmetic_items_slot_check;
alter table public.cosmetic_items add constraint cosmetic_items_slot_check check (slot in (
    'base','skin','hair','hair_color','eyes','mouth','glasses',
    'hat','outfit','accessory','background','frame','decoration','name_effect'));

alter table public.match_players add column if not exists team_side int;
alter table public.match_players add column if not exists team_id uuid;

-- ---------------------------------------------------------------------
-- Coins: wallet + append-only ledger
-- ---------------------------------------------------------------------
create table if not exists public.wallets (
    user_id         uuid primary key references public.profiles(id) on delete cascade,
    coins           bigint not null default 0 check (coins >= 0),
    lifetime_earned bigint not null default 0 check (lifetime_earned >= 0),
    updated_at      timestamptz not null default now()
);
create table if not exists public.coin_transactions (
    id            bigint generated always as identity primary key,
    user_id       uuid not null references public.profiles(id) on delete cascade,
    amount        int not null check (amount <> 0),
    reason        text not null check (reason in ('match','quest','tournament','purchase','achievement','admin','refund')),
    source_key    text not null,
    balance_after bigint not null check (balance_after >= 0),
    created_at    timestamptz not null default now(),
    unique (user_id, source_key)
);
create index if not exists coin_transactions_user_idx on public.coin_transactions(user_id, created_at desc);

-- Credits once per (user, key). Returns the amount credited (0 when already done).
create or replace function public._coins_grant(p_user uuid, p_amount int, p_reason text, p_key text)
returns int language plpgsql security definer set search_path = public, pg_temp as $$
declare bal bigint;
begin
    if p_amount <= 0 then return 0; end if;
    insert into public.wallets(user_id) values (p_user) on conflict do nothing;
    perform 1 from public.wallets where user_id = p_user for update;
    if exists (select 1 from public.coin_transactions where user_id = p_user and source_key = p_key) then return 0; end if;
    update public.wallets set coins = coins + p_amount, lifetime_earned = lifetime_earned + p_amount, updated_at = now()
     where user_id = p_user returning coins into bal;
    insert into public.coin_transactions(user_id, amount, reason, source_key, balance_after)
    values (p_user, p_amount, p_reason, p_key, bal);
    return p_amount;
end $$;

-- Debits once per (user, key); raises INSUFFICIENT_COINS without changing anything.
create or replace function public._coins_spend(p_user uuid, p_amount int, p_reason text, p_key text)
returns bigint language plpgsql security definer set search_path = public, pg_temp as $$
declare bal bigint;
begin
    if p_amount <= 0 then raise exception 'LOOPA:INVALID_INPUT'; end if;
    insert into public.wallets(user_id) values (p_user) on conflict do nothing;
    select coins into bal from public.wallets where user_id = p_user for update;
    if exists (select 1 from public.coin_transactions where user_id = p_user and source_key = p_key) then return bal; end if;
    if bal < p_amount then raise exception 'LOOPA:INSUFFICIENT_COINS'; end if;
    update public.wallets set coins = coins - p_amount, updated_at = now() where user_id = p_user returning coins into bal;
    insert into public.coin_transactions(user_id, amount, reason, source_key, balance_after)
    values (p_user, -p_amount, p_reason, p_key, bal);
    return bal;
end $$;

-- ---------------------------------------------------------------------
-- Streaks, reputation, persistent teams
-- ---------------------------------------------------------------------
create table if not exists public.player_streaks (
    user_id    uuid primary key references public.profiles(id) on delete cascade,
    current    int not null default 0 check (current >= 0),
    best       int not null default 0 check (best >= 0),
    updated_at timestamptz not null default now()
);

create table if not exists public.reputation_events (
    id         bigint generated always as identity primary key,
    user_id    uuid not null references public.profiles(id) on delete cascade,
    amount     int not null check (amount > 0),
    reason     text not null,
    source_key text not null,
    created_at timestamptz not null default now(),
    unique (user_id, source_key)
);
create table if not exists public.player_reputation (
    user_id    uuid primary key references public.profiles(id) on delete cascade,
    points     int not null default 0 check (points >= 0),
    updated_at timestamptz not null default now()
);

create or replace function public._reputation_add(p_user uuid, p_amount int, p_reason text, p_key text)
returns int language plpgsql security definer set search_path = public, pg_temp as $$
begin
    if p_amount <= 0 then return 0; end if;
    insert into public.reputation_events(user_id, amount, reason, source_key) values (p_user, p_amount, p_reason, p_key)
    on conflict (user_id, source_key) do nothing;
    if not found then return 0; end if;
    insert into public.player_reputation(user_id, points) values (p_user, p_amount)
    on conflict (user_id) do update set points = player_reputation.points + excluded.points, updated_at = now();
    return p_amount;
end $$;

create table if not exists public.teams (
    id           uuid primary key default gen_random_uuid(),
    name         text not null check (char_length(name) between 3 and 24),
    join_code    text not null unique check (join_code ~ '^[A-Z0-9]{6}$'),
    owner_id     uuid not null references public.profiles(id) on delete cascade,
    current_streak int not null default 0 check (current_streak >= 0),
    best_streak    int not null default 0 check (best_streak >= 0),
    created_at   timestamptz not null default now(),
    disbanded_at timestamptz
);
create table if not exists public.team_members (
    team_id   uuid not null references public.teams(id) on delete cascade,
    user_id   uuid not null references public.profiles(id) on delete cascade,
    joined_at timestamptz not null default now(),
    primary key (team_id, user_id)
);
create index if not exists team_members_user_idx on public.team_members(user_id);
-- A player belongs to at most one active team.
create or replace function public._active_team(p_user uuid)
returns uuid language sql stable security definer set search_path = public, pg_temp as $$
    select tm.team_id from public.team_members tm join public.teams t on t.id = tm.team_id
     where tm.user_id = p_user and t.disbanded_at is null limit 1;
$$;

-- ---------------------------------------------------------------------
-- Per-match reward record (idempotency + anti-farming bookkeeping)
-- ---------------------------------------------------------------------
create table if not exists public.match_rewards (
    match_id     uuid not null references public.matches(id) on delete cascade,
    user_id      uuid not null references public.profiles(id) on delete cascade,
    game_mode    text not null,
    reward_class text not null,
    won          boolean not null,
    valid        boolean not null,
    reason       text,                      -- why a match was not rewarded
    coins        int not null default 0,
    xp_bonus     int not null default 0,
    multiplier   numeric(4,2) not null default 1,
    opponents_key text not null,
    team_id      uuid,
    created_at   timestamptz not null default now(),
    primary key (match_id, user_id)
);
create index if not exists match_rewards_user_day_idx on public.match_rewards(user_id, created_at desc);

-- ---------------------------------------------------------------------
-- Quests
-- ---------------------------------------------------------------------
create table if not exists public.quest_definitions (
    id         text primary key check (id ~ '^[a-z0-9_]{3,48}$'),
    period     text not null check (period in ('daily','weekly')),
    difficulty text not null check (difficulty in ('easy','medium','hard')),
    kind       text not null check (kind in ('play_matches','win_matches','play_tag','win_tag','team_games','reach_streak','play_distinct_games')),
    target     int  not null check (target > 0),
    params     jsonb not null default '{}'::jsonb,       -- e.g. {"tag":"word"}
    title_tr   text not null,
    coins      int  not null check (coins >= 0),
    xp         int  not null check (xp >= 0),
    active     boolean not null default true
);
create table if not exists public.player_quests (
    user_id      uuid not null references public.profiles(id) on delete cascade,
    period_key   text not null,
    quest_id     text not null references public.quest_definitions(id),
    progress     int  not null default 0 check (progress >= 0),
    target       int  not null,
    meta         jsonb not null default '{}'::jsonb,       -- e.g. distinct games played
    completed_at timestamptz,
    claimed_at   timestamptz,
    primary key (user_id, period_key, quest_id)
);

insert into public.quest_definitions(id, period, difficulty, kind, target, params, title_tr, coins, xp) values
 ('d_play_3',      'daily',  'easy',   'play_matches', 3, '{}',               '3 maç tamamla',                    300,  40),
 ('d_play_word_2', 'daily',  'easy',   'play_tag',     2, '{"tag":"word"}',   '2 kelime oyunu oyna',              300,  40),
 ('d_play_duel_2', 'daily',  'easy',   'play_tag',     2, '{"tag":"duel"}',   '2 düello (1v1) oyunu oyna',        300,  40),
 ('d_win_1',       'daily',  'medium', 'win_matches',  1, '{}',               '1 maç kazan',                      600,  80),
 ('d_play_3_kinds','daily',  'medium', 'play_distinct_games', 3, '{}',        '3 farklı oyun oyna',               600,  80),
 ('d_team_2',      'daily',  'medium', 'team_games',   2, '{}',               '2 takım oyunu oyna',               600,  80),
 ('d_win_3',       'daily',  'hard',   'win_matches',  3, '{}',               '3 maç kazan',                     1000, 140),
 ('d_streak_3',    'daily',  'hard',   'reach_streak', 3, '{}',               '3 maçlık galibiyet serisine ulaş', 1000, 140),
 ('w_play_15',     'weekly', 'easy',   'play_matches', 15, '{}',              '15 maç tamamla',                  2000, 250),
 ('w_play_word_8', 'weekly', 'easy',   'play_tag',      8, '{"tag":"word"}',  '8 kelime oyunu oyna',             2000, 250),
 ('w_win_8',       'weekly', 'medium', 'win_matches',   8, '{}',              '8 maç kazan',                     3500, 400),
 ('w_play_6_kinds','weekly', 'medium', 'play_distinct_games', 6, '{}',        '6 farklı oyun oyna',              3500, 400),
 ('w_win_15',      'weekly', 'hard',   'win_matches',  15, '{}',              '15 maç kazan',                    5000, 600),
 ('w_streak_5',    'weekly', 'hard',   'reach_streak',  5, '{}',              '5 maçlık galibiyet serisine ulaş', 5000, 600)
on conflict (id) do nothing;

-- ---------------------------------------------------------------------
-- Badges and titles (content is data: admins add / retire them without an app update)
-- ---------------------------------------------------------------------
create table if not exists public.badges (
    id             text primary key check (id ~ '^[a-z0-9_]{3,48}$'),
    kind           text not null check (kind in ('badge','title')),
    name_tr        text not null check (char_length(name_tr) between 2 and 40),
    description_tr text not null default '',
    icon           text not null default 'star',          -- icon key or emoji
    rarity         text not null default 'common' check (rarity in ('common','rare','epic','legendary')),
    condition      jsonb not null default '{"type":"manual"}'::jsonb,
    available_from  timestamptz,
    available_until timestamptz,                           -- seasonal / meme items
    active         boolean not null default true,
    sort_order     int not null default 0,
    created_at     timestamptz not null default now()
);
create table if not exists public.player_badges (
    user_id     uuid not null references public.profiles(id) on delete cascade,
    badge_id    text not null references public.badges(id) on delete cascade,
    unlocked_at timestamptz not null default now(),
    primary key (user_id, badge_id)
);
create table if not exists public.player_showcase (
    user_id    uuid primary key references public.profiles(id) on delete cascade,
    title_id   text references public.badges(id) on delete set null,
    badge_ids  text[] not null default '{}' check (cardinality(badge_ids) <= 3),
    updated_at timestamptz not null default now()
);

insert into public.badges(id, kind, name_tr, description_tr, icon, rarity, condition, sort_order) values
 ('first_win',      'badge', 'İlk Zafer',        'İlk maçını kazan.',                       'trophy',  'common',    '{"type":"stat","stat":"match_wins","min":1}', 10),
 ('veteran_100',    'badge', 'Müdavim',          '100 maç tamamla.',                        'medal',   'rare',      '{"type":"stat","stat":"matches_played","min":100}', 20),
 ('streak_5',       'badge', 'Alev Aldı',        '5 maçlık galibiyet serisi.',              'flame',   'rare',      '{"type":"streak","min":5}', 30),
 ('streak_10',      'badge', 'Durdurulamaz',     '10 maçlık galibiyet serisi.',             'flame',   'epic',      '{"type":"streak","min":10}', 40),
 ('team_streak_5',  'badge', 'Kader Ortakları',  'Takımınla 5 maçlık seri.',                'groups',  'epic',      '{"type":"team_streak","min":5}', 50),
 ('champion',       'badge', 'Şampiyon',         'Bir turnuva kazan.',                      'trophy',  'epic',      '{"type":"tournament_wins","min":1}', 60),
 ('prestige_1',     'badge', 'Prestij',          'Prestij seviyesine ulaş.',                'diamond', 'legendary', '{"type":"prestige","min":1}', 70),
 ('t_main_character','title','Ana Karakter',     '3 maçlık seri yap.',                      'star',    'common',    '{"type":"streak","min":3}', 100),
 ('t_aura_farmer',  'title', 'Aura Kasıyor',     '25 maç kazan.',                           'sparkle', 'rare',      '{"type":"stat","stat":"match_wins","min":25}', 110),
 ('t_npc_degil',    'title', 'NPC Değilim',      '10 maç tamamla.',                         'person',  'common',    '{"type":"stat","stat":"matches_played","min":10}', 120),
 ('t_kaos_ustasi',  'title', 'Kaosun Efendisi',  '5 farklı oyunda maç kazan.',              'bolt',    'rare',      '{"type":"distinct_game_wins","min":5}', 130),
 ('t_efsane',       'title', 'Efsane',           'Bir turnuva şampiyonluğu.',               'crown',   'epic',      '{"type":"tournament_wins","min":1}', 140)
on conflict (id) do nothing;

-- ---------------------------------------------------------------------
-- Game preferences
-- ---------------------------------------------------------------------
create table if not exists public.player_game_prefs (
    user_id    uuid primary key references public.profiles(id) on delete cascade,
    likes      text[] not null default '{}',
    dislikes   text[] not null default '{}',
    updated_at timestamptz not null default now(),
    check (not (likes && dislikes))
);
create table if not exists public.room_wheel_spins (
    id         uuid primary key default gen_random_uuid(),
    room_id    uuid not null references public.rooms(id) on delete cascade,
    result     text not null,
    candidates jsonb not null,                -- [{mode, weight}]
    created_at timestamptz not null default now()
);
create index if not exists room_wheel_spins_room_idx on public.room_wheel_spins(room_id, created_at desc);

-- ---------------------------------------------------------------------
-- RLS: own rows only, read only
-- ---------------------------------------------------------------------
alter table public.economy_config    enable row level security;
alter table public.wallets           enable row level security;
alter table public.coin_transactions enable row level security;
alter table public.player_streaks    enable row level security;
alter table public.reputation_events enable row level security;
alter table public.player_reputation enable row level security;
alter table public.teams             enable row level security;
alter table public.team_members      enable row level security;
alter table public.match_rewards     enable row level security;
alter table public.quest_definitions enable row level security;
alter table public.player_quests     enable row level security;
alter table public.badges            enable row level security;
alter table public.player_badges     enable row level security;
alter table public.player_showcase   enable row level security;
alter table public.player_game_prefs enable row level security;
alter table public.room_wheel_spins  enable row level security;

drop policy if exists economy_config_read on public.economy_config;
create policy economy_config_read on public.economy_config for select to authenticated using (true);
drop policy if exists wallets_own on public.wallets;
create policy wallets_own on public.wallets for select to authenticated using (user_id = auth.uid());
drop policy if exists coin_tx_own on public.coin_transactions;
create policy coin_tx_own on public.coin_transactions for select to authenticated using (user_id = auth.uid());
drop policy if exists streaks_read on public.player_streaks;
create policy streaks_read on public.player_streaks for select to authenticated using (true);          -- shown under names
drop policy if exists rep_events_own on public.reputation_events;
create policy rep_events_own on public.reputation_events for select to authenticated using (user_id = auth.uid());
drop policy if exists rep_read on public.player_reputation;
create policy rep_read on public.player_reputation for select to authenticated using (true);
drop policy if exists teams_read on public.teams;
create policy teams_read on public.teams for select to authenticated
    using (exists (select 1 from public.team_members tm where tm.team_id = id and tm.user_id = auth.uid()));
drop policy if exists team_members_read on public.team_members;
create policy team_members_read on public.team_members for select to authenticated
    using (exists (select 1 from public.team_members tm where tm.team_id = team_members.team_id and tm.user_id = auth.uid()));
drop policy if exists match_rewards_own on public.match_rewards;
create policy match_rewards_own on public.match_rewards for select to authenticated using (user_id = auth.uid());
drop policy if exists quest_defs_read on public.quest_definitions;
create policy quest_defs_read on public.quest_definitions for select to authenticated using (true);
drop policy if exists player_quests_own on public.player_quests;
create policy player_quests_own on public.player_quests for select to authenticated using (user_id = auth.uid());
drop policy if exists badges_read on public.badges;
create policy badges_read on public.badges for select to authenticated using (true);
drop policy if exists player_badges_read on public.player_badges;
create policy player_badges_read on public.player_badges for select to authenticated using (true);
drop policy if exists showcase_read on public.player_showcase;
create policy showcase_read on public.player_showcase for select to authenticated using (true);
drop policy if exists prefs_own on public.player_game_prefs;
create policy prefs_own on public.player_game_prefs for select to authenticated using (user_id = auth.uid());
drop policy if exists wheel_read on public.room_wheel_spins;
create policy wheel_read on public.room_wheel_spins for select to authenticated using (public.is_room_member(room_id));

revoke all on public.economy_config, public.wallets, public.coin_transactions, public.player_streaks,
              public.reputation_events, public.player_reputation, public.teams, public.team_members,
              public.match_rewards, public.quest_definitions, public.player_quests, public.badges,
              public.player_badges, public.player_showcase, public.player_game_prefs, public.room_wheel_spins
  from anon, authenticated;
grant select on public.economy_config, public.wallets, public.coin_transactions, public.player_streaks,
                public.reputation_events, public.player_reputation, public.teams, public.team_members,
                public.match_rewards, public.quest_definitions, public.player_quests, public.badges,
                public.player_badges, public.player_showcase, public.player_game_prefs, public.room_wheel_spins
  to authenticated;
do $$
declare s text;
begin
    foreach s in array array[pg_get_serial_sequence('public.coin_transactions','id'),
                             pg_get_serial_sequence('public.reputation_events','id')] loop
        execute format('revoke all on sequence %s from anon, authenticated', s);
    end loop;
end $$;

-- ---------------------------------------------------------------------
-- Streak multiplier (one table, never stacked: the better of individual / team counts)
-- ---------------------------------------------------------------------
create or replace function public._streak_mult(p_table text, p_streak int)
returns numeric language sql stable security definer set search_path = public, pg_temp as $$
    select coalesce((select (x->>1)::numeric from jsonb_array_elements(public._cfg(p_table)) x
                      where (x->>0)::int <= greatest(p_streak, 1) order by (x->>0)::int desc limit 1), 1.0);
$$;

-- ---------------------------------------------------------------------
-- Badge evaluation
-- ---------------------------------------------------------------------
create or replace function public._prestige_of(p_level int)
returns int language sql stable security definer set search_path = public, pg_temp as $$
    select case when p_level < coalesce((public._cfg('prestige')->>'level_required')::int, 100) then 0
                else 1 + (p_level - coalesce((public._cfg('prestige')->>'level_required')::int, 100))
                         / greatest(coalesce((public._cfg('prestige')->>'levels_per_prestige')::int, 25), 1) end;
$$;

create or replace function public._badge_met(p_user uuid, c jsonb)
returns boolean language plpgsql stable security definer set search_path = public, pg_temp as $$
declare v int;
begin
    case c->>'type'
    when 'stat' then
        execute format('select coalesce((select %I from public.player_statistics where user_id = $1), 0)',
                       case when c->>'stat' in ('matches_played','match_wins','round_wins','second_places','third_places') then c->>'stat' else 'matches_played' end)
           into v using p_user;
    when 'streak' then select coalesce(max(best), 0) into v from public.player_streaks where user_id = p_user;
    when 'team_streak' then
        select coalesce(max(t.best_streak), 0) into v from public.teams t join public.team_members tm on tm.team_id = t.id where tm.user_id = p_user;
    when 'tournament_wins' then
        v := coalesce((select count(*) from public.reputation_events where user_id = p_user and reason = 'tournament_champion'), 0);
    when 'prestige' then select public._prestige_of(level) into v from public.profiles where id = p_user;
    when 'distinct_game_wins' then select count(*) into v from public.player_mode_stats where user_id = p_user and wins > 0;
    else return false;
    end case;
    return coalesce(v, 0) >= coalesce((c->>'min')::int, 1);
end $$;

create or replace function public._check_badges(p_user uuid)
returns int language plpgsql security definer set search_path = public, pg_temp as $$
declare b record; n int := 0;
begin
    for b in select * from public.badges
              where active and condition->>'type' <> 'manual'
                and (available_from is null or now() >= available_from)
                and (available_until is null or now() < available_until)
                and not exists (select 1 from public.player_badges pb where pb.user_id = p_user and pb.badge_id = badges.id) loop
        if public._badge_met(p_user, b.condition) then
            insert into public.player_badges(user_id, badge_id) values (p_user, b.id) on conflict do nothing;
            if found then
                n := n + 1;
                perform public._reputation_add(p_user, coalesce((public._cfg('reputation')->>('badge_' || b.rarity))::int, 0),
                                               'badge', 'badge:' || b.id);
            end if;
        end if;
    end loop;
    return n;
end $$;

-- ---------------------------------------------------------------------
-- Quests: assignment, progress, claim
-- ---------------------------------------------------------------------
create or replace function public._quest_period_key(p_period text)
returns text language sql stable as $$
    select case when p_period = 'daily' then public._day_key() else public._week_key() end;
$$;

-- Draws one quest per difficulty for the current period (only quests the player can do).
create or replace function public._ensure_quests(p_user uuid, p_period text)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare k text := public._quest_period_key(p_period); d text; q public.quest_definitions;
begin
    perform pg_advisory_xact_lock(hashtextextended('loopa:quests:' || p_user || ':' || k, 0));
    if exists (select 1 from public.player_quests where user_id = p_user and period_key = k) then return; end if;
    foreach d in array array['easy','medium','hard'] loop
        select * into q from public.quest_definitions qd
         where qd.active and qd.period = p_period and qd.difficulty = d
           and (qd.kind not in ('play_tag','win_tag') or exists (
                   select 1 from public.game_modes gm where gm.status in ('available','early_access') and (qd.params->>'tag') = any(gm.tags)
                      and (public._mode_access(p_user, gm.id)->>'allowed')::boolean))
         order by random() limit 1;
        if found then
            insert into public.player_quests(user_id, period_key, quest_id, target) values (p_user, k, q.id, q.target)
            on conflict do nothing;
        end if;
    end loop;
end $$;

create or replace function public._quest_progress(p_user uuid, p_mode text, p_won boolean, p_team boolean, p_streak int)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare pq record; inc int; tags text[]; games jsonb;
begin
    select coalesce(gm.tags, '{}') into tags from public.game_modes gm where gm.id = p_mode;
    perform public._ensure_quests(p_user, 'daily');
    perform public._ensure_quests(p_user, 'weekly');
    for pq in select q.*, d.kind, d.params from public.player_quests q join public.quest_definitions d on d.id = q.quest_id
               where q.user_id = p_user and q.period_key in (public._day_key(), public._week_key()) and q.completed_at is null
               for update of q loop
        inc := 0;
        if pq.kind = 'play_matches' then inc := 1;
        elsif pq.kind = 'win_matches' and p_won then inc := 1;
        elsif pq.kind = 'play_tag' and (pq.params->>'tag') = any(tags) then inc := 1;
        elsif pq.kind = 'win_tag' and p_won and (pq.params->>'tag') = any(tags) then inc := 1;
        elsif pq.kind = 'team_games' and p_team then inc := 1;
        elsif pq.kind = 'reach_streak' then
            update public.player_quests set progress = greatest(progress, least(p_streak, target)),
                   completed_at = case when p_streak >= target then now() end
             where user_id = pq.user_id and period_key = pq.period_key and quest_id = pq.quest_id;
            continue;
        elsif pq.kind = 'play_distinct_games' then
            games := coalesce(pq.meta->'games', '[]'::jsonb);
            if not games ? p_mode then
                games := games || to_jsonb(p_mode);
                update public.player_quests set meta = jsonb_set(meta, '{games}', games), progress = least(jsonb_array_length(games), target),
                       completed_at = case when jsonb_array_length(games) >= target then now() end
                 where user_id = pq.user_id and period_key = pq.period_key and quest_id = pq.quest_id;
            end if;
            continue;
        end if;
        if inc > 0 then
            update public.player_quests set progress = least(progress + inc, target),
                   completed_at = case when progress + inc >= target then now() end
             where user_id = pq.user_id and period_key = pq.period_key and quest_id = pq.quest_id;
        end if;
    end loop;
end $$;

create or replace function public.get_my_quests()
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid();
begin
    perform public._require_profile(u);
    perform public._ensure_quests(u, 'daily');
    perform public._ensure_quests(u, 'weekly');
    return jsonb_build_object(
        'day_resets_at', public._day_start() + interval '1 day',
        'week_resets_at', public._week_start() + interval '7 days',
        'quests', coalesce((select jsonb_agg(jsonb_build_object(
                'id', q.quest_id, 'period', d.period, 'difficulty', d.difficulty, 'title', d.title_tr,
                'progress', q.progress, 'target', q.target, 'coins', d.coins, 'xp', d.xp,
                'completed', q.completed_at is not null, 'claimed', q.claimed_at is not null)
                order by d.period, case d.difficulty when 'easy' then 1 when 'medium' then 2 else 3 end)
            from public.player_quests q join public.quest_definitions d on d.id = q.quest_id
            where q.user_id = u and q.period_key in (public._day_key(), public._week_key())), '[]'::jsonb));
end $$;

create or replace function public.claim_quest(p_quest_id text)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); q public.player_quests; d public.quest_definitions; k text; c int; x int;
begin
    select * into d from public.quest_definitions where id = p_quest_id;
    if not found then raise exception 'LOOPA:QUEST_NOT_FOUND'; end if;
    k := public._quest_period_key(d.period);
    select * into q from public.player_quests where user_id = u and period_key = k and quest_id = p_quest_id for update;
    if not found then raise exception 'LOOPA:QUEST_NOT_FOUND'; end if;
    if q.completed_at is null then raise exception 'LOOPA:QUEST_NOT_COMPLETE'; end if;
    if q.claimed_at is not null then
        return jsonb_build_object('status', 'already_claimed');
    end if;
    c := public._coins_grant(u, d.coins, 'quest', 'quest:' || k || ':' || d.id);
    x := public._award_xp(u, d.xp, 'quest', 'quest:' || k || ':' || d.id, null);
    update public.player_quests set claimed_at = now() where user_id = u and period_key = k and quest_id = p_quest_id;
    perform public._check_badges(u);
    return jsonb_build_object('status', 'claimed', 'coins', c, 'xp', x);
end $$;

-- ---------------------------------------------------------------------
-- After a match: coins, streaks, team streaks, quests, badges (exactly once per match)
-- ---------------------------------------------------------------------
create or replace function public._progress_match(p_match uuid)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare
    m public.matches; p record; cls text; rule jsonb; dur numeric; n_players int; v_won boolean; v_valid boolean; v_why text;
    opp text; today_count int; same_opp int; streak int; tstreak int := 0; mult numeric; coins int; xpb int;
    team uuid; side_full boolean; is_team_game boolean; af jsonb := public._cfg('anti_farm');
begin
    select * into m from public.matches where id = p_match;
    if exists (select 1 from public.match_rewards where match_id = p_match) then return; end if;
    cls := coalesce(public._cfg('game_reward_class')->>m.game_mode, 'ffa');
    rule := public._cfg('match_rewards')->cls;
    dur := extract(epoch from (coalesce(m.finished_at, now()) - m.started_at));
    select count(*) into n_players from public.match_players where match_id = p_match;
    is_team_game := exists (select 1 from public.match_players where match_id = p_match and team_side is not null);

    for p in select mp.*, (rp.status = 'active') as present
               from public.match_players mp
               join public.room_players rp on rp.room_id = mp.room_id and rp.user_id = mp.user_id
              where mp.match_id = p_match loop
        -- a shared first place is a draw, not a win (unless it is one team)
        v_won := p.final_rank = 1 and (
            (select count(*) from public.match_players x where x.match_id = p_match and x.final_rank = 1) = 1
            or (p.team_side is not null and not exists (select 1 from public.match_players x
                    where x.match_id = p_match and x.final_rank = 1 and x.team_side is distinct from p.team_side)));
        select coalesce(string_agg(x.user_id::text, ',' order by x.user_id), '') into opp
          from public.match_players x where x.match_id = p_match and x.user_id <> p.user_id;
        v_valid := true; v_why := null;
        if m.status <> 'finished' then v_valid := false; v_why := 'not_finished';
        elsif n_players < 2 then v_valid := false; v_why := 'solo';
        elsif not p.present then v_valid := false; v_why := 'left';
        elsif dur < coalesce((rule->>'min_seconds')::numeric, 60) then v_valid := false; v_why := 'too_short';
        else
            select count(*) filter (where match_rewards.valid), count(*) filter (where match_rewards.valid and opponents_key = opp)
              into today_count, same_opp
              from public.match_rewards where user_id = p.user_id and created_at >= public._day_start();
            if today_count >= coalesce((af->>'daily_rewarded_matches')::int, 40) then v_valid := false; v_why := 'daily_cap';
            elsif same_opp >= coalesce((af->>'same_opponents_daily')::int, 6) then v_valid := false; v_why := 'same_opponents';
            end if;
        end if;

        -- individual streak: only v_valid, decided matches move it
        insert into public.player_streaks(user_id) values (p.user_id) on conflict do nothing;
        if v_valid then
            update public.player_streaks set current = case when v_won then current + 1 else 0 end,
                   best = greatest(best, case when v_won then current + 1 else 0 end), updated_at = now()
             where user_id = p.user_id returning current into streak;
        else
            select current into streak from public.player_streaks where user_id = p.user_id;
        end if;

        -- team streak: the whole active team played on one side
        team := null; tstreak := 0;
        if is_team_game and v_valid and p.team_side is not null then
            team := public._active_team(p.user_id);
            if team is not null then
                select bool_and(exists (select 1 from public.match_players x where x.match_id = p_match and x.user_id = tm.user_id and x.team_side = p.team_side))
                  into side_full from public.team_members tm where tm.team_id = team;
                if not coalesce(side_full, false) then team := null; end if;
            end if;
            if team is not null then
                update public.match_players set team_id = team where match_id = p_match and user_id = p.user_id;
                -- once per team per match
                if not exists (select 1 from public.match_rewards where match_id = p_match and team_id = team) then
                    update public.teams set current_streak = case when v_won then current_streak + 1 else 0 end,
                           best_streak = greatest(best_streak, case when v_won then current_streak + 1 else 0 end)
                     where id = team;
                end if;
                select current_streak into tstreak from public.teams where id = team;
            end if;
        end if;

        mult := 1.0; coins := 0; xpb := 0;
        if v_valid then
            -- the better of the two multipliers, never the product
            mult := greatest(public._streak_mult('streak_individual', case when v_won then streak else 0 end),
                             case when team is not null and v_won then public._streak_mult('streak_team', tstreak) else 1.0 end);
            coins := round((coalesce((rule->>'participation')::int, 0) + case when v_won then coalesce((rule->>'win')::int, 0) else 0 end) * mult);
            perform public._coins_grant(p.user_id, coins, 'match', 'match:' || p_match);
            if mult > 1 and coalesce((public._cfg('streak_xp')->>'apply_to_xp')::boolean, true) then
                xpb := round(p.xp_earned * (mult - 1));
                if xpb > 0 then perform public._award_xp(p.user_id, xpb, 'streak_bonus', 'streak:' || p_match, p_match); end if;
            end if;
            perform public._quest_progress(p.user_id, m.game_mode, v_won, p.team_side is not null, coalesce(streak, 0));
        end if;
        insert into public.match_rewards(match_id, user_id, game_mode, reward_class, won, valid, reason, coins, xp_bonus, multiplier, opponents_key, team_id)
        values (p_match, p.user_id, m.game_mode, cls, v_won, v_valid, v_why, coins, xpb, mult, opp, team);
        perform public._check_badges(p.user_id);
    end loop;
end $$;

create or replace function public._progress_trigger()
returns trigger language plpgsql security definer set search_path = public, pg_temp as $$
begin
    if new.rewards_processed and not old.rewards_processed then
        perform public._progress_match(new.id);
    end if;
    return new;
end $$;
drop trigger if exists matches_progress on public.matches;
-- named so it runs after matches_mode_stats (alphabetical order of triggers)
create trigger matches_progress after update of rewards_processed on public.matches
    for each row execute function public._progress_trigger();

-- ---------------------------------------------------------------------
-- Shop: coins for cosmetics (never gameplay power)
-- ---------------------------------------------------------------------
create or replace function public.purchase_cosmetic(p_item_id text)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); it public.cosmetic_items; lvl int; bal bigint;
begin
    perform public._require_profile(u);
    select * into it from public.cosmetic_items where id = p_item_id;
    if not found then raise exception 'LOOPA:UNKNOWN_COSMETIC'; end if;
    if it.price_coins is null then raise exception 'LOOPA:NOT_FOR_SALE'; end if;
    select level into lvl from public.profiles where id = u;
    if lvl < it.required_level then raise exception 'LOOPA:LEVEL_TOO_LOW'; end if;
    if exists (select 1 from public.player_cosmetics where user_id = u and item_id = it.id) then
        return jsonb_build_object('status', 'already_owned', 'coins', (select coins from public.wallets where user_id = u));
    end if;
    bal := public._coins_spend(u, it.price_coins, 'purchase', 'purchase:' || it.id);
    insert into public.player_cosmetics(user_id, item_id) values (u, it.id) on conflict do nothing;
    return jsonb_build_object('status', 'purchased', 'coins', bal);
end $$;

-- Level unlocks stay free; priced items can only be bought.
create or replace function public.unlock_cosmetic(p_item_id text)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); need int; lvl int; price int;
begin
    select required_level, price_coins into need, price from public.cosmetic_items where id = p_item_id;
    if need is null then raise exception 'LOOPA:UNKNOWN_COSMETIC'; end if;
    if price is not null then raise exception 'LOOPA:PURCHASE_REQUIRED'; end if;
    select level into lvl from public.profiles where id = u;
    if lvl is null then raise exception 'LOOPA:NO_PROFILE'; end if;
    if lvl < need then raise exception 'LOOPA:LEVEL_TOO_LOW'; end if;
    insert into public.player_cosmetics(user_id, item_id) values (u, p_item_id) on conflict do nothing;
end $$;

insert into public.cosmetic_items(id, slot, name_tr, required_level, sort_order, price_coins, is_premium) values
 ('frame_sunset',   'frame',       'Gün Batımı Çerçeve', 3, 50,  2500, false),
 ('frame_ocean',    'frame',       'Okyanus Çerçeve',    5, 51,  2500, false),
 ('frame_royal',    'frame',       'Kraliyet Çerçeve',  15, 52, 15000, true),
 ('acc_star_pin',   'accessory',   'Yıldız Rozet',       4, 60,  5000, false),
 ('acc_crown_mini', 'accessory',   'Mini Taç',          20, 61, 25000, true),
 ('name_rainbow',   'name_effect', 'Gökkuşağı İsim',     8, 70,  9000, false),
 ('name_gold',      'name_effect', 'Altın İsim',        25, 71, 25000, true),
 ('name_aurora',    'name_effect', 'Kutup Işığı İsim',  40, 72, 60000, true)
on conflict (id) do nothing;

-- ---------------------------------------------------------------------
-- Showcase (equipped title / badges / name effect)
-- ---------------------------------------------------------------------
create or replace function public.set_showcase(p_title_id text, p_badge_ids text[])
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); b text;
begin
    perform public._require_profile(u);
    if p_title_id is not null and not exists (select 1 from public.player_badges pb join public.badges bd on bd.id = pb.badge_id
                                               where pb.user_id = u and pb.badge_id = p_title_id and bd.kind = 'title') then
        raise exception 'LOOPA:BADGE_LOCKED';
    end if;
    if cardinality(coalesce(p_badge_ids, '{}')) > 3 then raise exception 'LOOPA:INVALID_INPUT'; end if;
    foreach b in array coalesce(p_badge_ids, '{}') loop
        if not exists (select 1 from public.player_badges pb join public.badges bd on bd.id = pb.badge_id
                        where pb.user_id = u and pb.badge_id = b and bd.kind = 'badge') then
            raise exception 'LOOPA:BADGE_LOCKED';
        end if;
    end loop;
    insert into public.player_showcase(user_id, title_id, badge_ids) values (u, p_title_id, coalesce(p_badge_ids, '{}'))
    on conflict (user_id) do update set title_id = excluded.title_id, badge_ids = excluded.badge_ids, updated_at = now();
end $$;

-- ---------------------------------------------------------------------
-- Progression overview (profile)
-- ---------------------------------------------------------------------
create or replace function public._player_card_extras(p_user uuid)
returns jsonb language sql stable security definer set search_path = public, pg_temp as $$
    select jsonb_build_object(
        'streak', coalesce((select current from public.player_streaks where user_id = p_user), 0),
        'title', (select jsonb_build_object('id', b.id, 'name', b.name_tr, 'rarity', b.rarity)
                    from public.player_showcase s join public.badges b on b.id = s.title_id where s.user_id = p_user),
        'name_effect', (select pc.item_id from public.player_cosmetics pc join public.cosmetic_items ci on ci.id = pc.item_id
                         where pc.user_id = p_user and ci.slot = 'name_effect'
                           and pc.item_id = (select p.avatar->>'name_effect' from public.profiles p where p.id = p_user)));
$$;

create or replace function public.get_progression(p_user uuid default null)
returns jsonb language plpgsql stable security definer set search_path = public, pg_temp as $$
declare me uuid := public._uid(); u uuid := coalesce(p_user, me); p public.profiles; t uuid;
begin
    select * into p from public.profiles where id = u;
    if not found then raise exception 'LOOPA:USER_NOT_FOUND'; end if;
    t := public._active_team(u);
    return jsonb_build_object(
        'user_id', u, 'level', p.level, 'xp', p.xp,
        'prestige', public._prestige_of(p.level),
        'prestige_level_required', coalesce((public._cfg('prestige')->>'level_required')::int, 100),
        'reputation', coalesce((select points from public.player_reputation where user_id = u), 0),
        'coins', case when u = me then coalesce((select coins from public.wallets where user_id = u), 0) end,
        'streak', coalesce((select current from public.player_streaks where user_id = u), 0),
        'best_streak', coalesce((select best from public.player_streaks where user_id = u), 0),
        'streak_multiplier', public._streak_mult('streak_individual', coalesce((select current from public.player_streaks where user_id = u), 0) + 1),
        'team', case when t is null then null else (select jsonb_build_object('id', id, 'name', name,
                    'current_streak', current_streak, 'best_streak', best_streak,
                    'join_code', case when exists (select 1 from public.team_members where team_id = t and user_id = me) then join_code end,
                    'members', (select jsonb_agg(public._user_card(tm.user_id) order by tm.joined_at) from public.team_members tm where tm.team_id = t))
                   from public.teams where id = t) end,
        'weekly_xp', coalesce((select sum(amount) from public.xp_transactions where user_id = u and created_at >= public._week_start()), 0),
        'showcase', public._player_card_extras(u),
        'badges', coalesce((select jsonb_agg(jsonb_build_object('id', b.id, 'kind', b.kind, 'name', b.name_tr, 'description', b.description_tr,
                    'icon', b.icon, 'rarity', b.rarity, 'unlocked_at', pb.unlocked_at,
                    'equipped', (b.id = s.title_id or b.id = any(coalesce(s.badge_ids, '{}'))))
                   order by b.sort_order)
                 from public.player_badges pb join public.badges b on b.id = pb.badge_id
                 left join public.player_showcase s on s.user_id = u
                 where pb.user_id = u), '[]'::jsonb),
        'trophies', coalesce((select count(*) from public.reputation_events where user_id = u and reason = 'tournament_champion'), 0),
        'game_stats', coalesce((select jsonb_agg(jsonb_build_object('game_mode', s.game_mode, 'matches_played', s.matches_played, 'wins', s.wins,
                    'losses', s.matches_played - s.wins,
                    'win_rate', case when s.matches_played > 0 then round(100.0 * s.wins / s.matches_played) else 0 end,
                    'best_points', s.best_points, 'total_points', s.total_points,
                    'play_seconds', (select coalesce(sum(extract(epoch from (m.finished_at - m.started_at)))::bigint, 0)
                                       from public.match_rewards r join public.matches m on m.id = r.match_id
                                      where r.user_id = u and r.game_mode = s.game_mode),
                    'extra', coalesce((select stats from public.player_game_stats g where g.user_id = u and g.game_mode = s.game_mode), '{}'::jsonb))
                   order by s.matches_played desc)
                 from public.player_mode_stats s where s.user_id = u), '[]'::jsonb)
    );
end $$;

-- Game-specific statistics (e.g. Tavla: gammons, Okey: openings) written by each game's engine.
create table if not exists public.player_game_stats (
    user_id    uuid not null references public.profiles(id) on delete cascade,
    game_mode  text not null,
    stats      jsonb not null default '{}'::jsonb,
    updated_at timestamptz not null default now(),
    primary key (user_id, game_mode)
);
alter table public.player_game_stats enable row level security;
drop policy if exists game_stats_read on public.player_game_stats;
create policy game_stats_read on public.player_game_stats for select to authenticated using (true);
revoke all on public.player_game_stats from anon, authenticated;
grant select on public.player_game_stats to authenticated;

-- Adds numeric counters / maxima to a player's game-specific stats ({"k": n} added, {"max_k": n} kept as maximum).
create or replace function public._game_stat(p_user uuid, p_mode text, p_add jsonb)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare k text; v jsonb; cur jsonb;
begin
    insert into public.player_game_stats(user_id, game_mode) values (p_user, p_mode) on conflict do nothing;
    select stats into cur from public.player_game_stats where user_id = p_user and game_mode = p_mode for update;
    for k, v in select * from jsonb_each(p_add) loop
        if left(k, 4) = 'max_' then
            cur := jsonb_set(cur, array[k], to_jsonb(greatest(coalesce((cur->>k)::numeric, 0), (v#>>'{}')::numeric)));
        else
            cur := jsonb_set(cur, array[k], to_jsonb(coalesce((cur->>k)::numeric, 0) + (v#>>'{}')::numeric));
        end if;
    end loop;
    update public.player_game_stats set stats = cur, updated_at = now() where user_id = p_user and game_mode = p_mode;
end $$;

-- ---------------------------------------------------------------------
-- Leaderboards
-- ---------------------------------------------------------------------
create or replace function public.get_leaderboard(p_kind text, p_limit int default 50, p_offset int default 0)
returns jsonb language plpgsql stable security definer set search_path = public, pg_temp as $$
declare me uuid := public._uid(); lim int := least(greatest(coalesce(p_limit, 50), 1), 100); off int := greatest(coalesce(p_offset, 0), 0);
begin
    if p_kind not in ('total','weekly') then raise exception 'LOOPA:INVALID_INPUT'; end if;
    return (with scores as (
                select p.id as user_id, case when p_kind = 'total' then p.xp::bigint
                    else coalesce((select sum(x.amount) from public.xp_transactions x where x.user_id = p.id and x.created_at >= public._week_start()), 0) end as score
                  from public.profiles p),
            ranked as (
                select user_id, score, rank() over (order by score desc) as rnk,
                       row_number() over (order by score desc, user_id) as pos
                  from scores where score > 0 or p_kind = 'total')
            select jsonb_build_object(
                'kind', p_kind,
                'resets_at', case when p_kind = 'weekly' then public._week_start() + interval '7 days' end,
                'rows', coalesce((select jsonb_agg(public._user_card(r.user_id) || public._player_card_extras(r.user_id)
                                     || jsonb_build_object('rank', r.rnk, 'score', r.score) order by r.pos)
                                   from ranked r where r.pos > off and r.pos <= off + lim), '[]'::jsonb),
                'total_rows', (select count(*) from ranked),
                'me', (select jsonb_build_object('rank', r.rnk, 'score', r.score) from ranked r where r.user_id = me)));
end $$;

-- ---------------------------------------------------------------------
-- Teams
-- ---------------------------------------------------------------------
create or replace function public.create_team(p_name text)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); nm text := regexp_replace(btrim(coalesce(p_name, '')), '\s+', ' ', 'g'); t public.teams; code text; i int := 0;
begin
    perform public._require_profile(u);
    if char_length(nm) not between 3 and 24 then raise exception 'LOOPA:INVALID_INPUT'; end if;
    if public._active_team(u) is not null then raise exception 'LOOPA:ALREADY_IN_TEAM'; end if;
    loop
        code := (select string_agg(substr('ABCDEFGHJKLMNPQRSTUVWXYZ23456789', 1 + floor(random() * 32)::int, 1), '') from generate_series(1, 6));
        exit when not exists (select 1 from public.teams where join_code = code);
        i := i + 1; if i > 20 then raise exception 'LOOPA:CODE_GENERATION_FAILED'; end if;
    end loop;
    insert into public.teams(name, join_code, owner_id) values (nm, code, u) returning * into t;
    insert into public.team_members(team_id, user_id) values (t.id, u);
    return jsonb_build_object('id', t.id, 'name', t.name, 'join_code', t.join_code);
end $$;

create or replace function public.join_team(p_code text)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); t public.teams;
begin
    perform public._require_profile(u);
    if public._active_team(u) is not null then raise exception 'LOOPA:ALREADY_IN_TEAM'; end if;
    select * into t from public.teams where join_code = upper(btrim(coalesce(p_code, ''))) and disbanded_at is null for update;
    if not found then raise exception 'LOOPA:TEAM_NOT_FOUND'; end if;
    if (select count(*) from public.team_members where team_id = t.id) >= 5 then raise exception 'LOOPA:TEAM_FULL'; end if;
    insert into public.team_members(team_id, user_id) values (t.id, u);
    -- the line-up changed: it is a different team now as far as the streak goes
    update public.teams set current_streak = 0 where id = t.id;
    return jsonb_build_object('id', t.id, 'name', t.name);
end $$;

-- Leaving (or the owner disbanding) ends the active streak; the record stays.
create or replace function public.leave_team()
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); t public.teams;
begin
    select * into t from public.teams where id = public._active_team(u) for update;
    if not found then raise exception 'LOOPA:TEAM_NOT_FOUND'; end if;
    if t.owner_id = u then
        update public.teams set disbanded_at = now(), current_streak = 0 where id = t.id;
    else
        delete from public.team_members where team_id = t.id and user_id = u;
        update public.teams set current_streak = 0 where id = t.id;
    end if;
end $$;

-- ---------------------------------------------------------------------
-- Game preferences and the room's preference wheel
-- ---------------------------------------------------------------------
create or replace function public._pref_tags()
returns text[] language sql immutable as $$
    select array['word','drawing','quiz','social','team','duel','ffa','card','board','strategy'];
$$;

create or replace function public.set_game_prefs(p_likes text[], p_dislikes text[])
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); l text[] := coalesce(p_likes, '{}'); d text[] := coalesce(p_dislikes, '{}');
begin
    perform public._require_profile(u);
    if not (l <@ public._pref_tags() and d <@ public._pref_tags()) or l && d then raise exception 'LOOPA:INVALID_INPUT'; end if;
    insert into public.player_game_prefs(user_id, likes, dislikes) values (u, l, d)
    on conflict (user_id) do update set likes = excluded.likes, dislikes = excluded.dislikes, updated_at = now();
    return jsonb_build_object('likes', to_jsonb(l), 'dislikes', to_jsonb(d));
end $$;

create or replace function public.get_game_prefs()
returns jsonb language sql stable security definer set search_path = public, pg_temp as $$
    select coalesce((select jsonb_build_object('likes', to_jsonb(likes), 'dislikes', to_jsonb(dislikes), 'set', true)
                       from public.player_game_prefs where user_id = public._uid()),
                    '{"likes":[],"dislikes":[],"set":false}'::jsonb)
           || jsonb_build_object('tags', to_jsonb(public._pref_tags()));
$$;

-- Weights of the games this room can play now, from everybody's preferences.
create or replace function public._wheel_candidates(p_room uuid)
returns jsonb language plpgsql stable security definer set search_path = public, pg_temp as $$
declare r public.rooms; n int; cfg jsonb := public._cfg('wheel'); out jsonb := '[]'::jsonb; g record; w numeric; likes int; dislikes int;
begin
    select * into r from public.rooms where id = p_room;
    select count(*) into n from public.room_players where room_id = p_room and status = 'active' and role = 'player';
    for g in select gm.* from public.game_modes gm
              where gm.status in ('available','early_access') and n between gm.min_players and gm.max_players
                and (public._mode_access(r.host_id, gm.id)->>'allowed')::boolean loop
        w := 1.0;
        for likes, dislikes in
            select cardinality(array(select unnest(coalesce(pp.likes, '{}')) intersect select unnest(g.tags))),
                   cardinality(array(select unnest(coalesce(pp.dislikes, '{}')) intersect select unnest(g.tags)))
              from public.room_players rp left join public.player_game_prefs pp on pp.user_id = rp.user_id
             where rp.room_id = p_room and rp.status = 'active' and rp.role = 'player' loop
            w := w * (1 + coalesce((cfg->>'like_weight')::numeric, 0.6) * least(likes, 2))
                   * greatest(1 - coalesce((cfg->>'dislike_weight')::numeric, 0.7) * least(dislikes, 1), coalesce((cfg->>'min_weight')::numeric, 0.05));
        end loop;
        out := out || jsonb_build_array(jsonb_build_object('mode', g.id, 'weight', round(w, 4)));
    end loop;
    return out;
end $$;

-- Host spins: the server draws (weighted), stores the result and switches the room to it.
create or replace function public.spin_game_wheel(p_room_id uuid)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare r public.rooms := public._lock_room(p_room_id); cands jsonb; total numeric; pick numeric; acc numeric := 0; c jsonb; res text; spin uuid;
begin
    perform public._require_host(r);
    if r.status <> 'lobby' then raise exception 'LOOPA:GAME_IN_PROGRESS'; end if;
    if (select count(*) from public.room_wheel_spins where room_id = p_room_id and created_at > now() - interval '5 seconds') > 0 then
        raise exception 'LOOPA:RATE_LIMITED';
    end if;
    cands := public._wheel_candidates(p_room_id);
    select coalesce(sum((x->>'weight')::numeric), 0) into total from jsonb_array_elements(cands) x;
    if total <= 0 then raise exception 'LOOPA:NO_SUITABLE_GAME'; end if;
    pick := random() * total;
    for c in select value from jsonb_array_elements(cands) loop
        acc := acc + (c->>'weight')::numeric;
        res := c->>'mode';
        exit when acc >= pick;
    end loop;
    insert into public.room_wheel_spins(room_id, result, candidates) values (p_room_id, res, cands) returning id into spin;
    update public.rooms set settings = settings || jsonb_build_object('game_mode', res, 'configured', false, 'mode_options', '{}'::jsonb),
           updated_at = now() where id = p_room_id;
    update public.room_players set is_ready = false where room_id = p_room_id and user_id <> r.host_id and status = 'active';
    perform public._bump(p_room_id);
    return jsonb_build_object('spin_id', spin, 'result', res, 'candidates', cands);
end $$;

create or replace function public.get_last_wheel_spin(p_room_id uuid)
returns jsonb language sql stable security definer set search_path = public, pg_temp as $$
    select case when public.is_room_member(p_room_id) then
        (select jsonb_build_object('spin_id', id, 'result', result, 'candidates', candidates, 'created_at', created_at)
           from public.room_wheel_spins where room_id = p_room_id order by created_at desc limit 1) end;
$$;

-- ---------------------------------------------------------------------
-- Fair teams: per-game skill, greedy + pairwise-swap refinement (deterministic)
-- ---------------------------------------------------------------------
create or replace function public._skill(p_user uuid, p_mode text)
returns numeric language sql stable security definer set search_path = public, pg_temp as $$
    -- Bayesian win rate in the room's game (prior 50% over 4 games) blended with level when there is little data.
    select round(coalesce(
        (select (100.0 * (s.wins + 2) / (s.matches_played + 4)) * least(s.matches_played, 10) / 10.0
                + (40 + least(p.level, 60)) * (1 - least(s.matches_played, 10) / 10.0)
           from public.player_mode_stats s where s.user_id = p_user and s.game_mode = p_mode),
        40 + least(p.level, 60)), 2)
      from public.profiles p where p.id = p_user;
$$;

create or replace function public._balance(p_players jsonb, p_teams int)
returns jsonb language plpgsql immutable as $$
-- p_players: [{"id":..., "skill":n}], sorted input not required. Returns [[ids...], ...] and sums.
declare n int := jsonb_array_length(p_players); sizes int[]; teams jsonb[]; sums numeric[]; cap int[]; x jsonb; best int; i int; j int; a int; b int;
        improved boolean := true; ta jsonb; tb jsonb; pa jsonb; pb jsonb; da numeric; guard int := 0; cur numeric; nxt numeric;
begin
    if p_teams < 2 or n < p_teams then raise exception 'LOOPA:INVALID_INPUT'; end if;
    for i in 1..p_teams loop teams[i] := '[]'::jsonb; sums[i] := 0; cap[i] := n / p_teams + case when i <= n % p_teams then 1 else 0 end; end loop;
    for x in select value from jsonb_array_elements(p_players) order by (value->>'skill')::numeric desc, value->>'id' loop
        best := null;
        for i in 1..p_teams loop
            if jsonb_array_length(teams[i]) < cap[i] and (best is null or sums[i] < sums[best]) then best := i; end if;
        end loop;
        teams[best] := teams[best] || jsonb_build_array(x); sums[best] := sums[best] + (x->>'skill')::numeric;
    end loop;
    -- pairwise swaps while they reduce the spread between the two teams involved
    while improved and guard < 200 loop
        improved := false; guard := guard + 1;
        for a in 1..p_teams - 1 loop for b in a + 1..p_teams loop
            for i in 0..jsonb_array_length(teams[a]) - 1 loop for j in 0..jsonb_array_length(teams[b]) - 1 loop
                pa := teams[a]->i; pb := teams[b]->j;
                da := (pa->>'skill')::numeric - (pb->>'skill')::numeric;
                cur := abs(sums[a] - sums[b]); nxt := abs((sums[a] - da) - (sums[b] + da));
                if nxt + 0.0001 < cur then
                    teams[a] := jsonb_set(teams[a], array[i::text], pb); teams[b] := jsonb_set(teams[b], array[j::text], pa);
                    sums[a] := sums[a] - da; sums[b] := sums[b] + da; improved := true;
                end if;
            end loop; end loop;
        end loop; end loop;
    end loop;
    return (select jsonb_agg(jsonb_build_object('members', (select jsonb_agg(m->'id' order by (m->>'skill')::numeric desc, m->>'id') from jsonb_array_elements(teams[k]) m),
                                                'strength', sums[k]) order by k)
              from generate_series(1, p_teams) k);
end $$;

create or replace function public.balance_room_teams(p_room_id uuid, p_teams int default 2)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare r public.rooms := public._lock_room(p_room_id); players jsonb; res jsonb;
begin
    perform public._require_host(r);
    if r.status <> 'lobby' then raise exception 'LOOPA:GAME_IN_PROGRESS'; end if;
    select jsonb_agg(jsonb_build_object('id', rp.user_id, 'skill', public._skill(rp.user_id, public._room_mode(r.settings))))
      into players from public.room_players rp where rp.room_id = p_room_id and rp.status = 'active' and rp.role = 'player';
    if players is null or jsonb_array_length(players) < greatest(p_teams, 2) or p_teams not between 2 and 4 then
        raise exception 'LOOPA:NOT_ENOUGH_PLAYERS';
    end if;
    res := public._balance(players, p_teams);
    update public.rooms set settings = settings || jsonb_build_object('teams', res), updated_at = now() where id = p_room_id;
    perform public._bump(p_room_id);
    return res;
end $$;

-- ---------------------------------------------------------------------
-- Room setup flow: choose game -> room opens -> host configures -> everybody ready -> start
-- ---------------------------------------------------------------------
-- Per-game options are validated against a whitelist with defaults (the server never trusts them blindly).
create or replace function public._mode_options(p_mode text, p jsonb, p_players int)
returns jsonb language plpgsql stable security definer set search_path = public, pg_temp as $$
declare o jsonb := coalesce(p, '{}'::jsonb); out jsonb := '{}'::jsonb;
    function_exists boolean;
begin
    if jsonb_typeof(o) <> 'object' then raise exception 'LOOPA:INVALID_SETTINGS'; end if;
    select exists (select 1 from pg_proc where proname = '_opts_' || p_mode and pronamespace = 'public'::regnamespace) into function_exists;
    if function_exists then
        execute format('select public.%I($1, $2)', '_opts_' || p_mode) into out using o, p_players;
    end if;
    return coalesce(out, '{}'::jsonb);
end $$;

create or replace function public.update_room_settings(p_room_id uuid, p_settings jsonb, p_is_public boolean)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare r public.rooms := public._lock_room(p_room_id); n int; clean jsonb; v_mode text; acc jsonb; gm public.game_modes;
        maxp int; base jsonb; configured boolean;
begin
    perform public._require_host(r);
    if r.status <> 'lobby' then raise exception 'LOOPA:GAME_IN_PROGRESS'; end if;
    if r.settings ? 'tournament_match_id' then raise exception 'LOOPA:TOURNAMENT_ROOM'; end if;   -- set by the tournament
    v_mode := coalesce(nullif(p_settings->>'game_mode', ''), public._room_mode(r.settings));
    if v_mode <> public._room_mode(r.settings) then
        acc := public._mode_access(r.host_id, v_mode);
        if not (acc->>'allowed')::boolean then raise exception 'LOOPA:%', acc->>'reason'; end if;
    end if;
    select * into gm from public.game_modes where id = v_mode;
    select count(*) into n from public.room_players where room_id = p_room_id and status = 'active' and role = 'player';
    maxp := coalesce((p_settings->>'max_players')::int, 10);
    if v_mode not in ('isim_sehir','gizli_kelime','kelime_zinciri','bilgi_arenasi','iki_dogru_bir_yalan','ciz_tahmin',
                      'en_muhtemel_kim','anlat_bakalim','cizim_telefonu','sessiz_sinema') then
        -- new games (0.8+): any size inside the game's own limits
        if maxp < gm.min_players or maxp > gm.max_players then raise exception 'LOOPA:INVALID_SETTINGS'; end if;
        if maxp < n then raise exception 'LOOPA:MAX_BELOW_PLAYERS'; end if;
        base := public._validate_settings(p_settings || jsonb_build_object('max_players', 10), least(n, 10));
    else
        base := public._validate_settings(p_settings, n);            -- unchanged rules for the original games
    end if;
    -- "configured": false while the host is still setting up. Old clients never send it => true (old behaviour).
    configured := coalesce((p_settings->>'configured')::boolean, true);
    clean := base || jsonb_build_object('game_mode', v_mode, 'max_players', maxp,
                                        'mode_options', public._mode_options(v_mode, p_settings->'mode_options', n),
                                        'configured', configured)
                  || case when r.settings ? 'teams' and v_mode = public._room_mode(r.settings) then jsonb_build_object('teams', r.settings->'teams') else '{}'::jsonb end;
    if not (clean->>'allow_spectators')::boolean then
        update public.room_players set role = 'player'
         where room_id = p_room_id and status = 'active' and role = 'spectator'
           and (select count(*) from public.room_players x where x.room_id = p_room_id and x.status = 'active' and x.role = 'player') < maxp;
        update public.room_players set status = 'left'
         where room_id = p_room_id and status = 'active' and role = 'spectator';
    end if;
    update public.rooms set settings = clean, is_public = coalesce(p_is_public, is_public), updated_at = now()
     where id = p_room_id;
    update public.room_players set is_ready = false
     where room_id = p_room_id and user_id <> r.host_id and status = 'active';
    perform public._bump(p_room_id);
    return clean;
end $$;

-- Ready only once the host has finished the setup (rooms without the flag: as before).
create or replace function public.set_ready(p_room_id uuid, p_ready boolean)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); r public.rooms := public._lock_room(p_room_id);
begin
    if r.status <> 'lobby' then raise exception 'LOOPA:GAME_IN_PROGRESS'; end if;
    if coalesce(p_ready, false) and not coalesce((r.settings->>'configured')::boolean, true) and u <> r.host_id then
        raise exception 'LOOPA:SETUP_NOT_DONE';
    end if;
    update public.room_players set is_ready = coalesce(p_ready, false), last_seen_at = now()
     where room_id = p_room_id and user_id = u and status = 'active' and role = 'player';
    if not found then raise exception 'LOOPA:NOT_IN_ROOM'; end if;
    perform public._bump(p_room_id);
end $$;

-- start_match: setup must be done; player count inside the game's limits; teams written to match_players.
create or replace function public.start_match(p_room_id uuid)
returns uuid language plpgsql security definer set search_path = public, pg_temp as $$
declare r public.rooms := public._lock_room(p_room_id); v_match uuid; n int; not_ready int; p record;
        v_mode text; acc jsonb; v_min int; v_max int; t jsonb; k int := 0; v_member text;
begin
    perform public._require_host(r);
    if r.status <> 'lobby' then raise exception 'LOOPA:GAME_IN_PROGRESS'; end if;
    if not coalesce((r.settings->>'configured')::boolean, true) then raise exception 'LOOPA:SETUP_NOT_DONE'; end if;

    v_mode := public._room_mode(r.settings);
    acc := public._mode_access(r.host_id, v_mode);
    if not (acc->>'allowed')::boolean and not (r.settings ? 'tournament_match_id') then raise exception 'LOOPA:%', acc->>'reason'; end if;
    select min_players, max_players into v_min, v_max from public.game_modes where id = v_mode;

    select count(*), count(*) filter (where not is_ready and user_id <> r.host_id)
      into n, not_ready
      from public.room_players where room_id = p_room_id and status = 'active' and role = 'player';
    if n < greatest(2, coalesce(v_min, 2)) then raise exception 'LOOPA:NOT_ENOUGH_PLAYERS'; end if;
    if n > coalesce(v_max, 20) then raise exception 'LOOPA:TOO_MANY_PLAYERS'; end if;
    if not_ready > 0 then raise exception 'LOOPA:PLAYERS_NOT_READY'; end if;
    perform public._mode_check_start(v_mode, r.settings, n);
    if r.settings ? 'tournament_match_id' then
        execute 'select public._tm_check_start($1)' using p_room_id;      -- exactly the expected line-up
    end if;

    begin
        insert into public.matches(room_id, settings, total_rounds, game_mode, entitlement_snapshot)
        values (p_room_id, r.settings, coalesce((r.settings->>'rounds')::int, 1), v_mode,
                acc || jsonb_build_object('host_id', r.host_id, 'mode', v_mode, 'at', now()))
        returning id into v_match;
    exception when unique_violation then
        raise exception 'LOOPA:GAME_IN_PROGRESS';
    end;

    insert into public.match_players(match_id, room_id, user_id)
    select v_match, p_room_id, user_id from public.room_players
     where room_id = p_room_id and status = 'active' and role = 'player';
    -- balanced teams (if the host made them and they still match the line-up)
    if jsonb_typeof(r.settings->'teams') = 'array' then
        for t in select value from jsonb_array_elements(r.settings->'teams') loop
            k := k + 1;
            for v_member in select jsonb_array_elements_text(t->'members') loop
                update public.match_players set team_side = k where match_id = v_match and user_id = v_member::uuid;
            end loop;
        end loop;
        if exists (select 1 from public.match_players where match_id = v_match and team_side is null) then
            update public.match_players set team_side = null where match_id = v_match;     -- line-up changed: no teams
        end if;
    end if;

    update public.rooms set status = 'in_game', current_match_id = v_match, updated_at = now() where id = p_room_id;
    update public.room_players set is_ready = false where room_id = p_room_id and user_id <> r.host_id;

    if v_mode = 'isim_sehir' then
        for p in select user_id from public.match_players where match_id = v_match loop
            perform public._grant_joker(v_match, p.user_id, 'match_start', null);
        end loop;
        perform public._start_round(v_match);
    else
        perform public._mode_start(v_match);
    end if;
    perform public._bump(p_room_id);
    return v_match;
end $$;

-- Game-specific start checks (e.g. exact player counts); games add _startcheck_<mode>.
create or replace function public._mode_check_start(p_mode text, p_settings jsonb, p_players int)
returns void language plpgsql stable security definer set search_path = public, pg_temp as $$
begin
    if exists (select 1 from pg_proc where proname = '_startcheck_' || p_mode and pronamespace = 'public'::regnamespace) then
        execute format('select public.%I($1, $2)', '_startcheck_' || p_mode) using p_settings, p_players;
    end if;
end $$;

-- ---------------------------------------------------------------------
-- Economy overview and admin configuration
-- ---------------------------------------------------------------------
create or replace function public.get_wallet()
returns jsonb language sql stable security definer set search_path = public, pg_temp as $$
    select jsonb_build_object(
        'coins', coalesce((select coins from public.wallets where user_id = public._uid()), 0),
        'lifetime_earned', coalesce((select lifetime_earned from public.wallets where user_id = public._uid()), 0),
        'recent', coalesce((select jsonb_agg(jsonb_build_object('amount', amount, 'reason', reason, 'balance_after', balance_after, 'created_at', created_at)
                                    order by id desc)
                              from (select * from public.coin_transactions where user_id = public._uid() order by id desc limit 20) t), '[]'::jsonb));
$$;

create or replace function public.get_match_rewards(p_match_id uuid)
returns jsonb language sql stable security definer set search_path = public, pg_temp as $$
    select to_jsonb(r) - 'opponents_key' from public.match_rewards r where r.match_id = p_match_id and r.user_id = public._uid();
$$;

create or replace function public.admin_set_config(p_key text, p_value jsonb)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare a uuid := public._require_admin();
begin
    if not exists (select 1 from public.economy_config where key = p_key) then raise exception 'LOOPA:INVALID_INPUT'; end if;
    update public.economy_config set value = p_value, updated_at = now() where key = p_key;
    perform public._audit(a, 'admin', 'set_config', null, jsonb_build_object('key', p_key, 'value', p_value));
end $$;

create or replace function public.admin_upsert_badge(p_badge jsonb)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare a uuid := public._require_admin();
begin
    insert into public.badges(id, kind, name_tr, description_tr, icon, rarity, condition, available_from, available_until, active, sort_order)
    values (p_badge->>'id', p_badge->>'kind', p_badge->>'name_tr', coalesce(p_badge->>'description_tr', ''), coalesce(p_badge->>'icon', 'star'),
            coalesce(p_badge->>'rarity', 'common'), coalesce(p_badge->'condition', '{"type":"manual"}'),
            (p_badge->>'available_from')::timestamptz, (p_badge->>'available_until')::timestamptz,
            coalesce((p_badge->>'active')::boolean, true), coalesce((p_badge->>'sort_order')::int, 0))
    on conflict (id) do update set kind = excluded.kind, name_tr = excluded.name_tr, description_tr = excluded.description_tr,
        icon = excluded.icon, rarity = excluded.rarity, condition = excluded.condition, available_from = excluded.available_from,
        available_until = excluded.available_until, active = excluded.active, sort_order = excluded.sort_order;
    perform public._audit(a, 'admin', 'upsert_badge', null, p_badge);
end $$;

create or replace function public.admin_grant_badge(p_user uuid, p_badge_id text)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare a uuid := public._require_admin();
begin
    insert into public.player_badges(user_id, badge_id) values (p_user, p_badge_id) on conflict do nothing;
    perform public._audit(a, 'admin', 'grant_badge', p_user, jsonb_build_object('badge', p_badge_id));
end $$;

-- ---------------------------------------------------------------------
-- Grants
-- ---------------------------------------------------------------------
do $$
declare f record;
begin
    for f in select p.oid::regprocedure as sig, p.proname
               from pg_proc p join pg_namespace n on n.oid = p.pronamespace
              where n.nspname = 'public' and p.proname in (
                  '_cfg','_day_key','_week_key','_week_start','_day_start','_coins_grant','_coins_spend','_reputation_add','_active_team',
                  '_streak_mult','_prestige_of','_badge_met','_check_badges','_quest_period_key','_ensure_quests','_quest_progress',
                  '_progress_match','_progress_trigger','_player_card_extras','_game_stat','_pref_tags','_wheel_candidates','_skill','_balance',
                  '_mode_options','_mode_check_start',
                  'get_my_quests','claim_quest','purchase_cosmetic','unlock_cosmetic','set_showcase','get_progression','get_leaderboard',
                  'create_team','join_team','leave_team','set_game_prefs','get_game_prefs','spin_game_wheel','get_last_wheel_spin',
                  'balance_room_teams','update_room_settings','set_ready','start_match','get_wallet','get_match_rewards',
                  'admin_set_config','admin_upsert_badge','admin_grant_badge') loop
        execute format('revoke all on function %s from public, anon, authenticated', f.sig);
        if left(f.proname, 1) <> '_' then
            execute format('grant execute on function %s to authenticated', f.sig);
        end if;
    end loop;
end $$;
