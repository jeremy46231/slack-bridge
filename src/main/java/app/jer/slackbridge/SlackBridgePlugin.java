package app.jer.slackbridge;

import app.jer.slackbridge.commands.SlackCommand;
import app.jer.slackbridge.listeners.LinkRequirementListener;
import app.jer.slackbridge.slack.SlackBot;
import java.util.logging.Level;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class SlackBridgePlugin extends JavaPlugin {

  private LinkStore linkStore;
  private SlackBot bot;

  @Override
  public void onEnable() {
    // Create config
    this.saveDefaultConfig();

    this.linkStore = new LinkStore(this);
    this.linkStore.load();

    boolean enabled = this.getConfig().getBoolean("settings.slack-link.enabled", false);
    if (enabled) {
      String missing = this.missingSettings();
      if (missing.isEmpty()) {
        try {
          this.bot = new SlackBot(this);
        } catch (Exception e) {
          this.getLogger().log(Level.SEVERE, "Could not start the Slack bot", e);
        }
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

    // Register event listeners
    if (this.bot != null) {
      this.getServer().getPluginManager().registerEvents(this.bot, this);
    }
    String joinRequirement = this.getConfig()
        .getString("settings.slack-link.join-requirement", "none").toLowerCase();
    if (!joinRequirement.equals("none")) {
      if (!joinRequirement.equals("linked") && !joinRequirement.equals("channel")) {
        // A typo shouldn't open the server up, so fall back to the strictest mode
        this.getLogger().severe("Unknown join-requirement \"" + joinRequirement
            + "\"; expected none, linked or channel. Using channel.");
        joinRequirement = "channel";
      }
      if (enabled) {
        this.getServer().getPluginManager().registerEvents(
            new LinkRequirementListener(this, joinRequirement.equals("channel")), this);
      } else {
        this.getLogger().warning("join-requirement is ignored while Slack is disabled");
      }
    }
  }

  @Override
  public void onDisable() {
    if (this.bot != null) {
      try {
        this.bot.disconnect();
      } catch (Exception e) {
        this.getLogger().log(Level.WARNING, "Error while stopping the Slack bot", e);
      }

      this.bot = null;
    }
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
