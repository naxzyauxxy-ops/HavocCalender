package com.havoc.havoccalendar.listener;

import com.havoc.havoccalendar.HavocCalendarMain;
import com.havoc.havoccalendar.gui.CalendarGUI;
import com.havoc.havoccalendar.gui.CalendarGUI.DayState;
import com.havoc.havoccalendar.gui.CalendarHolder;
import com.havoc.havoccalendar.reward.RewardManager;
import com.havoc.havoccalendar.reward.RewardManager.DayReward;
import com.havoc.havoccalendar.util.DateUtils;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GUI protection + claim processing, plus the optional join reminder.
 * <p>
 * Protection strategy: <b>every</b> click and drag is cancelled while a calendar is the top
 * inventory - including clicks in the player's own inventory (shift-click, number-key swaps,
 * double-click collect) - so items can never enter or leave the GUI.
 */
public final class CalendarListener implements Listener {

    private final HavocCalendarMain plugin;
    private final Map<UUID, Long> lastClick = new ConcurrentHashMap<>();

    public CalendarListener(HavocCalendarMain plugin) {
        this.plugin = plugin;
    }

    // =================================================================== protection

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof CalendarHolder holder)) {
            return;
        }
        // Cancel first, unconditionally: nothing may move in or out of the calendar.
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)
                || !player.getUniqueId().equals(holder.viewer())
                || event.getRawSlot() < 0
                || event.getRawSlot() >= top.getSize()) {
            // Outside the window or inside the player's own inventory: already cancelled, ignore.
            return;
        }

        int slot = event.getRawSlot();
        CalendarGUI gui = plugin.calendarGUI();

        if (gui.isCloseSlot(slot)) {
            plugin.getServer().getScheduler().runTask(plugin, () -> player.closeInventory());
            return;
        }

        Integer day = gui.dayAt(slot);
        if (day == null || isOnCooldown(player)) {
            return;
        }
        handleDoorClick(player, top, day);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof CalendarHolder) {
            event.setCancelled(true);
        }
    }

    private boolean isOnCooldown(Player player) {
        long now = System.currentTimeMillis();
        long cooldown = plugin.getConfig().getLong("click-cooldown-ms", 300L);
        Long previous = lastClick.put(player.getUniqueId(), now);
        return previous != null && now - previous < cooldown;
    }

    // =================================================================== claim logic

    private void handleDoorClick(Player player, Inventory top, int day) {
        if (!player.hasPermission("havoccalendar.use")) {
            plugin.sendMessage(player, "no-permission", Map.of());
            return;
        }

        CalendarGUI gui = plugin.calendarGUI();
        DateUtils dates = plugin.dateUtils();
        DayState state = gui.resolveState(player.getUniqueId(), day);
        Map<String, String> ph = Map.of(
                "%day%", String.valueOf(day),
                "%unlock_date%", dates.formatUnlockDate(day),
                "%days_until%", String.valueOf(dates.daysUntil(day)));

        switch (state) {
            case LOCKED -> {
                plugin.lockedSound().play(player);
                plugin.sendMessage(player, dates.isDecember() ? "day-locked" : "day-locked-not-december", ph);
            }
            case CLAIMED -> {
                plugin.lockedSound().play(player);
                plugin.sendMessage(player, "already-claimed", ph);
            }
            case MISSED -> {
                plugin.lockedSound().play(player);
                plugin.sendMessage(player, "day-missed", ph);
            }
            case AVAILABLE -> claim(player, top, day, ph);
        }
    }

    private void claim(Player player, Inventory top, int day, Map<String, String> ph) {
        // markClaimed is atomic: a second concurrent attempt returns false => no duplicate reward.
        boolean newlyClaimed = plugin.dataManager().markClaimed(
                player.getUniqueId(), player.getName(), plugin.dateUtils().eventYear(), day);
        if (!newlyClaimed) {
            plugin.sendMessage(player, "already-claimed", ph);
            return;
        }

        DayReward reward = plugin.rewardManager().get(day);
        plugin.rewardManager().giveReward(player, reward);
        plugin.claimSound().play(player);

        if (reward.claimMessage() != null && !reward.claimMessage().isBlank()) {
            plugin.sendRaw(player, reward.claimMessage(), RewardManager.placeholders(player, day));
        } else {
            plugin.sendMessage(player, "claim-success", ph);
        }

        // Update the GUI in place (door -> claimed, info item counters).
        plugin.calendarGUI().render(top, player);
        plugin.dataManager().saveAsync();

        if (plugin.getConfig().getBoolean("close-on-claim", false)) {
            plugin.getServer().getScheduler().runTask(plugin, () -> player.closeInventory());
        }
    }

    // =================================================================== join / quit

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.dataManager().updateName(player.getUniqueId(), player.getName());

        if (!plugin.getConfig().getBoolean("join-reminder.enabled", true)
                || !player.hasPermission("havoccalendar.use")
                || !plugin.dateUtils().isDecember()) {
            return;
        }
        long delay = Math.max(1L, plugin.getConfig().getLong("join-reminder.delay-ticks", 60L));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            int available = plugin.calendarGUI().countAvailable(player.getUniqueId());
            if (available > 0) {
                plugin.sendMessage(player, "join-reminder", Map.of("%available%", String.valueOf(available)));
            }
        }, delay);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastClick.remove(event.getPlayer().getUniqueId());
    }
}
