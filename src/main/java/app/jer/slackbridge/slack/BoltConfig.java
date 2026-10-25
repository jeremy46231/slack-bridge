package app.jer.slackbridge.slack;

import com.slack.api.bolt.AppConfig;

/** Builds Bolt's configuration for this plugin. */
public final class BoltConfig {

  private BoltConfig() {
  }

  /**
   * A single-workspace app that uses the given bot token.
   *
   * <p>Bolt fills unset fields from SLACK_* environment variables, and treats any app with both a
   * client ID and secret as a multi-workspace OAuth app that looks up installations instead of
   * using the bot token. Click-to-link uses SLACK_CLIENT_ID and SLACK_CLIENT_SECRET for its own
   * OAuth flow, so the OAuth fields are cleared here to keep Bolt out of that mode.
   */
  public static AppConfig singleWorkspace(String botToken) {
    return AppConfig.builder()
        .singleTeamBotToken(botToken)
        .clientId(null)
        .clientSecret(null)
        .scope(null)
        .userScope(null)
        .redirectUri(null)
        .oAuthStartEnabled(false)
        .oAuthCallbackEnabled(false)
        .build();
  }
}
