package com.havoc.havoccalendar.gui;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.havoc.havoccalendar.HavocCalendarMain;
import com.havoc.havoccalendar.reward.RewardManager;
import com.havoc.havoccalendar.reward.RewardManager.DayReward;
import com.havoc.havoccalendar.util.DateUtils;
import com.havoc.havoccalendar.util.TextUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Builds and renders the 54-slot Advent Calendar.
 * <p>
 * The layout is driven by a 6x9 character pattern in config.yml, similar to a crafting recipe:
 * <ul>
 *     <li>{@code D} - an advent door (filled 1..25 in reading order)</li>
 *     <li>{@code I} - the progress / info item</li>
 *     <li>{@code X} - the close button</li>
 *     <li>{@code space} or {@code .} - empty</li>
 *     <li>any other char - a decoration defined under {@code gui.pattern-items}</li>
 * </ul>
 */
public final class CalendarGUI {

    public static final int SIZE = 54;
    private static final int ROWS = 6;
    private static final int COLUMNS = 9;
    private static final Pattern TEXTURE_HASH = Pattern.compile("^[0-9a-fA-F]{32,}$");

    /** Visual/claim state of a single door for a specific player. */
    public enum DayState { AVAILABLE, CLAIMED, LOCKED, MISSED }

    /** Material + name + glow for claimed / locked / missed doors. */
    private record StateStyle(Material material, String name, boolean glow) {
    }

    /** Pre-built decoration for a pattern character. */
    private record Decoration(Material material, String name, List<String> lore, boolean glow) {
    }

    private static final String[] DEFAULT_PATTERN = {
            "RLDDDDDLR",
            "WLDDDDDLW",
            "RLDDDDDLR",
            "WLDDDDDLW",
            "RLDDDDDLR",
            "LRWLIRWXL"
    };

    private final HavocCalendarMain plugin;

    // ---- loaded layout
    private Component title = Component.text("Advent Calendar");
    private final int[] daySlots = new int[DateUtils.LAST_ADVENT_DAY];
    private final Map<Integer, Integer> slotToDay = new HashMap<>();
    private final Map<Integer, ItemStack> decorationItems = new HashMap<>();
    private int infoSlot = -1;
    private int closeSlot = -1;
    private boolean dayAsAmount = true;
    private boolean glowAvailable = true;

    private StateStyle claimedStyle;
    private StateStyle lockedStyle;
    private StateStyle missedStyle;
    private Decoration infoDecoration;
    private Decoration closeDecoration;

    public CalendarGUI(HavocCalendarMain plugin) {
        this.plugin = plugin;
    }

    // =================================================================== loading

    /** (Re)reads layout + visual state settings from config.yml. */
    public void load() {
        FileConfiguration config = plugin.getConfig();
        title = TextUtil.parse(config.getString("gui.title", "&#C41E3A&lAdvent Calendar"));
        dayAsAmount = config.getBoolean("gui.day-as-stack-amount", true);
        glowAvailable = config.getBoolean("states.available.glow", true);

        claimedStyle = style(config.getConfigurationSection("states.claimed"), Material.MINECART, "&8&mDay %day%");
        lockedStyle = style(config.getConfigurationSection("states.locked"), Material.RED_STAINED_GLASS_PANE, "&cDay %day%");
        missedStyle = style(config.getConfigurationSection("states.missed"), Material.COAL, "&7Day %day% &8(Missed)");
        infoDecoration = decoration(config.getConfigurationSection("gui.info-item"), Material.CLOCK);
        closeDecoration = decoration(config.getConfigurationSection("gui.close-item"), Material.SPRUCE_DOOR);

        List<String> pattern = config.getStringList("gui.pattern");
        if (!parsePattern(pattern, config.getConfigurationSection("gui.pattern-items"))) {
            plugin.getLogger().warning("gui.pattern must be 6 lines of 9 characters containing exactly 25 'D' "
                    + "slots. Falling back to the default layout.");
            parsePattern(List.of(DEFAULT_PATTERN), config.getConfigurationSection("gui.pattern-items"));
        }
    }

    private boolean parsePattern(List<String> pattern, ConfigurationSection itemsSection) {
        if (pattern.size() != ROWS) {
            return false;
        }
        Map<Integer, Integer> newSlotToDay = new HashMap<>();
        Map<Integer, ItemStack> newDecorations = new HashMap<>();
        Map<Character, ItemStack> cache = new HashMap<>();
        int newInfo = -1;
        int newClose = -1;
        int day = 0;

        for (int row = 0; row < ROWS; row++) {
            String line = pattern.get(row);
            if (line == null || line.length() != COLUMNS) {
                return false;
            }
            for (int col = 0; col < COLUMNS; col++) {
                int slot = row * COLUMNS + col;
                char c = line.charAt(col);
                switch (c) {
                    case 'D' -> {
                        if (day >= DateUtils.LAST_ADVENT_DAY) {
                            return false;
                        }
                        day++;
                        newSlotToDay.put(slot, day);
                    }
                    case 'I' -> newInfo = slot;
                    case 'X' -> newClose = slot;
                    case ' ', '.' -> {
                        // intentionally empty
                    }
                    default -> {
                        ItemStack deco = cache.computeIfAbsent(c, ch -> buildDecoration(itemsSection, ch));
                        if (deco != null) {
                            newDecorations.put(slot, deco);
                        }
                    }
                }
            }
        }
        if (day != DateUtils.LAST_ADVENT_DAY) {
            return false;
        }

        slotToDay.clear();
        slotToDay.putAll(newSlotToDay);
        newSlotToDay.forEach((slot, d) -> daySlots[d - 1] = slot);
        decorationItems.clear();
        decorationItems.putAll(newDecorations);
        infoSlot = newInfo;
        closeSlot = newClose;
        return true;
    }

    private ItemStack buildDecoration(ConfigurationSection itemsSection, char c) {
        ConfigurationSection section = itemsSection == null ? null : itemsSection.getConfigurationSection(String.valueOf(c));
        if (section == null) {
            plugin.getLogger().warning("gui.pattern uses '" + c + "' but gui.pattern-items." + c + " is not defined.");
            return null;
        }
        Decoration deco = decoration(section, Material.WHITE_STAINED_GLASS_PANE);
        return buildItem(deco.material(), TextUtil.parse(deco.name()),
                deco.lore().stream().map(TextUtil::parse).toList(), deco.glow(), 1);
    }

    private StateStyle style(ConfigurationSection section, Material fallbackMaterial, String fallbackName) {
        if (section == null) {
            return new StateStyle(fallbackMaterial, fallbackName, false);
        }
        return new StateStyle(
                material(section.getString("material"), fallbackMaterial),
                section.getString("name", fallbackName),
                section.getBoolean("glow", false));
    }

    private Decoration decoration(ConfigurationSection section, Material fallback) {
        if (section == null) {
            return new Decoration(fallback, " ", List.of(), false);
        }
        return new Decoration(
                material(section.getString("material"), fallback),
                section.getString("name", " "),
                section.getStringList("lore"),
                section.getBoolean("glow", false));
    }

    private Material material(String name, Material fallback) {
        if (name == null) {
            return fallback;
        }
        Material material = Material.matchMaterial(name);
        if (material == null || material.isAir() || !material.isItem()) {
            plugin.getLogger().warning("Invalid material '" + name + "' in config.yml, using " + fallback + ".");
            return fallback;
        }
        return material;
    }

    // =================================================================== opening & rendering

    /** Opens a fresh calendar for the player and plays the open sound. */
    public void open(Player player) {
        CalendarHolder holder = new CalendarHolder(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, SIZE, title);
        holder.setInventory(inventory);
        render(inventory, player);
        player.openInventory(inventory);
        plugin.openSound().play(player);
    }

    /** Clears and redraws every slot of a calendar inventory. */
    public void render(Inventory inventory, Player player) {
        inventory.clear();
        decorationItems.forEach((slot, item) -> inventory.setItem(slot, item.clone()));
        for (int day = DateUtils.FIRST_DAY; day <= DateUtils.LAST_ADVENT_DAY; day++) {
            inventory.setItem(daySlots[day - 1], buildDayItem(player, day));
        }
        if (infoSlot >= 0) {
            inventory.setItem(infoSlot, buildInfoItem(player));
        }
        if (closeSlot >= 0) {
            inventory.setItem(closeSlot, buildItem(closeDecoration.material(), TextUtil.parse(closeDecoration.name()),
                    closeDecoration.lore().stream().map(TextUtil::parse).toList(), closeDecoration.glow(), 1));
        }
    }

    /** Re-renders the calendar for a player if they currently have it open. */
    public void refresh(Player player) {
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top.getHolder(false) instanceof CalendarHolder) {
            render(top, player);
        }
    }

    /** Re-renders every open calendar (after reload / setday / reset / midnight). */
    public void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            refresh(player);
        }
    }

    /** Closes every open calendar (used on disable so no stale GUI survives a reload). */
    public void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof CalendarHolder) {
                player.closeInventory();
            }
        }
    }

    // =================================================================== state logic

    /**
     * Single source of truth for whether a door can be claimed.
     * Used by both the renderer and the click handler so they can never disagree.
     */
    public DayState resolveState(UUID uuid, int day) {
        DateUtils dates = plugin.dateUtils();
        if (plugin.dataManager().hasClaimed(uuid, dates.eventYear(), day)) {
            return DayState.CLAIMED;
        }
        int today = dates.currentDecemberDay();
        if (today == 0) {
            return DayState.LOCKED;              // not December
        }
        if (today > plugin.lastClaimDay()) {
            return DayState.MISSED;              // event window over
        }
        if (day > today) {
            return DayState.LOCKED;              // future door
        }
        if (day == today) {
            return DayState.AVAILABLE;
        }
        return plugin.allowRetroactiveClaims() ? DayState.AVAILABLE : DayState.MISSED;
    }

    /** Counts doors the player could claim right now. */
    public int countAvailable(UUID uuid) {
        int count = 0;
        for (int day = DateUtils.FIRST_DAY; day <= DateUtils.LAST_ADVENT_DAY; day++) {
            if (resolveState(uuid, day) == DayState.AVAILABLE) {
                count++;
            }
        }
        return count;
    }

    // =================================================================== slot lookups

    /** @return the advent day at an inventory slot, or {@code null} if not a door. */
    public Integer dayAt(int slot) {
        return slotToDay.get(slot);
    }

    public boolean isCloseSlot(int slot) {
        return slot == closeSlot && closeSlot >= 0;
    }

    // =================================================================== item building

    private Map<String, String> dayPlaceholders(Player player, int day) {
        DateUtils dates = plugin.dateUtils();
        Map<String, String> ph = RewardManager.placeholders(player, day);
        ph.put("%unlock_date%", dates.formatUnlockDate(day));
        ph.put("%days_until%", String.valueOf(dates.daysUntil(day)));
        ph.put("%days_until_christmas%", String.valueOf(dates.daysUntilChristmas()));
        return ph;
    }

    public ItemStack buildDayItem(Player player, int day) {
        DayReward reward = plugin.rewardManager().get(day);
        DayState state = resolveState(player.getUniqueId(), day);
        Map<String, String> ph = dayPlaceholders(player, day);
        int amount = dayAsAmount ? day : 1;

        return switch (state) {
            case AVAILABLE -> {
                ItemStack item = buildItem(reward.material(), TextUtil.parse(reward.displayName(), ph),
                        TextUtil.parseList(reward.loreUnlocked(), ph), glowAvailable, amount);
                applyHeadTexture(item, reward.headTexture());
                yield item;
            }
            case CLAIMED -> buildItem(claimedStyle.material(), TextUtil.parse(claimedStyle.name(), ph),
                    TextUtil.parseList(reward.loreClaimed(), ph), claimedStyle.glow(), amount);
            case LOCKED -> buildItem(lockedStyle.material(), TextUtil.parse(lockedStyle.name(), ph),
                    TextUtil.parseList(reward.loreLocked(), ph), lockedStyle.glow(), amount);
            case MISSED -> buildItem(missedStyle.material(), TextUtil.parse(missedStyle.name(), ph),
                    TextUtil.parseList(reward.loreMissed(), ph), missedStyle.glow(), amount);
        };
    }

    private ItemStack buildInfoItem(Player player) {
        DateUtils dates = plugin.dateUtils();
        UUID uuid = player.getUniqueId();
        int today = dates.currentDecemberDay();

        Map<String, String> ph = RewardManager.placeholders(player, today);
        ph.put("%claimed%", String.valueOf(plugin.dataManager().getClaimedDays(uuid, dates.eventYear()).size()));
        ph.put("%total%", String.valueOf(DateUtils.LAST_ADVENT_DAY));
        ph.put("%available%", String.valueOf(countAvailable(uuid)));
        ph.put("%days_until_christmas%", String.valueOf(dates.daysUntilChristmas()));
        ph.put("%today%", today == 0
                ? plugin.getConfig().getString("gui.info-item.not-december-text", "Not December yet")
                : dates.formatUnlockDate(today));
        ph.put("%retroactive%", plugin.allowRetroactiveClaims()
                ? plugin.getConfig().getString("gui.info-item.retroactive-enabled-text", "&aEnabled")
                : plugin.getConfig().getString("gui.info-item.retroactive-disabled-text", "&cDisabled"));

        return buildItem(infoDecoration.material(), TextUtil.parse(infoDecoration.name(), ph),
                TextUtil.parseList(infoDecoration.lore(), ph), infoDecoration.glow(), 1);
    }

    /** Generic, fully non-deprecated item factory using Adventure components. */
    private static ItemStack buildItem(Material material, Component name, List<Component> lore, boolean glow, int amount) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            if (!lore.isEmpty()) {
                meta.lore(lore);
            }
            if (glow) {
                meta.setEnchantmentGlintOverride(true);
            }
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
            if (amount > 1 && item.getMaxStackSize() < amount) {
                // e.g. BUNDLE / MINECART stack to 1: raise the per-item cap so the day number shows.
                meta.setMaxStackSize(Math.min(99, Math.max(amount, 1)));
            }
            item.setItemMeta(meta);
        }
        item.setAmount(Math.clamp(amount, 1, 99));
        return item;
    }

    /**
     * Applies a custom head texture to PLAYER_HEAD items. Accepts a base64 value,
     * a full textures.minecraft.net URL or just the texture hash.
     */
    private static void applyHeadTexture(ItemStack item, String texture) {
        if (texture == null || texture.isBlank() || !(item.getItemMeta() instanceof SkullMeta skull)) {
            return;
        }
        String value = texture.trim();
        if (TEXTURE_HASH.matcher(value).matches()) {
            value = "https://textures.minecraft.net/texture/" + value;
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + value.replace("https://", "http://") + "\"}}}";
            value = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        }
        UUID id = UUID.nameUUIDFromBytes(("HavocCalendar:" + value).getBytes(StandardCharsets.UTF_8));
        PlayerProfile profile = Bukkit.createProfile(id, "HavocAdvent");
        profile.setProperty(new ProfileProperty("textures", value));
        skull.setPlayerProfile(profile);
        item.setItemMeta(skull);
    }
}
