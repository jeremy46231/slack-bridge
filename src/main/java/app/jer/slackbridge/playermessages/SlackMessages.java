package app.jer.slackbridge.playermessages;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.minimessage.MiniMessage.miniMessage;

import com.slack.api.model.User;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import app.jer.slackbridge.web.LinkWebServer.LinkAddress;
import org.bukkit.entity.Player;

/**
 * In-game messages, collected from HCCore's playermessages/slack/* and
 * playermessages/player/MustLinkMessage.
 */
public final class SlackMessages {

  private SlackMessages() {
  }

  public static Component slackChat(Component playerComponent, Component message) {
    return miniMessage().deserialize("""
            <blue>[Slack]</blue> \\<<playercomponent>> <message>""",
        TagResolver.resolver(Placeholder.component("playercomponent", playerComponent),
            Placeholder.component("message", message)));
  }

  /** The disconnect screen for an unlinked player; it can't be clicked, so the address is typed. */
  public static Component mustLink(LinkAddress address, long ttlMillis) {
    return miniMessage().deserialize("""
            <b><red>Link your Slack account to join</red></b>

            Go to <gold><address></gold>

            <gray>Expires in <minutes>.</gray>""",
        TagResolver.resolver(Placeholder.unparsed("address", address.typeable()),
            Placeholder.unparsed("minutes", minutes(ttlMillis))));
  }

  public static Component mustBeInChannel(String channelName, String slackName) {
    return miniMessage().deserialize("""
            <b><red>Only members of <channel> in <slackname> can join this server!</red></b>
            Your linked Slack account isn't in that channel. Ask someone there to add you, then try again.""",
        TagResolver.resolver(Placeholder.unparsed("channel", channelName),
            Placeholder.unparsed("slackname", slackName)));
  }

  public static Component slackRequiredButUnavailable() {
    return miniMessage().deserialize("""
        <red>This server requires a linked Slack account, but it can't reach Slack right now.
        Try again in a minute. If this keeps happening, please let a server admin know.</red>""");
  }

  /** @param clickable false for Bedrock players, whose chat can't open links */
  public static Component linkPrompt(LinkAddress address, boolean clickable, long ttlMillis) {
    // MiniMessage doesn't fill placeholders into click arguments, so the link is built here
    Component link = text(address.typeable(), NamedTextColor.GOLD);
    if (clickable) {
      link = text().append(link.decorate(TextDecoration.UNDERLINED))
          .append(text(" (click to open)", NamedTextColor.GRAY))
          .clickEvent(ClickEvent.openUrl(address.url())).hoverEvent(text("Open in your browser"))
          .build();
    }
    return miniMessage().deserialize("""
            <green>Link your Slack account:</green> <link>
            <gray>Expires in <minutes>.</gray>""",
        TagResolver.resolver(Placeholder.component("link", link),
            Placeholder.unparsed("minutes", minutes(ttlMillis))));
  }

  public static Component linkingUnavailable() {
    return miniMessage().deserialize(
        "<red>Slack linking isn't set up on this server yet. Ask a server admin.</red>");
  }

  private static String minutes(long millis) {
    long minutes = Math.max(1, (millis + 59_999) / 60_000);
    return minutes == 1 ? "1 minute" : minutes + " minutes";
  }

  public static Component linked(Player player, User linkedSlackUser, boolean postsAsUser) {
    Component slackInvalid = miniMessage().deserialize(
        "<b><red><hover:show_text:'Could not load the Slack account. It may have been deactivated.'>Unknown</hover></red></b>");

    Component slackNameComponent =
        linkedSlackUser == null ? slackInvalid : text(linkedSlackUser.getRealName());
    Component slackUserComponent =
        linkedSlackUser == null ? slackInvalid : text(linkedSlackUser.getName());
    Component slackIdComponent =
        linkedSlackUser == null ? slackInvalid : text(linkedSlackUser.getId());

    return miniMessage().deserialize("""
            <b><green>Your Minecraft account is linked to Slack!</green></b>
            <hover:show_text:'<b>Your Minecraft account</b>
            Minecraft UUID: <mcuuid>'><mcname></hover> <b><--></b> <hover:show_text:'<b>Your Slack account</b>
            Slack Name: <slackname>
            Slack ID: <slackid>'><slackuser></hover>
            <gray>Your chat shows in Slack <mode>.</gray>""",
        TagResolver.resolver(Placeholder.component("mcname", player.displayName()),
            Placeholder.unparsed("mcuuid", player.getUniqueId().toString()),
            Placeholder.component("slackname", slackNameComponent),
            Placeholder.component("slackuser", slackUserComponent),
            Placeholder.component("slackid", slackIdComponent),
            Placeholder.unparsed("mode", postsAsUser ? "as messages from you"
                : "under your Slack name and photo, posted by the bot")));
  }

  public static Component slackUsername(String playerName, String slackName) {
    return miniMessage().deserialize("""
            <green><player> is linked to <username> on Slack</green>""",
        TagResolver.resolver(Placeholder.unparsed("player", playerName),
            Placeholder.unparsed("username", slackName)));
  }

  public static Component accountLinked() {
    return miniMessage().deserialize("<green>Your accounts have been linked!</green>");
  }

  public static Component linkUndone() {
    return miniMessage().deserialize(
        "<red>The Slack account you linked said it wasn't you, so the link was removed.</red>");
  }

  public static Component accountUnlinked() {
    return miniMessage().deserialize("<green>Your Slack account has been unlinked.</green>");
  }

  public static Component notLinkedSelf() {
    return miniMessage().deserialize("<red>Your account is not linked to Slack.</red>");
  }

  public static Component invalidSlack() {
    return miniMessage().deserialize("<red>Couldn't find a Slack user with that ID. Member IDs start with U.</red>");
  }

  public static Component lookupError() {
    return miniMessage().deserialize(
        "<red>There was an error while looking up that player.</red>");
  }

  public static Component playerNotLinked() {
    return miniMessage().deserialize(
        "<red>That player has not linked their Slack account.</red>");
  }

  public static Component playerHasntPlayed() {
    return miniMessage().deserialize("<red>That player has not played on this server!</red>");
  }

  public static Component slackDisabled() {
    return miniMessage().deserialize("<red>Slack integration is not running.</red>");
  }

  public static Component noPermission() {
    return miniMessage().deserialize("<red>You don't have permission to do that.</red>");
  }

  public static Component adminUsage() {
    return miniMessage().deserialize("""
        <gold><b>/slack admin</b></gold>
        <green>/slack admin link <player or uuid> <slack member id></green> <gray>link without the Slack confirmation</gray>
        <green>/slack admin unlink <player or uuid></green> <gray>remove someone's link</gray>
        <green>/slack admin list</green> <gray>show every linked account</gray>
        <green>/slack admin status</green> <gray>show the Slack connection and settings</gray>""");
  }

  public static Component adminPlayerNotFound(String query) {
    return miniMessage().deserialize("<red>Couldn't find a Minecraft player called <query>.</red>",
        Placeholder.unparsed("query", query));
  }

  public static Component adminLinked(String playerName, String slackName) {
    return miniMessage().deserialize("<green>Linked <player> to <slackname> on Slack.</green>",
        TagResolver.resolver(Placeholder.unparsed("player", playerName),
            Placeholder.unparsed("slackname", slackName)));
  }

  public static Component adminUnlinked(String playerName) {
    return miniMessage().deserialize("<green>Unlinked <player>'s Slack account.</green>",
        Placeholder.unparsed("player", playerName));
  }

  public static Component adminListHeader(int count) {
    return miniMessage().deserialize("<gold><b>Linked accounts</b></gold> <gray>(<count>)</gray>",
        Placeholder.unparsed("count", String.valueOf(count)));
  }

  public static Component adminListEntry(String playerName, String slackId, String slackName,
      boolean postsAsUser) {
    return miniMessage().deserialize("<player> <gray>-></gray> <slack><postsasuser>",
        TagResolver.resolver(Placeholder.unparsed("player", playerName),
            Placeholder.unparsed("slack",
                slackName == null ? slackId : slackName + " (" + slackId + ")"),
            Placeholder.parsed("postsasuser", postsAsUser ? " <gray>(posts as them)</gray>" : "")));
  }

  public static Component adminStatus(String connection, String channel, String command,
      String joinRequirement, String clickToLink, int links, int postingAsThemselves) {
    return miniMessage().deserialize("""
            <gold><b>SlackBridge status</b></gold>
            Slack: <connection>
            Channel: <channel>
            Slash command: <command>
            Join requirement: <joinrequirement>
            Web linking: <clicktolink>
            Linked accounts: <links> (<postingasthemselves> posting as themselves)""",
        TagResolver.resolver(Placeholder.unparsed("connection", connection),
            Placeholder.unparsed("channel", channel.isEmpty() ? "(not set)" : channel),
            Placeholder.unparsed("command", command),
            Placeholder.unparsed("joinrequirement", joinRequirement),
            Placeholder.unparsed("clicktolink", clickToLink),
            Placeholder.unparsed("links", String.valueOf(links)),
            Placeholder.unparsed("postingasthemselves", String.valueOf(postingAsThemselves))));
  }

  public static Component mustBePlayer() {
    return miniMessage().deserialize("<red>You must be a player to use this.</red>");
  }
}
