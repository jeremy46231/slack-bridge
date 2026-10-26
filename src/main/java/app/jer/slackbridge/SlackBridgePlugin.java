package app.jer.slackbridge;

import app.jer.slackbridge.commands.SlackCommand;
import app.jer.slackbridge.listeners.LinkRequirementListener;
import app.jer.slackbridge.LinkStore.Link;
import app.jer.slackbridge.slack.SlackBot;
import app.jer.slackbridge.web.LinkWebServer;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;

public class SlackBridgePlugin extends JavaPlugin {

  // Wait between attempts to reach Slack: 30s, doubling up to 5 minutes, retrying until it works
  private static final long FIRST_RETRY_SECONDS = 30;
  private static final long MAX_RETRY_SECONDS = 5 * 60;
  private static final long TICKS_PER_SECOND = 20;

  public enum JoinRequirement { NONE, LINKED, CHANNEL }

  private static final int DEFAULT_WEB_PORT = 8101;

  private LinkStore linkStore;
  private TokenCipher tokenCipher;
  private LinkWebServer webServer;
  private JoinRequirement joinRequirement = JoinRequirement.NONE;
  private volatile SlackBot bot;
  private int failedStarts = 0;
  // Released once the first attempt to reach Slack has finished, whether or not it worked
  private final CountDownLatch firstStart = new CountDownLatch(1);

  @Override
  public void onEnable() {
    // Create config
    this.saveDefaultConfig();

    this.linkStore = new LinkStore(this);
    this.linkStore.load();

    String joinRequirementSetting = this.getConfig()
        .getString("settings.slack-link.join-requirement", "none").trim().toUpperCase(Locale.ROOT);
    try {
      this.joinRequirement = JoinRequirement.valueOf(joinRequirementSetting);
    } catch (IllegalArgumentException e) {
      // A typo shouldn't open the server up, so fall back to the strictest mode
      this.getLogger().severe("Unknown join-requirement \"" + joinRequirementSetting.toLowerCase(Locale.ROOT)
          + "\"; expected none, linked or channel. Using channel.");
      this.joinRequirement = JoinRequirement.CHANNEL;
    }

    boolean enabled = this.getConfig().getBoolean("settings.slack-link.enabled", false);
    if (enabled) {
      String missing = this.missingSettings();
      if (missing.isEmpty()) {
        // Connecting is network I/O, so it happens off the server thread
        this.getServer().getScheduler().runTaskAsynchronously(this, this::startBot);
        this.startWebServer();
      } else {
        this.getLogger().severe("Slack is enabled but " + missing + " not set; see the README");
        this.firstStart.countDown();
      }
    } else {
      this.firstStart.countDown();
    }

    // Register commands
    PluginCommand command = this.getCommand("slack");
    if (command != null) {
      SlackCommand slackCommand = new SlackCommand(this);
      command.setExecutor(slackCommand);
      command.setTabCompleter(slackCommand);
    }

    // Register event listeners (the bot registers itself once it connects)
    if (this.joinRequirement != JoinRequirement.NONE) {
      if (enabled) {
        this.getServer().getPluginManager().registerEvents(
            new LinkRequirementListener(this, this.joinRequirement == JoinRequirement.CHANNEL),
            this);
      } else {
        this.getLogger().warning("join-requirement is ignored while Slack is disabled");
      }
    }
    if (this.joinRequirement != JoinRequirement.CHANNEL && !this.getAdmitChannelIds().isEmpty()) {
      this.getLogger().warning("admit-channels is ignored unless join-requirement is channel");
    }
  }

  @Override
  public void onDisable() {
    if (this.webServer != null) {
      this.webServer.stop();
      this.webServer = null;
    }
    SlackBot bot = this.bot;
    if (bot != null) {
      stopBot(bot);
      this.bot = null;
    }
    this.firstStart.countDown();
  }

  /** Connects to Slack, and if that fails, tries again later. Runs off the server thread. */
  private void startBot() {
    if (!this.isEnabled()) {
      this.firstStart.countDown();
      return;
    }

    SlackBot newBot;
    try {
      newBot = new SlackBot(this);
    } catch (Exception e) {
      long delay = Math.min(FIRST_RETRY_SECONDS << Math.min(this.failedStarts, 10), MAX_RETRY_SECONDS);
      this.failedStarts++;
      if (this.failedStarts == 1) {
        this.getLogger().log(Level.SEVERE,
            "Could not start the Slack bot; retrying in " + delay + " seconds", e);
      } else {
        this.getLogger().warning("Could not start the Slack bot (attempt " + this.failedStarts
            + ", " + e.getMessage() + "); retrying in " + delay + " seconds");
      }
      try {
        this.getServer().getScheduler()
            .runTaskLaterAsynchronously(this, this::startBot, delay * TICKS_PER_SECOND);
      } catch (IllegalPluginAccessException ignored) {
        // The plugin was disabled meanwhile; stop retrying
      }
      this.firstStart.countDown();
      return;
    }

    if (this.failedStarts > 0) {
      this.getLogger().info("Connected to Slack after " + this.failedStarts + " failed attempts");
    }
    // Set right away so onDisable can always find the bot to disconnect it
    this.bot = newBot;
    this.firstStart.countDown();
    try {
      this.getServer().getScheduler().runTask(this,
          () -> this.getServer().getPluginManager().registerEvents(newBot, this));
    } catch (IllegalPluginAccessException e) {
      // The plugin was disabled while connecting
      this.bot = null;
      stopBot(newBot);
    }
  }

  private void stopBot(SlackBot bot) {
    try {
      bot.disconnect();
    } catch (Exception e) {
      this.getLogger().log(Level.WARNING, "Error while stopping the Slack bot", e);
    }
  }

  public JoinRequirement getJoinRequirement() {
    return this.joinRequirement;
  }

  /** In channel mode, only members of the bridge channel may join or use the Slack command. */
  public boolean isChannelOnly() {
    return this.joinRequirement == JoinRequirement.CHANNEL;
  }

  /**
   * Channel IDs from settings.slack-link.admit-channels, or the comma-separated SLACK_ADMIT_CHANNELS
   * environment variable if that's empty.
   */
  public List<String> getAdmitChannelIds() {
    List<String> ids = this.getConfig().getStringList("settings.slack-link.admit-channels");
    if (ids.isEmpty()) {
      String env = System.getenv("SLACK_ADMIT_CHANNELS");
      ids = env == null ? List.of() : Arrays.asList(env.split(","));
    }
    return ids.stream().map(String::trim).filter(id -> !id.isEmpty()).distinct().toList();
  }

  /** Starts web linking, which needs a public URL and the Slack app's OAuth credentials. */
  private void startWebServer() {
    String publicUrl = this.getSetting("public-url", "SLACK_PUBLIC_URL");
    String clientId = this.getSetting("client-id", "SLACK_CLIENT_ID");
    String clientSecret = this.getSetting("client-secret", "SLACK_CLIENT_SECRET");
    String tokenKey = this.getSetting("token-key", "SLACK_TOKEN_KEY");

    if (publicUrl.isEmpty() || clientId.isEmpty() || clientSecret.isEmpty()) {
      this.getLogger().severe("Players can't link their Slack accounts until public-url, client-id "
          + "and client-secret are set (or SLACK_PUBLIC_URL, SLACK_CLIENT_ID, SLACK_CLIENT_SECRET); "
          + "see the README");
      return;
    }
    if (!publicUrl.startsWith("https://")) {
      this.getLogger().warning("public-url must start with https:// (Slack requires it); "
          + "players can't link until it's fixed");
      return;
    }

    if (tokenKey.isEmpty()) {
      this.getLogger().warning("No token-key/SLACK_TOKEN_KEY set, so players can't let the bridge "
          + "post their chat as them; they can still link");
    } else {
      this.tokenCipher = new TokenCipher(tokenKey);
    }

    int port = this.getConfig().getInt("settings.slack-link.web-port", DEFAULT_WEB_PORT);
    try {
      this.webServer = new LinkWebServer(this, publicUrl, port, clientId, clientSecret);
      this.getLogger().info("Web linking is on at " + this.webServer.getPublicUrl()
          + "/slack (listening on port " + port + ")");
    } catch (IOException e) {
      this.getLogger().log(Level.SEVERE, "Could not start the web linking server on port "
          + port, e);
    }
  }

  public LinkWebServer getLinkWebServer() {
    return this.webServer;
  }

  /** Encrypts stored Slack user tokens, or null if no token-key is set (posting as users is off). */
  public TokenCipher getTokenCipher() {
    return this.tokenCipher;
  }

  /**
   * Links a player to a Slack account, keeping their post-as-you permission if it's the same Slack
   * account and revoking it otherwise.
   */
  public void link(UUID uuid, String slackId, String name) {
    Link old = this.linkStore.setLink(uuid, slackId, name);
    if (old != null && !old.slackId().equals(slackId)) {
      this.revokeStoredToken(old);
    }
  }

  /** Removes a player's link and revokes their post-as-you permission. @return whether they were linked */
  public boolean unlink(UUID uuid) {
    Link removed = this.linkStore.removeLink(uuid);
    this.revokeStoredToken(removed);
    return removed != null;
  }

  /** Stops a player posting as themselves (revoking the token) but keeps them linked. */
  public void dropUserToken(UUID uuid) {
    Link link = this.linkStore.getLink(uuid);
    if (link != null && link.userToken() != null) {
      this.linkStore.clearUserToken(uuid, link.slackId());
      this.revokeStoredToken(link);
    }
  }

  /** Tells Slack to revoke a link's user token, if it has one and the bot is running. */
  public void revokeStoredToken(Link link) {
    SlackBot bot = this.bot;
    if (link == null || link.userToken() == null || bot == null || this.tokenCipher == null) {
      return;
    }
    String token = this.tokenCipher.decrypt(link.userToken());
    if (token != null) {
      bot.revokeUserToken(token);
    }
  }

  public LinkStore getLinkStore() {
    return this.linkStore;
  }

  public SlackBot getSlackBot() {
    return this.bot;
  }

  /**
   * The Slack bot, waiting up to timeoutMillis if the first attempt to connect is still running
   * (e.g. right after startup). Returns null at once if that attempt already failed. Call only off
   * the server thread.
   */
  public SlackBot awaitSlackBot(long timeoutMillis) {
    try {
      this.firstStart.await(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return this.bot;
  }

  /**
   * Reads settings.slack-link.&lt;key&gt;, falling back to an environment variable so tokens can
   * come from the server's secrets instead of the config file.
   */
  public String getSetting(String key, String envVar) {
    String value = this.getConfig().getString("settings.slack-link." + key, "");
    if (value == null || value.isBlank()) {
      value = System.getenv(envVar);
    }
    return value == null ? "" : value.trim();
  }

  private String missingSettings() {
    StringBuilder missing = new StringBuilder();
    if (this.getSetting("bot-token", "SLACK_BOT_TOKEN").isEmpty()) {
      missing.append("bot-token/SLACK_BOT_TOKEN ");
    }
    if (this.getSetting("app-token", "SLACK_APP_TOKEN").isEmpty()) {
      missing.append("app-token/SLACK_APP_TOKEN ");
    }
    if (this.getSetting("channel-id", "SLACK_CHANNEL_ID").isEmpty()) {
      missing.append("channel-id/SLACK_CHANNEL_ID ");
    }
    if (this.getSetting("base-command", "SLACK_COMMAND").replaceFirst("^/", "").isEmpty()) {
      missing.append("base-command/SLACK_COMMAND ");
    }
    String result = missing.toString().trim();
    return result.isEmpty() ? "" : result + (result.contains(" ") ? " are" : " is");
  }
}
