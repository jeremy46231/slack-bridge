package app.jer.slackbridge.commands;

import app.jer.slackbridge.LinkStore.Link;
import app.jer.slackbridge.SlackBridgePlugin;
import app.jer.slackbridge.playermessages.SlackMessages;
import app.jer.slackbridge.slack.SlackBot;
import app.jer.slackbridge.web.LinkWebServer;
import app.jer.slackbridge.web.LinkWebServer.LinkAddress;
import com.slack.api.model.User;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;

public class SlackCommand implements TabExecutor {

  private final SlackBridgePlugin plugin;
  private final AdminCommand adminCommand;

  public SlackCommand(SlackBridgePlugin plugin) {
    this.plugin = plugin;
    this.adminCommand = new AdminCommand(plugin);
  }

  @Override
  public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
      @NotNull String alias, String[] args) {
    if (args.length > 0 && args[0].equalsIgnoreCase("admin")) {
      return this.adminCommand.onCommand(sender, Arrays.copyOfRange(args, 1, args.length));
    }

    SlackBot bot = this.plugin.getSlackBot();
    if (bot == null) {
      sender.sendMessage(SlackMessages.slackDisabled());
      return true;
    }

    if (args.length == 0) {
      return false;
    }

    switch (args[0]) {
      case "info" -> {
        if (!(sender instanceof Player player)) {
          sender.sendMessage(SlackMessages.mustBePlayer());
          return true;
        }
        Link link = this.plugin.getLinkStore().getLink(player.getUniqueId());
        String slackId = link == null ? null : link.slackId();
        if (slackId == null) {
          this.sendLinkPrompt(player);
          return true;
        }

        // linked, show paired account info
        this.async(() -> {
          User slackUser;
          try {
            slackUser = bot.getUserInfo(slackId);
          } catch (IOException e) {
            slackUser = null;
          }
          player.sendMessage(SlackMessages.linked(player, slackUser, link.userToken() != null));
        });
        return true;
      }
      case "link" -> {
        if (!(sender instanceof Player player)) {
          sender.sendMessage(SlackMessages.mustBePlayer());
          return true;
        }

        this.sendLinkPrompt(player);
        return true;
      }
      case "unlink" -> {
        if (!(sender instanceof Player player)) {
          sender.sendMessage(SlackMessages.mustBePlayer());
          return true;
        }
        if (this.plugin.unlink(player.getUniqueId())) {
          player.sendMessage(SlackMessages.accountUnlinked());
        } else {
          player.sendMessage(SlackMessages.notLinkedSelf());
        }
        return true;
      }
      case "lookup" -> {
        if (args.length != 2) {
          return false;
        }
        OfflinePlayer lookupPlayer = this.plugin.getServer().getOfflinePlayerIfCached(args[1]);
        if (lookupPlayer == null || !lookupPlayer.hasPlayedBefore()) {
          sender.sendMessage(SlackMessages.playerHasntPlayed());
          return true;
        }
        String slackId = this.plugin.getLinkStore().getSlackId(lookupPlayer.getUniqueId());
        if (slackId == null) {
          sender.sendMessage(SlackMessages.playerNotLinked());
          return true;
        }
        this.async(() -> {
          try {
            User slackUser = bot.getUserInfo(slackId);

            if (slackUser == null) {
              sender.sendMessage(SlackMessages.playerNotLinked());
              return;
            }

            sender.sendMessage(SlackMessages.slackUsername(lookupPlayer.getName(),
                SlackBot.displayNameOf(slackUser)));
          } catch (IOException e) {
            sender.sendMessage(SlackMessages.lookupError());
          }
        });
        return true;
      }
      default -> {
        return false;
      }
    }
  }

  @Override
  public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
      @NotNull String alias, String[] args) {
    if (args.length > 1 && args[0].equalsIgnoreCase("admin")) {
      return this.adminCommand.onTabComplete(sender, Arrays.copyOfRange(args, 1, args.length));
    }
    if (!(sender instanceof Player sendingPlayer)) {
      return null;
    }

    List<String> completions = new ArrayList<>();
    switch (args.length) {
      case 1 -> {
        List<String> subcommands = new ArrayList<>(List.of("info", "link", "unlink", "lookup"));
        if (sender.hasPermission(AdminCommand.PERMISSION)) {
          subcommands.add("admin");
        }
        StringUtil.copyPartialMatches(args[0], subcommands, completions);
      }
      case 2 -> {
        if (args[0].equalsIgnoreCase("lookup")) {
          for (Player player : sender.getServer().getOnlinePlayers()) {
            if (!sendingPlayer.canSee(player)) {
              continue;
            }
            if (StringUtil.startsWithIgnoreCase(player.getName(), args[1])) {
              completions.add(player.getName());
            }
          }
        }
      }
    }

    Collections.sort(completions);
    return completions;
  }

  /** Sends the player their web link, or says linking isn't set up. */
  private void sendLinkPrompt(Player player) {
    LinkWebServer web = this.plugin.getLinkWebServer();
    SlackBot bot = this.plugin.getSlackBot();
    if (web == null || bot == null) {
      player.sendMessage(SlackMessages.linkingUnavailable());
      return;
    }
    LinkAddress address = web.createLink(player.getUniqueId(), player.getName(),
        bot.getLinkCodeExpirationMillis());
    player.sendMessage(SlackMessages.linkPrompt(address, !isBedrock(player.getUniqueId()),
        bot.getLinkCodeExpirationMillis()));
  }

  /** Floodgate gives Bedrock players UUIDs with the top 64 bits zero; their chat can't click links. */
  static boolean isBedrock(UUID uuid) {
    return uuid.getMostSignificantBits() == 0;
  }

  // Slack API calls are network requests, so keep them off the server thread
  private void async(Runnable task) {
    this.plugin.getServer().getScheduler().runTaskAsynchronously(this.plugin, task);
  }
}
