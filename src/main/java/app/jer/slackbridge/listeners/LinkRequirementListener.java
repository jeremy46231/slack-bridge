package app.jer.slackbridge.listeners;

import app.jer.slackbridge.SlackBridgePlugin;
import app.jer.slackbridge.playermessages.SlackMessages;
import app.jer.slackbridge.slack.SlackBot;
import app.jer.slackbridge.slack.SlackBot.JoinCheck;
import app.jer.slackbridge.web.LinkWebServer;
import app.jer.slackbridge.web.LinkWebServer.LinkAddress;
import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result;

/**
 * Turns away players who don't meet settings.slack-link.join-requirement: "linked" needs a linked
 * Slack account, "channel" also needs that account to be in the bridge channel or an admit channel.
 * Operators always get in. Minecraft's own whitelist still applies on top of this.
 */
public class LinkRequirementListener implements Listener {

  // How long a join waits for Slack while the bot is still connecting after startup
  private static final long BOT_STARTUP_WAIT_MILLIS = 15 * 1000;

  private final SlackBridgePlugin plugin;
  private final boolean requireChannel;

  public LinkRequirementListener(SlackBridgePlugin plugin, boolean requireChannel) {
    this.plugin = plugin;
    this.requireChannel = requireChannel;
  }

  @EventHandler(priority = EventPriority.LOWEST) // Runs foremost
  public void onPrePlayerLogin(final AsyncPlayerPreLoginEvent event) {
    if (event.getLoginResult() != Result.ALLOWED) {
      return;
    }

    UUID playerUUID = event.getUniqueId();
    if (this.plugin.getServer().getOfflinePlayer(playerUUID).isOp()) {
      return;
    }

    // Runs off the server thread, so waiting here only holds up this player's login
    SlackBot bot = this.plugin.awaitSlackBot(BOT_STARTUP_WAIT_MILLIS);
    if (bot == null) {
      event.disallow(Result.KICK_WHITELIST, SlackMessages.slackRequiredButUnavailable());
      return;
    }

    JoinCheck check = bot.checkJoin(playerUUID, this.requireChannel);
    if (check == JoinCheck.NOT_LINKED) {
      // Check for slack link
      this.plugin.getLogger()
          .info("Preventing " + event.getName() + "'s join because they are not linked");
      LinkWebServer web = this.plugin.getLinkWebServer();
      if (web == null) {
        event.disallow(Result.KICK_WHITELIST, SlackMessages.linkingUnavailable());
        return;
      }
      // Disconnect screens can't be clicked, so this shows an address short enough to type
      LinkAddress address = web.createLink(playerUUID, event.getName(),
          bot.getLinkCodeExpirationMillis());
      event.disallow(Result.KICK_WHITELIST,
          SlackMessages.mustLink(address, bot.getLinkCodeExpirationMillis()));
    } else if (check == JoinCheck.NOT_IN_CHANNEL) {
      this.plugin.getLogger().info("Preventing " + event.getName()
          + "'s join because their Slack account is not in the bridge channel");
      event.disallow(Result.KICK_WHITELIST,
          SlackMessages.mustBeInChannel(bot.getChannelName(), bot.getSlackName()));
    }
  }
}
