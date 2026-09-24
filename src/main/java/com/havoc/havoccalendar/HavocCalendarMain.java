package com.havoc.havoccalendar;

import com.havoc.havoccalendar.command.CommandManager;
import com.havoc.havoccalendar.data.DataManager;
import com.havoc.havoccalendar.gui.CalendarGUI;
import com.havoc.havoccalendar.listener.CalendarListener;
import com.havoc.havoccalendar.reward.RewardManager;
import com.havoc.havoccalendar.util.DateUtils;
import com.havoc.havoccalendar.util.SoundUtil;
import com.havoc.havoccalendar.util.SoundUtil.SoundSettings;
import com.havoc.havoccalendar.util.TextUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * HavocCalendar entry point.
 * <p>
 * Wires together the managers, registers the command + listener and runs two repeating tasks:
 * <ul>
 *     <li>Auto-save: flushes dirty player data to disk asynchronously.</li>
 *     <li>Day watcher: detects midnight rollover (or a new /setday) and refreshes open GUIs,
 *         optionally announcing that a new door is open.</li>
 * </ul>
 */
public final class HavocCalendarMain extends JavaPlugin {

    private DateUtils dateUtils;
    private RewardManager rewardManager;
    private DataManager dataManager;
    private CalendarGUI calendarGUI;

    private SoundSettings openSound = SoundSettings.DISABLED;
    private SoundSettings claimSound = SoundSettings.DISABLED;
    private SoundSettings lockedSound = SoundSettings.DISABLED;
    private boolean allowRetroactiveClaims;
    private int lastClaimDay;
    private boolean actionBarMessages = true;

    private BukkitTask autoSaveTask;
    private BukkitTask dayWatcherTask;
    private int lastKnownDay = -1;

    // =================================================================== lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "rewards.yml").exists()) {
            saveResource("rewards.yml", false);
        }

        dateUtils = new DateUtils(getLogger());
        rewardManager = new RewardManager(this);
        dataManager = new DataManager(this);
        calendarGUI = new CalendarGUI(this);

        loadSettings();
        rewardManager.load();
        calendarGUI.load();
        dataManager.load();

        getServer().getPluginManager().registerEvents(new CalendarListener(this), this);

        PluginCommand command = getCommand("havoccalendar");
        if (command == null) {
            getLogger().severe("Command 'havoccalendar' missing from plugin.yml - disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        CommandManager commandManager = new CommandManager(this);
        command.setExecutor(commandManager);
        command.setTabCompleter(commandManager);

        startTasks();
        getLogger().info("HavocCalendar enabled - " + (dateUtils.isDecember()
                ? "Merry Christmas! Today is December " + dateUtils.currentDecemberDay() + "."
                : "the calendar opens on December 1st."));
    }

    @Override
    public void onDisable() {
        cancelTasks();
        if (calendarGUI != null) {
            calendarGUI.closeAll();
        }
        if (dataManager != null) {
            dataManager.saveSync();
        }
    }

    /** Reloads config.yml + rewards.yml and redraws any open calendars. Player data is kept in memory. */
    public void reloadAll() {
        reloadConfig();
        loadSettings();
        rewardManager.load();
        calendarGUI.load();
        cancelTasks();
        startTasks();
        calendarGUI.refreshAll();
    }

    private void loadSettings() {
        dateUtils.load(getConfig());
        allowRetroactiveClaims = getConfig().getBoolean("allow-retroactive-claims", true);
        actionBarMessages = !"CHAT".equalsIgnoreCase(getConfig().getString("message-display", "ACTION_BAR"));
        lastClaimDay = Math.clamp(getConfig().getInt("last-claim-day", 31), 25, 31);
        openSound = SoundUtil.fromConfig(getConfig().getConfigurationSection("open-sound"), getLogger(), "open-sound");
        claimSound = SoundUtil.fromConfig(getConfig().getConfigurationSection("claim-sound"), getLogger(), "claim-sound");
        lockedSound = SoundUtil.fromConfig(getConfig().getConfigurationSection("locked-sound"), getLogger(), "locked-sound");
    }

    // =================================================================== tasks

    private void startTasks() {
        long saveTicks = Math.max(30L, getConfig().getLong("data.autosave-interval-seconds", 300L)) * 20L;
        // Timer runs on main thread only to check the dirty flag; the actual file write is async.
        autoSaveTask = getServer().getScheduler().runTaskTimer(this, dataManager::saveAsync, saveTicks, saveTicks);

        lastKnownDay = dateUtils.currentDecemberDay();
        dayWatcherTask = getServer().getScheduler().runTaskTimer(this, this::checkDayRollover, 20L * 20, 20L * 20);
    }

    private void cancelTasks() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
            autoSaveTask = null;
        }
        if (dayWatcherTask != null) {
            dayWatcherTask.cancel();
            dayWatcherTask = null;
        }
    }

    /** Called every 20s: detects midnight and refreshes/announces. */
    private void checkDayRollover() {
        int today = dateUtils.currentDecemberDay();
        if (today == lastKnownDay) {
            return;
        }
        lastKnownDay = today;
        calendarGUI.refreshAll();

        if (DateUtils.isValidAdventDay(today) && getConfig().getBoolean("announce-new-day", true)) {
            Map<String, String> ph = Map.of("%day%", String.valueOf(today));
            for (Player player : getServer().getOnlinePlayers()) {
                if (player.hasPermission("havoccalendar.use")) {
                    sendMessage(player, "new-day-available", ph);
                }
            }
        }
    }

    /** Syncs the watcher after an admin /setday so it does not fire a "new day" announcement. */
    public void resetDayWatcher() {
        lastKnownDay = dateUtils.currentDecemberDay();
    }

    // =================================================================== messaging

    /**
     * Sends {@code messages.<key>} with the configured prefix. An empty message is treated as
     * disabled, letting server owners silence any line.
     * Players get it on the action bar (or chat if message-display: CHAT); console always gets chat.
     */
    public void sendMessage(CommandSender sender, String key, Map<String, String> placeholders) {
        String raw = getConfig().getString("messages." + key);
        if (raw == null || raw.isEmpty()) {
            return;
        }
        String prefix = getConfig().getString("messages.prefix", "");
        sendRaw(sender, prefix + raw, placeholders);
    }

    /** Sends a formatted line (no prefix) using the configured display mode. */
    public void sendRaw(CommandSender sender, String text, Map<String, String> placeholders) {
        deliver(sender, TextUtil.parse(text, withSender(sender, placeholders)));
    }

    /** Always sends to chat. Used for multi-line output (help / info) that can't fit an action bar. */
    public void sendChat(CommandSender sender, String text, Map<String, String> placeholders) {
        sender.sendMessage(TextUtil.parse(text, withSender(sender, placeholders)));
    }

    /** Sends a component to a sender using the configured display mode. */
    public void deliver(CommandSender sender, Component message) {
        if (actionBarMessages && sender instanceof Player player) {
            player.sendActionBar(message);
        } else {
            sender.sendMessage(message);
        }
    }

    /**
     * Server-wide announcement. In action-bar mode every online player (except {@code exclude},
     * e.g. the claimer who is already seeing their own message) gets it on the action bar and the
     * console gets a copy in the log.
     */
    public void broadcast(Component message, Player exclude) {
        if (!actionBarMessages) {
            getServer().broadcast(message);
            return;
        }
        for (Player player : getServer().getOnlinePlayers()) {
            if (!player.equals(exclude)) {
                player.sendActionBar(message);
            }
        }
        getServer().getConsoleSender().sendMessage(message);
    }

    private static Map<String, String> withSender(CommandSender sender, Map<String, String> placeholders) {
        Map<String, String> ph = new HashMap<>(placeholders);
        if (sender instanceof Player player) {
            ph.putIfAbsent("%player_name%", player.getName());
            ph.putIfAbsent("%player%", player.getName());
        }
        return ph;
    }

    // =================================================================== accessors

    public DateUtils dateUtils() {
        return dateUtils;
    }

    public RewardManager rewardManager() {
        return rewardManager;
    }

    public DataManager dataManager() {
        return dataManager;
    }

    public CalendarGUI calendarGUI() {
        return calendarGUI;
    }

    public SoundSettings openSound() {
        return openSound;
    }

    public SoundSettings claimSound() {
        return claimSound;
    }

    public SoundSettings lockedSound() {
        return lockedSound;
    }

    public boolean allowRetroactiveClaims() {
        return allowRetroactiveClaims;
    }

    public int lastClaimDay() {
        return lastClaimDay;
    }
}
