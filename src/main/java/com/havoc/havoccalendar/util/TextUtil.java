package com.havoc.havoccalendar.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Colour/formatting helper.
 * <p>
 * Every configurable string accepts <b>any mix</b> of:
 * <ul>
 *     <li>Legacy codes: {@code &a}, {@code &l}, {@code &m} ...</li>
 *     <li>Hex codes: {@code &#RRGGBB} and the Bungee style {@code &x&R&R&G&G&B&B}</li>
 *     <li>Full MiniMessage tags: {@code <gradient:#ff0000:#00ff00>}, {@code <rainbow>} ...</li>
 * </ul>
 * Legacy/hex codes are translated into MiniMessage tags first, then the whole string is
 * deserialized by MiniMessage into an Adventure {@link Component}. No deprecated
 * {@code ChatColor} / legacy-string API is used anywhere.
 */
public final class TextUtil {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    /** {@code &x&R&R&G&G&B&B} (Bungee / Spigot hex format). */
    private static final Pattern BUNGEE_HEX = Pattern.compile("[&§][xX]((?:[&§][0-9a-fA-F]){6})");
    /** {@code &#RRGGBB}. */
    private static final Pattern AMPERSAND_HEX = Pattern.compile("[&§]#([0-9a-fA-F]{6})");
    /** {@code &a}, {@code &l}, ... */
    private static final Pattern LEGACY = Pattern.compile("[&§]([0-9a-fk-orA-FK-OR])");

    private TextUtil() {
    }

    /**
     * Parses a config string into a component with italics disabled by default
     * (item names/lore are italic in vanilla unless explicitly turned off).
     */
    public static Component parse(String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }
        Component parsed = MINI_MESSAGE.deserialize(legacyToMiniMessage(input));
        return Component.text()
                .decoration(TextDecoration.ITALIC, false)
                .append(parsed)
                .build();
    }

    /** Applies placeholders, then parses. */
    public static Component parse(String input, Map<String, String> placeholders) {
        return parse(apply(input, placeholders));
    }

    /** Parses every line of a list after applying placeholders. */
    public static List<Component> parseList(List<String> lines, Map<String, String> placeholders) {
        List<Component> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            result.add(parse(line, placeholders));
        }
        return result;
    }

    /** Replaces every {@code key -> value} pair (keys should include the % signs). */
    public static String apply(String input, Map<String, String> placeholders) {
        if (input == null) {
            return "";
        }
        String out = input;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            out = out.replace(entry.getKey(), entry.getValue());
        }
        return out;
    }

    /**
     * Converts legacy {@code &} and hex codes to MiniMessage tags.
     * Legacy colour codes reset formatting (vanilla behaviour), so each colour is
     * emitted as {@code <reset><colour>}.
     */
    public static String legacyToMiniMessage(String input) {
        String out = replaceAll(BUNGEE_HEX, input, m -> {
            String hex = m.group(1).replace("&", "").replace("§", "");
            return "<reset><#" + hex.toLowerCase() + ">";
        });
        out = replaceAll(AMPERSAND_HEX, out, m -> "<reset><#" + m.group(1).toLowerCase() + ">");
        out = replaceAll(LEGACY, out, m -> legacyTag(Character.toLowerCase(m.group(1).charAt(0))));
        return out;
    }

    private static String legacyTag(char code) {
        return switch (code) {
            case '0' -> "<reset><black>";
            case '1' -> "<reset><dark_blue>";
            case '2' -> "<reset><dark_green>";
            case '3' -> "<reset><dark_aqua>";
            case '4' -> "<reset><dark_red>";
            case '5' -> "<reset><dark_purple>";
            case '6' -> "<reset><gold>";
            case '7' -> "<reset><gray>";
            case '8' -> "<reset><dark_gray>";
            case '9' -> "<reset><blue>";
            case 'a' -> "<reset><green>";
            case 'b' -> "<reset><aqua>";
            case 'c' -> "<reset><red>";
            case 'd' -> "<reset><light_purple>";
            case 'e' -> "<reset><yellow>";
            case 'f' -> "<reset><white>";
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            case 'r' -> "<reset>";
            default -> "";
        };
    }

    private static String replaceAll(Pattern pattern, String input,
                                     java.util.function.Function<Matcher, String> replacer) {
        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) {
            return input;
        }
        StringBuilder sb = new StringBuilder(input.length() + 32);
        do {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacer.apply(matcher)));
        } while (matcher.find());
        matcher.appendTail(sb);
        return sb.toString();
    }
}
