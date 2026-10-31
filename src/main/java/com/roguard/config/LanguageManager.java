package com.roguard.config;

import com.roguard.RoGuard;
import com.roguard.util.TextUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads a translation from the language folder.
 * Resolved strings are cached so hot paths never touch YAML.
 */
public class LanguageManager {

    private static final String[] BUNDLED = {
            "english.yml", "vietnamese.yml", "chinnese.yml",
            "thailand.yml", "portugal.yml", "spain.yml", "russian.yml"
    };

    private final RoGuard plugin;
    private final Map<String, String> cache = new HashMap<>();

    private FileConfiguration active;
    private FileConfiguration fallback;
    private String prefix = "";

    public LanguageManager(RoGuard plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        File folder = new File(plugin.getDataFolder(), "language");
        if (!folder.exists()) folder.mkdirs();

        for (String name : BUNDLED) {
            File file = new File(folder, name);
            if (!file.exists()) {
                try {
                    plugin.saveResource("language/" + name, false);
                } catch (Exception ignored) {
                    // The resource is missing from the jar; skip it.
                }
            }
        }

        fallback = readBundled("english.yml");

        String chosen = plugin.getConfigManager() == null
                ? "english.yml"
                : plugin.getConfigManager().getLanguageFile();

        File file = new File(folder, chosen);
        if (!file.exists()) {
            plugin.getLogger().warning("Language file " + chosen + " not found, using english.yml");
            file = new File(folder, "english.yml");
        }

        active = YamlConfiguration.loadConfiguration(file);
        if (fallback != null) active.setDefaults(fallback);

        cache.clear();
        prefix = TextUtil.colour(raw("prefix", ""));
    }

    public void reload() {
        load();
    }

    private FileConfiguration readBundled(String name) {
        try (InputStream stream = plugin.getResource("language/" + name)) {
            if (stream == null) return null;
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String raw(String path, String def) {
        String value = active.getString(path);
        if (value == null && fallback != null) value = fallback.getString(path);
        return value == null ? def : value;
    }

    /** Coloured message without the prefix. Cached. */
    public String get(String path) {
        String cached = cache.get(path);
        if (cached != null) return cached;

        String value = TextUtil.colour(raw(path, path));
        cache.put(path, value);
        return value;
    }

    /** Coloured message with placeholders filled in. */
    public String get(String path, String... pairs) {
        return TextUtil.fill(get(path), pairs);
    }

    /** Raw uncoloured value, used for Discord text. */
    public String plain(String path) {
        return raw(path, "");
    }

    public String plain(String path, String... pairs) {
        return TextUtil.fill(raw(path, ""), pairs);
    }

    /** Prefixed chat message. */
    public String msg(String path) {
        return prefix + get(path);
    }

    public String msg(String path, String... pairs) {
        return prefix + get(path, pairs);
    }

    public Component component(String path) {
        return TextUtil.component(raw(path, path));
    }

    public Component component(String path, String... pairs) {
        return TextUtil.component(TextUtil.fill(raw(path, path), pairs));
    }

    public String getPrefix() {
        return prefix;
    }
}
