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
| `/discordbridge inbound status` | Show the Discord to Minecraft relay settings |
| `/discordbridge inbound enabled <true\|false>` | Turn the inbound relay on or off |
| `/discordbridge inbound token <token>` | Set the bot token (run this in the console) |
| `/discordbridge inbound channel <id>` | Set the channel to read from |
| `/discordbridge inbound status` | View Gateway connection status and configuration |

Everything is also editable in `config/discordbridge-common.toml` and picked up on reload.

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

- **`/players`** (or `!players` text): Shows online player count and player list.
- **`/stats`** (or `!stats` text): Shows server leaderboards (Most Mobs Killed, Most Blocks Mined, Most Time Played, Most Blocks Walked, Most Achievements) and live Current Health and EXP of online players.
- **`/stats player:<name>`** (or `!stats <name>` text): Shows detailed statistics card for a specific player (including head skin thumbnail).
- **`/clearchat`** (or `!clearchat` text): Clears the last 100 messages with interactive confirmation buttons (requires Manage Messages permission).
- **`/status`** (or `!status` text): Shows server performance, TPS, RAM usage, and uptime.
- **`/restart`** (or `!restart` text): Gracefully restarts/stops the Minecraft server (strictly restricted to owner Discord ID `462616453653331968`).
- **`/cmd <command>`** (or `!cmd <command>` text): Executes any console command with Operator level 4 (`OP`) permissions and returns the output to Discord (strictly restricted to owner Discord ID `462616453653331968`).

Turn commands off with `respondToPlayersCommand = false` or `respondToStatsCommand = false` in the config.

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

## Building

```bash
./gradlew build      # jar lands in build/libs/
./gradlew runServer  # local test server
```
