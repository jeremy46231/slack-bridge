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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Web linking, the only way players link their Slack account: a small web server for Slack's OAuth
 * sign-in. Players get a short address like mc.example.com/slack/482913, short enough to type
 * where links can't be clicked (the disconnect screen, Bedrock chat). It shows which Minecraft
 * account is being linked, then sends them to Slack, where they can also let the bridge post their
 * chat as them (an optional chat:write user scope they can uncheck). Slack redirects back to
 * /slack/callback.
 *
 * <p>Serves /slack/&lt;code&gt;, /slack/authorize and /slack/callback; route the public URL's
 * /slack path to this server's port.
 */
public class LinkWebServer {

  /** Always requested, so Slack says who the user is even if they decline posting as themselves */
  private static final String IDENTITY_SCOPE = "users:read";
  public static final String POST_AS_USER_SCOPE = "chat:write";

  // Six digits: easy to read off a screen and type, even on a phone or a Bedrock keyboard. Codes
  // only exist for a few minutes and wrong guesses are rate limited, so this is plenty.
  private static final int CODE_LENGTH = 6;
  private static final Pattern CODE_PATH = Pattern.compile("^/slack/([0-9]{%d})/?$"
      .formatted(CODE_LENGTH));
  private static final int MAX_MISSES = 20;
  private static final long MISS_WINDOW_MILLIS = 10 * 60 * 1000;

  private record PendingLink(UUID uuid, String name, long expiresAt) {

  }

  private record Misses(int count, long windowStart) {

  }

  /**
   * @param url      the full address, for a clickable link
   * @param typeable the same address without "https://", for people who have to type it
   */
  public record LinkAddress(String url, String typeable) {

  }

  private final SlackBridgePlugin plugin;
  private final String publicUrl;
  private final String clientId;
  private final String clientSecret;
  private final HttpServer server;
  private final ExecutorService executor;
  private final Map<String, PendingLink> pendingLinks = new ConcurrentHashMap<>();
  // Wrong or expired codes per visitor, so nobody can try every code
  private final Map<String, Misses> misses = new ConcurrentHashMap<>();
  private final SecureRandom random = new SecureRandom();

  public LinkWebServer(SlackBridgePlugin plugin, String publicUrl, int port, String clientId,
      String clientSecret) throws IOException {
    this.plugin = plugin;
    this.publicUrl = publicUrl.replaceFirst("/+$", "");
    this.clientId = clientId;
    this.clientSecret = clientSecret;

    this.server = HttpServer.create(new InetSocketAddress(port), 0);
    this.server.createContext("/slack", this::route);
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

  /**
   * The address that links the given player, valid for ttlMillis. A player keeps the same code
   * until it expires, so asking again (or rejoining) doesn't break an address they're typing.
   */
  public LinkAddress createLink(UUID uuid, String name, long ttlMillis) {
    long now = System.currentTimeMillis();
    this.pendingLinks.values().removeIf(pending -> pending.expiresAt() < now);

    String code = this.pendingLinks.entrySet().stream()
        .filter(entry -> entry.getValue().uuid().equals(uuid)).map(Map.Entry::getKey).findFirst()
        .orElse(null);
    if (code == null) {
      do {
        code = this.newCode();
      } while (this.pendingLinks.containsKey(code));
    }
    this.pendingLinks.put(code, new PendingLink(uuid, name, now + ttlMillis));

    String url = this.publicUrl + "/slack/" + code;
    return new LinkAddress(url, url.replaceFirst("^https://", ""));
  }

  private String newCode() {
    StringBuilder code = new StringBuilder(CODE_LENGTH);
    for (int i = 0; i < CODE_LENGTH; i++) {
      code.append(this.random.nextInt(10));
    }
    return code.toString();
  }

  /**
   * Looks up a code, answering the request itself (expired, or too many wrong tries) if there's no
   * usable link.
   *
   * @return the pending link, or null if the request has been answered
   */
  private PendingLink findPending(HttpExchange exchange, String code) throws IOException {
    String visitor = visitor(exchange);
    long now = System.currentTimeMillis();
    Misses visitorMisses = this.misses.get(visitor);
    if (visitorMisses != null && now - visitorMisses.windowStart() < MISS_WINDOW_MILLIS
        && visitorMisses.count() >= MAX_MISSES) {
      this.respond(exchange, 429, "Too many tries", "<p>Wait a few minutes and try again.</p>");
      return null;
    }

    PendingLink pending = code == null ? null : this.pendingLinks.get(code);
    if (pending != null && pending.expiresAt() >= now) {
      return pending;
    }

    if (this.misses.size() > 10_000) {
      this.misses.values().removeIf(entry -> now - entry.windowStart() >= MISS_WINDOW_MILLIS);
    }
    this.misses.merge(visitor, new Misses(1, now), (old, added) ->
        now - old.windowStart() >= MISS_WINDOW_MILLIS ? added
            : new Misses(old.count() + 1, old.windowStart()));
    this.respondExpired(exchange);
    return null;
  }

  /**
   * Who's asking. Behind the ingress every request comes from the proxy, which adds the client's
   * address as the last X-Forwarded-For entry (earlier entries can be made up by the client).
   */
  private static String visitor(HttpExchange exchange) {
    String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      String[] hops = forwarded.split(",");
      return hops[hops.length - 1].trim();
    }
    return exchange.getRemoteAddress().getAddress().getHostAddress();
  }

  private void route(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    Matcher code = CODE_PATH.matcher(path);
    if (path.equals("/slack/authorize")) {
      this.handleAuthorize(exchange);
    } else if (path.equals("/slack/callback")) {
      this.handleCallback(exchange);
    } else if (code.matches()) {
      this.handleLink(exchange, code.group(1));
    } else {
      this.respond(exchange, 404, "Not found",
          "<p>Check the address, or run <code>/slack link</code> in Minecraft for a new one.</p>");
    }
  }

  private boolean canPostAsUser() {
    return this.plugin.getTokenCipher() != null;
  }

  /** Step 1: show which Minecraft account will be linked, so nobody links someone else's by accident */
  private void handleLink(HttpExchange exchange, String state) throws IOException {
    if (!exchange.getRequestMethod().equals("GET")) {
      this.respond(exchange, 405, "Not allowed", "<p>Open this link from Minecraft.</p>");
      return;
    }
    PendingLink pending = this.findPending(exchange, state);
    if (pending == null) {
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
    PendingLink pending = this.findPending(exchange, state);
    SlackBot bot = this.plugin.getSlackBot();
    if (pending == null) {
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
    PendingLink pending = this.findPending(exchange, state);
    if (pending == null) {
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
