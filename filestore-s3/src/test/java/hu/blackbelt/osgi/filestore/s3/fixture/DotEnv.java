package hu.blackbelt.osgi.filestore.s3.fixture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, zero-dependency loader for a {@code .env} file used by opt-in integration tests.
 *
 * <p>Lookup order for any key:
 * <ol>
 *   <li>real process environment variable (so CI can inject secrets)</li>
 *   <li>entry in the {@code .env} file</li>
 *   <li>the supplied default, or {@code null}</li>
 * </ol>
 *
 * <p>The file location defaults to {@code filestore-s3/.env} relative to the module working
 * directory and can be overridden with the {@code FILESTORE_ENV_FILE} environment variable.
 * A missing file is not an error — it simply means no values are available and the gated
 * tests will skip.
 */
public final class DotEnv {

    public static final String ENV_FILE_OVERRIDE = "FILESTORE_ENV_FILE";
    private static final String DEFAULT_ENV_FILE = ".env";

    private static final Map<String, String> VALUES = load();

    private DotEnv() {
    }

    private static Map<String, String> load() {
        final Path path = resolvePath();
        if (path == null || !Files.isReadable(path)) {
            return Collections.emptyMap();
        }
        final Map<String, String> values = new HashMap<>();
        try {
            final List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (final String raw : lines) {
                final String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                final int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                final String key = line.substring(0, eq).trim();
                values.put(key, unquote(line.substring(eq + 1).trim()));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read env file: " + path, e);
        }
        return Collections.unmodifiableMap(values);
    }

    private static Path resolvePath() {
        final String override = System.getenv(ENV_FILE_OVERRIDE);
        return override != null && !override.isEmpty() ? Paths.get(override) : Paths.get(DEFAULT_ENV_FILE);
    }

    private static String unquote(final String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** @return the value for {@code key}, or {@code null} when absent everywhere. */
    public static String get(final String key) {
        final String fromEnvironment = System.getenv(key);
        if (fromEnvironment != null && !fromEnvironment.isEmpty()) {
            return fromEnvironment;
        }
        return VALUES.get(key);
    }

    /** @return the value for {@code key}, or {@code defaultValue} when absent or empty. */
    public static String get(final String key, final String defaultValue) {
        final String value = get(key);
        return value == null || value.isEmpty() ? defaultValue : value;
    }

    /** @return {@code true} only when the key resolves to the literal {@code "true"}, case-insensitively. */
    public static boolean isEnabled(final String key) {
        return Boolean.parseBoolean(get(key, "false"));
    }
}
