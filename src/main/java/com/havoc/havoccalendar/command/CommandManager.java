package com.havoc.havoccalendar.command;

import com.havoc.havoccalendar.HavocCalendarMain;
import com.havoc.havoccalendar.util.DateUtils;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Handles {@code /havoccalendar} (aliases {@code /calendar}, {@code /advent}).
 * <pre>
 * /havoccalendar                      open the GUI           havoccalendar.use
 * /havoccalendar reload               reload configs         havoccalendar.admin
 * /havoccalendar reset &lt;player&gt; [day] reset claims          havoccalendar.admin
 * /havoccalendar setday &lt;day|off&gt;     simulate a Dec day     havoccalendar.admin
 * /havoccalendar info                 date/debug info        havoccalendar.admin
 * /havoccalendar help                 command list
 * </pre>
 */
public final class CommandManager implements CommandExecutor, TabCompleter {

    private static final String PERM_USE = "havoccalendar.use";
    private static final String PERM_ADMIN = "havoccalendar.admin";
    private static final List<String> ADMIN_SUBCOMMANDS = List.of("reload", "reset", "setday", "info", "help");

    private final HavocCalendarMain plugin;

    public CommandManager(HavocCalendarMain plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            openCalendar(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                if (requireAdmin(sender)) {
                    plugin.reloadAll();
                    plugin.sendMessage(sender, "reloaded", Map.of());
                }
            }
            case "reset" -> {
                if (requireAdmin(sender)) {
                    handleReset(sender, label, args);
                }
            }
            case "setday" -> {
                if (requireAdmin(sender)) {
                    handleSetDay(sender, label, args);
                }
            }
            case "info" -> {
                if (requireAdmin(sender)) {
                    handleInfo(sender);
                }
            }
            case "open" -> openCalendar(sender);
            default -> sendHelp(sender, label);
        }
        return true;
    }

    // =================================================================== sub-commands

    private void openCalendar(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.sendMessage(sender, "player-only", Map.of());
            return;
        }
        if (!player.hasPermission(PERM_USE)) {
            plugin.sendMessage(player, "no-permission", Map.of());
            return;
        }
        DateUtils dates = plugin.dateUtils();
        if (!dates.isDecember()) {
            Map<String, String> ph = Map.of("%days_until%", String.valueOf(dates.daysUntil(DateUtils.FIRST_DAY)));
            plugin.sendMessage(player, "not-december", ph);
            if (!plugin.getConfig().getBoolean("allow-open-outside-december", true)) {
                plugin.lockedSound().play(player);
                return;
            }
        }
        plugin.calendarGUI().open(player);
    }

    private void handleReset(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            plugin.sendMessage(sender, "usage-reset", Map.of("%label%", label));
            return;
        }
        Optional<ResolvedPlayer> target = resolvePlayer(args[1]);
        if (target.isEmpty()) {
            plugin.sendMessage(sender, "player-not-found", Map.of("%player%", args[1]));
            return;
        }
        ResolvedPlayer resolved = target.get();
        int year = plugin.dateUtils().eventYear();

        Integer day = null;
        if (args.length >= 3) {
            OptionalInt parsed = DateUtils.parseInt(args[2]);
            if (parsed.isEmpty() || !DateUtils.isValidAdventDay(parsed.getAsInt())) {
                plugin.sendMessage(sender, "invalid-day", Map.of("%input%", args[2], "%max%", "25"));
                return;
            }
            day = parsed.getAsInt();
        }

        boolean changed = plugin.dataManager().reset(resolved.uuid(), year, day);
        if (changed) {
            plugin.dataManager().saveAsync();
            refreshIfOnline(resolved.uuid());
        }
        Map<String, String> ph = Map.of(
                "%player%", resolved.name(),
                "%day%", day == null ? "all" : String.valueOf(day),
                "%year%", String.valueOf(year));
        if (!changed) {
            plugin.sendMessage(sender, "reset-nothing", ph);
        } else {
            plugin.sendMessage(sender, day == null ? "reset-all" : "reset-day", ph);
        }
    }

    private void handleSetDay(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            plugin.sendMessage(sender, "usage-setday", Map.of("%label%", label));
            return;
        }
        DateUtils dates = plugin.dateUtils();
        String input = args[1].toLowerCase(Locale.ROOT);
        if (input.equals("off") || input.equals("reset") || input.equals("clear") || input.equals("0")) {
            dates.clearOverride();
            plugin.sendMessage(sender, "setday-cleared", Map.of());
        } else {
            OptionalInt parsed = DateUtils.parseInt(input);
            if (parsed.isEmpty() || !DateUtils.isValidDecemberDay(parsed.getAsInt())) {
                plugin.sendMessage(sender, "invalid-day", Map.of("%input%", args[1], "%max%", "31"));
                return;
            }
            dates.setOverrideDay(parsed.getAsInt());
            plugin.sendMessage(sender, "setday-set", Map.of("%day%", String.valueOf(parsed.getAsInt())));
        }
        plugin.resetDayWatcher();
        plugin.calendarGUI().refreshAll();
    }

    private void handleInfo(CommandSender sender) {
        DateUtils dates = plugin.dateUtils();
        int today = dates.currentDecemberDay();
        Map<String, String> ph = Map.of(
                "%real_date%", dates.realToday().toString(),
                "%timezone%", dates.zone().getId(),
                "%override%", dates.isOverrideActive() ? String.valueOf(dates.overrideDay()) : "off",
                "%today%", today == 0 ? "-" : String.valueOf(today),
                "%year%", String.valueOf(dates.eventYear()),
                "%retroactive%", String.valueOf(plugin.allowRetroactiveClaims()));
        for (String line : plugin.getConfig().getStringList("messages.info")) {
            plugin.sendChat(sender, line, ph);
        }
    }

    private void sendHelp(CommandSender sender, String label) {
        String path = sender.hasPermission(PERM_ADMIN) ? "messages.help-admin" : "messages.help";
        for (String line : plugin.getConfig().getStringList(path)) {
            plugin.sendChat(sender, line, Map.of("%label%", label));
        }
    }

    // =================================================================== helpers

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission(PERM_ADMIN)) {
            return true;
        }
        plugin.sendMessage(sender, "no-permission", Map.of());
        return false;
    }

    private record ResolvedPlayer(UUID uuid, String name) {
    }

    /** Online player -> cached offline player -> name stored in data.yml. Never blocks on Mojang lookups. */
    private Optional<ResolvedPlayer> resolvePlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return Optional.of(new ResolvedPlayer(online.getUniqueId(), online.getName()));
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) {
            String cachedName = cached.getName() != null ? cached.getName() : name;
            return Optional.of(new ResolvedPlayer(cached.getUniqueId(), cachedName));
        }
        return plugin.dataManager().findByName(name).map(uuid -> new ResolvedPlayer(uuid, name));
    }

    private void refreshIfOnline(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            plugin.calendarGUI().refresh(player);
        }
    }

    // =================================================================== tab completion

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        boolean admin = sender.hasPermission(PERM_ADMIN);
        if (args.length == 1) {
            List<String> options = admin ? ADMIN_SUBCOMMANDS : List.of("help");
            return filter(options, args[0]);
        }
        if (!admin) {
            return List.of();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && sub.equals("reset")) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                names.add(player.getName());
            }
            return filter(names, args[1]);
        }
        if (args.length == 2 && sub.equals("setday")) {
            List<String> options = new ArrayList<>(dayRange(DateUtils.LAST_ADVENT_DAY));
            options.add("off");
            return filter(options, args[1]);
        }
        if (args.length == 3 && sub.equals("reset")) {
            // Suggest only days the target actually claimed, when resolvable.
            Optional<ResolvedPlayer> target = resolvePlayer(args[1]);
            List<String> days = target
                    .map(t -> plugin.dataManager().getClaimedDays(t.uuid(), plugin.dateUtils().eventYear())
                            .stream().sorted().map(String::valueOf).toList())
                    .filter(list -> !list.isEmpty())
                    .orElseGet(() -> dayRange(DateUtils.LAST_ADVENT_DAY));
            return filter(days, args[2]);
        }
        return List.of();
    }

    private static List<String> dayRange(int max) {
        return IntStream.rangeClosed(1, max).mapToObj(String::valueOf).toList();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}
