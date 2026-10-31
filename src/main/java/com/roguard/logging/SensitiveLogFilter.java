package com.roguard.logging;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.core.filter.AbstractFilterable;

import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps passwords out of every console, including panel consoles that read
 * the raw process stream.
 *
 * The built in command logger is switched off and RoGuard prints a safe
 * replacement line instead.
 */
public final class SensitiveLogFilter extends AbstractFilter {

    private static final Pattern PASSWORD_COMMAND = Pattern.compile(
            "(?i)(?:^|[\\s:])/(?:[a-z0-9_.-]+:)?"
                    + "(?:login|l|register|reg|changepassword|changepass|cp)"
                    + "(?=\\s)\\s+(\\S+)");

    private static final Set<LoggerConfig> LOGGERS =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<AbstractFilterable> APPENDERS =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private static SensitiveLogFilter instance;
    private static Field spigotField;
    private static boolean previousSpigot;
    private static PrintStream originalOut;
    private static PrintStream originalErr;
    private static boolean streamsWrapped;

    private SensitiveLogFilter() {
        super(Filter.Result.DENY, Filter.Result.NEUTRAL);
    }

    public static synchronized void install() {
        if (instance != null) return;

        wrapStreams();
        disableBuiltInLogger();

        try {
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            Configuration configuration = context.getConfiguration();

            instance = new SensitiveLogFilter();
            instance.start();

            attach(configuration.getRootLogger());
            for (LoggerConfig logger : configuration.getLoggers().values()) attach(logger);

            for (Appender appender : configuration.getAppenders().values()) {
                if (appender instanceof AbstractFilterable filterable && APPENDERS.add(filterable)) {
                    filterable.addFilter(instance);
                }
            }

            context.updateLoggers();
        } catch (Throwable ignored) {
            // The stream wrapper still protects the panel.
        }
    }

    private static void attach(LoggerConfig logger) {
        if (LOGGERS.add(logger)) logger.addFilter(instance);
    }

    private static void wrapStreams() {
        if (streamsWrapped) return;
        try {
            originalOut = System.out;
            originalErr = System.err;
            System.setOut(new Filtered(originalOut));
            System.setErr(new Filtered(originalErr));
            streamsWrapped = true;
        } catch (Throwable ignored) {}
    }

    private static void disableBuiltInLogger() {
        try {
            Class<?> spigotConfig = Class.forName("org.spigotmc.SpigotConfig");
            Field field = spigotConfig.getDeclaredField("logCommands");
            if (!Modifier.isStatic(field.getModifiers())) return;

            field.setAccessible(true);
            previousSpigot = field.getBoolean(null);
            field.setBoolean(null, false);
            spigotField = field;
        } catch (ReflectiveOperationException ignored) {}
    }

    public static synchronized void uninstall() {
        if (instance != null) {
            try {
                LoggerContext context = (LoggerContext) LogManager.getContext(false);
                for (LoggerConfig logger : LOGGERS) logger.removeFilter(instance);
                for (AbstractFilterable appender : APPENDERS) appender.removeFilter(instance);
                LOGGERS.clear();
                APPENDERS.clear();
                instance.stop();
                instance = null;
                context.updateLoggers();
            } catch (Throwable ignored) {}
        }

        if (spigotField != null) {
            try { spigotField.setBoolean(null, previousSpigot); } catch (Exception ignored) {}
            spigotField = null;
        }

        if (streamsWrapped) {
            try {
                if (originalOut != null) System.setOut(originalOut);
                if (originalErr != null) System.setErr(originalErr);
            } catch (Throwable ignored) {}
            streamsWrapped = false;
        }
    }

    @Override
    public Result filter(LogEvent event) {
        if (event == null || event.getMessage() == null) return Result.NEUTRAL;
        return shouldDrop(event.getMessage().getFormattedMessage())
                ? Result.DENY : Result.NEUTRAL;
    }

    /** True for SLF4J noise and for any line exposing a real password. */
    public static boolean shouldDrop(String message) {
        if (message == null) return false;
        if (message.contains("SLF4J") || message.contains("noProviders")) return true;
        return exposesPassword(message);
    }

    /** Redacted arguments such as *** are safe and stay visible. */
    public static boolean exposesPassword(String message) {
        if (message == null) return false;

        Matcher matcher = PASSWORD_COMMAND.matcher(message);
        while (matcher.find()) {
            if (!matcher.group(1).matches("\\*+")) return true;
        }
        return false;
    }

    /** Drops whole lines before they reach the terminal. */
    private static class Filtered extends PrintStream {

        private final StringBuilder buffer = new StringBuilder();

        Filtered(OutputStream out) {
            super(out, true);
        }

        @Override
        public void print(String s) {
            if (s == null) return;
            synchronized (buffer) {
                buffer.append(s);
                if (s.indexOf('\n') >= 0) flushBuffer();
            }
        }

        @Override public void println(String x) { print((x == null ? "null" : x) + "\n"); }
        @Override public void println() { print("\n"); }
        @Override public void print(Object obj) { print(String.valueOf(obj)); }
        @Override public void println(Object x) { println(String.valueOf(x)); }
        @Override public void print(boolean b) { print(String.valueOf(b)); }
        @Override public void println(boolean x) { println(String.valueOf(x)); }
        @Override public void print(char c) { print(String.valueOf(c)); }
        @Override public void println(char x) { println(String.valueOf(x)); }
        @Override public void print(int i) { print(String.valueOf(i)); }
        @Override public void println(int x) { println(String.valueOf(x)); }
        @Override public void print(long l) { print(String.valueOf(l)); }
        @Override public void println(long x) { println(String.valueOf(x)); }
        @Override public void print(float f) { print(String.valueOf(f)); }
        @Override public void println(float x) { println(String.valueOf(x)); }
        @Override public void print(double d) { print(String.valueOf(d)); }
        @Override public void println(double x) { println(String.valueOf(x)); }
        @Override public void print(char[] s) { print(new String(s)); }
        @Override public void println(char[] x) { println(new String(x)); }

        private void flushBuffer() {
            String content = buffer.toString();
            buffer.setLength(0);
            if (!shouldDrop(content)) super.print(content);
        }

        @Override
        public void flush() {
            synchronized (buffer) {
                if (buffer.length() > 0) {
                    if (!shouldDrop(buffer.toString())) super.print(buffer.toString());
                    buffer.setLength(0);
                }
            }
            super.flush();
        }
    }
}
