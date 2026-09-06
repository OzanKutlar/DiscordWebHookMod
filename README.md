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
| `/discordbridge events join\|leave\|chat\|death <true\|false>` | Toggle an individual relay |
| `/discordbridge safety <option> <true\|false>` | Toggle a hardening option (see below) |

Everything is also editable in `config/discordbridge-common.toml` and picked up on reload.

## Security notes

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
