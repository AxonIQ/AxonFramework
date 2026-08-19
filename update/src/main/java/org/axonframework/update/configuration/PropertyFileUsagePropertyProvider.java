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

package org.axonframework.update.configuration;

import org.axonframework.common.annotation.Internal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Properties;

/**
 * A {@link UsagePropertyProvider} that reads the Axoniq Data Collection properties from a file located at
 * {@code ~/.axoniq/update-checker.properties}.
 * <p>
 * If the file does not exist, it creates a default file with the default telemetry endpoint and opt-out settings, using
 * property names {@code "telemetry_url"} and {@code "disabled"} respectively. If the file cannot be written, it will
 * log a debug message and skip the property provider.
 *
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
@Internal
public class PropertyFileUsagePropertyProvider implements UsagePropertyProvider {

    private static final Logger logger = LoggerFactory.getLogger(PropertyFileUsagePropertyProvider.class);

    private static final String AXONIQ_PROPERTIES_PATH = "/.axoniq/update-checker.properties";
    private static final String TELEMETRY_URL_FIELD_NAME = "telemetry_url";
    private static final String DISABLED_PROPERTY_NAME = "disabled";

    private Boolean disabled;
    private String telemetryEndpoint;
    private boolean loaded = false;

    @Override
    public Boolean getDisabled() {
        ensureLoaded();
        return disabled;
    }

    @Override
    public String getUrl() {
        ensureLoaded();
        return telemetryEndpoint;
    }

    private void ensureLoaded() {
        if (!loaded) {
            load();
            loaded = true;
        }
    }

    private void load() {
        try {
            var installationIdFile = getFile();
            if (installationIdFile == null) {
                logger.debug("Could not determine user home directory. Skipping property provider from file.");
                return;
            }
            Properties properties = new Properties();
            if (!installationIdFile.exists()) {
                createDefaultFile();
            }
            try (InputStream in = Files.newInputStream(installationIdFile.toPath())) {
                properties.load(in);
            }
            this.telemetryEndpoint = properties.getProperty(TELEMETRY_URL_FIELD_NAME);
            this.disabled = Boolean.valueOf(properties.getProperty(DISABLED_PROPERTY_NAME));
        } catch (Exception e) {
            logger.debug("Failed to load Axoniq properties from file: {}. Skipping property provider from file.",
                         getFile() != null ? getFile().getAbsolutePath() : "unknown", e);
        }
    }

    @Override
    public int priority() {
        return 0;
    }

    private void createDefaultFile() throws IOException {
        File file = getFile();
        if (file == null) {
            logger.debug("Could not determine user home directory. Skipping creation of default properties file.");
            return;
        }
        logger.info("Creating default Axoniq Data Collection properties file at: {}", file.getAbsolutePath());
        Properties properties = new Properties();
        properties.setProperty(TELEMETRY_URL_FIELD_NAME, DefaultUsagePropertyProvider.INSTANCE.getUrl());
        properties.setProperty(DISABLED_PROPERTY_NAME,
                               String.valueOf(DefaultUsagePropertyProvider.INSTANCE.getDisabled()));
        // Ensure the parent directory exists
        File parentDir = file.getParentFile();
        if (!parentDir.exists() && !parentDir.mkdirs()) {
            throw new IOException("Failed to create parent directory: " + parentDir.getAbsolutePath());
        }
        try (OutputStream out = Files.newOutputStream(file.toPath())) {
            properties.store(out, "Axoniq Anonymous Usage Reporting");
        }
    }

    private File getFile() {
        String pwdDir = System.getProperty("user.home");
        if (pwdDir == null) {
            return null;
        }
        String installationIdFilePath = pwdDir + AXONIQ_PROPERTIES_PATH;
        return new File(installationIdFilePath);
    }
}
