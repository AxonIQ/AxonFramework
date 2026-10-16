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

package org.axonframework.messaging.core.annotation;

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.configuration.ComponentNotFoundException;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.AsyncInMemoryStreamableEventSource;
import org.axonframework.messaging.eventhandling.EventTestUtils;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.junit.jupiter.api.*;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Test class validating {@link InjectComponent} annotated parameters of event handlers in an event processor module,
 * where parameters are resolved through a hierarchy of the module's and the application's parameter resolvers.
 *
 * @author Allard Buijze
 */
class InjectComponentEventProcessingTest {

    private static final Greeter DEFAULT_GREETER = new Greeter("default");
    private static final Greeter NAMED_GREETER = new Greeter("named");

    private AsyncInMemoryStreamableEventSource eventSource;
    private MessagingConfigurer configurer;
    private AxonConfiguration configuration;

    @BeforeEach
    void setUp() {
        eventSource = new AsyncInMemoryStreamableEventSource();
        configurer = MessagingConfigurer.create();
    }

    @AfterEach
    void tearDown() {
        if (configuration != null) {
            configuration.shutdown();
        }
    }

    @Test
    void injectsComponentsByTypeAndByNameIntoEventHandlerOfPooledStreamingProcessor() {
        // given
        configurer.componentRegistry(cr -> cr.registerComponent(Greeter.class, c -> DEFAULT_GREETER)
                                             .registerComponent(Greeter.class, "namedGreeter", c -> NAMED_GREETER));
        AtomicReference<Greeter> unannotated = new AtomicReference<>();
        AtomicReference<Greeter> byType = new AtomicReference<>();
        AtomicReference<Greeter> byName = new AtomicReference<>();
        Object handler = new Object() {
            @EventHandler
            public void handle(String event,
                               Greeter unannotatedGreeter,
                               @InjectComponent Greeter byTypeGreeter,
                               @InjectComponent(name = "namedGreeter") Greeter byNameGreeter) {
                unannotated.set(unannotatedGreeter);
                byType.set(byTypeGreeter);
                byName.set(byNameGreeter);
            }
        };
        registerPooledStreamingProcessorWith(handler);
        configuration = configurer.start();

        // when
        eventSource.publishMessage(EventTestUtils.asEventMessage("test-event"));

        // then
        await().untilAsserted(() -> {
            assertThat(unannotated.get()).isSameAs(DEFAULT_GREETER);
            assertThat(byType.get()).isSameAs(DEFAULT_GREETER);
            assertThat(byName.get()).isSameAs(NAMED_GREETER);
        });
    }

    @Test
    void failsOnStartWhenNamedComponentIsMissing() {
        // given
        configurer.componentRegistry(cr -> cr.registerComponent(Greeter.class, c -> DEFAULT_GREETER));
        Object handler = new Object() {
            @EventHandler
            public void handle(String event, @InjectComponent(name = "namedGreeter") Greeter greeter) {
            }
        };
        registerPooledStreamingProcessorWith(handler);

        // when / then
        assertThatThrownBy(() -> configuration = configurer.start())
                .hasRootCauseInstanceOf(ComponentNotFoundException.class)
                .rootCause()
                .hasMessageContaining(Greeter.class.getName())
                .hasMessageContaining("namedGreeter");
    }

    @Test
    void failsOnStartWhenComponentOfTypeIsMissing() {
        // given
        Object handler = new Object() {
            @EventHandler
            public void handle(String event, @InjectComponent Greeter greeter) {
            }
        };
        registerPooledStreamingProcessorWith(handler);

        // when / then
        assertThatThrownBy(() -> configuration = configurer.start())
                .hasRootCauseInstanceOf(ComponentNotFoundException.class)
                .rootCause()
                .hasMessageContaining(Greeter.class.getName());
    }

    private void registerPooledStreamingProcessorWith(Object handler) {
        configurer.eventProcessing(
                ep -> ep.pooledStreaming(
                        ps -> ps.defaults(d -> d.eventSource(eventSource))
                                .defaultProcessor("test-processor",
                                                  components -> components.autodetected("handler", c -> handler))
                )
        );
    }

    private record Greeter(String greeting) {

    }
}
