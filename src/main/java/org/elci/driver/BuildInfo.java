package org.elci.driver;

import java.io.IOException;
import java.util.Properties;

/** Build metadata generated from gradle.properties, also embedded in the native CLI. */
public final class BuildInfo {
    private static final Properties METADATA = load();
    public static final String VERSION = required("version");
    public static final String JULC_VERSION = required("julcVersion");

    private BuildInfo() {}

    private static Properties load() {
        try (var input = BuildInfo.class.getResourceAsStream("/elci-build.properties")) {
            if (input == null) throw new IllegalStateException("Missing build metadata");
            var result = new Properties();
            result.load(input);
            return result;
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static String required(String key) {
        var value = METADATA.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + key);
        return value;
    }
}
