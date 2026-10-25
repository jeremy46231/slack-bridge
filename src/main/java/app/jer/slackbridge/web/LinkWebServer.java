package app.jer.slackbridge.web;

import app.jer.slackbridge.SlackBridgePlugin;
import app.jer.slackbridge.slack.SlackBot;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.response.oauth.OAuthV2AccessResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * Click-to-link: a small web server for linking through Slack's OAuth page instead of pasting a
 * code. Players get a one-time link in game; it shows which Minecraft account is being linked,
 * then sends them to Slack, where they can also let the bridge post their chat as them (an
 * optional chat:write user scope they can uncheck). Slack redirects back to /slack/callback.
 *
 * <p>Serves /slack/link, /slack/authorize and /slack/callback; route the public URL's /slack path
 * to this server's port.
 */
public class LinkWebServer {

  /** Always requested, so Slack says who the user is even if they decline posting as themselves */
  private static final String IDENTITY_SCOPE = "users:read";
  public static final String POST_AS_USER_SCOPE = "chat:write";

  private record PendingLink(UUID uuid, String name, long expiresAt) {

  }

  private final SlackBridgePlugin plugin;
  private final String publicUrl;
  private final String clientId;
  private final String clientSecret;
  private final HttpServer server;
  private final ExecutorService executor;
  private final Map<String, PendingLink> pendingLinks = new ConcurrentHashMap<>();
  private final SecureRandom random = new SecureRandom();

  public LinkWebServer(SlackBridgePlugin plugin, String publicUrl, int port, String clientId,
      String clientSecret) throws IOException {
    this.plugin = plugin;
    this.publicUrl = publicUrl.replaceFirst("/+$", "");
    this.clientId = clientId;
    this.clientSecret = clientSecret;

    this.server = HttpServer.create(new InetSocketAddress(port), 0);
    this.server.createContext("/slack/link", this::handleLink);
    this.server.createContext("/slack/authorize", this::handleAuthorize);
    this.server.createContext("/slack/callback", this::handleCallback);
    this.executor = Executors.newFixedThreadPool(2,
        runnable -> Thread.ofPlatform().name("SlackBridge-web").daemon().unstarted(runnable));
    this.server.setExecutor(this.executor);
    this.server.start();
  }

  public void stop() {
    this.server.stop(0);
    this.executor.shutdownNow();
  }

  public String getPublicUrl() {
    return this.publicUrl;
  }

  /** @return a one-time URL that links the given player when opened, valid for ttlMillis */
  public String createLinkUrl(UUID uuid, String name, long ttlMillis) {
    long now = System.currentTimeMillis();
    this.pendingLinks.values()
        .removeIf(pending -> pending.expiresAt() < now || pending.uuid().equals(uuid));

    byte[] bytes = new byte[24];
    this.random.nextBytes(bytes);
    String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    this.pendingLinks.put(state, new PendingLink(uuid, name, now + ttlMillis));
    return this.publicUrl + "/slack/link?state=" + state;
  }

  private PendingLink getPending(String state) {
    PendingLink pending = state == null ? null : this.pendingLinks.get(state);
    return pending == null || pending.expiresAt() < System.currentTimeMillis() ? null : pending;
  }

  private boolean canPostAsUser() {
    return this.plugin.getTokenCipher() != null;
  }

  /** Step 1: show which Minecraft account will be linked, so nobody links someone else's by accident */
  private void handleLink(HttpExchange exchange) throws IOException {
    if (!exchange.getRequestMethod().equals("GET")) {
      this.respond(exchange, 405, "Not allowed", "<p>Open this link from Minecraft.</p>");
      return;
    }
    String state = query(exchange).get("state");
    PendingLink pending = this.getPending(state);
    if (pending == null) {
      this.respondExpired(exchange);
      return;
    }

    this.respond(exchange, 200, "Link %s to Slack?".formatted(pending.name()),
        linkPageBody(pending.name(), this.publicUrl, state));
  }

  static String linkPageBody(String name, String publicUrl, String state) {
    return """
        <p>Only continue if %1$s is your Minecraft account.</p>
        <form method="post" action="%2$s/slack/authorize">
          <input type="hidden" name="state" value="%3$s">
          <button type="submit">Continue to Slack</button>
        </form>""".formatted(html(name), html(publicUrl), html(state));
  }

  /** Step 2: forget any old post-as-you permission (so the new choice counts), then go to Slack */
  private void handleAuthorize(HttpExchange exchange) throws IOException {
    if (!exchange.getRequestMethod().equals("POST")) {
      this.respond(exchange, 405, "Not allowed", "<p>Open this link from Minecraft.</p>");
      return;
    }
    String body = new String(exchange.getRequestBody().readNBytes(4096), StandardCharsets.UTF_8);
    String state = parseForm(body).get("state");
    PendingLink pending = this.getPending(state);
    SlackBot bot = this.plugin.getSlackBot();
    if (pending == null) {
      this.respondExpired(exchange);
      return;
    }
    if (bot == null) {
      this.respondUnavailable(exchange);
      return;
    }

    this.plugin.dropUserToken(pending.uuid());

    String scopes = this.canPostAsUser() ? IDENTITY_SCOPE + "," + POST_AS_USER_SCOPE : IDENTITY_SCOPE;
    String url = "https://slack.com/oauth/v2/authorize?client_id=" + encode(this.clientId)
        + "&user_scope=" + encode(scopes) + "&redirect_uri=" + encode(this.redirectUri())
        + "&state=" + encode(state) + "&team=" + encode(bot.getTeamId());
    exchange.getResponseHeaders().set("Location", url);
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(303, -1);
    exchange.close();
  }

  /** Step 3: Slack sends the user back here with a code to exchange for their identity and token */
  private void handleCallback(HttpExchange exchange) throws IOException {
    Map<String, String> query = query(exchange);
    String state = query.get("state");
    PendingLink pending = this.getPending(state);
    if (pending == null) {
      this.respondExpired(exchange);
      return;
    }
    // One use only
    this.pendingLinks.remove(state);

    if (query.containsKey("error") || !query.containsKey("code")) {
      this.respond(exchange, 200, "Canceled", "<p>Nothing was linked.</p>");
      return;
    }
    SlackBot bot = this.plugin.getSlackBot();
    if (bot == null) {
      this.respondUnavailable(exchange);
      return;
    }

    OAuthV2AccessResponse res;
    try {
      res = bot.getClient().oauthV2Access(r -> r.clientId(this.clientId)
          .clientSecret(this.clientSecret).code(query.get("code")).redirectUri(this.redirectUri()));
    } catch (IOException | SlackApiException e) {
      this.plugin.getLogger().log(Level.WARNING, "Slack OAuth exchange failed", e);
      this.respondError(exchange);
      return;
    }
    if (!res.isOk() || res.getAuthedUser() == null || res.getAuthedUser().getId() == null) {
      this.plugin.getLogger().warning("Slack OAuth exchange failed: " + res.getError());
      this.respondError(exchange);
      return;
    }

    String userToken = res.getAuthedUser().getAccessToken();
    if (res.getTeam() == null || !bot.getTeamId().equals(res.getTeam().getId())) {
      // Someone authorized from another workspace; don't link, and don't keep their token
      bot.revokeUserToken(userToken);
      this.respond(exchange, 200, "Wrong workspace",
          "<p>That Slack account is in a different workspace. Nothing was linked.</p>");
      return;
    }

    List<String> granted = res.getAuthedUser().getScope() == null ? List.of()
        : Arrays.asList(res.getAuthedUser().getScope().split(","));
    boolean postsAsUser = this.canPostAsUser() && granted.contains(POST_AS_USER_SCOPE);
    bot.completeOAuthLink(pending.uuid(), pending.name(), res.getAuthedUser().getId(), userToken,
        postsAsUser);

    this.respond(exchange, 200, "Linked",
        "<p>%s is linked to your Slack account. You can close this tab.</p>"
            .formatted(html(pending.name())));
  }

  private String redirectUri() {
    return this.publicUrl + "/slack/callback";
  }

  private void respondExpired(HttpExchange exchange) throws IOException {
    this.respond(exchange, 410, "Link expired",
        "<p>Run <code>/slack link</code> in Minecraft for a new one.</p>");
  }

  private void respondUnavailable(HttpExchange exchange) throws IOException {
    this.respond(exchange, 503, "Try again soon", "<p>The server can't reach Slack right now.</p>");
  }

  private void respondError(HttpExchange exchange) throws IOException {
    this.respond(exchange, 502, "Something went wrong",
        "<p>Nothing was linked. Run <code>/slack link</code> in Minecraft to try again.</p>");
  }

  private void respond(HttpExchange exchange, int status, String title, String body)
      throws IOException {
    byte[] page = page(title, body).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
    exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
    exchange.sendResponseHeaders(status, page.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(page);
    }
  }

  static String page(String title, String body) {
    return """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>%1$s</title>
          <style>
            body { font: 18px/1.5 system-ui, sans-serif; max-width: 26rem; margin: 20vh auto 0;
              padding: 0 1rem; text-align: center; color: #1d1c1d; background: #fff; }
            h1 { font-size: 1.5rem; }
            button { font: inherit; padding: .6rem 1.4rem; border: 0; border-radius: .4rem;
              background: #007a5a; color: #fff; cursor: pointer; }
            @media (prefers-color-scheme: dark) { body { color: #e8e8e8; background: #1a1d21; } }
          </style>
        </head>
        <body>
          <h1>%1$s</h1>
          %2$s
        </body>
        </html>
        """.formatted(html(title), body);
  }

  private static Map<String, String> query(HttpExchange exchange) {
    String raw = exchange.getRequestURI().getRawQuery();
    return raw == null ? Map.of() : parseForm(raw);
  }

  private static Map<String, String> parseForm(String raw) {
    Map<String, String> values = new HashMap<>();
    for (String pair : raw.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0) {
        values.putIfAbsent(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
      }
    }
    return values;
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String html(String text) {
    return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
  }
}
