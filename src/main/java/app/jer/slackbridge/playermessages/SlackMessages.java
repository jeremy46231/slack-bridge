package app.jer.slackbridge.playermessages;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.minimessage.MiniMessage.miniMessage;

import com.slack.api.model.User;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
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
            <blue>[Slack]</blue> <playercomponent><gold> » </gold><message>""",
        TagResolver.resolver(Placeholder.component("playercomponent", playerComponent),
            Placeholder.component("message", message)));
  }

  public static Component mustLink(String baseCommand, String slackName, String code, int seconds) {
    return miniMessage().deserialize("""
            <b><red>You must link your Slack account to join the server!</red></b>
            Please run <gold>/<basecommand> link <code></gold> in <slackname> to link your account.

            <i>This code expires in <seconds> seconds.</i>""",
        TagResolver.resolver(Placeholder.unparsed("basecommand", baseCommand),
            Placeholder.unparsed("slackname", slackName),
            Placeholder.unparsed("code", code),
            Placeholder.unparsed("seconds", String.valueOf(seconds))));
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

  public static Component linkCode(String baseCommand, String slackName, String code, int seconds) {
    return miniMessage().deserialize("""
            <green>Run this in <slackname> to link your account:</green>
            <click:copy_to_clipboard:'/<basecommand> link <code>'><hover:show_text:'Click to copy'><gold>/<basecommand> link <code></gold> <gray>(click to copy)</gray></hover></click>
            <i>This code expires in <seconds> seconds.</i>""",
        TagResolver.resolver(Placeholder.unparsed("basecommand", baseCommand),
            Placeholder.unparsed("slackname", slackName),
            Placeholder.unparsed("code", code),
            Placeholder.unparsed("seconds", String.valueOf(seconds))));
  }

  /**
   * @param slackIdHelp MiniMessage text from the config explaining how to find a Slack member ID
   */
  public static Component unlinked(String baseCommand, String slackName, String slackIdHelp) {
    return miniMessage().deserialize("""
            <b><red>Link your Minecraft account with <slackname></red></b>
            <gold><b>Either:</b></gold> run <click:run_command:'/slack link'><hover:show_text:'Click to run'><green>/slack link</green> <gray>(click to run)</gray></hover></click> to get a code, then run <gold>/<basecommand> link <code></gold> in <slackname>
            <gold><b>Or:</b></gold> run <click:suggest_command:'/slack link '><hover:show_text:'Click to fill in'><green>/slack link <your slack member id></green> <gray>(click to fill in)</gray></hover></click>
               <idhelp>""",
        TagResolver.resolver(Placeholder.unparsed("basecommand", baseCommand),
            Placeholder.unparsed("slackname", slackName),
            Placeholder.parsed("idhelp", slackIdHelp),
            Placeholder.unparsed("code", "<code>")));
  }

  public static Component linked(Player player, User linkedSlackUser) {
    Component slackInvalid = miniMessage().deserialize(
        "<b><red><hover:show_text:'Could not load the Slack account. It may have been deactivated.'>Unknown</hover></red></b>");

    Component slackNameComponent =
        linkedSlackUser == null ? slackInvalid : text(linkedSlackUser.getRealName());
    Component slackUserComponent =
        linkedSlackUser == null ? slackInvalid : text(linkedSlackUser.getName());
    Component slackIdComponent =
        linkedSlackUser == null ? slackInvalid : text(linkedSlackUser.getId());

    return miniMessage().deserialize("""
            <b><green>Your MC account is linked to Slack!</green></b>
            <hover:show_text:'<b>Your MC account</b>
            MC UUID: <mcuuid>'><mcname></hover> <b><--></b> <hover:show_text:'<b>Your Slack account</b>
            Slack Name: <slackname>
            Slack ID: <slackid>'><slackuser></hover>""",
        TagResolver.resolver(Placeholder.component("mcname", player.displayName()),
            Placeholder.unparsed("mcuuid", player.getUniqueId().toString()),
            Placeholder.component("slackname", slackNameComponent),
            Placeholder.component("slackuser", slackUserComponent),
            Placeholder.component("slackid", slackIdComponent)));
  }

  public static Component slackUsername(String playerName, String slackName) {
    return miniMessage().deserialize("""
            <green>Slack username for <player>: <username></green>""",
        TagResolver.resolver(Placeholder.unparsed("player", playerName),
            Placeholder.unparsed("username", slackName)));
  }

  public static Component accountLinked() {
    return miniMessage().deserialize("<green>Your accounts have been linked!</green>");
  }

  public static Component accountUnlinked() {
    return miniMessage().deserialize("<green>Your Slack account has been unlinked.</green>");
  }

  public static Component notLinkedSelf() {
    return miniMessage().deserialize("<red>Your account is not linked to Slack.</red>");
  }

  public static Component invalidSlack() {
    return miniMessage().deserialize("<red>That Slack ID is invalid!</red>");
  }

  public static Component linkDenied() {
    return miniMessage().deserialize("<red>The request to link the account was denied.</red>");
  }

  public static Component linkError() {
    return miniMessage().deserialize("<red>An error occurred while linking your account.</red>");
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

  public static Component verificationError() {
    return miniMessage().deserialize(
        "<red>An error occurred while sending the verification message.</red>");
  }

  public static Component verificationSent() {
    return miniMessage().deserialize(
        "<green>A verification message has been sent to your Slack account.</green>");
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

  public static Component adminListEntry(String playerName, String slackId, String slackName) {
    return miniMessage().deserialize("<player> <gray>→</gray> <slack>",
        TagResolver.resolver(Placeholder.unparsed("player", playerName),
            Placeholder.unparsed("slack",
                slackName == null ? slackId : slackName + " (" + slackId + ")")));
  }

  public static Component adminStatus(String connection, String channel, String command,
      String joinRequirement, int links) {
    return miniMessage().deserialize("""
            <gold><b>SlackBridge status</b></gold>
            Slack: <connection>
            Channel: <channel>
            Slash command: <command>
            Join requirement: <joinrequirement>
            Linked accounts: <links>""",
        TagResolver.resolver(Placeholder.unparsed("connection", connection),
            Placeholder.unparsed("channel", channel.isEmpty() ? "(not set)" : channel),
            Placeholder.unparsed("command", command),
            Placeholder.unparsed("joinrequirement", joinRequirement),
            Placeholder.unparsed("links", String.valueOf(links))));
  }

  public static Component mustBePlayer() {
    return miniMessage().deserialize("<red>You must be a player to use this.</red>");
  }
}
