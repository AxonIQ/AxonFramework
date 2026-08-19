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

package org.axonframework.examples.demo.multitenancy;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Runtime configuration toggles for the demo, loaded from {@code application.properties}.
 *
 * @param axonServerEnabled        whether the demo should drive tenants from Axon Server contexts rather than the
 *                                 bundled in-memory tenant provider
 * @param persistentStreamsEnabled whether the course-statistics projection runs on a persistent stream instead of a
 *                                 pooled streaming processor, only read while {@code axonServerEnabled} is
 *                                 {@code true}
 */
public record DemoProperties(boolean axonServerEnabled, boolean persistentStreamsEnabled) {

    /**
     * Loads the properties from the {@code application.properties} classpath resource, defaulting to
     * the in-memory setup when the resource or a property is absent.
     *
     * @return the loaded configuration properties
     */
    public static DemoProperties load() {
        Properties properties = new Properties();
        try (InputStream stream = DemoProperties.class.getResourceAsStream("/application.properties")) {
            if (stream != null) {
                properties.load(stream);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not load application.properties", e);
        }
        return new DemoProperties(
                Boolean.parseBoolean(properties.getProperty("demo.axon-server.enabled", "false")),
                Boolean.parseBoolean(properties.getProperty("demo.axon-server.persistent-streams.enabled", "false"))
        );
    }
}
