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

package org.axonframework.messaging.eventhandling.processing.streaming.pooled;

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.configuration.ComponentDefinition;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.configuration.MessagingConfigurer;
import org.axonframework.messaging.eventhandling.AsyncInMemoryStreamableEventSource;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.SimpleEventHandlingComponent;
import org.axonframework.messaging.eventhandling.configuration.EventHandlingComponentsConfigurer;
import org.axonframework.messaging.eventhandling.configuration.EventProcessorModule;
import org.axonframework.messaging.eventhandling.processing.streaming.StreamingEventProcessor;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.Segment;
import org.axonframework.messaging.eventhandling.processing.streaming.segmenting.SegmentChangeListener;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventstreaming.StreamableEventSource;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@link HandlerAwareProcessorCustomization} hook on
 * {@link PooledStreamingEventProcessorModule}: that registered customizations are invoked at processor build
 * time, scoped to the handlers assigned to that specific processor, and that their mutations to the
 * {@link PooledStreamingEventProcessorConfiguration} are picked up by the constructed processor.
 *
 * @author Allard Buijze
 * @since 5.2.0
 */
class HandlerAwareProcessorCustomizationTest {

    @Nested
    class Invocation {

        @Test
        void invokedOnceWithThisProcessorsHandlers() {
            // given
            var capturedProcessorNames = new ArrayList<String>();
            var capturedHandlerLists = new ArrayList<List<EventHandlingComponent>>();
            HandlerAwareProcessorCustomization customization =
                    (cfg, processorName, handlers, processorConfig) -> {
                        capturedProcessorNames.add(processorName);
                        capturedHandlerLists.add(handlers);
                        return processorConfig;
                    };

            // when
            AxonConfiguration configuration = buildConfigurationWithModule(
                    "alpha",
                    singleComponent("componentA"),
                    customization
            );
            triggerProcessorBuild(configuration, "alpha");

            // then
            assertThat(capturedProcessorNames).containsExactly("alpha");
            assertThat(capturedHandlerLists).hasSize(1);
            assertThat(capturedHandlerLists.get(0)).hasSize(1);
        }

        @Test
        void scopedPerProcessorWhenMultipleModulesExist() {
            // given - two processors with distinct handler component names; both should each see only their own
            var byProcessor = new java.util.LinkedHashMap<String, List<EventHandlingComponent>>();
            HandlerAwareProcessorCustomization customization =
                    (cfg, processorName, handlers, processorConfig) -> {
                        byProcessor.put(processorName, handlers);
                        return processorConfig;
                    };

            var configurer = MessagingConfigurer.create();
            configurer.componentRegistry(cr -> cr.registerComponent(
                    ComponentDefinition
                            .ofTypeAndName(HandlerAwareProcessorCustomization.class, "test-customization")
                            .withInstance(customization)
            ));
            configurer.eventProcessing(ep -> ep.pooledStreaming(ps -> ps
                    .processor(moduleFor("alpha", singleComponent("componentA")))
                    .processor(moduleFor("beta", singleComponent("componentB")))
            ));
            var configuration = configurer.build();
            triggerProcessorBuild(configuration, "alpha");
            triggerProcessorBuild(configuration, "beta");

            // then - each processor saw exactly its own handler list
            assertThat(byProcessor).containsOnlyKeys("alpha", "beta");
            assertThat(byProcessor.get("alpha")).hasSize(1);
            assertThat(byProcessor.get("beta")).hasSize(1);
            // and the two handler instances are distinct
            assertThat(byProcessor.get("alpha").get(0))
                    .isNotSameAs(byProcessor.get("beta").get(0));
        }

        @Test
        void multipleCustomizationsAreAllApplied() {
            // given - two customizations recording their invocations
            var invoked = new ArrayList<String>();
            HandlerAwareProcessorCustomization first =
                    (cfg, name, handlers, processorConfig) -> {
                        invoked.add("first");
                        return processorConfig;
                    };
            HandlerAwareProcessorCustomization second =
                    (cfg, name, handlers, processorConfig) -> {
                        invoked.add("second");
                        return processorConfig;
                    };

            // when - register both customizations under different names
            var configurer = MessagingConfigurer.create();
            configurer.componentRegistry(cr -> cr.registerComponent(
                    ComponentDefinition
                            .ofTypeAndName(HandlerAwareProcessorCustomization.class, "first")
                            .withInstance(first)
            ));
            configurer.componentRegistry(cr -> cr.registerComponent(
                    ComponentDefinition
                            .ofTypeAndName(HandlerAwareProcessorCustomization.class, "second")
                            .withInstance(second)
            ));
            configurer.eventProcessing(ep -> ep.pooledStreaming(
                    ps -> ps.processor(moduleFor("alpha", singleComponent("componentA")))
            ));
            var configuration = configurer.build();
            triggerProcessorBuild(configuration, "alpha");

            // then - both customizations were invoked; the order is not guaranteed
            assertThat(invoked).containsExactlyInAnyOrder("first", "second");
        }
    }

    @Nested
    class SideEffects {

        @Test
        void registeredSegmentChangeListenerIsObservedByCoordinator() {
            // given - a customization that registers a SegmentChangeListener returning a fixed reset position
            TrackingToken resetPosition = new GlobalSequenceTrackingToken(42);
            var listenerInvocations = new AtomicInteger();
            var capturedConfig = new java.util.concurrent.atomic.AtomicReference<PooledStreamingEventProcessorConfiguration>();
            HandlerAwareProcessorCustomization customization =
                    (cfg, name, handlers, processorConfig) -> {
                        processorConfig.addSegmentChangeListener(
                                SegmentChangeListener.onClaimWithReset(segment -> {
                                    listenerInvocations.incrementAndGet();
                                    return CompletableFuture.completedFuture(resetPosition);
                                })
                        );
                        capturedConfig.set(processorConfig);
                        return processorConfig;
                    };

            AxonConfiguration configuration = buildConfigurationWithModule(
                    "alpha",
                    singleComponent("componentA"),
                    customization
            );
            triggerProcessorBuild(configuration, "alpha");

            // when - drive the composed listener directly
            TrackingToken result =
                    capturedConfig.get()
                                  .segmentChangeListener()
                                  .onSegmentClaimed(Segment.ROOT_SEGMENT)
                                  .join();

            // then - the customization's listener was invoked and contributed the reset position
            assertThat(listenerInvocations).hasValue(1);
            assertThat(result).isEqualTo(resetPosition);
        }

        @Test
        void returnedConfigurationIsThreadedToNextCustomization() {
            // given - first customization records the original config; second checks identity
            var firstSeen = new java.util.concurrent.atomic.AtomicReference<PooledStreamingEventProcessorConfiguration>();
            var secondSeen = new java.util.concurrent.atomic.AtomicReference<PooledStreamingEventProcessorConfiguration>();

            HandlerAwareProcessorCustomization first = (cfg, name, handlers, processorConfig) -> {
                firstSeen.set(processorConfig);
                return processorConfig;
            };
            HandlerAwareProcessorCustomization second = (cfg, name, handlers, processorConfig) -> {
                secondSeen.set(processorConfig);
                return processorConfig;
            };

            // when
            var configurer = MessagingConfigurer.create();
            configurer.componentRegistry(cr -> cr.registerComponent(
                    ComponentDefinition
                            .ofTypeAndName(HandlerAwareProcessorCustomization.class, "first")
                            .withInstance(first)
            ));
            configurer.componentRegistry(cr -> cr.registerComponent(
                    ComponentDefinition
                            .ofTypeAndName(HandlerAwareProcessorCustomization.class, "second")
                            .withInstance(second)
            ));
            configurer.eventProcessing(ep -> ep.pooledStreaming(
                    ps -> ps.processor(moduleFor("alpha", singleComponent("componentA")))
            ));
            var configuration = configurer.build();
            triggerProcessorBuild(configuration, "alpha");

            // then - the second customization received the same instance the first one returned (mutate-and-return-this)
            assertThat(firstSeen.get()).isNotNull();
            assertThat(secondSeen.get()).isSameAs(firstSeen.get());
        }
    }

    private static void triggerProcessorBuild(AxonConfiguration configuration, String processorName) {
        // Resolving the StreamingEventProcessor component materialises the processor, which is what
        // fires the HandlerAwareProcessorCustomization chain.
        configuration.getModuleConfiguration("EventProcessor[" + processorName + "]")
                     .flatMap(m -> m.getOptionalComponent(StreamingEventProcessor.class, processorName))
                     .orElseThrow();
    }

    private static AxonConfiguration buildConfigurationWithModule(
            String processorName,
            Function<EventHandlingComponentsConfigurer.RequiredComponentPhase,
                    EventHandlingComponentsConfigurer.CompletePhase> componentsConfigurer,
            HandlerAwareProcessorCustomization customization
    ) {
        var configurer = MessagingConfigurer.create();
        configurer.componentRegistry(cr -> cr.registerComponent(
                ComponentDefinition
                        .ofTypeAndName(HandlerAwareProcessorCustomization.class, "test-customization")
                        .withInstance(customization)
        ));
        configurer.eventProcessing(ep -> ep.pooledStreaming(
                ps -> ps.processor(moduleFor(processorName, componentsConfigurer))
        ));
        return configurer.build();
    }

    private static PooledStreamingEventProcessorModule moduleFor(
            String processorName,
            Function<EventHandlingComponentsConfigurer.RequiredComponentPhase,
                    EventHandlingComponentsConfigurer.CompletePhase> componentsConfigurer
    ) {
        return EventProcessorModule
                .pooledStreaming(processorName)
                .eventHandlingComponents(componentsConfigurer)
                .customized((cfg, c) -> c.eventSource(
                        cfg.getOptionalComponent(StreamableEventSource.class)
                           .orElse(new AsyncInMemoryStreamableEventSource())
                ));
    }

    private static @NonNull
    Function<EventHandlingComponentsConfigurer.RequiredComponentPhase,
            EventHandlingComponentsConfigurer.CompletePhase> singleComponent(String componentName) {
        return components -> components.declarative(componentName, cfg -> {
            var component = SimpleEventHandlingComponent.create("test");
            component.subscribe(new QualifiedName(String.class), (e, c) -> MessageStream.empty());
            return component;
        });
    }
}
