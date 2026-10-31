package com.roguard.discord;

import com.roguard.RoGuard;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

/** Forwards console output to the Discord mirror. */
public class ConsoleAppender extends AbstractAppender {

    private static ConsoleAppender instance;
    private final RoGuard plugin;

    private ConsoleAppender(RoGuard plugin) {
        super("RoGuardConsole", null, PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY);
        this.plugin = plugin;
    }

    public static void register(RoGuard plugin) {
        if (instance != null) return;
        instance = new ConsoleAppender(plugin);
        instance.start();
        ((Logger) LogManager.getRootLogger()).addAppender(instance);
    }

    public static void unregister() {
        if (instance == null) return;
        ((Logger) LogManager.getRootLogger()).removeAppender(instance);
        instance.stop();
        instance = null;
    }

    @Override
    public void append(LogEvent event) {
        SecurityBot bot = plugin.getSecurityBot();
        if (bot == null) return;

        try {
            String message = event.getMessage().getFormattedMessage();
            if (message == null || message.isEmpty()) return;

            String logger = event.getLoggerName();
            if (logger != null) {
                String lower = logger.toLowerCase();
                if (lower.contains("jda") || lower.contains("netty")) return;
            }

            bot.mirror("[" + event.getLevel().name() + "] " + message);
        } catch (Exception ignored) {}
    }
}
