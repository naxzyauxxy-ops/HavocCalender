package com.havoc.havoccalendar.reward;

import com.havoc.havoccalendar.util.DateUtils;
import com.havoc.havoccalendar.util.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * Loads {@code rewards.yml} (days 1-25) and executes the configured reward actions.
 */
public final class RewardManager {

    /**
     * One advent door.
     *
     * @param headTexture optional base64 texture / textures.minecraft.net URL for PLAYER_HEAD doors
     */
    public record DayReward(
            int day,
            Material material,
            String headTexture,
            String displayName,
            List<String> loreUnlocked,
            List<String> loreClaimed,
            List<String> loreLocked,
            List<String> loreMissed,
            List<String> commands,
            String claimMessage,
            String broadcastMessage
    ) {
    }

    private final JavaPlugin plugin;
    private final Map<Integer, DayReward> rewards = new HashMap<>();

    public RewardManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** (Re)loads rewards.yml. Missing days fall back to the {@code defaults} section. */
    public void load() {
        File file = new File(plugin.getDataFolder(), "rewards.yml");
        if (!file.exists()) {
            plugin.saveResource("rewards.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection defaults = yaml.getConfigurationSection("defaults");
        ConfigurationSection days = yaml.getConfigurationSection("days");

        Map<Integer, DayReward> loaded = new HashMap<>();
        for (int day = DateUtils.FIRST_DAY; day <= DateUtils.LAST_ADVENT_DAY; day++) {
            ConfigurationSection section = days == null ? null : days.getConfigurationSection(String.valueOf(day));
            if (section == null) {
                plugin.getLogger().warning("rewards.yml has no entry for day " + day + "; using defaults (no commands).");
            }
            loaded.put(day, parse(day, section, defaults));
        }
        rewards.clear();
        rewards.putAll(loaded);
        plugin.getLogger().info("Loaded " + rewards.size() + " advent rewards.");
    }

    private DayReward parse(int day, ConfigurationSection s, ConfigurationSection d) {
        String materialName = string(s, d, "item-material", "CHEST");
        Material material = Material.matchMaterial(materialName);
        if (material == null || material.isAir() || !material.isItem()) {
            plugin.getLogger().warning("Day " + day + ": '" + materialName + "' is not a valid item material. Using CHEST.");
            material = Material.CHEST;
        }
        return new DayReward(
                day,
                material,
                string(s, d, "head-texture", ""),
                string(s, d, "display-name", "&#C41E3A&lDay %day%"),
                list(s, d, "lore-unlocked"),
                list(s, d, "lore-claimed"),
                list(s, d, "lore-locked"),
                list(s, d, "lore-missed"),
                s == null ? List.of() : s.getStringList("commands"),
                string(s, d, "claim-message", ""),
                s == null ? "" : s.getString("broadcast-message", "")
        );
    }

    private static String string(ConfigurationSection s, ConfigurationSection d, String key, String fallback) {
        if (s != null && s.isSet(key)) {
            return s.getString(key, fallback);
        }
        if (d != null && d.isSet(key)) {
            return d.getString(key, fallback);
        }
        return fallback;
    }

    private static List<String> list(ConfigurationSection s, ConfigurationSection d, String key) {
        if (s != null && s.isList(key)) {
            return List.copyOf(s.getStringList(key));
        }
        if (d != null && d.isList(key)) {
            return List.copyOf(d.getStringList(key));
        }
        return List.of();
    }

    public DayReward get(int day) {
        return rewards.get(day);
    }

    // ---------------------------------------------------------------- execution

    /** Standard placeholders shared by commands, messages and lore. */
    public static Map<String, String> placeholders(Player player, int day) {
        Map<String, String> map = new HashMap<>();
        map.put("%player_name%", player.getName());
        map.put("%player%", player.getName());
        map.put("%player_uuid%", player.getUniqueId().toString());
        map.put("%day%", String.valueOf(day));
        return map;
    }

    /**
     * Runs every reward action for the day. Must be called on the main thread.
     * <p>
     * Supported prefixes (default is console):
     * <ul>
     *     <li>{@code [console] give %player_name% diamond 1}</li>
     *     <li>{@code [player] spawn} - run as the player</li>
     *     <li>{@code [message] &aHello!} - private message to the player</li>
     *     <li>{@code [broadcast] &6Hi all} - server-wide message</li>
     * </ul>
     */
    public void giveReward(Player player, DayReward reward) {
        Map<String, String> ph = placeholders(player, reward.day());
        for (String raw : reward.commands()) {
            String line = TextUtil.apply(raw, ph).trim();
            if (line.isEmpty()) {
                continue;
            }
            try {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("[player]")) {
                    player.performCommand(stripSlash(line.substring(8).trim()));
                } else if (lower.startsWith("[message]")) {
                    player.sendMessage(TextUtil.parse(line.substring(9).trim()));
                } else if (lower.startsWith("[broadcast]")) {
                    Bukkit.getServer().broadcast(TextUtil.parse(line.substring(11).trim()));
                } else {
                    String command = lower.startsWith("[console]") ? line.substring(9).trim() : line;
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), stripSlash(command));
                }
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.SEVERE,
                        "Error running reward action for day " + reward.day() + ": " + line, ex);
            }
        }

        if (reward.broadcastMessage() != null && !reward.broadcastMessage().isBlank()) {
            Bukkit.getServer().broadcast(TextUtil.parse(reward.broadcastMessage(), ph));
        }
    }

    private static String stripSlash(String command) {
        return command.startsWith("/") ? command.substring(1) : command;
    }
}
