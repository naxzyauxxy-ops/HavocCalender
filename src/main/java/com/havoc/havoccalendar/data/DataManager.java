package com.havoc.havoccalendar.data;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * YAML persistence for player claim history.
 * <p>
 * File layout ({@code plugins/HavocCalendar/data.yml}):
 * <pre>
 * players:
 *   0f3c...-uuid:
 *     name: Steve
 *     claims:
 *       '2026': [1, 2, 5]
 * </pre>
 * Claims are stored per event year so the calendar automatically resets every December
 * while keeping history.
 * <p>
 * Threading model: the in-memory cache uses concurrent collections, so the main thread
 * can read/write at any time while the async auto-save serialises a snapshot. Writes go to
 * a temp file which is then atomically moved over {@code data.yml} to avoid corruption if the
 * server crashes mid-write.
 */
public final class DataManager {

    private final JavaPlugin plugin;
    private final File file;
    private final Object ioLock = new Object();
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    /** uuid -> record. */
    private final Map<UUID, PlayerRecord> players = new ConcurrentHashMap<>();

    /** Per-player data: last known name and year -> claimed days. */
    private static final class PlayerRecord {
        private volatile String name;
        private final Map<Integer, Set<Integer>> claims = new ConcurrentHashMap<>();

        private PlayerRecord(String name) {
            this.name = name;
        }

        private Set<Integer> year(int year) {
            return claims.computeIfAbsent(year, y -> ConcurrentHashMap.newKeySet());
        }
    }

    public DataManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    // ---------------------------------------------------------------- load / save

    /** Loads data.yml synchronously. Call once on enable. */
    public void load() {
        players.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not read data.yml! A backup copy will be made and "
                    + "an empty data set used to avoid overwriting it.", ex);
            backupCorruptFile();
            return;
        }

        ConfigurationSection root = yaml.getConfigurationSection("players");
        if (root == null) {
            return;
        }
        int loaded = 0;
        for (String uuidKey : root.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidKey);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Skipping invalid UUID in data.yml: " + uuidKey);
                continue;
            }
            ConfigurationSection section = root.getConfigurationSection(uuidKey);
            if (section == null) {
                continue;
            }
            PlayerRecord record = new PlayerRecord(section.getString("name", "unknown"));
            ConfigurationSection claims = section.getConfigurationSection("claims");
            if (claims != null) {
                for (String yearKey : claims.getKeys(false)) {
                    int year;
                    try {
                        year = Integer.parseInt(yearKey);
                    } catch (NumberFormatException ex) {
                        continue;
                    }
                    Set<Integer> days = record.year(year);
                    for (Integer day : claims.getIntegerList(yearKey)) {
                        if (day != null && day >= 1 && day <= 25) {
                            days.add(day);
                        }
                    }
                }
            }
            players.put(uuid, record);
            loaded++;
        }
        plugin.getLogger().info("Loaded advent data for " + loaded + " player(s).");
    }

    /** Saves asynchronously if anything changed. Safe to call from any thread. */
    public void saveAsync() {
        if (!dirty.get()) {
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::saveIfDirty);
    }

    /** Blocking save; used on disable. */
    public void saveSync() {
        dirty.set(true);
        saveIfDirty();
    }

    private void saveIfDirty() {
        synchronized (ioLock) {
            if (!dirty.getAndSet(false)) {
                return;
            }
            String contents = serialise();
            try {
                writeAtomically(contents);
            } catch (IOException ex) {
                dirty.set(true); // retry on next cycle
                plugin.getLogger().log(Level.SEVERE, "Failed to save data.yml", ex);
            }
        }
    }

    /** Builds the YAML text from a consistent-enough snapshot of the concurrent cache. */
    private String serialise() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(java.util.List.of(
                "HavocCalendar player data - edit only while the server is stopped.",
                "Structure: players -> <uuid> -> claims -> <year> -> [claimed days]"));
        for (Map.Entry<UUID, PlayerRecord> entry : players.entrySet()) {
            String base = "players." + entry.getKey();
            PlayerRecord record = entry.getValue();
            yaml.set(base + ".name", record.name);
            // TreeMap/TreeSet keep the file sorted and human-friendly.
            Map<Integer, Set<Integer>> sorted = new TreeMap<>(record.claims);
            for (Map.Entry<Integer, Set<Integer>> year : sorted.entrySet()) {
                if (!year.getValue().isEmpty()) {
                    yaml.set(base + ".claims." + year.getKey(), new java.util.ArrayList<>(new TreeSet<>(year.getValue())));
                }
            }
        }
        return yaml.saveToString();
    }

    private void writeAtomically(String contents) throws IOException {
        Path target = file.toPath();
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling("data.yml.tmp");
        Files.writeString(temp, contents, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void backupCorruptFile() {
        Path target = file.toPath();
        Path backup = target.resolveSibling("data.yml.corrupt-" + System.currentTimeMillis());
        try {
            Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().severe("Corrupt data.yml copied to " + backup.getFileName());
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not back up corrupt data.yml", ex);
        }
    }

    // ---------------------------------------------------------------- queries & mutations

    public boolean hasClaimed(UUID uuid, int year, int day) {
        PlayerRecord record = players.get(uuid);
        if (record == null) {
            return false;
        }
        Set<Integer> days = record.claims.get(year);
        return days != null && days.contains(day);
    }

    /** Unmodifiable view of claimed days for the given year. */
    public Set<Integer> getClaimedDays(UUID uuid, int year) {
        PlayerRecord record = players.get(uuid);
        if (record == null) {
            return Set.of();
        }
        Set<Integer> days = record.claims.get(year);
        return days == null ? Set.of() : Collections.unmodifiableSet(days);
    }

    /**
     * Atomically marks a day as claimed.
     *
     * @return {@code true} if it was newly claimed, {@code false} if it was already claimed
     *         (protects against double-click / packet spam duplication).
     */
    public boolean markClaimed(UUID uuid, String name, int year, int day) {
        PlayerRecord record = players.computeIfAbsent(uuid, id -> new PlayerRecord(name));
        record.name = name;
        boolean added = record.year(year).add(day);
        if (added) {
            dirty.set(true);
        }
        return added;
    }

    /** Resets one day, or every day when {@code day} is {@code null}, for the given year. */
    public boolean reset(UUID uuid, int year, Integer day) {
        PlayerRecord record = players.get(uuid);
        if (record == null) {
            return false;
        }
        boolean changed;
        if (day == null) {
            Set<Integer> removed = record.claims.remove(year);
            changed = removed != null && !removed.isEmpty();
        } else {
            Set<Integer> days = record.claims.get(year);
            changed = days != null && days.remove(day);
        }
        if (changed) {
            dirty.set(true);
        }
        return changed;
    }

    /** Keeps the stored name fresh (used by /reset for offline players). */
    public void updateName(UUID uuid, String name) {
        PlayerRecord record = players.get(uuid);
        if (record != null && !name.equals(record.name)) {
            record.name = name;
            dirty.set(true);
        }
    }

    /** Case-insensitive lookup of a UUID by the last known player name. */
    public Optional<UUID> findByName(String name) {
        for (Map.Entry<UUID, PlayerRecord> entry : players.entrySet()) {
            if (name.equalsIgnoreCase(entry.getValue().name)) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }
}
