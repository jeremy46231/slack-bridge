package app.jer.slackbridge.listeners;

import app.jer.slackbridge.SlackBridgePlugin;
import app.jer.slackbridge.playermessages.SlackMessages;
import app.jer.slackbridge.slack.SlackBot;
import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result;

/** Turns away players without a linked Slack account when settings.slack-link.required is on. */
public class LinkRequirementListener implements Listener {

  private final SlackBridgePlugin plugin;

  public LinkRequirementListener(SlackBridgePlugin plugin) {
    this.plugin = plugin;
  }

  @EventHandler(priority = EventPriority.LOWEST) // Runs foremost
  public void onPrePlayerLogin(final AsyncPlayerPreLoginEvent event) {
    if (event.getLoginResult() != Result.ALLOWED) {
      return;
    }

    SlackBot bot = this.plugin.getSlackBot();
    if (bot == null) {
      event.disallow(Result.KICK_WHITELIST, SlackMessages.slackRequiredButUnavailable());
      return;
    }

    UUID playerUUID = event.getUniqueId();
    if (!bot.isJoinAllowed(playerUUID)) {
      // Check for slack link
      this.plugin.getLogger()
          .info("Preventing " + event.getName() + "'s join because they are not linked");
      String code = bot.generateVerificationCode(playerUUID, event.getName());
      event.disallow(Result.KICK_WHITELIST,
          SlackMessages.mustLink(bot.getCommandBase(), bot.getSlackName(), code,
              bot.getLinkCodeExpirationSeconds()));
    }
  }
}
