/*
 * Copyright (c) 2010-2026. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.examples.demo.coursecatalog;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Runtime configuration toggles loaded from {@code application.properties} on the
 * classpath and an optional {@code .env} file in the working directory. Default
 * {@code axon.server.enabled=false} keeps the demo portable for CI; AxonIQ Platform
 * credentials are absent by default, and the integration stays dormant unless they
 * are filled in.
 *
 * <p>Resolution order for the platform credentials, from highest to lowest priority:
 * real environment variables, {@code .env} file, {@code application.properties}.
 */
public final class ConfigurationProperties {

    private static final Logger logger = LoggerFactory.getLogger(ConfigurationProperties.class);

    private static final String AXON_SERVER_ENABLED = "axon.server.enabled";
    private static final String PLATFORM_ENVIRONMENT_ID = "axoniq.platform.environment-id";
    private static final String PLATFORM_ACCESS_TOKEN = "axoniq.platform.access-token";
    private static final String PLATFORM_APPLICATION_NAME = "axoniq.platform.application-name";

    private static final String ENV_PLATFORM_ENVIRONMENT_ID = "AXONIQ_PLATFORM_ENVIRONMENT_ID";
    private static final String ENV_PLATFORM_ACCESS_TOKEN = "AXONIQ_PLATFORM_ACCESS_TOKEN";
    private static final String ENV_PLATFORM_APPLICATION_NAME = "AXONIQ_PLATFORM_APPLICATION_NAME";

    private boolean axonServerEnabled = false;
    private @Nullable String platformEnvironmentId;
    private @Nullable String platformAccessToken;
    private @Nullable String platformApplicationName;

    /** @return a fresh instance with defaults */
    public static ConfigurationProperties defaults() {
        return new ConfigurationProperties();
    }

    /**
     * Reads {@code application.properties} from the classpath, layers a local
     * {@code .env} file (working directory) on top, and finally lets real
     * environment variables override everything.
     *
     * @return the loaded properties
     */
    public static ConfigurationProperties load() {
        ConfigurationProperties props = new ConfigurationProperties();
        applyClasspathProperties(props);
        Map<String, String> dotenv = loadDotenv();
        props.platformEnvironmentId = resolve(ENV_PLATFORM_ENVIRONMENT_ID, dotenv, props.platformEnvironmentId);
        props.platformAccessToken = resolve(ENV_PLATFORM_ACCESS_TOKEN, dotenv, props.platformAccessToken);
        props.platformApplicationName = resolve(ENV_PLATFORM_APPLICATION_NAME, dotenv, props.platformApplicationName);
        return props;
    }

    private static void applyClasspathProperties(ConfigurationProperties props) {
        Properties file = loadPropertiesFile();
        if (file == null) {
            logger.info("No application.properties on the classpath; using default configuration");
            return;
        }
        props.axonServerEnabled = isAxonServerEnabled(file, props.axonServerEnabled);
        props.platformEnvironmentId = file.getProperty(PLATFORM_ENVIRONMENT_ID, props.platformEnvironmentId);
        props.platformAccessToken = file.getProperty(PLATFORM_ACCESS_TOKEN, props.platformAccessToken);
        props.platformApplicationName = file.getProperty(PLATFORM_APPLICATION_NAME, props.platformApplicationName);
    }

    private static @Nullable Properties loadPropertiesFile() {
        Properties properties = new Properties();
        try (InputStream input = ConfigurationProperties.class.getClassLoader()
                                                              .getResourceAsStream("application.properties")) {
            if (input != null) {
                properties.load(input);
                return properties;
            }
        } catch (IOException e) {
            logger.warn("Error loading application.properties: {}", e.getMessage(), e);
        }
        return null;
    }

    /**
     * Reads a {@code .env} file in the current working directory, parsing
     * {@code KEY=value} lines and skipping blanks/comments. Quoted values are
     * unwrapped. Missing file is silently ignored.
     */
    private static Map<String, String> loadDotenv() {
        Path dotenv = Path.of(".env");
        if (!Files.isReadable(dotenv)) {
            return Map.of();
        }
        Map<String, String> values = new HashMap<>();
        try {
            List<String> lines = Files.readAllLines(dotenv);
            for (String raw : lines) {
                parseDotenvLine(raw, values);
            }
            logger.info("Loaded {} key(s) from .env", values.size());
        } catch (IOException e) {
            logger.warn("Could not read .env: {}", e.getMessage(), e);
        }
        return values;
    }

    private static void parseDotenvLine(String raw, Map<String, String> sink) {
        String line = raw.trim();
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }
        int eq = line.indexOf('=');
        if (eq <= 0) {
            return;
        }
        String key = line.substring(0, eq).trim();
        String value = unquote(line.substring(eq + 1).trim());
        if (!value.isEmpty()) {
            sink.put(key, value);
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static boolean isAxonServerEnabled(Properties props, boolean fallback) {
        String value = props.getProperty(ConfigurationProperties.AXON_SERVER_ENABLED);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    /** Real env var, then .env, then the existing fallback. Blanks are treated as absent. */
    private static @Nullable String resolve(String envName, Map<String, String> dotenv, @Nullable String fallback) {
        String fromEnv = System.getenv(envName);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        String fromDotenv = dotenv.get(envName);
        if (fromDotenv != null && !fromDotenv.isBlank()) {
            return fromDotenv;
        }
        return fallback;
    }

    /** @return whether to connect to Axon Server */
    public boolean axonServerEnabled() {
        return axonServerEnabled;
    }

    /** @return whether all three AxonIQ Platform credentials are present so the integration can be wired in */
    public boolean platformEnabled() {
        return platformEnvironmentId != null && platformAccessToken != null && platformApplicationName != null;
    }

    /** @return the platform environment id, or {@code null} when not configured */
    public @Nullable String platformEnvironmentId() {
        return platformEnvironmentId;
    }

    /** @return the platform access token, or {@code null} when not configured */
    public @Nullable String platformAccessToken() {
        return platformAccessToken;
    }

    /** @return the application name shown in the platform UI, or {@code null} when not configured */
    public @Nullable String platformApplicationName() {
        return platformApplicationName;
    }
}
