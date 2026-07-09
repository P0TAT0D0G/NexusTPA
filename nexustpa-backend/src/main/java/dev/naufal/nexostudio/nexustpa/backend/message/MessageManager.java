package dev.naufal.nexostudio.nexustpa.backend.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * Loads and serves messages from messages.yml using MiniMessage format.
 * Paper 1.21+ Player implements Audience natively — no BukkitAudiences needed.
 *
 * <p>Verified: MiniMessage.miniMessage().deserialize() is the correct API.
 * Placeholder.unparsed() creates tag resolvers for safe text substitution.
 */
public class MessageManager {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private YamlConfiguration messages;
    private String prefix;

    public MessageManager(JavaPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    /**
     * Loads or reloads messages.yml.
     */
    public void load() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(file);
        prefix = messages.getString("prefix", "");
    }

    /**
     * Builds a Component from a message key with optional placeholders.
     *
     * @param key          dot-notation key in messages.yml
     * @param placeholders tag resolvers for placeholder substitution
     * @return deserialized Component with prefix prepended
     */
    public Component getMessage(String key, TagResolver... placeholders) {
        String raw = messages.getString(key, "<red>Missing message: " + key + "</red>");
        String full = prefix + raw;
        return MINI_MESSAGE.deserialize(full, placeholders);
    }

    /**
     * Sends a message to a player.
     * Uses Paper's native Audience implementation on Player.
     *
     * @param player       the player to send to
     * @param key          message key
     * @param placeholders tag resolvers
     */
    public void send(Player player, String key, TagResolver... placeholders) {
        player.sendMessage(getMessage(key, placeholders));
    }

    /**
     * Creates an unparsed placeholder (safe text substitution, no MiniMessage parsing).
     */
    public static TagResolver playerPlaceholder(String value) {
        return Placeholder.unparsed("player", value);
    }

    /**
     * Creates an unparsed placeholder for seconds.
     */
    public static TagResolver secondsPlaceholder(int seconds) {
        return Placeholder.unparsed("seconds", String.valueOf(seconds));
    }

    public void reload() {
        load();
    }
}
