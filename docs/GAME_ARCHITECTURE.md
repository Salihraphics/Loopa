# Game architecture and the road to 23 games

Today there are 10 playable games. İsim Şehir has its own engine. The other 9 run on the common
mode engine from 0.5.0 (`20261115000001_mode_engine.sql`):

- `mode_match_state`: server-only and public state per match.
- `mode_rounds`: one row per round, turn or step, with phase, actor, public state and deadline.
- `mode_secrets`: words, answers, hands. Never readable by clients.
- `mode_submissions`: guesses, votes, moves.
- `mode_content`: content pools.
- `player_mode_stats`: per-game statistics.

Clients read through `get_mode_state`, which is filtered per player, and write through
`mode_act`. Rooms, players, ready checks, host change, matches, results, XP, history and
statistics are shared by every game.

## Kinds of games (`GameKind` in `core/.../GameModes.kt`)

| Kind | Sync model | Fits the current engine? | What is reused | What a new game adds |
|---|---|---|---|---|
| ROUND_BASED | snapshot on signal | yes | everything | content pool, scoring step |
| TURN_BASED | snapshot on signal | yes | everything (actor_id, deadlines) | turn order rule |
| HIDDEN_ROLE (Kurt Adam) | snapshot on signal | mostly | rooms, phases, votes (`mode_submissions`), secrets | a secret per player (`mode_secrets` keyed by player), night/day phase table, eliminated players as spectators |
| CARD (Okey, 21, original colour-card game) | snapshot on signal | yes, with per-player secrets | turn order, deadlines, results | server-side shuffle and deal, hand per player as a secret, strict move validation |
| BOARD (Kızma Birader, Amiral Battı, 4-player chess) | snapshot on signal | yes | turns, deadlines | board state in public state, move validator and server dice in SQL; hidden board per player for Amiral Battı |
| STRATEGY (Diplomasi, hex 4X) | simultaneous orders | partly | rooms, players, results | orders as hidden submissions, one resolver per turn at the deadline, long matches that survive app restarts (a match is already server state), larger state (separate tables instead of one jsonb) |
| REALTIME (car race, billiards) | client simulation, server verify | no | rooms, lobby, results, XP | input stream over Realtime **broadcast** (no database writes per frame), deterministic physics so the server can re-check results, a latency budget. Needs a dedicated design before coding. |

## Rules for new games

1. Do not force every game into one generic engine. Turn, card and board games fit
   `mode_rounds` / `mode_secrets` / `mode_act`. Simultaneous strategy needs a resolver step.
   Realtime games need broadcast input and a different trust model.
2. The server decides every outcome: dice, shuffles, hidden information, scores and XP. The client
   only renders.
3. A per-player secret is never in public state. Read it only through the per-player snapshot.
4. Reuse the room flow (`create_room`, `join_room`, ready, `start_match`) and the results/XP path.
   Social invites (0.7) work for every game automatically.
5. Declare `GameOrientation`. Board, card and realtime games are expected to be LANDSCAPE. The
   room screen must not force orientation for the others.
6. A new game enters `GameModes.all` only when it is playable end to end. Until then it lives in
   `GameRoadmap.planned`, which is shown nowhere in the UI.

## Roadmap (games 11–23)

The roadmap is in `core/src/main/kotlin/com/loopa/core/game/GameRoadmap.kt`, together with what
each game still needs. Suggested order:

1. Adam Asmaca: fits the engine as it is.
2. 21, Kızma Birader, Amiral Battı: per-player secrets plus move validation.
3. Okey and the original colour-card game: full hands and turn rules.
4. Kurt Adam: hidden roles and phases.
5. 4-player chess.
6. Diplomasi and the hex 4X: simultaneous orders and long matches.
7. Car race and billiards: the realtime model.

Games 18 and 20 must be original designs with original names and art. They are not copies of
existing commercial games.
