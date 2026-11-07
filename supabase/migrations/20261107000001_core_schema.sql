-- =====================================================================
-- LOOPA — core schema
-- All competitive state is written exclusively by SECURITY DEFINER RPCs
-- (see 20261107000002_game_functions.sql). Clients only get SELECT
-- access, filtered by Row Level Security.
-- =====================================================================

create extension if not exists pgcrypto with schema extensions;

-- ---------------------------------------------------------------------
-- Catalogues
-- ---------------------------------------------------------------------
create table public.categories (
    key          text primary key check (key ~ '^[a-z_]{2,24}$'),
    name_tr      text not null,
    name_en      text not null,
    is_default   boolean not null default false,  -- enabled in new rooms
    sort_order   int not null default 0
);

create table public.word_dictionary (
    category_key text not null references public.categories(key) on delete cascade,
    word         text not null,                    -- normalized form
    primary key (category_key, word)
);

create table public.cosmetic_items (
    id              text primary key check (id ~ '^[a-z0-9_]{2,48}$'),
    slot            text not null check (slot in (
                        'base','skin','hair','hair_color','eyes','mouth','glasses',
                        'hat','outfit','accessory','background','frame','decoration')),
    name_tr         text not null,
    required_level  int  not null default 1 check (required_level >= 1),
    sort_order      int  not null default 0,
    created_at      timestamptz not null default now()
);
create index cosmetic_items_slot_idx on public.cosmetic_items(slot, sort_order);

-- ---------------------------------------------------------------------
-- Players
-- ---------------------------------------------------------------------
create table public.profiles (
    id          uuid primary key references auth.users(id) on delete cascade,
    username    text not null check (char_length(username) between 3 and 16),
    avatar      jsonb not null default '{}'::jsonb,
    xp          int  not null default 0 check (xp >= 0),
    level       int  not null default 1 check (level >= 1),
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create table public.player_statistics (
    user_id        uuid primary key references public.profiles(id) on delete cascade,
    matches_played int not null default 0,
    match_wins     int not null default 0,
    second_places  int not null default 0,
    third_places   int not null default 0,
    rounds_played  int not null default 0,
    round_wins     int not null default 0,
    total_points   bigint not null default 0,
    early_bonuses  int not null default 0,
    jokers_used    int not null default 0,
    updated_at     timestamptz not null default now()
);

create table public.player_cosmetics (
    user_id     uuid not null references public.profiles(id) on delete cascade,
    item_id     text not null references public.cosmetic_items(id) on delete cascade,
    unlocked_at timestamptz not null default now(),
    primary key (user_id, item_id)
);

create table public.xp_transactions (
    id          bigint generated always as identity primary key,
    user_id     uuid not null references public.profiles(id) on delete cascade,
    amount      int  not null check (amount > 0),
    reason      text not null check (reason in
                    ('match_complete','round_win','match_winner','match_second','match_third')),
    source_key  text not null,               -- idempotency key
    match_id    uuid,
    created_at  timestamptz not null default now(),
    unique (user_id, source_key)
);
create index xp_transactions_user_idx on public.xp_transactions(user_id, created_at desc);

-- ---------------------------------------------------------------------
-- Rooms
-- ---------------------------------------------------------------------
create table public.rooms (
    id               uuid primary key default gen_random_uuid(),
    code             text not null check (code ~ '^[A-Z0-9]{6}$'),
    host_id          uuid not null references public.profiles(id),
    status           text not null default 'lobby' check (status in ('lobby','in_game','closed')),
    is_public        boolean not null default false,
    settings         jsonb not null,
    current_match_id uuid,
    last_match_id    uuid,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    closed_at        timestamptz
);
-- Codes are unique among rooms that are still open.
create unique index rooms_open_code_uidx on public.rooms(code) where status <> 'closed';
create index rooms_public_idx on public.rooms(is_public, status) where status = 'lobby';

create table public.room_players (
    room_id      uuid not null references public.rooms(id) on delete cascade,
    user_id      uuid not null references public.profiles(id) on delete cascade,
    role         text not null default 'player' check (role in ('player','spectator')),
    status       text not null default 'active' check (status in ('active','left','kicked')),
    is_ready     boolean not null default false,
    joined_at    timestamptz not null default now(),
    last_seen_at timestamptz not null default now(),
    primary key (room_id, user_id)
);
create index room_players_user_idx on public.room_players(user_id, status);

-- One row per room; bumped by every RPC that changes room state.
-- Clients subscribe to this single table via Supabase Realtime and
-- re-fetch the authoritative snapshot (get_room_state) on each bump.
create table public.room_signals (
    room_id    uuid primary key references public.rooms(id) on delete cascade,
    version    bigint not null default 0,
    updated_at timestamptz not null default now()
);

-- ---------------------------------------------------------------------
-- Matches & rounds
-- ---------------------------------------------------------------------
create table public.matches (
    id            uuid primary key default gen_random_uuid(),
    room_id       uuid not null references public.rooms(id) on delete cascade,
    status        text not null default 'in_progress' check (status in ('in_progress','finished','abandoned')),
    settings      jsonb not null,                       -- snapshot at start
    total_rounds  int not null,
    current_round int not null default 0,
    used_letters  text[] not null default '{}',
    started_at    timestamptz not null default now(),
    finished_at   timestamptz,
    rewards_processed boolean not null default false
);
create index matches_room_idx on public.matches(room_id, started_at desc);
-- Only one running match per room => prevents double starts.
create unique index matches_one_running_uidx on public.matches(room_id) where status = 'in_progress';

alter table public.rooms
    add constraint rooms_current_match_fk foreign key (current_match_id) references public.matches(id) on delete set null,
    add constraint rooms_last_match_fk    foreign key (last_match_id)    references public.matches(id) on delete set null;

create table public.match_players (
    match_id     uuid not null references public.matches(id) on delete cascade,
    room_id      uuid not null references public.rooms(id) on delete cascade,
    user_id      uuid not null references public.profiles(id) on delete cascade,
    total_points int not null default 0,
    round_wins   int not null default 0,
    final_rank   int,
    xp_earned    int not null default 0,
    primary key (match_id, user_id)
);

create table public.rounds (
    id                 uuid primary key default gen_random_uuid(),
    match_id           uuid not null references public.matches(id) on delete cascade,
    room_id            uuid not null references public.rooms(id) on delete cascade,
    round_number       int not null,
    letter             text not null,
    categories         jsonb not null,           -- [{key,name,custom}]
    phase              text not null default 'answering'
                       check (phase in ('answering','voting','reveal','results','finished')),
    started_at         timestamptz not null,     -- answering opens (after letter reveal)
    base_deadline      timestamptz not null,
    answering_closed_at timestamptz,
    voting_deadline    timestamptz,
    reveal_started_at  timestamptz,
    reveal_deadline    timestamptz,
    results_deadline   timestamptz,
    first_submitter_id uuid references public.profiles(id),
    scored             boolean not null default false,
    created_at         timestamptz not null default now(),
    unique (match_id, round_number)
);
create index rounds_room_idx on public.rounds(room_id, created_at desc);

-- Individual timers: every player has their own authoritative deadline
-- so time-jokers never cause clients to disagree.
create table public.round_players (
    round_id        uuid not null references public.rounds(id) on delete cascade,
    room_id         uuid not null references public.rooms(id) on delete cascade,
    user_id         uuid not null references public.profiles(id) on delete cascade,
    deadline_at     timestamptz not null,
    submitted_at    timestamptz,
    double_category text,
    early_bonus     int not null default 0,
    points          int not null default 0,
    is_round_winner boolean not null default false,
    primary key (round_id, user_id)
);

create table public.answers (
    id            uuid primary key default gen_random_uuid(),
    round_id      uuid not null references public.rounds(id) on delete cascade,
    room_id       uuid not null references public.rooms(id) on delete cascade,
    user_id       uuid not null references public.profiles(id) on delete cascade,
    category_key  text not null,
    raw_text      text not null default '' check (char_length(raw_text) <= 40),
    normalized    text not null default '',
    validation    text check (validation in ('valid','invalid','uncertain','empty')),
    final_status  text check (final_status in ('accepted','rejected','empty')),
    is_duplicate  boolean not null default false,
    points        int not null default 0,
    doubled       boolean not null default false,
    updated_at    timestamptz not null default now(),
    unique (round_id, user_id, category_key)
);
create index answers_round_idx on public.answers(round_id);

create table public.answer_votes (
    answer_id  uuid not null references public.answers(id) on delete cascade,
    room_id    uuid not null references public.rooms(id) on delete cascade,
    voter_id   uuid not null references public.profiles(id) on delete cascade,
    accept     boolean not null,
    created_at timestamptz not null default now(),
    primary key (answer_id, voter_id)          -- one immutable vote per voter
);

create table public.player_jokers (
    id              uuid primary key default gen_random_uuid(),
    match_id        uuid not null references public.matches(id) on delete cascade,
    room_id         uuid not null references public.rooms(id) on delete cascade,
    user_id         uuid not null references public.profiles(id) on delete cascade,
    joker_type      text not null check (joker_type in ('extra_time','time_thief','slowdown','double_points')),
    status          text not null default 'available' check (status in ('available','used')),
    source          text not null check (source in ('match_start','round_win')),
    source_round_id uuid references public.rounds(id) on delete set null,
    acquired_at     timestamptz not null default now(),
    used_at         timestamptz
);
create index player_jokers_owner_idx on public.player_jokers(match_id, user_id, status);
-- A round win can award at most one joker per player.
create unique index player_jokers_round_award_uidx on public.player_jokers(user_id, source_round_id) where source = 'round_win';
create unique index player_jokers_start_uidx on public.player_jokers(match_id, user_id) where source = 'match_start';

create table public.joker_usage (
    id             uuid primary key default gen_random_uuid(),
    round_id       uuid not null references public.rounds(id) on delete cascade,
    room_id        uuid not null references public.rooms(id) on delete cascade,
    user_id        uuid not null references public.profiles(id) on delete cascade,
    joker_id       uuid not null unique references public.player_jokers(id) on delete cascade,
    joker_type     text not null,
    target_user_id uuid references public.profiles(id),
    category_key   text,
    seconds_delta  int,
    created_at     timestamptz not null default now(),
    unique (round_id, user_id)                -- max one joker per player per round
);
-- Yavaşlatma can hit the same target at most once per round.
create unique index joker_usage_slowdown_target_uidx on public.joker_usage(round_id, target_user_id) where joker_type = 'slowdown';

create table public.match_history (
    match_id     uuid not null references public.matches(id) on delete cascade,
    user_id      uuid not null references public.profiles(id) on delete cascade,
    room_code    text not null,
    final_rank   int not null,
    player_count int not null,
    total_points int not null,
    round_wins   int not null,
    xp_earned    int not null,
    finished_at  timestamptz not null default now(),
    primary key (match_id, user_id)
);
create index match_history_user_idx on public.match_history(user_id, finished_at desc);

-- =====================================================================
-- Row Level Security
-- =====================================================================
alter table public.categories        enable row level security;
alter table public.word_dictionary   enable row level security;
alter table public.cosmetic_items    enable row level security;
alter table public.profiles          enable row level security;
alter table public.player_statistics enable row level security;
alter table public.player_cosmetics  enable row level security;
alter table public.xp_transactions   enable row level security;
alter table public.rooms             enable row level security;
alter table public.room_players      enable row level security;
alter table public.room_signals      enable row level security;
alter table public.matches           enable row level security;
alter table public.match_players     enable row level security;
alter table public.rounds            enable row level security;
alter table public.round_players     enable row level security;
alter table public.answers           enable row level security;
alter table public.answer_votes      enable row level security;
alter table public.player_jokers     enable row level security;
alter table public.joker_usage       enable row level security;
alter table public.match_history     enable row level security;

-- Membership helper (security definer avoids recursive RLS).
create or replace function public.is_room_member(p_room_id uuid)
returns boolean language sql stable security definer set search_path = public, pg_temp as $$
    select exists (select 1 from public.room_players rp
                   where rp.room_id = p_room_id and rp.user_id = auth.uid());
$$;

create or replace function public.round_phase(p_round_id uuid)
returns text language sql stable security definer set search_path = public, pg_temp as $$
    select phase from public.rounds where id = p_round_id;
$$;

-- Read-only catalogues
create policy categories_read     on public.categories      for select to authenticated using (true);
create policy cosmetic_items_read on public.cosmetic_items  for select to authenticated using (true);
-- word_dictionary: no policy => not readable by clients (server-side only).

create policy profiles_read on public.profiles for select to authenticated using (true);
create policy stats_read    on public.player_statistics for select to authenticated using (true);
create policy cosmetics_own on public.player_cosmetics  for select to authenticated using (user_id = auth.uid());
create policy xp_own        on public.xp_transactions   for select to authenticated using (user_id = auth.uid());
create policy history_own   on public.match_history     for select to authenticated using (user_id = auth.uid());

create policy rooms_read on public.rooms for select to authenticated
    using (public.is_room_member(id) or (is_public and status = 'lobby'));
create policy room_players_read on public.room_players for select to authenticated
    using (public.is_room_member(room_id));
create policy room_signals_read on public.room_signals for select to authenticated
    using (public.is_room_member(room_id));
create policy matches_read       on public.matches       for select to authenticated using (public.is_room_member(room_id));
create policy match_players_read on public.match_players for select to authenticated using (public.is_room_member(room_id));
create policy rounds_read        on public.rounds        for select to authenticated using (public.is_room_member(room_id));
create policy round_players_read on public.round_players for select to authenticated using (public.is_room_member(room_id));
-- Opponents' answers are hidden while the round is still being answered.
create policy answers_read on public.answers for select to authenticated
    using (user_id = auth.uid()
           or (public.is_room_member(room_id) and public.round_phase(round_id) <> 'answering'));
create policy votes_read on public.answer_votes for select to authenticated
    using (voter_id = auth.uid() or public.is_room_member(room_id));
create policy jokers_read on public.player_jokers for select to authenticated using (public.is_room_member(room_id));
create policy joker_usage_read on public.joker_usage for select to authenticated using (public.is_room_member(room_id));

-- No INSERT/UPDATE/DELETE policies exist: every write goes through RPCs.
revoke insert, update, delete, truncate on all tables in schema public from anon, authenticated;
revoke all on public.word_dictionary from anon, authenticated;
revoke all on all tables in schema public from anon;

-- Realtime: clients only listen to the lightweight signal table.
do $$
begin
    if exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
        alter publication supabase_realtime add table public.room_signals;
    end if;
end $$;
