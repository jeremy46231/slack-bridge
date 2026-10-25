# SlackBridge

A slim Paper plugin that connects a Minecraft server to one Slack channel. It is a fork of
[HCCore](https://github.com/hackclub/HCCore), the main plugin of the Hack Club Minecraft server,
cut down to only its Slack integration (forked from upstream commit `5cd00ba`). Everything else
HCCore does (nicknames, AFK, spawn, colors, saved locations, stats, rules, welcome, advancements tab,
vanish hooks and so on) has been removed so it can run next to EssentialsX without clashing.

What it does:

- **Two-way chat** between the server and one Slack channel. Players who have linked Slack post under
  their Slack display name and profile picture, with message metadata naming their Slack account
  (`event_type: minecraft_chat`, `event_payload.source_user_id`); everyone else posts under their
  Minecraft display name and head. Slack messages show up in game as `[Slack] <name> message`, like vanilla chat.
  Mentions, channel links and URLs are turned into readable (and clickable) text. Thread replies stay
  in Slack.
- **Game events** posted to Slack by the bot, with exactly the text the game shows in chat: joins and
  leaves, deaths and advancements (for example "Steve has made the advancement [Stone Age]"), plus
  "Server started" and "Server stopped". Each can be turned off. Anything the game doesn't announce
  isn't posted, and players vanished with EssentialsX are not announced.
- **Account linking** with `/slack` in game and your own slash command in Slack, or optionally by
  clicking a link and signing in to Slack, which can also let a player's chat post as them.
- **Join requirements** (optional): only let in players with a linked Slack account, or only those
  whose linked account is a member of the bridge channel, so the channel works as a whitelist.
  Operators always get in.

Built for Paper 26.3 (Java 25). Package `app.jer.slackbridge`, plugin name `SlackBridge`, so it
can't collide with HCCore.

## Build

Needs JDK 25 or newer.

```bash
./gradlew build
```

The plugin is `build/libs/SlackBridge-<version>.jar` (Bolt, OkHttp, Gson and Java-WebSocket are shaded
and relocated under `app.jer.slackbridge.libs`). Ignore the `-thin` jar.

GitHub Actions builds every push and keeps the jar as a build artifact. Pushing a tag like `v1.0.0`
also attaches the jar to a GitHub release, so a server on the itzg/minecraft-server image can install
it by adding the release asset's URL to its `PLUGINS` environment variable. Bump `version` in
`build.gradle.kts` before tagging.

## 1. Create the Slack app

At <https://api.slack.com/apps>, choose **Create New App > From an app manifest**, pick the
workspace, and paste [manifest.yml](manifest.yml). Rename the app and bot first, and pick the slash
command.

**Each server needs its own slash command.** Slash command names should be unique in the workspace
(in the Hack Club Slack, HCCore's bot already owns `/minecraft`), so the plugin has no built-in one:
set the name in the manifest, then give the plugin the same name through `base-command` in the config
or the `SLACK_COMMAND` environment variable. If neither is set, the bot stays off and the log says
so. Running more than one server means one Slack app per server, each with its own command. The
examples below use `/mc`; the plugin's own messages always show the name you configured.

Why each scope is there:

| Scope | Used for |
| --- | --- |
| `channels:history`, `groups:history` | Receiving messages from the bridge channel (public or private) |
| `channels:read`, `groups:read` | Listing the channel's members for `join-requirement: channel`, and its name for messages |
| `chat:write` | Posting to the channel and sending link-verification DMs |
| `chat:write.customize` | Posting each player's chat under their Slack profile, or their Minecraft name and head |
| `commands` | The slash command |
| `im:write` | Opening the verification DM with a user |
| `users:read` | Showing Slack display names in game and checking linked accounts are still active |
| User scope `users:read` | Click-to-link only: tells the plugin which Slack user signed in |
| User scope `chat:write` (optional) | Click-to-link only: posting a player's chat as them, if they allow it |

Socket Mode means the plugin opens an outgoing WebSocket to Slack, so the server needs no public
HTTP endpoint, request URL or signing secret.

Then:

1. **Basic Information > App-Level Tokens > Generate Token and Scopes**, add the
   `connections:write` scope, and copy the `xapp-...` token. This is the **app token**.
2. **Install App > Install to Workspace**, then copy the **Bot User OAuth Token** (`xoxb-...`). This
   is the **bot token**.
3. In Slack, invite the bot to the bridge channel (`/invite @<bot name>`) and copy the channel ID
   (channel name > *About* > bottom of the panel, starts with `C`).

Treat both tokens as passwords: don't commit them, paste them in chat, or put them in the jar.

## 2. Configure the server

Install the jar in `plugins/`, start the server once to generate `plugins/SlackBridge/config.yml`,
then edit it:

```yaml
settings:
  slack-link:
    enabled: true          # turn the bridge on
    join-requirement: "none"  # none, linked or channel; see "Join requirements" below
    link-code-expiration: 600
    bot-token: ""          # leave empty to use SLACK_BOT_TOKEN
    app-token: ""          # leave empty to use SLACK_APP_TOKEN
    channel-id: ""         # leave empty to use SLACK_CHANNEL_ID
    base-command: ""       # required: the slash command without "/"; empty = SLACK_COMMAND
    slack-name: "Slack"    # what in-game messages call the workspace
    slack-id-help: "In Slack, open your profile, click the three dots and choose <i>Copy member ID</i>"
    public-url: ""         # click-to-link, see section 3; empty = SLACK_PUBLIC_URL
    client-id: ""          # empty = SLACK_CLIENT_ID
    client-secret: ""      # empty = SLACK_CLIENT_SECRET
    web-port: 8101
    token-key: ""          # empty = SLACK_TOKEN_KEY
    avatar-url: "https://mc-heads.net/avatar/{uuid}/128"
  events:
    chat: true
    join-leave: true
    deaths: true
    advancements: true
    server-start-stop: true
```

The tokens, channel ID and slash command can live in the config file or in environment variables.
Environment variables are the better fit for a container: on the itzg/minecraft-server image every
container environment variable reaches the server process, so set `SLACK_BOT_TOKEN`,
`SLACK_APP_TOKEN`, `SLACK_CHANNEL_ID` and `SLACK_COMMAND` on the deployment, the tokens as secrets,
and leave those config values empty. Restart the server after changing either.

If `enabled` is true but a token, the channel ID or the slash command is missing, the plugin logs
which one and stays off. If it can't connect to Slack (Slack is down, the network isn't up yet, or a
token is wrong), it logs the error and tries again after 30 seconds, doubling the wait up to every 5
minutes until it connects; once connected, Slack's Socket Mode client reconnects on its own. While
it isn't connected, only operators can join if a join requirement is on, so check the log after
setting it up.

`slack-name` and `slack-id-help` set the wording of the in-game linking instructions. For the Hack
Club Slack, where `/id` shows your member ID:

```yaml
    slack-name: "the Hack Club Slack"
    slack-id-help: "Run <gold>/id</gold> in Slack to see it"
```

Account links are stored in `plugins/SlackBridge/links.yml` (Minecraft UUID, Slack user ID, last
known name, and an encrypted Slack token for players who post as themselves). The plugin makes the
file readable by the server's own user only.

## 3. Click-to-link (optional)

Instead of copying a code into Slack, players can click a link in game, sign in to Slack in their
browser, and be linked. Slack also asks them whether their Minecraft chat may be posted as real
messages from their Slack account. That permission (`chat:write` as a user scope) is optional, so
they can uncheck it; their chat is then posted by the bot under their Slack name and photo, as
before. The code flow keeps working alongside it, which matters on the join screen (links there
can't be clicked) and for Bedrock players (Bedrock chat has no clickable links).

How it goes:

1. `/slack link` shows "Click here to link your Slack account" plus the usual code. The link works
   once, for the same time as the code.
2. The link opens a page on the server saying which Minecraft account is being linked, with a
   warning not to continue if someone else sent the link. "Continue to Slack" goes to Slack's
   approval screen, restricted to the bot's workspace.
3. Slack sends the player back to `/slack/callback`. The plugin checks the workspace, links the
   accounts, and keeps the player's Slack token only if they left "post as me" checked. Otherwise
   the token is revoked right away; it was only needed to learn who they are.
4. The bot DMs the Slack user which Minecraft account they just linked, with a "Not me, unlink"
   button, since a link can be passed to someone else.

Setting it up:

1. In the Slack app, under **OAuth & Permissions**, add the redirect URL
   `https://<your host>/slack/callback` and the user scopes `users:read` and `chat:write`, with
   `chat:write` marked optional. [manifest.yml](manifest.yml) has these; replace its example
   redirect URL. Reinstall the app after changing scopes.
2. Copy the app's **Client ID** and **Client Secret** from **Basic Information**.
3. Expose `web-port` (8101 by default) on the server and route `https://<your host>/slack` to it.
   On a host that already serves BlueMap, a second route on the same domain for the `/slack` path
   is enough.
4. Set `SLACK_PUBLIC_URL` (for example `https://mc.example.com`), `SLACK_CLIENT_ID`,
   `SLACK_CLIENT_SECRET` and `SLACK_TOKEN_KEY` (any long random string; keep it secret and don't
   change it, or everyone posting as themselves has to link again). Restart.

The log says "Click-to-link is on at ..." when it's working, and `/slack admin status` shows it.

Things to know:

- A stored token can post as that person anywhere they can post in Slack, not just the bridge
  channel; Slack has no narrower permission. The plugin only uses it for the bridge channel, keeps
  it encrypted with `token-key`, and revokes it when the player unlinks, relinks or links a
  different account. Anyone who can read both `links.yml` and the key (for example the server's
  environment variables) could use the tokens, so treat access to the server accordingly.
- Messages posted as a player are real messages from them. If someone else gets into that
  player's Minecraft account, they can post as that player in the bridge channel.
- If a token stops working (revoked in Slack, account deactivated), the plugin stops using it and
  goes back to posting under the player's name and photo. If posting fails for another reason, such
  as the player having left the channel, that message is posted for them instead.
- Messages the plugin posts with a player's token come back from Slack like any other message; the
  plugin recognizes them and doesn't echo them into the game.
- Linking again always starts fresh: the old token is revoked before the player goes to Slack, so
  unchecking "post as me" the second time is respected.

## Commands

In game (`/slack`, permission `slackbridge.command.slack`, everyone by default):

| Command | What it does |
| --- | --- |
| `/slack info` | Shows your linked Slack account, or how to link one |
| `/slack link` | Gives you a code to run as `/mc link <code>` in Slack, and a link to click if click-to-link is on |
| `/slack link <slackId>` | DMs that Slack user a "Yes, link it" / "No" prompt instead |
| `/slack unlink` | Removes your link (and stops your chat posting as you) |
| `/slack lookup <player>` | Shows the Slack name a player is linked to |

Admin commands (`/slack admin`, permission `slackbridge.admin`, operators by default; they also work
from the server console):

| Command | What it does |
| --- | --- |
| `/slack admin link <player or uuid> <slack member id>` | Links an account directly, without the Slack confirmation. Works for players who have never joined (looked up with Mojang); use the UUID for Bedrock players. |
| `/slack admin unlink <player or uuid>` | Removes someone's link |
| `/slack admin list` | Every linked account with its Slack name and ID |
| `/slack admin status` | Whether Slack is connected, plus the channel, slash command, join requirement and number of links |

In Slack (your configured command, shown here as `/mc`; replies are only visible to you except
`players`). With `join-requirement: channel`, only members of the bridge channel can use it:

| Command | What it does |
| --- | --- |
| `/mc players` | Lists who is online, posted to the channel |
| `/mc link <code>` | Links your Slack account using a code from the game or the join screen |
| `/mc lookup <@user or player>` | Shows which accounts are linked |

## Join requirements

`join-requirement` decides who may join:

| Value | Who gets in |
| --- | --- |
| `none` | Anyone the server otherwise allows (default) |
| `linked` | Players who have linked an active Slack account. Anyone in the workspace can link, so in a big workspace this alone doesn't keep a server private. |
| `channel` | Linked players whose Slack account is a member of the bridge channel. Adding or removing someone from the channel adds or removes their access, like a whitelist. |

- Server operators (`ops.json`) always get in, whatever the setting.
- Minecraft's own whitelist still applies on top: a player has to pass both. With `channel` you can
  leave the whitelist off and manage access from Slack instead.
- An unlinked player who tries to join is turned away with a code to run as `/mc link <code>`; once
  that's done they can join. Linked accounts whose Slack user has been deactivated are turned away
  the same way.
- With `channel`, a linked player who isn't in the channel is told which channel to ask to join, and
  only channel members can use the Slack slash command.
  The member list is fetched from Slack at most once a minute.
- If Slack can't be reached once the bot is connected, linked players are let in under `linked`. Under `channel` the last
  member list fetched is used, and nobody but operators gets in if none has been fetched yet.
- An unrecognized value is treated as `channel`, so a typo can't open the server up.

One Slack account may be linked to several Minecraft accounts (for example a Java account and a
Bedrock one through Floodgate).

## Changes from HCCore

- Only the Slack bridge and account linking are left; HCCore's other features, and its dependencies
  on ProtocolLib, UltimateAdvancementAPI, SuperVanish and PlaceholderAPI, are gone.
- Socket Mode (app token) instead of an HTTP server with a signing secret, and the bot keeps retrying
  if Slack can't be reached when the server starts.
- Built against Paper 26.3 and Java 25, with Slack Bolt 1.52.0.
- No workspace-specific branding: the slash command must be configured, the linking instructions
  are configurable, and game events are posted by the bot itself instead of under per-event names
  and hosted images.
- Slack posts go out on a background thread instead of blocking the server thread, and `/slack`
  lookups run asynchronously.
- Player text is escaped before it goes to Slack, so in-game chat can't trigger `@channel` or other
  mentions; messages from muted players (canceled chat events) are not bridged.
- Event posts use the game's own chat text, and the link confirmation DM is a short yes/no
  question. Its buttons only work for the Slack user it was sent to, before the code expires, and
  the prompt is replaced with the result.
- Player names come from the server's display name (so EssentialsX nicknames show up), and links
  are kept in one `links.yml` instead of per-player JSON files.
- `required: true/false` became `join-requirement`, which adds a channel-members-only mode; operators
  bypass it.
- Added click-to-link through Slack sign-in, with optional posting as the player.
- Added `/slack link` (code), `/slack unlink`, `/slack admin`, per-event toggles, a configurable avatar
  URL, and environment-variable fallbacks for the tokens, channel and slash command.

## License

MIT, same as HCCore. See [LICENSE.txt](LICENSE.txt); the original HCCore copyright notice is kept.
