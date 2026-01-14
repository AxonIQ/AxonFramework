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

package org.axonframework.extension.micronaut.actuator.axonserver;

import org.axonframework.extension.micronaut.actuator.HealthStatus;
import org.springframework.boot.actuate.health.SimpleStatusAggregator;
import org.springframework.boot.actuate.health.Status;

/**
 * A {@link SimpleStatusAggregator} implementation determining the overall health of an Axon Framework application using
 * Axon Server. Adds the {@link HealthStatus#WARN} status to the regular set of {@link Status statuses}.
 *
 * @author Steven van Beelen
 * @author Marc Gathier
 * @since 4.6.0
 */
public class AxonServerStatusAggregator extends SimpleStatusAggregator {

    /**
     * Constructs a default Axon Server specific {@link SimpleStatusAggregator}. Adds the {@link HealthStatus#WARN}
     * after {@link Status#OUT_OF_SERVICE} and before {@link Status#UP}.
     */
    public AxonServerStatusAggregator() {
        super(Status.DOWN, Status.OUT_OF_SERVICE, HealthStatus.WARN, Status.UP, Status.UNKNOWN);
    }
}
