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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Runtime configuration toggles loaded from {@code application.properties}.
 * Default {@code axon.server.enabled=false} keeps the demo portable for CI.
 */
public final class ConfigurationProperties {

    private static final Logger logger = LoggerFactory.getLogger(ConfigurationProperties.class);

    boolean axonServerEnabled = false;

    /** @return a fresh instance with defaults */
    public static ConfigurationProperties defaults() {
        return new ConfigurationProperties();
    }

    /** @return properties loaded from {@code application.properties} on the classpath */
    public static ConfigurationProperties load() {
        ConfigurationProperties props = new ConfigurationProperties();
        Properties properties = loadPropertiesFile();
        if (properties != null) {
            String value = properties.getProperty("axon.server.enabled");
            if (value != null) {
                props.axonServerEnabled = Boolean.parseBoolean(value);
            }
        } else {
            logger.info("No application.properties on the classpath; using default configuration");
        }
        return props;
    }

    private static Properties loadPropertiesFile() {
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

    /** @return whether to connect to Axon Server */
    public boolean axonServerEnabled() {
        return axonServerEnabled;
    }

    /**
     * @param axonServerEnabled overrides the loaded value (intended for tests)
     * @return this instance
     */
    public ConfigurationProperties axonServerEnabled(boolean axonServerEnabled) {
        this.axonServerEnabled = axonServerEnabled;
        return this;
    }
}
