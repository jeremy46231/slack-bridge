package app.jer.slackbridge;

import app.jer.slackbridge.commands.SlackCommand;
import app.jer.slackbridge.listeners.LinkRequirementListener;
import app.jer.slackbridge.slack.SlackBot;
import java.util.Locale;
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

  private LinkStore linkStore;
  private JoinRequirement joinRequirement = JoinRequirement.NONE;
  private volatile SlackBot bot;
  private int failedStarts = 0;

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
      } else {
        this.getLogger().severe("Slack is enabled but " + missing + " not set; see the README");
      }
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
  }

  @Override
  public void onDisable() {
    SlackBot bot = this.bot;
    if (bot != null) {
      stopBot(bot);
      this.bot = null;
    }
  }

  /** Connects to Slack, and if that fails, tries again later. Runs off the server thread. */
  private void startBot() {
    if (!this.isEnabled()) {
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
      return;
    }

    if (this.failedStarts > 0) {
      this.getLogger().info("Connected to Slack after " + this.failedStarts + " failed attempts");
    }
    // Set right away so onDisable can always find the bot to disconnect it
    this.bot = newBot;
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

  public LinkStore getLinkStore() {
    return this.linkStore;
  }

  public SlackBot getSlackBot() {
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
