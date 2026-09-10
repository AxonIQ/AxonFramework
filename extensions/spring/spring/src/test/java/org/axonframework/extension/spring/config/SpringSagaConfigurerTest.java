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

package org.axonframework.extension.spring.config;

import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.messaging.core.annotation.Namespace;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.annotation.AnnotatedEventHandlingComponent;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorConfiguration;
import org.axonframework.messaging.eventhandling.processing.streaming.pooled.PooledStreamingEventProcessorModule;
import org.axonframework.messaging.eventhandling.processing.streaming.token.GlobalSequenceTrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.TrackingToken;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.TokenStore;
import org.axonframework.messaging.eventhandling.processing.streaming.token.store.inmemory.InMemoryTokenStore;
import org.axonframework.messaging.eventhandling.processing.subscribing.SubscribingEventProcessorConfiguration;
import org.axonframework.messaging.eventstreaming.TrackingTokenSource;
import org.axonframework.modelling.saga.AnnotatedSagaManager;
import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.StartSaga;
import org.axonframework.modelling.saga.repository.SagaStore;
import org.axonframework.modelling.saga.repository.inmemory.InMemorySagaStore;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.support.GenericApplicationContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the {@link SpringSagaConfigurer}, the enhancer registering an event processor module for every
 * Saga discovered in a Spring application context.
 * <p>
 * The tests pin the Axon Framework 4 behavior the configurer reproduces: the derived processor name, the head-token
 * default, and the co-location of Sagas deriving the same processor name on one processor. They also pin the one
 * deliberate deviation: the head token survives explicit processor entries, since the properties cannot express an
 * initial token, and only a code-level customization may switch a Saga to replaying the stream.
 *
 * @author Mateusz Nowak
 */
class SpringSagaConfigurerTest {

    private static final String MY_SAGA_MODULE = "EventProcessor[MySagaProcessor]";
    private static final String SHARED_MODULE = "EventProcessor[shared]";
    private static final String SHARED_NAME_MODULE = "EventProcessor[SharedNameSagaProcessor]";

    @Nested
    class ProcessorRegistration {

        @Test
        void registersAPooledStreamingProcessorNamedAfterTheSagaType() {
            // given
            try (GenericApplicationContext context = springContext(ctx -> registrar(ctx, "mySaga", MySaga.class))) {
                // when
                AxonConfiguration configuration = axonConfiguration(context);

                // then - the Axon Framework 4 default name is "<SimpleName>Processor", pooled by default
                Configuration module = moduleConfiguration(configuration, MY_SAGA_MODULE);
                assertThat(module.getOptionalComponent(PooledStreamingEventProcessorConfiguration.class)).isPresent();
            }
        }

        @Test
        void registersTheSagaManagerAsADeclarativeComponent() {
            // given
            try (GenericApplicationContext context = springContext(ctx -> registrar(ctx, "mySaga", MySaga.class))) {
                // when
                Configuration module = moduleConfiguration(axonConfiguration(context), MY_SAGA_MODULE);

                // then - a Saga is its own event handling component, not an annotated handler bean
                Map<String, EventHandlingComponent> components = module.getComponents(EventHandlingComponent.class);
                assertThat(components).hasSize(1);
                assertThat(components.keySet().iterator().next()).contains("Saga[MySaga]");
                EventHandlingComponent component = components.values().iterator().next();
                assertThat(component.unwrap(AnnotatedSagaManager.class)).isPresent();
                assertThat(component.unwrap(AnnotatedEventHandlingComponent.class)).isEmpty();
            }
        }

        @Test
        void usesTheNamespaceOfTheSagaTypeAsProcessorName() {
            // given
            try (GenericApplicationContext context =
                         springContext(ctx -> registrar(ctx, "namespacedSaga", NamespacedSaga.class))) {
                // when
                AxonConfiguration configuration = axonConfiguration(context);

                // then
                assertThat(configuration.getModuleConfiguration(SHARED_MODULE)).isPresent();
                assertThat(configuration.getModuleConfiguration("EventProcessor[NamespacedSagaProcessor]")).isEmpty();
            }
        }

        @Test
        void registersOneModulePerDerivedProcessorName() {
            // given
            try (GenericApplicationContext context = springContext(ctx -> {
                registrar(ctx, "mySaga", MySaga.class);
                registrar(ctx, "otherSaga", OtherSaga.class);
            })) {
                // when
                AxonConfiguration configuration = axonConfiguration(context);

                // then
                assertThat(configuration.getModuleConfiguration(MY_SAGA_MODULE)).isPresent();
                assertThat(configuration.getModuleConfiguration("EventProcessor[OtherSagaProcessor]")).isPresent();
            }
        }
    }

    @Nested
    class SagaProcessorDefaults {

        @Test
        void startsAtTheHeadOfTheStreamWithoutAnExplicitProcessorEntry() {
            // given
            try (GenericApplicationContext context = springContext(ctx -> registrar(ctx, "mySaga", MySaga.class))) {
                Configuration module = moduleConfiguration(axonConfiguration(context), MY_SAGA_MODULE);

                // when
                RecordingTrackingTokenSource source = new RecordingTrackingTokenSource();
                pooledConfiguration(module).initialToken().apply(source);

                // then - Axon Framework 4 Sagas ignore history, unlike the generic first-token-as-replay default
                assertThat(source.invocations()).containsExactly("latestToken");
            }
        }

        @Test
        void keepsTheHeadTokenWhenAnExplicitProcessorEntryExists() {
            // given
            try (GenericApplicationContext context = springContext(ctx -> {
                registrar(ctx, "mySaga", MySaga.class);
                settings(ctx, Map.of("MySagaProcessor", new TestPooledSettings(7)));
            })) {
                Configuration module = moduleConfiguration(axonConfiguration(context), MY_SAGA_MODULE);

                // when
                RecordingTrackingTokenSource source = new RecordingTrackingTokenSource();
                PooledStreamingEventProcessorConfiguration pooled = pooledConfiguration(module);
                pooled.initialToken().apply(source);

                // then - the entry tunes the processor without expressing an initial token, so tuning must not flip
                // the Saga into processing the stream from the start (deliberate deviation from Axon Framework 4,
                // where any customization of the processor name replaced the Saga defaults)
                assertThat(source.invocations()).containsExactly("latestToken");
                assertThat(pooled.batchSize()).isEqualTo(7);
            }
        }

        @Test
        void startsWithASingleSegmentWithoutAnExplicitProcessorEntry() {
            // given
            try (GenericApplicationContext context = springContext(ctx -> registrar(ctx, "mySaga", MySaga.class))) {
                Configuration module = moduleConfiguration(axonConfiguration(context), MY_SAGA_MODULE);

                // then - Axon Framework 4 gave a Saga a single-threaded tracking processor with one segment, not the
                // generic 16-segment pooled default
                assertThat(pooledConfiguration(module).initialSegmentCount()).isEqualTo(1);
            }
        }

        @Test
        void anExplicitProcessorEntryAppliesTheRegularSegmentSemantics() {
            // given
            try (GenericApplicationContext context = springContext(ctx -> {
                registrar(ctx, "mySaga", MySaga.class);
                settings(ctx, Map.of("MySagaProcessor", new TestPooledSettings(7)));
            })) {
                Configuration module = moduleConfiguration(axonConfiguration(context), MY_SAGA_MODULE);

                // then - segment count is expressible in the properties, so an explicit entry opts the processor into
                // the regular property semantics (the entry's segment count, here the test settings' 4)
                assertThat(pooledConfiguration(module).initialSegmentCount()).isEqualTo(4);
            }
        }

        @Test
        void defaultSettingsDoNotChangeTheSingleSegment() {
            // given
            try (GenericApplicationContext context = springContext(ctx -> {
                registrar(ctx, "mySaga", MySaga.class);
                settings(ctx, Map.of(EventProcessorSettings.DEFAULT, new TestPooledSettings(9)));
            })) {
                Configuration module = moduleConfiguration(axonConfiguration(context), MY_SAGA_MODULE);

                // then - like the head token, the default entry carries no Saga-specific intent
                assertThat(pooledConfiguration(module).initialSegmentCount()).isEqualTo(1);
            }
        }

        @Test
        void keepsTheHeadTokenWhenOnlyDefaultSettingsExist() {
            // given
            try (GenericApplicationContext context = springContext(ctx -> {
                registrar(ctx, "mySaga", MySaga.class);
                settings(ctx, Map.of(EventProcessorSettings.DEFAULT, new TestPooledSettings(9)));
            })) {
                Configuration module = moduleConfiguration(axonConfiguration(context), MY_SAGA_MODULE);

                // when
                RecordingTrackingTokenSource source = new RecordingTrackingTokenSource();
                PooledStreamingEventProcessorConfiguration pooled = pooledConfiguration(module);
                pooled.initialToken().apply(source);

                // then - default settings carry no Saga-specific intent, so the head token stays
                assertThat(source.invocations()).containsExactly("latestToken");
                assertThat(pooled.batchSize()).isEqualTo(9);
            }
        }
    }

    @Nested
    class Grouping {

        @Test
        void sharesOneProcessorBetweenSagasWithTheSameNamespace() {
            // given
            try (GenericApplicationContext context = springContext(ctx -> {
                registrar(ctx, "namespacedSaga", NamespacedSaga.class);
                registrar(ctx, "otherNamespacedSaga", OtherNamespacedSaga.class);
            })) {
                // when
                AxonConfiguration configuration = axonConfiguration(context);

                // then
                Configuration module = moduleConfiguration(configuration, SHARED_MODULE);
                assertThat(module.getComponents(EventHandlingComponent.class)).hasSize(2);
                assertThat(componentNames(module)).anyMatch(name -> name.contains("Saga[NamespacedSaga]"))
                                                  .anyMatch(name -> name.contains("Saga[OtherNamespacedSaga]"));
            }
        }

        @Test
        void fallsBackToFullyQualifiedComponentNamesOnClashingSimpleNames() {
            // given - two Saga types with the same simple name derive the same processor name
            try (GenericApplicationContext context = springContext(ctx -> {
                registrar(ctx, "alphaSaga",
                          org.axonframework.extension.spring.config.saga.alpha.SharedNameSaga.class);
                registrar(ctx, "betaSaga",
                          org.axonframework.extension.spring.config.saga.beta.SharedNameSaga.class);
            })) {
                // when
                Configuration module = moduleConfiguration(axonConfiguration(context), SHARED_NAME_MODULE);

                // then
                assertThat(module.getComponents(EventHandlingComponent.class)).hasSize(2);
                assertThat(componentNames(module))
                        .anyMatch(name -> name.contains(
                                "Saga[org.axonframework.extension.spring.config.saga.alpha.SharedNameSaga]"))
                        .anyMatch(name -> name.contains(
                                "Saga[org.axonframework.extension.spring.config.saga.beta.SharedNameSaga]"));
            }
        }

        @Test
        void registersTheSameSagaTypeOnceWhenDeclaredTwice() {
            // given - two beans of the same Saga type, each naming its own store
            AtomicInteger firstStoreInstantiations = new AtomicInteger();
            AtomicInteger secondStoreInstantiations = new AtomicInteger();
            try (GenericApplicationContext context = springContext(ctx -> {
                sagaStoreBean(ctx, "firstStore", firstStoreInstantiations);
                sagaStoreBean(ctx, "secondStore", secondStoreInstantiations);
                registrar(ctx, "firstMySaga", MySaga.class, "firstStore");
                registrar(ctx, "secondMySaga", MySaga.class, "secondStore");
            })) {
                // when
                Configuration module = moduleConfiguration(axonConfiguration(context, cr -> {
                }), MY_SAGA_MODULE);

                // then - the last registration wins, mirroring the Axon Framework 4 registration map
                assertThat(module.getComponents(EventHandlingComponent.class)).hasSize(1);
                assertThat(secondStoreInstantiations).hasValue(1);
                assertThat(firstStoreInstantiations).hasValue(0);
            }
        }
    }

    @Nested
    class SagaStoreBeanResolution {

        @Test
        void resolvesOnlyTheNamedStoreBean() {
            // given
            AtomicInteger namedStoreInstantiations = new AtomicInteger();
            AtomicInteger otherStoreInstantiations = new AtomicInteger();
            try (GenericApplicationContext context = springContext(ctx -> {
                sagaStoreBean(ctx, "namedStore", namedStoreInstantiations);
                sagaStoreBean(ctx, "otherStore", otherStoreInstantiations);
                registrar(ctx, "mySaga", MySaga.class, "namedStore");
            })) {
                // when - no SagaStore component is registered, so the Saga can only build from the named bean
                Configuration module = moduleConfiguration(axonConfiguration(context, cr -> {
                }), MY_SAGA_MODULE);
                module.getComponents(EventHandlingComponent.class);

                // then
                assertThat(namedStoreInstantiations).hasValue(1);
                assertThat(otherStoreInstantiations).hasValue(0);
            }
        }

        @Test
        void resolvesTheStoreBeanOnlyWhenTheComponentIsBuilt() {
            // given
            AtomicInteger namedStoreInstantiations = new AtomicInteger();
            try (GenericApplicationContext context = springContext(ctx -> {
                sagaStoreBean(ctx, "namedStore", namedStoreInstantiations);
                registrar(ctx, "mySaga", MySaga.class, "namedStore");
            })) {
                // when
                AxonConfiguration configuration = axonConfiguration(context, cr -> {
                });

                // then - enhancing must not touch the store bean yet
                assertThat(namedStoreInstantiations).hasValue(0);

                // when
                moduleConfiguration(configuration, MY_SAGA_MODULE).getComponents(EventHandlingComponent.class);

                // then
                assertThat(namedStoreInstantiations).hasValue(1);
            }
        }
    }

    @Nested
    class ProcessorMode {

        @Test
        void switchesToSubscribingThroughAnExplicitProcessorEntry() {
            // given
            try (GenericApplicationContext context = springContext(ctx -> {
                registrar(ctx, "mySaga", MySaga.class);
                settings(ctx, Map.of("MySagaProcessor", new TestSubscribingSettings()));
            })) {
                // when
                Configuration module = moduleConfiguration(axonConfiguration(context), MY_SAGA_MODULE);

                // then
                assertThat(module.getOptionalComponent(SubscribingEventProcessorConfiguration.class)).isPresent();
                assertThat(module.getOptionalComponent(PooledStreamingEventProcessorConfiguration.class)).isEmpty();
            }
        }

        @Test
        void switchesToSubscribingThroughTheDefaultProcessorEntry() {
            // given
            try (GenericApplicationContext context = springContext(ctx -> {
                registrar(ctx, "mySaga", MySaga.class);
                settings(ctx, Map.of(EventProcessorSettings.DEFAULT, new TestSubscribingSettings()));
            })) {
                // when
                Configuration module = moduleConfiguration(axonConfiguration(context), MY_SAGA_MODULE);

                // then
                assertThat(module.getOptionalComponent(SubscribingEventProcessorConfiguration.class)).isPresent();
            }
        }
    }

    @Nested
    class CodeLevelOverrides {

        /**
         * Platform-wide extensions attach cross-cutting behavior through {@code Customization} beans, dead-letter
         * queues among them. A Saga must never be dead-lettered, matching Axon Framework 4, which never wrapped the
         * Saga invoker in dead-lettering, so those beans must not reach a Saga's processor.
         */
        @Test
        void genericCustomizationBeansDoNotReachTheSagaProcessor() {
            // given
            try (GenericApplicationContext context = springContext(ctx -> {
                registrar(ctx, "mySaga", MySaga.class);
                ctx.registerBean("batchSizeCustomization",
                                 PooledStreamingEventProcessorModule.Customization.class,
                                 () -> (axonConfig, processorConfig) -> processorConfig.batchSize(42));
            })) {
                // when
                Configuration module = moduleConfiguration(axonConfiguration(context), MY_SAGA_MODULE);

                // then - the generic bean is ignored; the batch size stays at its default
                assertThat(pooledConfiguration(module).batchSize()).isEqualTo(1);
            }
        }

        @Test
        void anEventProcessorDefinitionCustomizesTheSagaProcessor() {
            // given - a definition names the one processor it targets, so it is the supported code-level override
            try (GenericApplicationContext context = springContext(ctx -> {
                registrar(ctx, "mySaga", MySaga.class);
                ctx.registerBean("mySagaProcessorDefinition",
                                 EventProcessorDefinition.class,
                                 () -> EventProcessorDefinition
                                         .pooledStreamingMatching("MySagaProcessor")
                                         .customized(config -> config.batchSize(21)));
            })) {
                // when
                Configuration module = moduleConfiguration(axonConfiguration(context), MY_SAGA_MODULE);

                // then
                assertThat(pooledConfiguration(module).batchSize()).isEqualTo(21);
            }
        }

        @Test
        void aDefinitionLeavesUntouchedSagaDefaultsInPlace() {
            // given - a definition customizing only the batch size
            try (GenericApplicationContext context = springContext(ctx -> {
                registrar(ctx, "mySaga", MySaga.class);
                ctx.registerBean("mySagaProcessorDefinition",
                                 EventProcessorDefinition.class,
                                 () -> EventProcessorDefinition
                                         .pooledStreamingMatching("MySagaProcessor")
                                         .customized(config -> config.batchSize(21)));
            })) {
                Configuration module = moduleConfiguration(axonConfiguration(context), MY_SAGA_MODULE);

                // when
                RecordingTrackingTokenSource source = new RecordingTrackingTokenSource();
                PooledStreamingEventProcessorConfiguration pooled = pooledConfiguration(module);
                pooled.initialToken().apply(source);

                // then - the head token and the single segment stay; the definition overrides only what it defines
                assertThat(source.invocations()).containsExactly("latestToken");
                assertThat(pooled.initialSegmentCount()).isEqualTo(1);
            }
        }

        @Test
        void aDefinitionOverridesTheHeadToken() {
            // given - replaying into a Saga is possible, but only as an explicit code-level decision
            try (GenericApplicationContext context = springContext(ctx -> {
                registrar(ctx, "mySaga", MySaga.class);
                ctx.registerBean("replayFromStart",
                                 EventProcessorDefinition.class,
                                 () -> EventProcessorDefinition
                                         .pooledStreamingMatching("MySagaProcessor")
                                         .customized(config -> config.initialToken(
                                                 source -> source.firstToken(null))));
            })) {
                Configuration module = moduleConfiguration(axonConfiguration(context), MY_SAGA_MODULE);

                // when
                RecordingTrackingTokenSource source = new RecordingTrackingTokenSource();
                pooledConfiguration(module).initialToken().apply(source);

                // then - the definition runs after the head-token base and wins
                assertThat(source.invocations()).containsExactly("firstToken");
            }
        }

        @Test
        void aDefinitionSwitchesTheSagaProcessorToSubscribing() {
            // given
            try (GenericApplicationContext context = springContext(ctx -> {
                registrar(ctx, "mySaga", MySaga.class);
                ctx.registerBean("subscribingDefinition",
                                 EventProcessorDefinition.class,
                                 () -> EventProcessorDefinition
                                         .subscribingMatching("MySagaProcessor")
                                         .customized(config -> config));
            })) {
                // when
                Configuration module = moduleConfiguration(axonConfiguration(context), MY_SAGA_MODULE);

                // then - the definition's mode wins over the settings, as for factory-built processors
                assertThat(module.getOptionalComponent(SubscribingEventProcessorConfiguration.class)).isPresent();
            }
        }
    }

    private static GenericApplicationContext springContext(Consumer<GenericApplicationContext> beans) {
        GenericApplicationContext context = new GenericApplicationContext();
        beans.accept(context);
        context.refresh();
        return context;
    }

    private static void registrar(GenericApplicationContext context, String beanName, Class<?> sagaType) {
        registrar(context, beanName, sagaType, null);
    }

    private static void registrar(GenericApplicationContext context,
                                  String beanName,
                                  Class<?> sagaType,
                                  @Nullable String sagaStore) {
        BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(SpringSagaConfigurer.class)
                                                             .addConstructorArgValue(sagaType);
        if (sagaStore != null) {
            builder.addPropertyValue("sagaStore", sagaStore);
        }
        context.registerBeanDefinition(beanName + "$$Registrar", builder.getBeanDefinition());
    }

    private static void settings(GenericApplicationContext context, Map<String, EventProcessorSettings> settings) {
        context.registerBean("eventProcessorSettings",
                             EventProcessorSettings.MapWrapper.class,
                             () -> new EventProcessorSettings.MapWrapper(settings));
    }

    private static void sagaStoreBean(GenericApplicationContext context, String beanName, AtomicInteger counter) {
        context.registerBean(beanName, InMemorySagaStore.class, () -> {
            counter.incrementAndGet();
            return new InMemorySagaStore();
        }, definition -> definition.setLazyInit(true));
    }

    private static AxonConfiguration axonConfiguration(GenericApplicationContext context) {
        return axonConfiguration(context, cr -> cr.registerComponent(SagaStore.class, c -> new InMemorySagaStore()));
    }

    /**
     * Builds the Axon configuration the way the Spring extension does, so that every registrar bean acts as its own
     * enhancer. A plain component registry would collapse them, since it keys enhancers by class.
     */
    private static AxonConfiguration axonConfiguration(GenericApplicationContext context,
                                                       Consumer<ComponentRegistry> components) {
        DefaultListableBeanFactory beanFactory = context.getDefaultListableBeanFactory();
        SpringLifecycleRegistry lifecycleRegistry = new SpringLifecycleRegistry();
        lifecycleRegistry.setBeanFactory(beanFactory);
        SpringComponentRegistry componentRegistry = new SpringComponentRegistry(beanFactory, lifecycleRegistry);
        componentRegistry.postProcessBeanFactory(beanFactory);
        componentRegistry.registerComponent(TokenStore.class, "tokenStore", c -> new InMemoryTokenStore());
        components.accept(componentRegistry);
        SpringAxonApplication application = new SpringAxonApplication(componentRegistry, lifecycleRegistry);
        componentRegistry.postProcessAfterInitialization(new Object(), "axonInitializationTrigger");
        return application.build();
    }

    private static Configuration moduleConfiguration(AxonConfiguration configuration, String moduleName) {
        return configuration.getModuleConfiguration(moduleName).orElseThrow();
    }

    private static PooledStreamingEventProcessorConfiguration pooledConfiguration(Configuration module) {
        return module.getComponent(PooledStreamingEventProcessorConfiguration.class);
    }

    private static List<String> componentNames(Configuration module) {
        return List.copyOf(module.getComponents(EventHandlingComponent.class).keySet());
    }

    private static class RecordingTrackingTokenSource implements TrackingTokenSource {

        private final List<String> invocations = new ArrayList<>();

        @Override
        public CompletableFuture<TrackingToken> firstToken(@Nullable ProcessingContext context) {
            invocations.add("firstToken");
            return CompletableFuture.completedFuture(new GlobalSequenceTrackingToken(0));
        }

        @Override
        public CompletableFuture<TrackingToken> latestToken(@Nullable ProcessingContext context) {
            invocations.add("latestToken");
            return CompletableFuture.completedFuture(new GlobalSequenceTrackingToken(10));
        }

        @Override
        public CompletableFuture<TrackingToken> tokenAt(Instant at, @Nullable ProcessingContext context) {
            invocations.add("tokenAt");
            return CompletableFuture.completedFuture(new GlobalSequenceTrackingToken(5));
        }

        List<String> invocations() {
            return invocations;
        }
    }

    private record TestPooledSettings(int batchSize) implements EventProcessorSettings.PooledEventProcessorSettings {

        @Override
        public @Nullable String source() {
            return null;
        }

        @Override
        public int initialSegmentCount() {
            return 4;
        }

        @Override
        public long tokenClaimIntervalInMillis() {
            return 1000;
        }

        @Override
        public int threadCount() {
            return 1;
        }

        @Override
        public @Nullable String tokenStore() {
            return null;
        }
    }

    private record TestSubscribingSettings() implements EventProcessorSettings.SubscribingEventProcessorSettings {

        @Override
        public @Nullable String source() {
            return null;
        }
    }

    static class MySaga {

        @StartSaga
        @SagaEventHandler(associationProperty = "id")
        void on(SagaStarted event) {
            // Intentionally empty; the Saga only needs a handler to be a valid event handling component.
        }
    }

    static class OtherSaga {

        @StartSaga
        @SagaEventHandler(associationProperty = "id")
        void on(SagaStarted event) {
            // Intentionally empty; the Saga only needs a handler to be a valid event handling component.
        }
    }

    @Namespace("shared")
    static class NamespacedSaga {

        @StartSaga
        @SagaEventHandler(associationProperty = "id")
        void on(SagaStarted event) {
            // Intentionally empty; the Saga only needs a handler to be a valid event handling component.
        }
    }

    @Namespace("shared")
    static class OtherNamespacedSaga {

        @StartSaga
        @SagaEventHandler(associationProperty = "id")
        void on(SagaStarted event) {
            // Intentionally empty; the Saga only needs a handler to be a valid event handling component.
        }
    }

    record SagaStarted(String id) {

    }
}
