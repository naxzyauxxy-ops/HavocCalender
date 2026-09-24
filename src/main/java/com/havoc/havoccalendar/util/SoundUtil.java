package com.havoc.havoccalendar.util;

import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Parses configurable sounds and plays them through the Adventure audience API.
 * <p>
 * Accepts either the Bukkit constant name ({@code ENTITY_PLAYER_LEVELUP}) or a namespaced
 * key ({@code minecraft:entity.player.levelup} / {@code entity.player.levelup}), which also
 * allows custom resource-pack sounds. Using Adventure sounds avoids the {@code Sound.valueOf}
 * enum lookup, which is deprecated since {@code org.bukkit.Sound} became a registry type.
 */
public final class SoundUtil {

    private SoundUtil() {
    }

    /** Immutable, pre-parsed sound definition. */
    public record SoundSettings(boolean enabled, Sound sound) {

        public static final SoundSettings DISABLED = new SoundSettings(false, null);

        public void play(Player player) {
            if (enabled && sound != null && player != null) {
                player.playSound(sound, Sound.Emitter.self());
            }
        }
    }

    /**
     * Reads a section shaped like:
     * <pre>
     * enabled: true
     * sound: ENTITY_PLAYER_LEVELUP
     * volume: 1.0
     * pitch: 1.0
     * </pre>
     */
    public static SoundSettings fromConfig(ConfigurationSection section, Logger logger, String path) {
        if (section == null || !section.getBoolean("enabled", true)) {
            return SoundSettings.DISABLED;
        }
        String raw = section.getString("sound", "");
        Key key = resolveKey(raw);
        if (key == null) {
            logger.warning("Invalid sound '" + raw + "' at '" + path + "'. This sound is disabled.");
            return SoundSettings.DISABLED;
        }
        float volume = (float) Math.max(0.0, section.getDouble("volume", 1.0));
        float pitch = (float) Math.clamp(section.getDouble("pitch", 1.0), 0.5, 2.0);
        return new SoundSettings(true, Sound.sound(key, Sound.Source.MASTER, volume, pitch));
    }

    /** Resolves a Bukkit constant name or namespaced key into an Adventure {@link Key}. */
    public static Key resolveKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();

        // 1) Namespaced key form: "minecraft:entity.player.levelup" or "entity.player.levelup"
        if (value.indexOf('.') >= 0 || value.indexOf(':') >= 0) {
            try {
                return Key.key(value.toLowerCase(Locale.ROOT));
            } catch (InvalidKeyException ex) {
                return null;
            }
        }

        // 2) Bukkit constant form: look up the public static field on org.bukkit.Sound.
        //    Works whether Sound is an enum (<=1.21.1) or a registry interface (1.21.3+).
        try {
            Field field = org.bukkit.Sound.class.getField(value.toUpperCase(Locale.ROOT));
            if (Modifier.isStatic(field.getModifiers())
                    && field.get(null) instanceof net.kyori.adventure.key.Keyed keyed) {
                return keyed.key();
            }
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
            // fall through
        }
        return null;
    }
}
