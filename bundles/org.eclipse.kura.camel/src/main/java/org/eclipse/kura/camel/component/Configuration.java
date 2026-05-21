/*******************************************************************************
 * Copyright (c) 2016, 2020 Red Hat Inc and others
 * 
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 * 
 * SPDX-License-Identifier: EPL-2.0
 * 
 * Contributors:
 *  Red Hat Inc
 *******************************************************************************/
package org.eclipse.kura.camel.component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.slf4j.Logger;

/**
 * A few helper methods for consuming configuration from a properties map
 */
public final class Configuration {

    private Configuration() {
    }

    /**
     * Get a string value, defaulting to {@code null}
     *
     * @param properties
     *            the properties to read from, may be {@code null}
     * @param key
     *            the key to read, may be {@code null}
     * @return the string value or {@code null}
     */
    public static String asString(final Map<String, ?> properties, final String key) {
        return asString(properties, key, null);
    }

    /**
     * Get a string value
     *
     * @param properties
     *            the properties to read from, may be {@code null}
     * @param key
     *            the key to read, may be {@code null}
     * @param defaultValue
     *            the default value, may be {@code null}
     * @return the string value or the default value
     */
    public static String asString(final Map<String, ?> properties, final String key, final String defaultValue) {
        if (properties == null) {
            return defaultValue;
        }

        final Object value = properties.get(key);
        if (value instanceof String) {
            return (String) value;
        }

        return defaultValue;
    }

    /**
     * Get a string value, unless it is empty
     * <p>
     * If the properties map contains the string, but the string is empty by {@link String#isEmpty()}, then
     * also the default value will be returned.
     * </p>
     *
     * @param properties
     *            the properties to read from, may be {@code null}
     * @param key
     *            the key to read, may be {@code null}
     * @param defaultValue
     *            the default value, may be {@code null}
     * @return the string value or the default value
     */
    public static String asStringNotEmpty(final Map<String, ?> properties, final String key, final String defaultValue) {
        if (properties == null) {
            return defaultValue;
        }

        final Object value = properties.get(key);
        if (!(value instanceof String)) {
            return defaultValue;
        }

        String stringValue = (String) value;
        if (stringValue.isEmpty()) {
            return defaultValue;
        }

        return stringValue;
    }

    public static Integer asInteger(final Map<String, ?> properties, final String key) {
        return asInteger(properties, key, null);
    }

    public static Integer asInteger(final Map<String, ?> properties, final String key, final Integer defaultValue) {
        if (properties == null) {
            return defaultValue;
        }

        final Object value = properties.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }

        return defaultValue;
    }

    public static int asInt(final Map<String, ?> properties, final String key, final int defaultValue) {
        if (properties == null) {
            return defaultValue;
        }

        final Object value = properties.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }

        return defaultValue;
    }

    public static Long asLong(final Map<String, ?> properties, final String key) {
        return asLong(properties, key, null);
    }

    public static Long asLong(final Map<String, ?> properties, final String key, final Long defaultValue) {
        if (properties == null) {
            return defaultValue;
        }

        final Object value = properties.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }

        return defaultValue;
    }

    public static long asLong(final Map<String, ?> properties, final String key, final long defaultValue) {
        if (properties == null) {
            return defaultValue;
        }

        final Object value = properties.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }

        return defaultValue;
    }

    public static Double asDouble(final Map<String, ?> properties, final String key) {
        return asDouble(properties, key, null);
    }

    public static Double asDouble(final Map<String, ?> properties, final String key, final Double defaultValue) {
        if (properties == null) {
            return defaultValue;
        }

        final Object value = properties.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }

        return defaultValue;
    }

    public static double asDouble(final Map<String, ?> properties, final String key, final double defaultValue) {
        if (properties == null) {
            return defaultValue;
        }

        final Object value = properties.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }

        return defaultValue;
    }

    /**
     * Get a boolean parameter from the configuration
     *
     * @param properties
     *            the configuration
     * @param key
     *            the key to fetch
     * @return the boolean value from the configuration, or {@code false} if the property set is {@code null}, the
     *         property is not set or it is not boolean
     */
    public static boolean asBoolean(Map<String, ?> properties, String key) {
        return asBoolean(properties, key, false);
    }

    public static Boolean asBoolean(Map<String, ?> properties, String key, Boolean defaultValue) {
        if (properties == null) {
            return defaultValue;
        }

        final Object value = properties.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }

        return defaultValue;
    }

    public static boolean asBoolean(Map<String, ?> properties, String key, boolean defaultValue) {
        if (properties == null) {
            return defaultValue;
        }

        final Object value = properties.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }

        return defaultValue;
    }

    /**
     * Read the file at {@code filePath} and return its UTF-8 content.
     * Returns {@code null} (and logs a warning) on missing path or read failure,
     * so callers can fall back to an inline configuration value.
     *
     * @param filePath
     *            absolute path; {@code null}/blank returns {@code null} silently
     * @param logger
     *            logger for diagnostics; may be {@code null}
     * @return file content or {@code null} on any failure
     */
    public static String tryReadFile(final String filePath, final Logger logger) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return null;
        }
        final Path p = Paths.get(filePath.trim());
        try {
            return Files.readString(p);
        } catch (IOException e) {
            if (logger != null) {
                logger.warn("Failed to read {}: {} - falling back to inline value", filePath, e.toString());
            }
            return null;
        }
    }

    /**
     * Derive a file name from a path string (basename only). Used to pick the
     * routes-loader extension when content was sourced from disk.
     *
     * @param filePath
     *            file path; {@code null}/blank returns {@code null}
     * @return base name (e.g. "MesRoute.java") or {@code null}
     */
    public static String fileNameOf(final String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return null;
        }
        final Path p = Paths.get(filePath.trim()).getFileName();
        return p == null ? null : p.toString();
    }
}
