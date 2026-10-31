package com.roguard.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns :shortcode: text into something Discord renders inside embeds.
 * Custom server emojis win, then a built in unicode table.
 */
public final class EmojiResolver {

    private static final Pattern SHORTCODE =
            Pattern.compile("(?<![a-zA-Z0-9_]):([a-zA-Z0-9_]{2,32}):");

    private static final Map<String, String> UNICODE = new HashMap<>();
    private static final Map<String, String> CACHE = new HashMap<>();

    static {
        UNICODE.put("skull", "\uD83D\uDC80");
        UNICODE.put("trophy", "\uD83C\uDFC6");
        UNICODE.put("green_circle", "\uD83D\uDFE2");
        UNICODE.put("red_circle", "\uD83D\uDD34");
        UNICODE.put("globe_with_meridians", "\uD83C\uDF10");
        UNICODE.put("joystick", "\uD83D\uDD79\uFE0F");
        UNICODE.put("video_game", "\uD83C\uDFAE");
        UNICODE.put("robot", "\uD83E\uDD16");
        UNICODE.put("shield", "\uD83D\uDEE1\uFE0F");
        UNICODE.put("lock", "\uD83D\uDD12");
        UNICODE.put("key", "\uD83D\uDD11");
        UNICODE.put("warning", "\u26A0\uFE0F");
        UNICODE.put("x", "\u274C");
        UNICODE.put("white_check_mark", "\u2705");
        UNICODE.put("no_entry", "\u26D4");
        UNICODE.put("fire", "\uD83D\uDD25");
        UNICODE.put("star", "\u2B50");
        UNICODE.put("sparkles", "\u2728");
        UNICODE.put("zap", "\u26A1");
        UNICODE.put("gem", "\uD83D\uDC8E");
        UNICODE.put("crown", "\uD83D\uDC51");
        UNICODE.put("wave", "\uD83D\uDC4B");
        UNICODE.put("door", "\uD83D\uDEAA");
        UNICODE.put("computer", "\uD83D\uDCBB");
        UNICODE.put("link", "\uD83D\uDD17");
        UNICODE.put("bell", "\uD83D\uDD14");
        UNICODE.put("speech_balloon", "\uD83D\uDCAC");
        UNICODE.put("rotating_light", "\uD83D\uDEA8");
        UNICODE.put("eyes", "\uD83D\uDC40");
        UNICODE.put("ghost", "\uD83D\uDC7B");
        UNICODE.put("heart", "\u2764\uFE0F");
    }

    private EmojiResolver() {}

    public static String resolve(String text, JDA jda) {
        if (text == null || text.isEmpty()) return text;

        Matcher matcher = SHORTCODE.matcher(text);
        StringBuilder out = new StringBuilder();

        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = custom(jda, name);
            if (replacement == null) replacement = UNICODE.getOrDefault(name, matcher.group(0));
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }

        matcher.appendTail(out);
        return out.toString();
    }

    private static String custom(JDA jda, String name) {
        if (jda == null) return null;

        String cacheKey = name.toLowerCase();
        if (CACHE.containsKey(cacheKey)) return CACHE.get(cacheKey);

        for (Guild guild : jda.getGuilds()) {
            try {
                List<RichCustomEmoji> found = guild.getEmojisByName(name, true);
                if (found.isEmpty()) continue;

                RichCustomEmoji emoji = found.get(0);
                String formatted = (emoji.isAnimated() ? "<a:" : "<:")
                        + emoji.getName() + ":" + emoji.getId() + ">";
                CACHE.put(cacheKey, formatted);
                return formatted;
            } catch (Exception ignored) {}
        }
        return null;
    }

    public static void clearCache() {
        CACHE.clear();
    }
}
