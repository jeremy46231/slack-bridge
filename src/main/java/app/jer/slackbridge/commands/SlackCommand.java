package app.jer.slackbridge.commands;

import app.jer.slackbridge.SlackBridgePlugin;
import app.jer.slackbridge.playermessages.SlackMessages;
import app.jer.slackbridge.slack.SlackBot;
import com.slack.api.model.User;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
        String slackId = this.plugin.getLinkStore().getSlackId(player.getUniqueId());
        if (slackId == null) {
          // unlinked, show slack join + link info
          sender.sendMessage(SlackMessages.unlinked(bot.getCommandBase(), bot.getSlackName(),
              bot.getSlackIdHelp()));
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
          player.sendMessage(SlackMessages.linked(player, slackUser));
        });
        return true;
      }
      case "link" -> {
        if (!(sender instanceof Player player)) {
          sender.sendMessage(SlackMessages.mustBePlayer());
          return true;
        }

        if (args.length == 1) {
          // Code flow: the player runs /<base-command> link <code> in Slack
          String code = bot.generateVerificationCode(player.getUniqueId(), player.getName());
          player.sendMessage(SlackMessages.linkCode(bot.getCommandBase(), bot.getSlackName(), code,
              bot.getLinkCodeExpirationSeconds()));
          return true;
        }

        if (args.length != 2) {
          return false;
        }

        // Verification flow: the Slack user gets a DM with Verify/Deny buttons
        String slackId = args[1];
        this.async(() -> {
          try {
            if (bot.getUserInfo(slackId) == null) {
              player.sendMessage(SlackMessages.invalidSlack());
              return;
            }

            boolean sent = bot.sendVerificationMessage(slackId, player.getName(),
                player.getUniqueId().toString());

            if (sent) {
              player.sendMessage(SlackMessages.verificationSent());
            } else {
              player.sendMessage(SlackMessages.verificationError());
            }
          } catch (IOException e) {
            player.sendMessage(SlackMessages.linkError());
          }
        });
        return true;
      }
      case "unlink" -> {
        if (!(sender instanceof Player player)) {
          sender.sendMessage(SlackMessages.mustBePlayer());
          return true;
        }
        if (this.plugin.getLinkStore().removeLink(player.getUniqueId())) {
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

  // Slack API calls are network requests, so keep them off the server thread
  private void async(Runnable task) {
    this.plugin.getServer().getScheduler().runTaskAsynchronously(this.plugin, task);
  }
}
