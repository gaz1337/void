# The Zidion Jubilee — Festival Event Design

**Goal:** A signature, recurring, marquee holiday-style event set at the Grand Exchange, built to
create buzz, pull lapsed players back, and give everyone a reason to log in. Zidion's own answer to
a Jagex holiday event — a fairground, silly mini-games, and a coveted collectible cosmetic.

**Working title:** *The Zidion Jubilee* (rename freely). Lore hook: once a year the Grand Exchange
throws open its doors and the Zidion treasury "gives back" — the market square becomes a carnival.

**Status:** Design approved in brainstorming (2026-09-04). Next step after review: implementation plan.

---

## 1. Design decisions (locked in brainstorming)

| Decision | Choice |
|---|---|
| Primary purpose | Marquee hype spectacle — buzz, returning players, shareable |
| Event type | Zidion-original festival — ownable, recurring (annual/seasonal) |
| Setting | The Grand Exchange, transformed into a festive fairground |
| Core loop | Festive stalls → earn Jubilee Tokens → spend in a cosmetic reward shop |
| Hype centrepiece | The Grand Draw — a treasury-funded jackpot with a cinematic fireworks finale |

## 2. Hard constraints (project rules — non-negotiable)

- **Scripts + data only. No core engine/content edits.** Everything lives in new
  `content/zidion/…` Script classes and new `data/…` files, following the existing Zidion pattern
  (`ZidionMarket`, `ZidionTradingBots`, `ZidionKnights`).
- **Reuse existing cache assets.** No new models or cache edits. Reward cosmetics, decorations,
  and fireworks all reuse item/object/graphic ids that already exist in the shipped 634 cache
  (party hats, masks, capes, festive objects, firework gfx), rebranded with Zidion names via data
  item-definition overrides where wanted.
- **Data-driven and live-tunable.** Cadence, payouts, jackpot size, token prices, stall configs,
  reward tiers, and all dialogue live in `.toml` tables so they can be tuned without a rebuild.
- **Adhere to the wiki idioms.** World timers started in `worldSpawn` (`World.timers.start`),
  queues for delayed steps, list-backed event handlers registered only in `init {}`, shared state
  via Kotlin objects/companions.
- **Reuses existing Zidion systems:** `ZidionTreasury` (jackpot funding + payouts), `ZidionMarket`
  price API (Guess the Price), the cutscene camera helpers (`moveCamera`/`turnCamera`/`areaGfx`)
  for the Grand Draw, the trading bots already loitering in the GE as ambient crowd.

## 3. Activation & festival window

- A single toggle plus an optional date window, so it can run on a real calendar beat or be flipped
  on for a promo weekend.
  - `game.properties`: `zidion.jubilee.active=false` (master switch), and optional
    `zidion.jubilee.start` / `zidion.jubilee.end` ISO dates.
  - Admin commands (mirroring `::market`): `::jubilee start|stop|status`, `::jubilee draw` (force a
    Grand Draw for testing), `::jubilee tokens <n>` (grant tokens for testing).
- When inactive: no decorations spawn, no host, stalls do nothing, timers idle. The GE is normal.
- When active: `worldSpawn` (or the toggle handler) spawns the decorations/NPCs and starts the
  timers. Turning it off despawns them and stops the timers.

## 4. The fairground (hub transformation)

The GE safe-zone compound (`x=[3145,3189] y=[3469,3513]`) becomes the carnival for the window.

- **Decorations** (object spawns, existing cache object ids): banners/bunting, lanterns/torches, a
  central **bonfire**, festival stalls (market-stall objects), a fireworks launch platform. Spawned
  only while active; placed on open tiles inside the compound, clear of the clerk desk and offer
  booths so normal GE use is never blocked.
- **The Festival Marshal** — the host NPC (reuse a jolly humanoid npc id, e.g. a festival-dressed
  guard/entertainer). Stands centrally. `npcApproach("Talk-to")` explains the festival, shows a
  token balance, and points to the stalls and the reward shop. Dialogue from a data lines table,
  goofy and warm.
- **Ambient crowd:** the eight `ZidionTradingBots` already loiter and banter in the GE. During the
  festival they get festive lines and emotes (dance/cheer) mixed into their existing idle/banter
  rotation — no new bots, just a festive line set and a raised emote chance while active.

## 5. Core loop — the stalls

Each stall is one object (or attendant NPC) with an `Operate`/`Talk-to` handler. Playing it costs
nothing (or a tiny coin/entry fee, configurable) and pays **Jubilee Tokens**. A short per-player
cooldown per stall keeps it from being spammed. Four stalls at launch (data-driven, easy to add
more later):

1. **Guess the Price** *(the signature, Zidion-only stall).* The attendant names a random tradeable
   item; the player guesses its live Grand Exchange price via an integer-entry interface. Payout
   scales with accuracy against `ZidionMarket.price(item)` (within X% → jackpot tokens; within Y% →
   partial; else a consolation token). Teaches the market and is pure Zidion.
2. **Lucky Coin Dip.** A light gamble: pay a small coin fee, flip for a random token payout from a
   weighted table (mostly small, rare big). A festive "coin flip" gfx/animation.
3. **Treasure Dig.** A handful of marked dig spots around the fair (object interactions). Digging a
   fresh spot yields tokens and occasionally a trinket; spots refresh on a timer so there's always
   one to find. Reuses a spade/dig interaction pattern.
4. **Light the Bonfire / Fireworks.** A simple festive interaction at the bonfire/fireworks
   platform: contribute (a log, or just click) to "stoke" the bonfire; each contribution pays a
   token and adds a firework gfx to the sky. Communal and pretty — feeds the ambient spectacle.

**Token cooldowns & anti-farm:** per-stall per-player cooldown clocks; Guess-the-Price uses a fresh
random item each attempt; daily soft cap on tokens per account (configurable) so the grind has a
healthy ceiling.

## 6. Jubilee Tokens (the currency)

- A new item, `jubilee_token`, defined via data (reuse a token/coin model, festive name). Stackable,
  untradeable (bound to the event's own reward economy so it can't distort the real market).
- Held in the inventory; the Marshal and the reward shop read the inventory count.
- Optionally cleared or converted to a token-sink reward at festival end (configurable) so tokens
  don't linger between years.

## 7. The reward shop (the collectible engine)

The **Festival Stall** (`npcApproach("Trade"/"Rewards")`) opens a shop/interface priced in Jubilee
Tokens. Tiered so everyone leaves with something and the flex item is a real grind:

| Tier | Example reward | Notes |
|---|---|---|
| Cheap | festive emote unlock, balloons, a firework item | Everyone affords these fast |
| Mid | festive cape / mask / gloves (rebranded cache cosmetics) | The "I was there" tier |
| Marquee | **The Jubilee Crown** (rebranded party-hat / crown model) | The santa-hat equivalent — the status symbol |
| Prestige | a festival **pet** or a rare recolour (reuse cache pet/recolour) | Optional top grind / collector flex |

- **The Jubilee Crown is tradeable.** That is deliberate and is the marketing engine: it lands on
  the Zidion market, gets a live price, and becomes a coveted, screenshot-worthy status symbol that
  people log in and grind (or trade) for — exactly like a santa hat. Everything else is untradeable
  cosmetic fluff so only the marquee item carries market weight.
- Reward ids, prices, and tiers all live in a data table.

## 8. The Grand Draw (hype centrepiece)

The recurring, clip-worthy, server-wide moment.

- **Cadence:** a world timer fires the draw every `draw_interval_ticks` (e.g. a few hours, aligned to
  peak times), announced with a countdown broadcast so people gather.
- **Entries:** playing stalls / spending tokens earns draw entries (configurable — e.g. 1 entry per
  N tokens earned, capped per player per draw). Being at the fairground when it fires may grant a
  bonus entry, to pull a crowd.
- **The funding:** the jackpot is paid from `ZidionTreasury` ("the bank gives back") — a configurable
  slice of the treasury balance, floored/capped, so it's generous but never drains the bank.
- **The finale (cinematic):** on fire, everyone in/near the fairground gets a short scripted moment —
  camera lifts over the bonfire, a **fireworks** volley (`areaGfx` firework graphics over the
  compound), a server-wide broadcast of the winner(s), and the payout (coins and/or a token bundle
  and/or a shot at a marquee cosmetic). Reuses the camera/gfx/queue tooling from the execution
  cutscene, kept lightweight (no instance — it plays in the live GE so the whole crowd sees it).
- **Winner selection:** weighted random over entries (`weightedSample` pattern already used by the
  market), logged for transparency.

## 9. Data model (file layout)

New content under `data/activity/event/zidion_jubilee/` and item/npc/object defs alongside:

- `zidion_jubilee.tables.toml` — rules (cadence, payouts, jackpot %, caps, cooldowns, token soft
  cap), stall configs, reward-shop tiers/prices, Marshal + attendant + ambient festive line sets,
  decoration spawn list (object id + tile).
- `zidion_jubilee.npcs.toml` + `.npc-spawns.toml` — the Festival Marshal and any stall attendants
  (clones of existing humanoid npcs, festive names).
- `zidion_jubilee.objs.toml`? / object spawns — decorations, stalls, bonfire, dig spots, fireworks
  platform (existing cache object ids; spawned/despawned by the script on activation).
- `jubilee_token` and the reward cosmetics — item-definition data (reused cache models, Zidion
  names, wear slots, tradeable flags).
- `game.properties` — `zidion.jubilee.active` (+ optional date window).

## 10. Script architecture (scripts + data, no core edits)

Following the Zidion pattern. One or two `Script` classes, handlers only in `init {}`:

- **`ZidionFestival`** — the spine. `worldSpawn`: if active, spawn decorations/NPCs and
  `World.timers.start("zidion_jubilee")`. `worldTimerTick`: Grand Draw cadence + dig-spot refresh +
  decoration/firework ambience. The Marshal's `npcApproach` handlers (talk, token balance). The
  activation toggle + admin commands. Grand Draw logic (entries, treasury jackpot, cinematic,
  winner). Shared festival state in a companion/object (`ZidionFestival` object: active flag,
  entries map, `token`/`spend` helpers).
- **`ZidionFestivalStalls`** (split out if `ZidionFestival` grows too large) — the four stall
  handlers (`npcApproach`/`objectOperate`), Guess-the-Price interface + market read, Lucky Coin Dip
  weighted payout, Treasure Dig spots, Bonfire/fireworks. Reads/writes tokens via the shared object.
- Reuse: `ZidionTreasury` for jackpot funding + any coin payouts; `ZidionMarket.price(item)` for
  Guess the Price; the camera/gfx/queue helpers for the Grand Draw.

Shared state lives in a Kotlin `object` (scripts aren't singletons); constructor params
(`ZidionTreasury` is an object; `GrandExchange`/loader/accounts if needed) are Koin-injected exactly
as the other Zidion scripts do.

## 11. Economy & balance

- **Token faucet:** stalls (bounded by cooldowns + a daily soft cap). Tokens are untradeable, so the
  faucet can't leak into the real economy.
- **Token sinks:** the reward shop (cosmetics) is the only sink; leftover tokens optionally expire /
  convert at festival end.
- **The one market-facing item:** the tradeable Jubilee Crown. Its supply is the number minted
  through the shop over the festival, so its market price self-regulates on scarcity — the collector
  dynamic. Everything else is untradeable.
- **Treasury protection:** the Grand Draw jackpot is a capped/floored slice of the treasury balance,
  never a full drain; if the treasury is thin, the draw pays a floor from a small reserve rule.

## 12. Marketing hooks (why it drives livelihood)

- **Limited-time collectible → FOMO + returning players.** The Jubilee Crown only mints during the
  window; miss it and you pay market price forever.
- **Server-wide Grand Draw broadcasts → log-in-now urgency** and a recurring gather point.
- **"The bank gives back" narrative → goodwill** and a reason to care about the treasury you built.
- **Screenshot/clip fuel:** the fairground, the fireworks finale, and the crown flex are made to be
  shared. The Grand Draw reuses the cinematic camera work for a promo-worthy moment.
- **Recurring & ownable:** a Zidion tradition you re-run every year with a fresh marquee cosmetic,
  compounding the collection/status game.

## 13. Testing (WorldTest, matching existing Zidion tests)

- **Activation:** toggling on spawns the Marshal + decorations and starts the timer; toggling off
  despawns/stops. Inactive = GE untouched.
- **Stalls:** each stall pays tokens within its rules and respects its cooldown; Guess-the-Price
  scores against a known `ZidionMarket.price` and pays the right tier.
- **Reward shop:** spending tokens grants the item and deducts the balance; can't overspend; the
  Crown is tradeable, the rest are not.
- **Grand Draw:** with seeded entries and `setRandom(Random(seed))`, a draw picks a weighted winner,
  pays a treasury-funded jackpot that respects the cap/floor and never overdraws the bank, and fires
  the broadcast. Uses the `setCurrentTime{}` + single-tick patterns already established.
- **Boot:** a `WorldTest` subclass boots with the festival active and asserts no errors, mirroring
  the existing Zidion market/knights tests.

## 14. Out of scope (future)

- New custom models / a bespoke fairground map (needs cache work — deliberately avoided for v1).
- Cross-town decorations beyond the GE.
- Seasonal reskins (Halloween/Christmas variants) — trivial data swaps once the engine exists.
- Leaderboards / competitive layers (could bolt on later; v1 is inclusive and casual-first).

## 15. Open questions for review

1. **Entry fee:** are stalls free, or a tiny coin fee (a coin sink + a reason to have earned)?
2. **Token persistence:** wipe/convert tokens at festival end, or let them roll to next year?
3. **Crown scarcity:** roughly how grindy should the marquee crown be (how many hours of stalls)?
4. **Draw cadence:** every ~3 hours, or fewer/bigger draws at fixed peak times?
5. **The name:** keep "Zidion Jubilee", or something else (Grand Bazaar, Coin Carnival, Founders'
   Fair)?
