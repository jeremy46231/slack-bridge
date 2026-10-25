# SlackBridge

A slim Paper plugin that connects a Minecraft server to one Slack channel. It is a fork of
[HCCore](https://github.com/hackclub/HCCore), the main plugin of the Hack Club Minecraft server,
cut down to only its Slack integration (forked from upstream commit `5cd00ba`). Everything else
HCCore does (nicknames, AFK, spawn, colors, saved locations, stats, rules, welcome, advancements tab,
vanish hooks and so on) has been removed so it can run next to EssentialsX without clashing.

What it does:

- **Two-way chat** between the server and one Slack channel. Players' messages are posted with their
  display name and head as the avatar; Slack messages show up in game as `[Slack] name » message`.
  Mentions, channel links and URLs are turned into readable (and clickable) text. Thread replies stay
  in Slack.
- **Game events** posted to Slack: joins and leaves, deaths, advancements, and server start and stop.
  Each can be turned off. Players vanished with EssentialsX are not announced.
- **Account linking** with `/slack` in game and your own slash command in Slack, with an optional
  "must be linked to join" mode.

Built for Paper 26.3 (Java 25). Package `app.jer.slackbridge`, plugin name `SlackBridge`, so it
can't collide with HCCore.

## Build

Needs JDK 25 or newer.

```bash
./gradlew build
```

The plugin is `build/libs/SlackBridge-1.0.0.jar` (Bolt, OkHttp, Gson and Java-WebSocket are shaded
and relocated under `app.jer.slackbridge.libs`). Ignore the `-thin` jar.

GitHub Actions builds every push and keeps the jar as a build artifact. Pushing a tag like `v1.0.0`
also attaches the jar to a GitHub release, so a server on the itzg/minecraft-server image can install
it by adding the release asset's URL to its `PLUGINS` environment variable. Bump `version` in
`build.gradle.kts` before tagging.

## 1. Create the Slack app

At <https://api.slack.com/apps>, choose **Create New App → From an app manifest**, pick the
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
| `chat:write` | Posting to the channel and sending link-verification DMs |
| `chat:write.customize` | Posting under each player's name and head, and event posts under their own name and emoji |
| `commands` | The slash command |
| `im:write` | Opening the verification DM with a user |
| `users:read` | Showing Slack display names in game and checking linked accounts are still active |

Socket Mode means the plugin opens an outgoing WebSocket to Slack, so the server needs no public
HTTP endpoint, request URL or signing secret.

Then:

1. **Basic Information → App-Level Tokens → Generate Token and Scopes**, add the
   `connections:write` scope, and copy the `xapp-…` token. This is the **app token**.
2. **Install App → Install to Workspace**, then copy the **Bot User OAuth Token** (`xoxb-…`). This
   is the **bot token**.
3. In Slack, invite the bot to the bridge channel (`/invite @<bot name>`) and copy the channel ID
   (channel name → *About* → bottom of the panel, starts with `C`).

Treat both tokens as passwords: don't commit them, paste them in chat, or put them in the jar.

## 2. Configure the server

Install the jar in `plugins/`, start the server once to generate `plugins/SlackBridge/config.yml`,
then edit it:

```yaml
settings:
  slack-link:
    enabled: true          # turn the bridge on
    required: false        # true = players must link Slack before they can join
    link-code-expiration: 600
    bot-token: ""          # leave empty to use SLACK_BOT_TOKEN
    app-token: ""          # leave empty to use SLACK_APP_TOKEN
    channel-id: ""         # leave empty to use SLACK_CHANNEL_ID
    base-command: ""       # required: the slash command without "/"; empty = SLACK_COMMAND
    slack-name: "Slack"    # what in-game messages call the workspace
    slack-id-help: "In Slack, open your profile, click <b>⋮</b> and choose <i>Copy member ID</i>"
    avatar-url: "https://mc-heads.net/avatar/{uuid}/128"
  events:
    chat: true
    join-leave: true
    deaths: true
    advancements: true
    server-start-stop: true
  icons:                   # a Slack emoji (:name:) or an image URL
    join: ":wave:"
    leave: ":door:"
    death: ":skull:"
    advancement: ":trophy:"
    server: ":desktop_computer:"
```

The tokens, channel ID and slash command can live in the config file or in environment variables.
Environment variables are the better fit for a container: on the itzg/minecraft-server image every
container environment variable reaches the server process, so set `SLACK_BOT_TOKEN`,
`SLACK_APP_TOKEN`, `SLACK_CHANNEL_ID` and `SLACK_COMMAND` on the deployment, the tokens as secrets,
and leave those config values empty. Restart the server after changing either.

If `enabled` is true but a token, the channel ID or the slash command is missing, the plugin logs
which one and stays off. With `required: true` and the bot not running, nobody can join, so check
the log after setting it up.

`slack-name` and `slack-id-help` set the wording of the in-game linking instructions. For the Hack
Club Slack, where `/id` shows your member ID:

```yaml
    slack-name: "the Hack Club Slack"
    slack-id-help: "Run <gold>/id</gold> in Slack to see it"
```

Account links are stored in `plugins/SlackBridge/links.yml` (Minecraft UUID → Slack user ID and
last known name).

## Commands

In game (`/slack`, permission `slackbridge.command.slack`, everyone by default):

| Command | What it does |
| --- | --- |
| `/slack info` | Shows your linked Slack account, or how to link one |
| `/slack link` | Gives you a code to run as `/mc link <code>` in Slack |
| `/slack link <slackId>` | DMs that Slack user a Verify / Deny prompt instead |
| `/slack unlink` | Removes your link |
| `/slack lookup <player>` | Shows the Slack name a player is linked to |

In Slack (your configured command, shown here as `/mc`; replies are only visible to you except
`players`):

| Command | What it does |
| --- | --- |
| `/mc players` | Lists who is online, posted to the channel |
| `/mc link <code>` | Links your Slack account using a code from the game or the join screen |
| `/mc lookup <@user or player>` | Shows which accounts are linked |

With `required: true`, an unlinked player who tries to join is turned away with a code to run as
`/mc link <code>`; once that's done they can join. Linked accounts whose Slack user has been
deactivated are turned away too. If Slack can't be reached, linked players are let in.

One Slack account may be linked to several Minecraft accounts (for example a Java account and a
Bedrock one through Floodgate).

## Changes from HCCore

- Only the Slack bridge and account linking are left; HCCore's other features, and its dependencies
  on ProtocolLib, UltimateAdvancementAPI, SuperVanish and PlaceholderAPI, are gone.
- Socket Mode (app token) instead of an HTTP server with a signing secret.
- Built against Paper 26.3 and Java 25, with Slack Bolt 1.52.0.
- No workspace-specific branding: the slash command must be configured, the linking instructions
  are configurable, and event posts use Slack emoji instead of hosted images.
- Slack posts go out on a background thread instead of blocking the server thread, and `/slack`
  lookups run asynchronously.
- Player text is escaped before it goes to Slack, so in-game chat can't trigger `@channel` or other
  mentions; messages from muted players (cancelled chat events) are not bridged.
- Verify buttons only work for the Slack user the request was sent to, before the code expires, and
  the prompt is replaced with the result.
- Player names come from the server's display name (so EssentialsX nicknames show up), and links
  are kept in one `links.yml` instead of per-player JSON files.
- Added `/slack link` (code), `/slack unlink`, per-event toggles and icons, a configurable avatar
  URL, and environment-variable fallbacks for the tokens, channel and slash command.

## License

MIT, same as HCCore. See [LICENSE.txt](LICENSE.txt); the original HCCore copyright notice is kept.
