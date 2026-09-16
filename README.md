# Discord Bridge

A small **server-side only** Minecraft Forge 1.20.1 mod that posts player join, leave,
chat and death events to a Discord channel via a webhook.

Players do **not** need to install anything. Vanilla clients connect normally.

## Setup

1. Drop the jar in your server's `mods/` folder and start the server once.
2. In Discord: **Channel Settings > Integrations > Webhooks > New Webhook**, then copy the URL.
3. From the **server console** (not in-game chat, so the URL never lands in a player's chat log):

   ```
   discordbridge url https://discord.com/api/webhooks/<id>/<token>
   discordbridge test
   ```

That's it. Join the server and you should see messages appear in the channel.

## Commands

All commands require operator permission level 4.

| Command | What it does |
| --- | --- |
| `/discordbridge status` | Show the current configuration |
| `/discordbridge test` | Send a test message and report the result |
| `/discordbridge enabled <true\|false>` | Master on/off switch |
| `/discordbridge url <url>` | Set the webhook URL |
| `/discordbridge name <name>` | Set the display name messages post under |
| `/discordbridge avatar <url>` | Set the avatar image (empty to clear) |
| `/discordbridge events join\|leave\|chat\|death\|commands\|advancements <true\|false>` | Toggle an individual relay |
| `/discordbridge safety <option> <true\|false>` | Toggle a hardening option (see below) |
| `/discordbridge mentions status` | Show the Minecraft to Discord mention settings |
| `/discordbridge mentions allow <true\|false>` | Turn `@name` resolution on or off (reconnects the bot) |
| `/discordbridge mentions everyone <true\|false>` | Allow `@everyone` and `@here` from in game |
| `/discordbridge mentions max <0-10>` | Maximum mentions resolved per message |
| `/discordbridge mentions cooldown <0-3600>` | Seconds between one player's pings |
| `/discordbridge inbound status` | Show the Discord to Minecraft relay settings |
| `/discordbridge inbound enabled <true\|false>` | Turn the inbound relay on or off |
| `/discordbridge inbound token <token>` | Set the bot token (run this in the console) |
| `/discordbridge inbound channel <id>` | Set the channel to read from |
| `/discordbridge inbound status` | View Gateway connection status and configuration |

Everything is also editable in `config/discordbridge-common.toml` and picked up on reload.

### In-game commands

These mirror the read-only Discord commands. They are available to **all players**
(permission level 0) and reply only to whoever ran them, so nothing is broadcast to
other players or relayed to Discord.

| Command | What it does |
| --- | --- |
| `/players` | Table of everyone online with health, experience level and ping |
| `/status` | Table of TPS, tick time, RAM and uptime |
| `/stats` | Leaderboard table: the leader in **every** statistic anyone has scored on, grouped |
| `/stats player <name>` | Table of one player's lifetime statistics |
| `/stats category <name>` | Full ranked leaderboard for one statistic |

Output is drawn with box characters. Minecraft's default font is not monospaced, so
columns are padded by character count and will line up closely rather than exactly.

> [!NOTE]
> `/restart` and `/cmd` are deliberately **not** available in game. Vanilla already
> provides `/stop`, and an in-game command that executes console commands at operator
> level 4 would be a permission escalation. Both stay restricted to the Discord owner.

## Discord to Minecraft

Webhooks are outbound only, so relaying the other direction uses a **bot application**
powered by JDA (Java Discord API). It connects via Discord's **WebSocket Gateway**, appears **Online** with a "Playing Minecraft" status, and relays chat in real-time.

### Setup

1. [Discord Developer Portal](https://discord.com/developers/applications) > **New Application** > **Bot**, then copy the token.
2. On the same Bot page, enable **Message Content Intent**. Without it Discord strips the
   message body and everything arrives blank.
3. Invite the bot to your server with the scopes `bot` and `applications.commands`, and permissions **View Channel**, **Send Messages**, and **Read Message History**.
4. In Discord, enable **Developer Mode** (User Settings > Advanced), right-click the channel
   and choose **Copy Channel ID**.
5. From the server console:

   ```
   discordbridge inbound token <bot token>
   discordbridge inbound channel <channel id>
   discordbridge inbound enabled true
   ```

### Discord Slash Commands

The bot registers official Discord slash commands directly to your guild for instant availability:

- **`/players`** (or `!players` text): Rich embed listing everyone online with their current health and experience level, plus the online count in the footer.
- **`/stats overview`** (or `!stats` text): Shows the leader in every statistic anyone has scored on, grouped into sections, plus live Current Health and EXP of online players.
- **`/stats player name:<name>`** (or `!stats player <name>` text): Shows detailed statistics card for a specific player (including head skin thumbnail).
- **`/stats category name:<category>`** (or `!stats category <category>` text): Full ranked leaderboard for one statistic. The `name` option autocompletes.
- **`/clearchat`** (or `!clearchat` text): Clears the last 100 messages with interactive confirmation buttons (requires Manage Messages permission).
- **`/overview`** (or `!overview` text): Reads the bridge channel's full history plus the final `/stats` data, and DMs you a ready-made LLM prompt for an end-of-season recap. Owner or **Manage Messages** only. See [End-of-season overview](#end-of-season-overview).
- **`/status`** (or `!status` text): Shows server performance, TPS, RAM usage, and uptime.
- **`/restart`** (or `!restart` text): Gracefully restarts/stops the Minecraft server (strictly restricted to owner Discord ID `462616453653331968`).
- **`/cmd <command>`** (or `!cmd <command>` text): Executes any console command with Operator level 4 (`OP`) permissions and returns the output to Discord (strictly restricted to owner Discord ID `462616453653331968`).
- **`/mentions status|allow|everyone|max|cooldown`**: Reads and changes the Minecraft to Discord mention settings (strictly restricted to the same owner Discord ID). Mirrors `/discordbridge mentions ...` in game, so either side can turn the feature on or off.

Turn commands off with `respondToPlayersCommand = false` or `respondToStatsCommand = false` in the config.

> [!NOTE]
> Discord does not allow a bare `/stats` once a command declares subcommands, which is why the summary is `/stats overview` there but plain `/stats` in game.

### Stat categories

Every statistic is read straight out of the vanilla `stats/<uuid>.json` files, so
nothing extra is tracked or stored. **Categories that every player scores zero on
are hidden** from the overview, from in-game tab completion and from Discord
autocomplete, and reappear on their own the moment somebody scores.

Each category can be named by its id or any of its aliases, so `/stats category
death`, `deaths` and `died` are the same query.

| Group | Category ids |
| --- | --- |
| Combat | `deaths`, `mob_kills`, `player_kills`, `damage_taken`, `damage_dealt`, `damage_blocked` |
| Blocks & items | `blocks_mined`, `items_crafted`, `items_used`, `tools_broken`, `items_enchanted` |
| Time | `play_time`, `time_since_death`, `time_since_rest`, `sneak_time` |
| Movement | `blocks_walked`, `fall_distance`, `swim_distance`, `climb_distance`, `elytra_distance`, `boat_distance`, `horse_distance`, `pig_distance`, `minecart_distance` |
| Interactions | `jumps`, `leave_game`, `chests_opened`, `bells_rung`, `flowers_potted`, `cake_slices`, `fish_caught`, `villager_trades`, `animals_bred`, `raids_won`, `nights_slept` |
| Other | `advancements`, `deaths_per_hour` |

Player cards also show a **Nemesis** line: whatever has killed that player most
often, read from `minecraft:killed_by`.

To add a statistic, add one entry to `StatCategories.buildAll()`. The loader, the
overview, both `/stats category` implementations and the autocomplete all pick it
up with no further changes.

### How the echo loop is prevented

Your outbound webhook posts appear in the same channel the bot reads. Messages carrying a
`webhook_id`, and messages from bots, are skipped — that is what stops the bridge feeding
itself forever. `relayBotMessages` exists to override this, and should stay `false`.

### Other behaviour worth knowing

- The **first poll after startup only records the newest message id and discards it**, so
  restarting the server never replays the channel backlog into chat.
- A bad token or a missing channel logs one error and **stops polling** rather than retrying
  every few seconds forever. Fix the setting and run `discordbridge inbound enabled true`.
- Rate limits (`429`) are honoured via `retry_after`; other errors back off up to 60 seconds.
- Relayed messages are capped at `maxRelayedLength` (default 256), stripped of section-sign
  formatting codes, and flattened to a single line. Messages starting with `/` are dropped.

## End-of-season overview

`/overview` turns the whole bridge channel into a prompt you can hand to any LLM
(ChatGPT, Claude, Gemini, ...). The LLM then writes a recap of the server: the
story arc, the funniest moments, the best sequences, running jokes and a
tongue-in-cheek award for every player.

1. Run `/overview` in Discord. It replies privately with progress, then DMs you a `.txt` file.
2. Upload the file to your LLM, or open it and paste the text.
3. Post the recap back to the channel. The LLM is told to split it into chunks under 1,900 characters for this.

The prompt contains three things:

- the LLM instructions
- a plain-text export of every statistic: the leader in each category, the top 5
  per category, and one card per player with their nemesis
- a compact log with one line per message

In the log, in-game chat, Discord chat, joins, deaths, advancements and
commands each get their own marker. The bot's own command replies are left out,
and repeated lines are collapsed.

If your DMs are closed, the file is attached to the private slash command reply
instead. `!overview` has no private reply, so it asks you to open your DMs. Very
large logs are split into numbered parts under 8 MB each, and the LLM is told to
wait for the part marked `FINAL`. The summary includes a rough token count, so you
can check that your model can take it in one go.

| Setting | Default | What it does |
| --- | --- | --- |
| `respondToOverviewCommand` | `true` | Turns the command on or off |
| `maxOverviewMessages` | `50000` | Most messages read, newest first. Older messages past this are left out |
| `overviewCooldownSeconds` | `600` | Gap between successful runs. The owner is exempt, and only one overview builds at a time |

> [!CAUTION]
> The prompt contains everything anyone said in the channel, and the requester
> will paste it into a third-party service. That is why the command is limited to
> the owner and members with **Manage Messages**. Consider telling the group first.

> [!NOTE]
> - Only the configured bridge channel is read.
> - Messages removed with `/clearchat` or deleted by hand are gone for good.
> - Discord returns 100 messages per request, so a channel with tens of thousands
>   of messages takes a few minutes to read.
> - The bot needs **Read Message History**, which the setup steps already grant.

## Minecraft to Discord mentions

Players can ping Discord users by typing `@name` in Minecraft chat. All of
`@Name`, `@First_Last` and `@"First Last"` work, matched case-insensitively
against nickname, then global name, then username, longest match first. Anything
that does not resolve is relayed exactly as typed, so `steve@example.com` is
never mangled. Resolved mentions are highlighted in aqua in game so the player
can see the ping landed.

The feature ships **disabled**:

```
discordbridge mentions allow true
```

> [!CAUTION]
> **This requires the privileged Server Members Intent.** Enable it at
> [Developer Portal](https://discord.com/developers/applications) > your
> application > **Bot** > **Privileged Gateway Intents** > **Server Members
> Intent**. If `allowMentions` is on and that intent is not granted, Discord
> rejects the connection and **the whole bridge stops working**, not just
> mentions. The server log will name the toggle explicitly if this happens.
> The intent is only requested while the setting is on, so leaving it off keeps
> the previous behaviour exactly.

> [!WARNING]
> **This gives every player a way to notify people who are not in the game.**
> The README already notes that the linked Discord channel is effectively a chat
> permission on your server; this is the same door in the other direction. The
> per-message cap and per-player cooldown exist to blunt the obvious abuse, but
> anyone who can type in game can ping anyone in the guild.

| Setting | Default | What it does |
| --- | --- | --- |
| `allowMentions` | `false` | Master switch for `@name` resolution |
| `allowEveryoneMention` | `false` | Whether `@everyone` and `@here` resolve. Gated separately because it is much noisier |
| `maxMentionsPerMessage` | `3` | Mentions past this count stay plain text |
| `mentionCooldownSeconds` | `10` | Minimum gap between one player's pings. The message still relays, just without pings. `0` disables |

> [!IMPORTANT]
> **`suppressMentions` always wins.** If the safety option is on, no ping
> resolves regardless of `allowMentions`. Two settings that both control pinging
> could otherwise contradict each other, and the safe one should take priority.

Every webhook payload now carries an explicit `allowed_mentions` whitelist of
exactly the ids the resolver matched. Discord itself refuses to render anything
else, so a player typing a raw `<@123456>` into Minecraft chat can no longer
produce a real ping the way it previously could.

## Security notes

> [!CAUTION]
> **Anyone who can type in the linked Discord channel can type into your Minecraft server.**
> That is the whole point of the inbound relay, but it means the channel is effectively a
> chat permission on your game server. Keep it private. There is deliberately no way to run
> Minecraft commands from Discord.

> [!IMPORTANT]
> **The bot token is a stronger secret than the webhook URL.** A leaked webhook lets someone
> spam one channel; a leaked bot token lets someone act as the bot in every server it has
> joined. It lives in the same non-synced `COMMON` config, is never logged, and no command
> will ever print it — `inbound status` shows only `set` or `not set`.

> [!IMPORTANT]
> **The webhook URL is a secret.** Anyone who has it can post anything into your channel,
> forever, until you delete the webhook in Discord. The config is registered as `COMMON`
> rather than `SERVER` specifically so that Forge never syncs it to connecting clients.
> The URL is also never written to the server log, even in error messages.

This mod is built for a small private server, so the hardening features below ship
**disabled by default** — output stays exactly as typed. If you ever open the server up
to people you don't know, turn them on.

### Mention injection

Any player can type `@everyone` in Minecraft chat and ping your entire Discord server.

```
/discordbridge safety suppressMentions true
```

Rewrites `@everyone` and `@here` as plain text, and sets `allowed_mentions.parse` to an
empty list so Discord itself resolves no pings at all.

### Markdown injection

Discord renders `*`, `_`, `~`, `` ` ``, `|` and `>` as formatting. A player can use these to
fake quotes, hide text in spoilers, or mangle the channel's readability.

```
/discordbridge safety sanitizeMarkdown true
```

Escapes those characters so relayed chat appears literally.

### Secret leaking via `/discordbridge status`

By default `status` prints the full webhook URL. If anyone other than you holds op level 4,
that's a secret sitting in their chat log.

```
/discordbridge safety maskUrlInStatus true
```

### Webhook host validation

URLs are restricted to `https://discord.com/api/webhooks/...` (and `discordapp.com`). This
stops a typo or a hostile op from redirecting your server's event stream to an arbitrary
endpoint. To test against something like [webhook.site](https://webhook.site) first:

```
/discordbridge safety allowCustomWebhookHost true
```

Turn it back off when you're done.

## Reliability

- Sends run on a single daemon thread behind a bounded 256-message queue. The server tick
  is never blocked on network I/O.
- If Discord is unreachable the queue fills and further messages are dropped with a log
  warning rather than growing without bound.
- Colour codes are always stripped and content is always truncated to 1900 characters,
  since Discord rejects anything over 2000.

## Amethyst transmutation recipes

Alongside the chat bridge, the mod ships two cross-mod recipes that convert Hex
Casting amethyst dust into charged amethyst using Mekanism machinery.

| Step | Machine | Input | Output |
| --- | --- | --- | --- |
| 1 | Enrichment Chamber | 2x amethyst dust | 1x amethyst shard |
| 2 | Pressurized Reaction Chamber | 1x amethyst shard, 1000mB water, 100mB hydrogen, 37,500 J | 1x charged amethyst |

The recipes live in `src/main/resources/data/discordbridge/recipes/` and are
plain data pack JSON, so they can be edited without touching any Java. They are
registered under this mod's own `discordbridge` namespace rather than Mekanism's,
so there is no risk of overwriting a stock recipe.

> [!NOTE]
> Both recipes carry `forge:mod_loaded` conditions for `mekanism` and
> `hexcasting`. Forge evaluates these before the recipe reaches Mekanism's
> deserializer, so a server missing either mod drops the recipes silently
> instead of logging a parse error. The mod itself still loads and relays chat
> normally with neither mod present.

> [!IMPORTANT]
> `energyRequired` is measured in **Joules**, not FE. The 37,500 J figure
> assumes Mekanism's default 2.5 J per FE conversion, which works out to
> 15,000 FE. If `config/mekanism.toml` overrides that ratio, the effective
> cost scales with it.

> [!WARNING]
> These ratios form a closed loop. Charged amethyst decomposes back into 10
> amethyst dust, so a full cycle returns more dust than it consumed. This is
> intentional and is priced through the reaction chamber's energy cost, but it
> does mean amethyst is effectively unbounded given sufficient power. Raise
> `energyRequired` in `shard_to_charged_amethyst.json`, or raise the input
> `amount` in `amethyst_dust_to_shard.json`, if you want the loop to be
> break-even or lossy.

### Changing or removing the recipes

Edit the JSON files and rebuild, or delete them to drop the feature entirely.
If you delete both, the optional `mekanism` and `hexcasting` dependency blocks
in `META-INF/mods.toml` can go too.

## Building

```bash
./gradlew build      # jar lands in build/libs/
./gradlew runServer  # local test server
```
