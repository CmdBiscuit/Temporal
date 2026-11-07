package com.temporal.config;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Temporal's settings file. Fabric has no config system, so this stands in for the part of NeoForge's ModConfigSpec
 * that Temporal uses: typed values with defaults and ranges, saved as {@code key = value} lines under their comments
 * (the same file NeoForge writes, so the two builds read each other's settings). Like NeoForge's, a change is saved
 * right away.
 */
public final class ConfigSpec {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final List<ConfigValue<?>> values;
    @Nullable private Path file;

    private ConfigSpec(List<ConfigValue<?>> values) {
        this.values = List.copyOf(values);
        for (ConfigValue<?> value : values) {
            value.spec = this;
        }
    }

    /** Reads the file (missing or unreadable values keep their defaults) and writes it back if anything was off. */
    public void load(Path path) {
        file = path;
        Map<String, String> read = new HashMap<>();
        if (Files.exists(path)) {
            try {
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    int equals = trimmed.indexOf('=');
                    if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("[") || equals < 0) {
                        continue;
                    }
                    read.put(trimmed.substring(0, equals).trim(), trimmed.substring(equals + 1).trim());
                }
            } catch (IOException e) {
                LOGGER.warn("Temporal: could not read {}, using the defaults", path, e);
            }
        }
        boolean rewrite = !Files.exists(path);
        for (ConfigValue<?> value : values) {
            String text = read.get(value.key);
            if (text == null || !value.load(unquote(text))) {
                if (text != null) {
                    LOGGER.warn("Temporal: setting {} = {} is not valid, using {}", value.key, text, value.write());
                }
                value.reset();
                rewrite = true;
            }
        }
        if (rewrite) {
            save();
        }
    }

    public void save() {
        if (file == null) {
            return;
        }
        List<String> lines = new ArrayList<>();
        for (ConfigValue<?> value : values) {
            for (String comment : value.comment.split("\n")) {
                lines.add("#" + comment);
            }
            lines.addAll(value.notes());
            lines.add(value.key + " = " + value.write());
        }
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Temporal: could not save {}", file, e);
        }
    }

    private static String unquote(String text) {
        return text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"") ? text.substring(1, text.length() - 1) : text;
    }

    public abstract static class ConfigValue<T> {
        final String key;
        final String comment;
        private final T defaultValue;
        private volatile T value;
        @Nullable private ConfigSpec spec;

        ConfigValue(String key, String comment, T defaultValue) {
            this.key = key;
            this.comment = comment;
            this.defaultValue = defaultValue;
            this.value = defaultValue;
        }

        public T get() {
            return value;
        }

        public void set(T newValue) {
            T old = value;
            value = valid(newValue) ? newValue : defaultValue;
            if (spec != null && !value.equals(old)) {
                spec.save();
            }
        }

        public T getDefault() {
            return defaultValue;
        }

        void reset() {
            value = defaultValue;
        }

        /** Takes the value written in the file; false if it isn't one. */
        boolean load(String text) {
            T parsed = parse(text);
            if (parsed == null || !valid(parsed)) {
                return false;
            }
            value = parsed;
            return true;
        }

        boolean valid(T candidate) {
            return candidate != null;
        }

        /** Extra comment lines (default, range, allowed values). */
        List<String> notes() {
            return List.of();
        }

        @Nullable
        abstract T parse(String text);

        String write() {
            return String.valueOf(value);
        }
    }

    public static final class BooleanValue extends ConfigValue<Boolean> {
        BooleanValue(String key, String comment, boolean defaultValue) {
            super(key, comment, defaultValue);
        }

        @Override
        @Nullable
        Boolean parse(String text) {
            return text.equals("true") ? Boolean.TRUE : text.equals("false") ? Boolean.FALSE : null;
        }
    }

    public static final class DoubleValue extends ConfigValue<Double> {
        private final double min;
        private final double max;

        DoubleValue(String key, String comment, double defaultValue, double min, double max) {
            super(key, comment, defaultValue);
            this.min = min;
            this.max = max;
        }

        @Override
        boolean valid(Double candidate) {
            return candidate != null && candidate >= min && candidate <= max;
        }

        @Override
        List<String> notes() {
            return List.of("# Default: " + getDefault(), "# Range: " + min + " ~ " + max);
        }

        @Override
        @Nullable
        Double parse(String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    public static final class IntValue extends ConfigValue<Integer> {
        private final int min;
        private final int max;

        IntValue(String key, String comment, int defaultValue, int min, int max) {
            super(key, comment, defaultValue);
            this.min = min;
            this.max = max;
        }

        @Override
        boolean valid(Integer candidate) {
            return candidate != null && candidate >= min && candidate <= max;
        }

        @Override
        List<String> notes() {
            return List.of("# Default: " + getDefault(), "# Range: " + min + " ~ " + max);
        }

        @Override
        @Nullable
        Integer parse(String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    public static final class EnumValue<E extends Enum<E>> extends ConfigValue<E> {
        private final Class<E> type;

        EnumValue(String key, String comment, E defaultValue) {
            super(key, comment, defaultValue);
            this.type = defaultValue.getDeclaringClass();
        }

        @Override
        List<String> notes() {
            return List.of("#Allowed Values: " + Arrays.stream(type.getEnumConstants()).map(Enum::name)
                    .collect(Collectors.joining(", ")));
        }

        @Override
        @Nullable
        E parse(String text) {
            try {
                return Enum.valueOf(type, text.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        @Override
        String write() {
            return "\"" + get().name() + "\"";
        }
    }

    public static final class Builder {
        private final List<ConfigValue<?>> values = new ArrayList<>();
        private String comment = "";

        public Builder comment(String text) {
            comment = text;
            return this;
        }

        /** NeoForge's config screen name for the setting; Temporal's own screen names its settings itself. */
        public Builder translation(String key) {
            return this;
        }

        public BooleanValue define(String key, boolean defaultValue) {
            return add(new BooleanValue(key, comment, defaultValue));
        }

        public DoubleValue defineInRange(String key, double defaultValue, double min, double max) {
            return add(new DoubleValue(key, comment, defaultValue, min, max));
        }

        public IntValue defineInRange(String key, int defaultValue, int min, int max) {
            return add(new IntValue(key, comment, defaultValue, min, max));
        }

        public <E extends Enum<E>> EnumValue<E> defineEnum(String key, E defaultValue) {
            return add(new EnumValue<>(key, comment, defaultValue));
        }

        private <V extends ConfigValue<?>> V add(V value) {
            values.add(value);
            comment = "";
            return value;
        }

        public ConfigSpec build() {
            return new ConfigSpec(values);
        }
    }
}
