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

package org.axonframework.extension.micronaut.actuator;

import org.springframework.boot.actuate.health.Status;

/**
 * Utility class holding additional {@link Status} instances.
 *
 * @author Steven van Beelen
 * @author Marc Gathier
 * @since 4.6.0
 */
public abstract class HealthStatus {

    /**
     * A {@link Status} suggesting the connection is still working but not at full capacity.
     */
    public static final Status WARN = new Status("WARN");

    private HealthStatus() {
        // Utility class
    }
}
