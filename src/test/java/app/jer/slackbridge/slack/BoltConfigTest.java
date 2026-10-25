package app.jer.slackbridge.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.slack.api.bolt.AppConfig;
import org.junit.jupiter.api.Test;

class BoltConfigTest {

  @Test
  void staysSingleWorkspaceWhenOAuthEnvVarsAreSet() {
    // build.gradle.kts sets these for tests, as the server does for click-to-link
    assertNotNull(System.getenv("SLACK_CLIENT_ID"));
    assertNotNull(System.getenv("SLACK_CLIENT_SECRET"));
    // Without the fix, Bolt's defaults pick them up and switch to multi-workspace mode
    assertTrue(AppConfig.builder().singleTeamBotToken("xoxb-test").build().isDistributedApp());

    AppConfig config = BoltConfig.singleWorkspace("xoxb-test");

    assertFalse(config.isDistributedApp());
    assertEquals("xoxb-test", config.getSingleTeamBotToken());
    assertNull(config.getClientId());
    assertNull(config.getClientSecret());
    assertNull(config.getScope());
    assertNull(config.getUserScope());
    assertNull(config.getRedirectUri());
  }
}
