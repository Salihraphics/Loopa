-- Rollback of 20261121000001_buckshot.sql (run in one transaction).
-- Played matches and coins already granted stay (history and the coin ledger are never rewritten);
-- the game is only switched off so no new room can start it.
begin;

update public.game_modes set status = 'disabled' where id = 'buckshot';

-- the turn-game layer without Buckshot (as defined by 20261120000002_turn_games.sql)
create or replace function public._tg_is(p_mode text)
returns boolean language sql immutable as $$
    select p_mode = any(array['tavla','yirmi_bir','kizma_birader','amiral_batti','renk_kartlari','okey','milyoner','kurt_adam','kralliklar']);
$$;
revoke all on function public._tg_is(text) from public, anon, authenticated;

do $$
declare f record;
begin
    for f in select p.oid::regprocedure as sig from pg_proc p where p.pronamespace = 'public'::regnamespace
               and (p.proname like '\_bs\_%' or p.proname like '\_g\_buckshot\_%' or p.proname in ('_opts_buckshot','_startcheck_buckshot')) loop
        execute format('drop function %s', f.sig);
    end loop;
end $$;

delete from public.economy_config where key = 'buckshot';
update public.economy_config set value = value - 'buckshot' where key = 'game_reward_class';
delete from supabase_migrations.schema_migrations where version = '20261121000001';

commit;
