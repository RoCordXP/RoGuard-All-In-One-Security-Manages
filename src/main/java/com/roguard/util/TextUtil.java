package com.roguard.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared colour and text helpers. */
public final class TextUtil {

    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");
    private static final Pattern SECTION_CODES = Pattern.compile("(?i)\u00a7[0-9A-FK-ORX]");
    private static final Pattern HEX_SECTION = Pattern.compile("(?i)\u00a7x(?:\u00a7[0-9A-F]){6}");
    private static final Pattern AMP_CODES = Pattern.compile("(?i)&[0-9A-FK-ORX]");

    private TextUtil() {}

    /** Converts &a and &#RRGGBB codes into section colours. */
    public static String colour(String input) {
        if (input == null || input.isEmpty()) return "";

        Matcher matcher = HEX.matcher(input);
        StringBuilder out = new StringBuilder();

        while (matcher.find()) {
            StringBuilder replacement = new StringBuilder("\u00a7x");
            for (char c : matcher.group(1).toCharArray()) {
                replacement.append('\u00a7').append(c);
            }
            matcher.appendReplacement(out, replacement.toString());
        }
        matcher.appendTail(out);

        return out.toString().replace('&', '\u00a7');
    }

    /** Builds an Adventure component from a coloured string. */
    public static Component component(String input) {
        return LegacyComponentSerializer.legacySection().deserialize(colour(input));
    }

    /** Flattens a component to plain text with no formatting at all. */
    public static String plain(Component component) {
        if (component == null) return "";
        return stripAll(PlainTextComponentSerializer.plainText().serialize(component));
    }

    /**
     * Removes every colour marker, including hex sequences and leftover
     * ampersand codes that item names often carry.
     */
    public static String stripAll(String input) {
        if (input == null || input.isEmpty()) return "";

        String out = HEX_SECTION.matcher(input).replaceAll("");
        out = SECTION_CODES.matcher(out).replaceAll("");
        out = HEX.matcher(out).replaceAll("");
        out = AMP_CODES.matcher(out).replaceAll("");
        return out;
    }

    /** Replaces %key% style placeholders in pairs. */
    public static String fill(String input, String... pairs) {
        if (input == null) return "";
        String out = input;
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out = out.replace(pairs[i], pairs[i + 1]);
        }
        return out;
    }

    /** Formats milliseconds as a short readable duration. */
    public static String duration(long millis) {
        if (millis <= 0) return "0s";

        long seconds = millis / 1000L;
        long days = seconds / 86400L;
        long hours = (seconds % 86400L) / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;

        StringBuilder out = new StringBuilder();
        if (days > 0) out.append(days).append("d ");
        if (hours > 0) out.append(hours).append("h ");
        if (minutes > 0) out.append(minutes).append("m ");
        if (out.length() == 0) out.append(secs).append("s");
        return out.toString().trim();
    }

    /** Parses 30m, 2h, 7d, 1w and combinations. Returns -1 when invalid. */
    public static long parseDuration(String input) {
        if (input == null || input.isBlank()) return -1;

        Matcher matcher = Pattern.compile("(?i)(\\d+)\\s*([smhdw])").matcher(input.trim());
        long total = 0;
        boolean found = false;

        while (matcher.find()) {
            long amount = Long.parseLong(matcher.group(1));
            found = true;
            switch (matcher.group(2).toLowerCase()) {
                case "s" -> total += amount * 1000L;
                case "m" -> total += amount * 60_000L;
                case "h" -> total += amount * 3_600_000L;
                case "d" -> total += amount * 86_400_000L;
                case "w" -> total += amount * 604_800_000L;
                default -> { }
            }
        }

        return found && total > 0 ? total : -1;
    }
}
