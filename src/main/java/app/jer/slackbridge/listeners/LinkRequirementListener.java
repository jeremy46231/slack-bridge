package app.jer.slackbridge.listeners;

import app.jer.slackbridge.SlackBridgePlugin;
import app.jer.slackbridge.playermessages.SlackMessages;
import app.jer.slackbridge.slack.SlackBot;
import app.jer.slackbridge.slack.SlackBot.JoinCheck;
import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result;

/**
 * Turns away players who don't meet settings.slack-link.join-requirement: "linked" needs a linked
 * Slack account, "channel" also needs that account to be in the bridge channel. Operators always
 * get in. Minecraft's own whitelist still applies on top of this.
 */
public class LinkRequirementListener implements Listener {

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

    SlackBot bot = this.plugin.getSlackBot();
    if (bot == null) {
      event.disallow(Result.KICK_WHITELIST, SlackMessages.slackRequiredButUnavailable());
      return;
    }

    JoinCheck check = bot.checkJoin(playerUUID, this.requireChannel);
    if (check == JoinCheck.NOT_LINKED) {
      // Check for slack link
      this.plugin.getLogger()
          .info("Preventing " + event.getName() + "'s join because they are not linked");
      String code = bot.generateVerificationCode(playerUUID, event.getName());
      event.disallow(Result.KICK_WHITELIST,
          SlackMessages.mustLink(bot.getCommandBase(), bot.getSlackName(), code,
              bot.getLinkCodeExpirationSeconds()));
    } else if (check == JoinCheck.NOT_IN_CHANNEL) {
      this.plugin.getLogger().info("Preventing " + event.getName()
          + "'s join because their Slack account is not in the bridge channel");
      event.disallow(Result.KICK_WHITELIST,
          SlackMessages.mustBeInChannel(bot.getChannelName(), bot.getSlackName()));
    }
  }
}
