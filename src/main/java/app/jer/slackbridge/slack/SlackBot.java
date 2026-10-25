package app.jer.slackbridge.slack;

import static com.slack.api.model.block.Blocks.actions;
import static com.slack.api.model.block.Blocks.asBlocks;
import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;
import static com.slack.api.model.block.element.BlockElements.asElements;
import static com.slack.api.model.block.element.BlockElements.button;
import static net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText;

import app.jer.slackbridge.LinkStore.Link;
import app.jer.slackbridge.SlackBridgePlugin;
import app.jer.slackbridge.playermessages.SlackMessages;
import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.bolt.context.builtin.EventContext;
import com.slack.api.bolt.response.Response;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.model.User;
import com.slack.api.model.event.MessageBotEvent;
import com.slack.api.model.event.MessageChangedEvent;
import com.slack.api.model.event.MessageChannelJoinEvent;
import com.slack.api.model.event.MessageDeletedEvent;
import com.slack.api.model.event.MessageEvent;
import com.slack.api.model.event.MessageFileShareEvent;
import com.slack.api.socket_mode.SocketModeClient;
import io.papermc.paper.advancement.AdvancementDisplay;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.MetadataValue;

public class SlackBot implements Listener {

  public static final String DEFAULT_SLACK_ID_HELP =
      "In Slack, open your profile, click <b>⋮</b> and choose <i>Copy member ID</i>";
  private static final int MAX_NAME_LENGTH = 16;
  private static final long USER_CACHE_MILLIS = 10 * 60 * 1000;
  private static final long CHANNEL_MEMBERS_CACHE_MILLIS = 60 * 1000;
  // <@U123>, <@U123|name>, <#C123|channel>, <!here>, <!subteam^S123|@team>, <https://url|label>
  private static final Pattern SLACK_ENTITY = Pattern.compile("<([@#!]?)([^<>|]+)(?:\\|([^<>]*))?>");

  private record LinkCode(UUID uuid, String name, String code, long expiresAt) {

  }

  private record PendingVerification(String slackId, long expiresAt) {

  }

  private record CachedUser(User user, long fetchedAt) {

  }

  private final SlackBridgePlugin plugin;
  private final App app;
  private final SocketModeApp socketModeApp;
  private final String botToken;
  private final String channelId;
  private final String commandBase;
  private final long linkCodeExpirationMillis;
  private final Map<UUID, LinkCode> mcLinkCodes = new ConcurrentHashMap<>();
  private final Map<UUID, PendingVerification> pendingVerifications = new ConcurrentHashMap<>();
  private final Map<String, CachedUser> userCache = new ConcurrentHashMap<>();
  private volatile Set<String> channelMembers = Set.of();
  private long channelMembersFetchedAt = 0;
  private volatile String channelName;
  private final SecureRandom random = new SecureRandom();
  // Posts to Slack in order without blocking the server thread
  private final ExecutorService outbox = Executors.newSingleThreadExecutor(
      runnable -> Thread.ofPlatform().name("SlackBridge-outbox").daemon().unstarted(runnable));

  public SlackBot(SlackBridgePlugin plugin) throws Exception {
    this.plugin = plugin;
    this.botToken = plugin.getSetting("bot-token", "SLACK_BOT_TOKEN");
    String appToken = plugin.getSetting("app-token", "SLACK_APP_TOKEN");
    this.channelId = plugin.getSetting("channel-id", "SLACK_CHANNEL_ID");
    this.commandBase = plugin.getSetting("base-command", "SLACK_COMMAND").replaceFirst("^/", "");
    this.linkCodeExpirationMillis =
        plugin.getConfig().getLong("settings.slack-link.link-code-expiration", 60 * 10) * 1000;

    this.app = new App(AppConfig.builder().singleTeamBotToken(this.botToken).build());

    app.event(MessageEvent.class, (payload, ctx) -> {
      MessageEvent event = payload.getEvent();
      // Thread replies stay in Slack; only top-level channel messages are bridged
      boolean isThreadReply = event.getThreadTs() != null && !event.getThreadTs().equals(event.getTs());
      if (event.getBotId() == null && !isThreadReply) {
        this.relayToMinecraft(event.getChannel(), event.getUser(), event.getText(), false);
      }
      return ctx.ack();
    });

    app.event(MessageFileShareEvent.class, (payload, ctx) -> {
      MessageFileShareEvent event = payload.getEvent();
      boolean isThreadReply = event.getThreadTs() != null && !event.getThreadTs().equals(event.getTs());
      if (!isThreadReply) {
        this.relayToMinecraft(event.getChannel(), event.getUser(), event.getText(), true);
      }
      return ctx.ack();
    });

    app.event(MessageBotEvent.class, (payload, ctx) -> ctx.ack());
    app.event(MessageChangedEvent.class, (payload, ctx) -> ctx.ack());
    app.event(MessageDeletedEvent.class, (payload, ctx) -> ctx.ack());
    app.event(MessageChannelJoinEvent.class, (payload, ctx) -> ctx.ack());

    app.blockAction("verify-link", (req, ctx) -> {
      String clickerId = req.getPayload().getUser().getId();
      UUID mcUuid = UUID.fromString(req.getPayload().getActions().get(0).getValue());
      PendingVerification pending = this.pendingVerifications.get(mcUuid);

      if (pending == null || pending.expiresAt() < System.currentTimeMillis()
          || !pending.slackId().equals(clickerId)) {
        ctx.respond(r -> r.replaceOriginal(true).text(
            "This link request has expired. Run `/slack link` in Minecraft to start again."));
        return ctx.ack();
      }

      this.pendingVerifications.remove(mcUuid);
      OfflinePlayer player = Bukkit.getOfflinePlayer(mcUuid);
      this.plugin.getLinkStore().setLink(mcUuid, clickerId, player.getName());
      ctx.respond(r -> r.replaceOriginal(true)
          .text("Your Slack account is now linked to *%s*.".formatted(escape(player.getName()))));
      this.sendToPlayer(mcUuid, SlackMessages.accountLinked());

      return ctx.ack();
    });

    app.blockAction("deny-link", (req, ctx) -> {
      UUID mcUuid = UUID.fromString(req.getPayload().getActions().get(0).getValue());
      this.pendingVerifications.remove(mcUuid);
      ctx.respond(r -> r.replaceOriginal(true).text("Denied link request"));
      this.sendToPlayer(mcUuid, SlackMessages.linkDenied());
      return ctx.ack();
    });

    app.command("/%s".formatted(commandBase), (req, ctx) -> {
      String text = req.getPayload().getText() == null ? "" : req.getPayload().getText().trim();
      String userId = req.getPayload().getUserId();
      plugin.getLogger().info(
          "Received slack command from %s: \"/%s %s\"".formatted(userId, commandBase, text));

      String[] args = text.split("\\s+", 2);
      String argument = args.length > 1 ? args[1].trim() : "";
      return switch (args[0].toLowerCase()) {
        case "players" -> ctx.ack(r -> r.responseType("in_channel").text(this.getPlayerList()));
        case "link" -> ctx.ack(argument.isEmpty() ? "Missing argument: link code" : this.linkWithCode(argument, userId));
        case "lookup" -> ctx.ack(argument.isEmpty() ? "Missing argument: slack user mention or id"
            : this.lookup(argument));
        default -> ctx.ack("""
            Possible commands:
            `/%1$s players`: list who is online
            `/%1$s link <code>`: link your Minecraft account using the code shown in game
            `/%1$s lookup <@user | player>`: see which accounts are linked""".formatted(commandBase));
      };
    });

    this.socketModeApp = new SocketModeApp(appToken, SocketModeClient.Backend.JavaWebSocket, app);
    try {
      this.socketModeApp.startAsync();
    } catch (Exception e) {
      // Don't leave a half-open connection behind when the plugin retries with a new bot
      try {
        this.socketModeApp.close();
      } catch (Exception closeException) {
        e.addSuppressed(closeException);
      }
      this.outbox.shutdownNow();
      throw e;
    }
    this.plugin.getLogger().info("Slack bridge started!");
    if (this.isEventEnabled("server-start-stop")) {
      sendMessage(":large_green_circle: *Server Started*", this.icon("server"), "Console");
    }
  }

  public void disconnect() throws Exception {
    if (this.isEventEnabled("server-start-stop")) {
      sendMessage(":octagonal_sign: *Server Stopped*", this.icon("server"), "Console");
    }
    this.outbox.shutdown();
    this.outbox.awaitTermination(5, TimeUnit.SECONDS);
    this.socketModeApp.stop();
  }

  public String getCommandBase() {
    return this.commandBase;
  }

  /** What in-game messages call the Slack workspace, e.g. "Slack" or "the Hack Club Slack". */
  public String getSlackName() {
    String name = this.plugin.getConfig().getString("settings.slack-link.slack-name", "Slack");
    return name == null || name.isBlank() ? "Slack" : name;
  }

  /** MiniMessage text explaining how to find your Slack member ID. */
  public String getSlackIdHelp() {
    String help = this.plugin.getConfig()
        .getString("settings.slack-link.slack-id-help", DEFAULT_SLACK_ID_HELP);
    return help == null || help.isBlank() ? DEFAULT_SLACK_ID_HELP : help;
  }

  public int getLinkCodeExpirationSeconds() {
    return (int) (this.linkCodeExpirationMillis / 1000);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onChat(AsyncChatEvent e) {
    if (!this.isEventEnabled("chat")) {
      return;
    }
    Player player = e.getPlayer();
    sendMessage(escape(plainText().serialize(e.message())), this.getPlayerAvatarLink(player),
        plainText().serialize(player.displayName()));
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onJoin(PlayerJoinEvent e) {
    Player player = e.getPlayer();
    this.plugin.getLinkStore().updateName(player.getUniqueId(), player.getName());
    if (!this.isEventEnabled("join-leave") || isVanished(player)) {
      return;
    }
    sendMessage("*" + escape(plainText().serialize(player.displayName())) + "* joined the game!",
        this.icon("join"), "Join");
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onQuit(PlayerQuitEvent e) {
    Player player = e.getPlayer();
    if (!this.isEventEnabled("join-leave") || isVanished(player)) {
      return;
    }
    sendMessage("*" + escape(plainText().serialize(player.displayName())) + "* left the game!",
        this.icon("leave"), "Leave");
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onDeath(PlayerDeathEvent e) {
    Component deathMessage = e.deathMessage();
    if (!this.isEventEnabled("deaths") || deathMessage == null) {
      return;
    }
    sendMessage(escape(plainText().serialize(deathMessage)), this.icon("death"), "R.I.P.");
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onAdvancementDone(PlayerAdvancementDoneEvent e) {
    Advancement advancement = e.getAdvancement();

    // Filter out hidden advancements and recipes
    if (!this.isEventEnabled("advancements") || advancement.getDisplay() == null) {
      return;
    }
    if (!advancement.getDisplay().doesAnnounceToChat()) {
      return;
    }

    String advancementName = plainText().serialize(advancement.getDisplay().title());

    String advancementType;
    switch (advancement.getDisplay().frame()) {
      case AdvancementDisplay.Frame.GOAL -> advancementType = "goal";
      case AdvancementDisplay.Frame.CHALLENGE -> advancementType = "challenge";
      default -> advancementType = "advancement";
    }

    sendMessage("%s has completed the %s *%s*".formatted(
        escape(plainText().serialize(e.getPlayer().displayName())), advancementType,
        escape(advancementName)), this.icon("advancement"), "Advancement");
  }

  public enum JoinCheck { ALLOWED, NOT_LINKED, NOT_IN_CHANNEL }

  /**
   * Whether a player may join. Unlinked accounts may not; deactivated Slack accounts may not; with
   * requireChannel, the linked Slack account must also be a member of the bridge channel. If Slack
   * can't be reached, linked accounts are let in, but channel membership falls back to the last list
   * fetched (and is denied if there is none).
   */
  public JoinCheck checkJoin(UUID uuid, boolean requireChannel) {
    String slackId = this.plugin.getLinkStore().getSlackId(uuid);
    if (slackId == null) {
      return JoinCheck.NOT_LINKED;
    }

    try {
      var res = app.client().usersInfo(r -> r.token(this.botToken).user(slackId));
      // user_not_found means the account is gone; other errors are on Slack's side
      if (res.isOk() ? res.getUser().isDeleted() : "user_not_found".equals(res.getError())) {
        return JoinCheck.NOT_LINKED;
      }
    } catch (IOException | SlackApiException e) {
      this.plugin.getLogger().log(Level.WARNING,
          "Could not check whether Slack account " + slackId + " is active; assuming it is", e);
    }

    if (requireChannel && !this.getChannelMembers().contains(slackId)) {
      return JoinCheck.NOT_IN_CHANNEL;
    }
    return JoinCheck.ALLOWED;
  }

  /** Member IDs of the bridge channel, refreshed at most every CHANNEL_MEMBERS_CACHE_MILLIS. */
  private synchronized Set<String> getChannelMembers() {
    if (System.currentTimeMillis() - this.channelMembersFetchedAt < CHANNEL_MEMBERS_CACHE_MILLIS) {
      return this.channelMembers;
    }

    Set<String> members = new HashSet<>();
    String cursor = null;
    try {
      do {
        String page = cursor;
        var res = app.client().conversationsMembers(
            r -> r.token(this.botToken).channel(this.channelId).limit(1000).cursor(page));
        if (!res.isOk()) {
          throw new IOException("conversations.members failed: " + res.getError());
        }
        members.addAll(res.getMembers());
        cursor = res.getResponseMetadata() == null ? null
            : res.getResponseMetadata().getNextCursor();
      } while (cursor != null && !cursor.isEmpty());
    } catch (IOException | SlackApiException e) {
      this.plugin.getLogger().log(Level.WARNING,
          "Could not list the Slack channel's members; using the last list fetched", e);
      return this.channelMembers;
    }

    this.channelMembers = Set.copyOf(members);
    this.channelMembersFetchedAt = System.currentTimeMillis();
    return this.channelMembers;
  }

  /** The bridge channel's name for kick messages, falling back to "the server's Slack channel". */
  public String getChannelName() {
    if (this.channelName == null) {
      try {
        var res = app.client().conversationsInfo(r -> r.token(this.botToken).channel(this.channelId));
        if (res.isOk()) {
          this.channelName = "#" + res.getChannel().getName();
        }
      } catch (IOException | SlackApiException e) {
        this.plugin.getLogger().log(Level.WARNING, "Could not look up the Slack channel's name", e);
      }
    }
    return this.channelName == null ? "the server's Slack channel" : this.channelName;
  }

  public User getUserInfo(String id) throws IOException {
    CachedUser cached = this.userCache.get(id);
    if (cached != null && System.currentTimeMillis() - cached.fetchedAt() < USER_CACHE_MILLIS) {
      return cached.user();
    }

    try {
      var res = app.client().usersInfo(r -> r.token(this.botToken).user(id));
      if (!res.isOk()) {
        return null;
      }
      this.userCache.put(id, new CachedUser(res.getUser(), System.currentTimeMillis()));
      return res.getUser();
    } catch (SlackApiException e) {
      return null;
    }
  }

  public boolean sendVerificationMessage(String id, String mcName, String uuid) throws IOException {
    MethodsClient client = app.client();

    try {
      var res = client.chatPostMessage(r -> r.token(this.botToken).channel(id).text(
              "A player on the Minecraft server with username " + mcName
                  + " (UUID " + uuid + ") is trying to link to your Slack account")
          .blocks(asBlocks(section(s -> s.text(
              markdownText("A player on the Minecraft server with username *" + escape(mcName)
                  + "*" + " (UUID `" + uuid + "`) is trying to link to " + "your "
                  + "Slack account. If you are this player, click the \"Verify\" button. If you "
                  + "are " + "not this player, click the \"Deny\" button."))), actions(
              actions -> actions.elements(asElements(button(b -> b.actionId("verify-link").text(
                  com.slack.api.model.block.composition.BlockCompositions.plainText(
                      pt -> pt.text("Verify"))).value(uuid).style("primary")), button(
                  b -> b.actionId("deny-link").text(
                      com.slack.api.model.block.composition.BlockCompositions.plainText(
                          pt -> pt.text("Deny"))).value(uuid).style("danger"))))))));

      if (!res.isOk()) {
        this.plugin.getLogger()
            .log(Level.WARNING, "SlackBot failed to send verification message: " + res.getError());
        return false;
      }

      this.pendingVerifications.put(UUID.fromString(uuid),
          new PendingVerification(id, System.currentTimeMillis() + this.linkCodeExpirationMillis));
      return true;
    } catch (SlackApiException e) {
      this.plugin.getLogger().log(Level.WARNING, "SlackBot failed to send verification message", e);
      return false;
    }
  }

  public String generateVerificationCode(UUID mcUuid, String name) {
    long expiresAt = System.currentTimeMillis() + this.linkCodeExpirationMillis;
    LinkCode existing = this.mcLinkCodes.get(mcUuid);
    // refresh the time to live of the code
    String code = existing != null && existing.expiresAt() > System.currentTimeMillis()
        ? existing.code() : this.newCode();
    this.mcLinkCodes.put(mcUuid, new LinkCode(mcUuid, name, code, expiresAt));
    return code;
  }

  private String newCode() {
    byte[] bytes = new byte[3];
    this.random.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  private String linkWithCode(String code, String slackId) {
    long now = System.currentTimeMillis();
    this.mcLinkCodes.values().removeIf(linkCode -> linkCode.expiresAt() < now);
    Optional<LinkCode> match = this.mcLinkCodes.values().stream()
        .filter(linkCode -> linkCode.code().equalsIgnoreCase(code)).findFirst();

    if (match.isEmpty()) {
      return "Invalid or expired code. Run `/slack link` in Minecraft (or try joining again) to get a new one.";
    }

    LinkCode linkCode = match.get();
    String existing = this.plugin.getLinkStore().getSlackId(linkCode.uuid());
    if (existing != null) {
      return "This Minecraft account is already linked to <@%s>".formatted(existing);
    }

    this.plugin.getLinkStore().setLink(linkCode.uuid(), slackId, linkCode.name());
    this.mcLinkCodes.remove(linkCode.uuid());
    this.sendToPlayer(linkCode.uuid(), SlackMessages.accountLinked());

    return "Successfully linked your Slack account to the Minecraft account *%s*! You may now join the server.".formatted(
        escape(linkCode.name()));
  }

  private String lookup(String query) {
    String id;

    // User mention
    if (query.startsWith("<@") && query.endsWith(">")) {
      int pipeIdx = query.indexOf('|');
      if (pipeIdx == -1) {
        id = query.substring(2, query.length() - 1);
      } else {
        id = query.substring(2, pipeIdx);
      }
    } else {
      // Try user id
      id = query;
    }

    Optional<Entry<UUID, Link>> bySlack = this.plugin.getLinkStore().findBySlackId(id);
    if (bySlack.isPresent()) {
      return "<@%s> is linked to the Minecraft account `%s`.".formatted(id,
          bySlack.get().getValue().name());
    }

    Optional<Entry<UUID, Link>> byName = this.plugin.getLinkStore().findByName(query);
    if (byName.isPresent()) {
      return "Minecraft account `%s` is linked to <@%s>.".formatted(byName.get().getValue().name(),
          byName.get().getValue().slackId());
    }

    return "No linked Slack or Minecraft account was found for `%s`.".formatted(escape(query));
  }

  private String getPlayerList() {
    List<? extends Player> onlinePlayers = plugin.getServer().getOnlinePlayers().stream()
        .filter(player -> !isVanished(player)).toList();
    if (onlinePlayers.isEmpty()) {
      return "There are currently no players online";
    }

    StringBuilder message = new StringBuilder();
    message.append("*Players online* (%d/%d)\n\n".formatted(onlinePlayers.size(),
        plugin.getServer().getMaxPlayers()));
    for (Player player : onlinePlayers) {
      String displayName = plainText().serialize(player.displayName());
      String name = player.getName();
      message.append("%s%s\n".formatted(escape(displayName),
          (name.equals(displayName)) ? "" : (", AKA " + escape(name))));
    }
    return message.toString();
  }

  private void relayToMinecraft(String channel, String userId, String text, boolean hasFiles) {
    if (!this.channelId.equals(channel) || userId == null) {
      return;
    }

    String displayName = userId;
    String realName = userId;
    try {
      User user = this.getUserInfo(userId);
      if (user != null) {
        realName = user.getRealName() != null ? user.getRealName() : user.getName();
        String profileName = user.getProfile() == null ? null : user.getProfile().getDisplayName();
        displayName = profileName != null && !profileName.isBlank() ? profileName : realName;
      }
    } catch (IOException e) {
      this.plugin.getLogger().log(Level.WARNING, "Could not look up Slack user " + userId, e);
    }

    TextComponent nameComponent = Component.text(displayName.length() > MAX_NAME_LENGTH
        ? displayName.substring(0, MAX_NAME_LENGTH) + "..."
        : displayName).color(NamedTextColor.WHITE).hoverEvent(Component.text(realName));

    Component message = this.toComponent(text == null ? "" : text);
    if (hasFiles) {
      message = message.append(Component.text(" [attachment]").color(NamedTextColor.GRAY));
    }

    Component chatMessage = SlackMessages.slackChat(nameComponent, message);
    Bukkit.getScheduler().runTask(this.plugin, () -> plugin.getServer().broadcast(chatMessage));
  }

  /** Turns Slack's message markup into a component, resolving mentions and keeping links clickable. */
  private Component toComponent(String text) {
    TextComponent.Builder builder = Component.text();
    Matcher matcher = SLACK_ENTITY.matcher(text);
    int last = 0;

    while (matcher.find()) {
      builder.append(Component.text(unescape(text.substring(last, matcher.start()))));
      last = matcher.end();

      String kind = matcher.group(1);
      String target = matcher.group(2);
      String label = matcher.group(3);

      switch (kind) {
        case "@" -> builder.append(Component.text("@" + (label != null ? label : this.nameOf(target)))
            .color(NamedTextColor.AQUA));
        case "#" -> builder.append(Component.text("#" + (label != null ? label : target))
            .color(NamedTextColor.AQUA));
        case "!" -> builder.append(Component.text(label != null ? unescape(label)
            : "@" + target.replaceFirst("\\^.*", "")).color(NamedTextColor.AQUA));
        default -> {
          String url = unescape(target);
          builder.append(Component.text(label != null && !label.isEmpty() ? unescape(label) : url)
              .decorate(TextDecoration.UNDERLINED).clickEvent(ClickEvent.openUrl(url))
              .hoverEvent(Component.text(url)));
        }
      }
    }

    builder.append(Component.text(unescape(text.substring(last))));
    return builder.build();
  }

  private String nameOf(String userId) {
    try {
      User user = this.getUserInfo(userId);
      if (user != null) {
        String profileName = user.getProfile() == null ? null : user.getProfile().getDisplayName();
        return profileName != null && !profileName.isBlank() ? profileName : user.getRealName();
      }
    } catch (IOException ignored) {
      // Fall back to the raw ID
    }
    return userId;
  }

  private String getPlayerAvatarLink(Player player) {
    String template = this.plugin.getConfig()
        .getString("settings.slack-link.avatar-url", "https://mc-heads.net/avatar/{uuid}/128");
    return template.replace("{uuid}", player.getUniqueId().toString())
        .replace("{name}", player.getName());
  }

  private boolean isEventEnabled(String event) {
    return this.plugin.getConfig().getBoolean("settings.events." + event, true);
  }

  private void sendToPlayer(UUID uuid, Component message) {
    Bukkit.getScheduler().runTask(this.plugin, () -> {
      Player player = Bukkit.getPlayer(uuid);
      if (player != null) {
        player.sendMessage(message);
      }
    });
  }

  /** The icon for an event's posts: a Slack emoji like :wave: or an image URL. */
  private String icon(String event) {
    return this.plugin.getConfig().getString("settings.icons." + event, ":speech_balloon:");
  }

  /** @param icon a Slack emoji such as :skull:, or an image URL */
  void sendMessage(String msg, String icon, String username) {
    if (this.outbox.isShutdown()) {
      return;
    }
    this.outbox.execute(() -> {
      try {
        var res = app.client().chatPostMessage(
            r -> {
              r.token(this.botToken).channel(this.channelId).text(msg).username(username);
              return icon.startsWith(":") ? r.iconEmoji(icon) : r.iconUrl(icon);
            });

        if (!res.isOk()) {
          this.plugin.getLogger().log(Level.WARNING, "SlackBot failed to send message: " + res.getError());
        }
      } catch (IOException | SlackApiException e) {
        this.plugin.getLogger().log(Level.WARNING, "SlackBot failed to send message", e);
      }
    });
  }

  /** EssentialsX and most vanish plugins set this metadata on vanished players. */
  @SuppressWarnings("deprecation") // Paper deprecated metadata, but vanish plugins still use it
  private static boolean isVanished(Player player) {
    for (MetadataValue value : player.getMetadata("vanished")) {
      if (value.asBoolean()) {
        return true;
      }
    }
    return false;
  }

  /** Escapes the characters Slack treats as markup, so players can't post mentions like @channel. */
  private static String escape(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static String unescape(String text) {
    return text.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
  }
}
