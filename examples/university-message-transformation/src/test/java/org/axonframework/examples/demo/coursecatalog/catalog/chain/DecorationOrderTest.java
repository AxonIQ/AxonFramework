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

package org.axonframework.examples.demo.coursecatalog.catalog.chain;

import io.axoniq.framework.axonserver.connector.configuration.AxonServerConfigurationEnhancer;
import io.axoniq.framework.messaging.transformation.events.EventTransformerChain;
import io.axoniq.framework.messaging.transformation.events.TransformingEventStore;
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DecorationOrderTest {

    @Test
    void transformingEventStoreWrapsOuterThanInterceptingEventStore() {
        // Boot a real configurer with the transformation chain registered, so the
        // EventTransformationConfigurationEnhancer installs TransformingEventStore.
        // Disable the Axon Server enhancer so the test runs in-memory.
        EventSourcingConfigurer configurer = EventSourcingConfigurer.create()
                .componentRegistry(r -> r
                        .disableEnhancer(AxonServerConfigurationEnhancer.class)
                        .registerComponent(EventTransformerChain.class,
                                           cfg -> EventTransformerChain.builder().build()));

        AxonConfiguration configuration = configurer.start();
        try {
            EventStore eventStore = configuration.getComponent(EventStore.class);
            // Outermost wrap proves the chain runs on the event stream before any
            // handler-side interceptor sees the events.
            assertThat(eventStore)
                    .as("outermost EventStore decorator")
                    .isInstanceOf(TransformingEventStore.class);
        } finally {
            configuration.shutdown();
        }
    }
}
