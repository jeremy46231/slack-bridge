package app.jer.slackbridge;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Stores which Minecraft account is linked to which Slack account, in plugins/SlackBridge/links.yml.
 * Replaces HCCore's per-player JSON files, which also held nicknames, colors and locations.
 */
public class LinkStore {

  /**
   * @param userToken the player's Slack user token, encrypted with {@link TokenCipher}, if they let
   *                  the bridge post as them; otherwise null
   */
  public record Link(String slackId, String name, String userToken) {

  }

  private final SlackBridgePlugin plugin;
  private final File file;
  private final Map<UUID, Link> links = new ConcurrentHashMap<>();

  public LinkStore(SlackBridgePlugin plugin) {
    this.plugin = plugin;
    this.file = new File(plugin.getDataFolder(), "links.yml");
  }

  public void load() {
    this.links.clear();
    if (!this.file.exists()) {
      return;
    }

    ConfigurationSection section = YamlConfiguration.loadConfiguration(this.file)
        .getConfigurationSection("links");
    if (section == null) {
      return;
    }

    for (String key : section.getKeys(false)) {
      try {
        String slackId = section.getString(key + ".slack-id");
        if (slackId != null) {
          this.links.put(UUID.fromString(key), new Link(slackId, section.getString(key + ".name"),
              section.getString(key + ".user-token")));
        }
      } catch (IllegalArgumentException exception) {
        this.plugin.getLogger().log(Level.WARNING, "Ignoring link with an invalid UUID: " + key);
      }
    }
  }

  public synchronized void save() {
    YamlConfiguration yaml = new YamlConfiguration();
    for (Entry<UUID, Link> entry : this.links.entrySet()) {
      String path = "links." + entry.getKey();
      yaml.set(path + ".slack-id", entry.getValue().slackId());
      yaml.set(path + ".name", entry.getValue().name());
      yaml.set(path + ".user-token", entry.getValue().userToken());
    }

    try {
      this.file.getParentFile().mkdirs();
      yaml.save(this.file);
      // Holds (encrypted) Slack tokens, so keep it to the server's own user where possible
      try {
        Files.setPosixFilePermissions(this.file.toPath(), PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException ignored) {
        // Not a POSIX file system
      }
    } catch (IOException e) {
      this.plugin.getLogger().log(Level.SEVERE, "Could not save " + this.file, e);
    }
  }

  public Map<UUID, Link> getLinks() {
    return Map.copyOf(this.links);
  }

  public Link getLink(UUID uuid) {
    return this.links.get(uuid);
  }

  public String getSlackId(UUID uuid) {
    Link link = this.links.get(uuid);
    return link == null ? null : link.slackId();
  }

  /**
   * Links a player without changing whether they post as themselves: their user token is kept if
   * they're relinked to the same Slack account and dropped otherwise.
   *
   * @return the link this replaced, or null
   */
  public Link setLink(UUID uuid, String slackId, String name) {
    Link old = this.links.get(uuid);
    String userToken = old != null && old.slackId().equals(slackId) ? old.userToken() : null;
    return this.setLink(uuid, slackId, name, userToken);
  }

  /** @return the link this replaced, or null */
  public Link setLink(UUID uuid, String slackId, String name, String userToken) {
    Link old = this.links.put(uuid, new Link(slackId, name, userToken));
    this.save();
    return old;
  }

  /** Stops a player posting as themselves, if they're still linked to the same Slack account. */
  public void clearUserToken(UUID uuid, String slackId) {
    Link link = this.links.get(uuid);
    if (link != null && link.slackId().equals(slackId) && link.userToken() != null) {
      this.setLink(uuid, slackId, link.name(), null);
    }
  }

  /** @return the removed link, or null if there was none */
  public Link removeLink(UUID uuid) {
    Link removed = this.links.remove(uuid);
    if (removed != null) {
      this.save();
    }
    return removed;
  }

  /** Keeps the stored name current so lookups from Slack show the player's latest username. */
  public void updateName(UUID uuid, String name) {
    Link link = this.links.get(uuid);
    if (link != null && !name.equals(link.name())) {
      this.setLink(uuid, link.slackId(), name, link.userToken());
    }
  }

  public Optional<Entry<UUID, Link>> findBySlackId(String slackId) {
    return this.links.entrySet().stream().filter(entry -> entry.getValue().slackId().equals(slackId))
        .findFirst();
  }

  public Optional<Entry<UUID, Link>> findByName(String name) {
    return this.links.entrySet().stream()
        .filter(entry -> name.equalsIgnoreCase(entry.getValue().name())).findFirst();
  }
}
