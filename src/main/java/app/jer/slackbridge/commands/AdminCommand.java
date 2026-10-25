package app.jer.slackbridge.commands;

import app.jer.slackbridge.LinkStore.Link;
import app.jer.slackbridge.SlackBridgePlugin;
import app.jer.slackbridge.playermessages.SlackMessages;
import app.jer.slackbridge.slack.SlackBot;
import app.jer.slackbridge.web.LinkWebServer;
import com.destroystokyo.paper.profile.PlayerProfile;
import com.slack.api.model.User;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

/** /slack admin: manage other players' links. Needs slackbridge.admin (ops by default). */
public class AdminCommand {

  public static final String PERMISSION = "slackbridge.admin";

  private record Target(UUID uuid, String name) {

  }

  private final SlackBridgePlugin plugin;

  public AdminCommand(SlackBridgePlugin plugin) {
    this.plugin = plugin;
  }

  /** @param args the arguments after "admin" */
  public boolean onCommand(CommandSender sender, String[] args) {
    if (!sender.hasPermission(PERMISSION)) {
      sender.sendMessage(SlackMessages.noPermission());
      return true;
    }
    if (args.length == 0) {
      sender.sendMessage(SlackMessages.adminUsage());
      return true;
    }

    switch (args[0].toLowerCase(Locale.ROOT)) {
      case "link" -> {
        if (args.length != 3) {
          sender.sendMessage(SlackMessages.adminUsage());
          return true;
        }
        SlackBot bot = this.plugin.getSlackBot();
        if (bot == null) {
          sender.sendMessage(SlackMessages.slackDisabled());
          return true;
        }
        String slackId = args[2];
        this.async(() -> {
          Target target = this.resolve(args[1]);
          if (target == null) {
            sender.sendMessage(SlackMessages.adminPlayerNotFound(args[1]));
            return;
          }
          User slackUser;
          try {
            slackUser = bot.getUserInfo(slackId);
          } catch (IOException e) {
            sender.sendMessage(SlackMessages.lookupError());
            return;
          }
          if (slackUser == null) {
            sender.sendMessage(SlackMessages.invalidSlack());
            return;
          }

          this.plugin.link(target.uuid(), slackUser.getId(), target.name());
          this.plugin.getLogger().info("%s linked %s to Slack user %s".formatted(sender.getName(),
              target.name(), slackUser.getId()));
          sender.sendMessage(SlackMessages.adminLinked(displayName(target),
              SlackBot.displayNameOf(slackUser)));
          Player player = this.plugin.getServer().getPlayer(target.uuid());
          if (player != null) {
            player.sendMessage(SlackMessages.accountLinked());
          }
        });
        return true;
      }
      case "unlink" -> {
        if (args.length != 2) {
          sender.sendMessage(SlackMessages.adminUsage());
          return true;
        }
        this.async(() -> {
          Target target = this.resolve(args[1]);
          if (target == null || !this.plugin.unlink(target.uuid())) {
            sender.sendMessage(SlackMessages.playerNotLinked());
            return;
          }
          this.plugin.getLogger().info("%s unlinked %s".formatted(sender.getName(), target.name()));
          sender.sendMessage(SlackMessages.adminUnlinked(displayName(target)));
        });
        return true;
      }
      case "list" -> {
        this.async(() -> {
          Map<UUID, Link> links = this.plugin.getLinkStore().getLinks();
          SlackBot bot = this.plugin.getSlackBot();
          sender.sendMessage(SlackMessages.adminListHeader(links.size()));
          links.entrySet().stream()
              .sorted(Comparator.comparing(entry -> String.valueOf(entry.getValue().name()),
                  String.CASE_INSENSITIVE_ORDER))
              .forEach(entry -> {
                Link link = entry.getValue();
                String slackName = null;
                if (bot != null) {
                  try {
                    User user = bot.getUserInfo(link.slackId());
                    slackName = user == null ? null : SlackBot.displayNameOf(user);
                  } catch (IOException ignored) {
                    // Show the ID alone
                  }
                }
                sender.sendMessage(SlackMessages.adminListEntry(
                    link.name() != null ? link.name() : entry.getKey().toString(), link.slackId(),
                    slackName, link.userToken() != null));
              });
        });
        return true;
      }
      case "status" -> {
        this.async(() -> {
          SlackBot bot = this.plugin.getSlackBot();
          LinkWebServer web = this.plugin.getLinkWebServer();
          Map<UUID, Link> links = this.plugin.getLinkStore().getLinks();
          boolean enabled = this.plugin.getConfig().getBoolean("settings.slack-link.enabled", false);
          String connection = bot != null ? "connected" : enabled ? "not connected" : "disabled";
          String channel = bot == null ? this.plugin.getSetting("channel-id", "SLACK_CHANNEL_ID")
              : bot.getChannelName() + " (" + bot.getChannelId() + ")";
          String command = bot == null ? this.plugin.getSetting("base-command", "SLACK_COMMAND")
              : bot.getCommandBase();
          sender.sendMessage(SlackMessages.adminStatus(connection, channel,
              command.isEmpty() ? "(not set)" : "/" + command.replaceFirst("^/", ""),
              this.plugin.getJoinRequirement().name().toLowerCase(Locale.ROOT),
              web == null ? "not set up, players can't link" : web.getPublicUrl() + "/slack"
                  + (this.plugin.getTokenCipher() == null ? " (linking only)" : ""),
              links.size(),
              (int) links.values().stream().filter(link -> link.userToken() != null).count()));
        });
        return true;
      }
      default -> {
        sender.sendMessage(SlackMessages.adminUsage());
        return true;
      }
    }
  }

  /** @param args the arguments after "admin" */
  public List<String> onTabComplete(CommandSender sender, String[] args) {
    List<String> completions = new ArrayList<>();
    if (!sender.hasPermission(PERMISSION)) {
      return completions;
    }

    if (args.length == 1) {
      StringUtil.copyPartialMatches(args[0], List.of("link", "unlink", "list", "status"),
          completions);
    } else if (args.length == 2 && args[0].equalsIgnoreCase("link")) {
      for (Player player : this.plugin.getServer().getOnlinePlayers()) {
        if (StringUtil.startsWithIgnoreCase(player.getName(), args[1])) {
          completions.add(player.getName());
        }
      }
    } else if (args.length == 2 && args[0].equalsIgnoreCase("unlink")) {
      for (Link link : this.plugin.getLinkStore().getLinks().values()) {
        if (link.name() != null && StringUtil.startsWithIgnoreCase(link.name(), args[1])) {
          completions.add(link.name());
        }
      }
    }
    completions.sort(String.CASE_INSENSITIVE_ORDER);
    return completions;
  }

  /**
   * Finds a player by UUID or name: first among linked and known players, then (for players who
   * have never joined, e.g. because they were turned away) by asking Mojang. Blocks, so call it off
   * the server thread.
   */
  private Target resolve(String query) {
    try {
      UUID uuid = UUID.fromString(query);
      OfflinePlayer player = this.plugin.getServer().getOfflinePlayer(uuid);
      Link link = this.plugin.getLinkStore().getLinks().get(uuid);
      return new Target(uuid, player.getName() != null ? player.getName()
          : link != null ? link.name() : null);
    } catch (IllegalArgumentException notAUuid) {
      // Fall through to a name lookup
    }

    Optional<Entry<UUID, Link>> linked = this.plugin.getLinkStore().findByName(query);
    if (linked.isPresent()) {
      return new Target(linked.get().getKey(), linked.get().getValue().name());
    }

    OfflinePlayer cached = this.plugin.getServer().getOfflinePlayerIfCached(query);
    if (cached != null) {
      return new Target(cached.getUniqueId(), cached.getName());
    }

    PlayerProfile profile = this.plugin.getServer().createProfile(query);
    if (profile.complete(false) && profile.getId() != null) {
      return new Target(profile.getId(), profile.getName());
    }
    return null;
  }

  private static String displayName(Target target) {
    return target.name() != null ? target.name() : target.uuid().toString();
  }

  private void async(Runnable task) {
    this.plugin.getServer().getScheduler().runTaskAsynchronously(this.plugin, task);
  }
}
