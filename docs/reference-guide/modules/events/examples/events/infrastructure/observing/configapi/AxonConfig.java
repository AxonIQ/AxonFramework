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
package events.infrastructure.observing.configapi;

// tag::observing-appends-configapi[]
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.eventstore.AppendObservation;
import org.axonframework.eventsourcing.eventstore.AppendObserver;
import org.axonframework.eventsourcing.eventstore.AppendObservingEventStorageEngine;

import java.util.concurrent.atomic.LongAdder;

public class AxonConfig {

    private final LongAdder rejectedAppends = new LongAdder();

    public void configureAppendObservation(EventSourcingConfigurer configurer) {
        configurer.componentRegistry(registry -> registry
                .registerComponent(AppendObserver.class, config -> observation -> {
                    if (observation.outcome() instanceof AppendObservation.Outcome.Rejected) {
                        rejectedAppends.increment();
                    }
                })
                .registerDecorator(AppendObservingEventStorageEngine.decoratorDefinition())
        );
    }
}
// end::observing-appends-configapi[]
