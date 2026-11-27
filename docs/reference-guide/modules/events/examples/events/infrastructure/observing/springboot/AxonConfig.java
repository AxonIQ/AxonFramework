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
package events.infrastructure.observing.springboot;

// tag::observing-appends-springboot[]
import org.axonframework.common.configuration.DecoratorDefinition;
import org.axonframework.eventsourcing.eventstore.AppendObservation;
import org.axonframework.eventsourcing.eventstore.AppendObserver;
import org.axonframework.eventsourcing.eventstore.AppendObservingEventStorageEngine;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.LongAdder;

@Configuration
public class AxonConfig {

    private final LongAdder rejectedAppends = new LongAdder();

    @Bean
    public DecoratorDefinition<EventStorageEngine, AppendObservingEventStorageEngine> appendObservation() {
        return AppendObservingEventStorageEngine.decoratorDefinition();
    }

    @Bean
    public AppendObserver rejectedAppendCounter() {
        return observation -> {
            if (observation.outcome() instanceof AppendObservation.Outcome.Rejected) {
                rejectedAppends.increment();
            }
        };
    }
}
// end::observing-appends-springboot[]
