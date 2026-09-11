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
import org.axonframework.common.Registration;
import org.axonframework.common.configuration.DuplicateModuleRegistrationException;
import org.axonframework.messaging.core.SubscribableEventSource;
import org.axonframework.messaging.core.annotation.Namespace;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventHandlingComponent;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.annotation.AnnotatedEventHandlingComponent;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.processing.errorhandling.ErrorContext;
import org.axonframework.messaging.eventhandling.processing.errorhandling.ErrorHandler;
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
import org.axonframework.spring.stereotype.Saga;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.support.GenericApplicationContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class validating the {@link SpringSagaConfigurer} descriptor contributed for every Saga discovered in a Spring
 * application context, and the processor {@link SagaProcessorConfigurer} assembles from it.
 * <p>
 * The tests pin the Axon Framework 4 behavior these reproduce: the derived processor name, the co-location of Sagas
 * deriving the same processor name on one processor, and the head token, which survives anything that configures
 * the processor without setting an initial token of its own. They also pin the one deliberate departure, that a
 * Saga and an ordinary event handler resolving to the same processor name are rejected rather than merged.
 *
 * @author Mateusz Nowak
 */
class SpringSagaConfigurerTest {

    private static final String MY_SAGA_MODULE = "EventProcessor[MySagaProcessor]";
    private static final String SHARED_PROCESSOR = "shared";
    private static final String SHARED_MODULE = "EventProcessor[" + SHARED_PROCESSOR + "]";
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
        void anEventProcessorDefinitionCanSelectASaga() {
            // given
            try (GenericApplicationContext context = springContext(ctx -> {
                registrar(ctx, "mySaga", MySaga.class);
                processorDefinition(
                        ctx,
                        EventProcessorDefinition.pooledStreaming("selected")
                                                .assigningHandlers(handler -> handler.beanType() == MySaga.class)
                                                .notCustomized()
                );
            })) {
                // when
                AxonConfiguration configuration = axonConfiguration(context);

                // then - Sagas use the same explicit selector pipeline as ordinary event handlers
                assertThat(configuration.getModuleConfiguration("EventProcessor[selected]")).isPresent();
                assertThat(configuration.getModuleConfiguration(MY_SAGA_MODULE)).isEmpty();
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
    class InitialToken {

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
        void aCustomizationBeanDoesNotOverrideTheHeadToken() {
            // given - Customization beans do not reach a Saga's processor, so this is not the way to replay
            try (GenericApplicationContext context = springContext(ctx -> {
                registrar(ctx, "mySaga", MySaga.class);
                ctx.registerBean("replayFromStart",
                                 PooledStreamingEventProcessorModule.Customization.class,
                                 () -> (axonConfig, processorConfig) ->
                                         processorConfig.initialToken(source -> source.firstToken(null)));
            })) {
                Configuration module = moduleConfiguration(axonConfiguration(context), MY_SAGA_MODULE);

                // when
                RecordingTrackingTokenSource source = new RecordingTrackingTokenSource();
                pooledConfiguration(module).initialToken().apply(source);

                // then - use an EventProcessorDefinition to replay; see the ProcessorDefinition test below
                assertThat(source.invocations()).containsExactly("latestToken");
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

        @Test
        void keepsTheHeadTokenWhenANamedProcessorDefinitionOnlyTunesTheProcessor() {
            // given
            try (GenericApplicationContext context = springContext(ctx -> {
                registrar(ctx, "mySaga", MySaga.class);
                processorDefinition(
                        ctx,
                        EventProcessorDefinition.pooledStreaming("MySagaProcessor")
                                                .assigningHandlers(handler -> false)
                                                .customized(configuration -> configuration.batchSize(42))
                );
            })) {
                Configuration module = moduleConfiguration(axonConfiguration(context), MY_SAGA_MODULE);

                // when
                RecordingTrackingTokenSource source = new RecordingTrackingTokenSource();
                PooledStreamingEventProcessorConfiguration pooled = pooledConfiguration(module);
                pooled.initialToken().apply(source);

                // then - a definition that sets no initial token leaves the Saga's head token in place
                assertThat(source.invocations()).containsExactly("latestToken");
                assertThat(pooled.batchSize()).isEqualTo(42);
            }
        }

        @Test
        void aProcessorDefinitionSettingAnInitialTokenOverridesTheHeadToken() {
            // given - the definition's own customization runs after the Saga default
            try (GenericApplicationContext context = springContext(ctx -> {
                registrar(ctx, "mySaga", MySaga.class);
                processorDefinition(
                        ctx,
                        EventProcessorDefinition.pooledStreaming("MySagaProcessor")
                                                .assigningHandlers(handler -> false)
                                                .customized(configuration -> configuration.initialToken(
                                                        source -> source.firstToken(null)
                                                ))
                );
            })) {
                Configuration module = moduleConfiguration(axonConfiguration(context), MY_SAGA_MODULE);

                // when
                RecordingTrackingTokenSource source = new RecordingTrackingTokenSource();
                pooledConfiguration(module).initialToken().apply(source);

                // then
                assertThat(source.invocations()).containsExactly("firstToken");
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
                assertThat(componentNames(module)).anyMatch(name -> name.contains("NamespacedSaga"))
                                                  .anyMatch(name -> name.contains("OtherNamespacedSaga"));
            }
        }

        @Test
        void rejectsASagaAndAnOrdinaryEventHandlerResolvingToOneProcessor() {
            // given - Axon Framework 4 put every invoker of one processing group on one processor; Sagas are
            // assembled separately here, so the same collision surfaces as two modules of one name instead
            try (GenericApplicationContext context = springContext(ctx -> {
                registrar(ctx, "namespacedSaga", NamespacedSaga.class);
                ctx.registerBean("namespacedProjection", NamespacedProjection.class);
            })) {
                // when / then - a loud startup failure, never a silent merge or a silently dropped handler
                assertThatThrownBy(() -> axonConfiguration(context))
                        .isInstanceOf(DuplicateModuleRegistrationException.class)
                        .hasMessageContaining(SHARED_PROCESSOR);
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
                        .anyMatch(name -> name.contains("config.saga.alpha.SharedNameSaga"))
                        .anyMatch(name -> name.contains("config.saga.beta.SharedNameSaga"));
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

        @Test
        void switchesToSubscribingThroughANamedProcessorDefinition() {
            // given
            try (GenericApplicationContext context = springContext(ctx -> {
                registrar(ctx, "mySaga", MySaga.class);
                processorDefinition(
                        ctx,
                        EventProcessorDefinition.subscribing("MySagaProcessor")
                                                .assigningHandlers(handler -> false)
                                                .notCustomized()
                );
            })) {
                // when
                Configuration module = moduleConfiguration(axonConfiguration(context), MY_SAGA_MODULE);

                // then - definitions configure a processor by name; their handler selector does not own Saga discovery
                assertThat(module.getOptionalComponent(SubscribingEventProcessorConfiguration.class)).isPresent();
                assertThat(module.getOptionalComponent(PooledStreamingEventProcessorConfiguration.class)).isEmpty();
            }
        }
    }

    @Nested
    class Segments {

        @Test
        void runsOnASingleSegment() {
            // given
            try (GenericApplicationContext context = springContext(ctx -> registrar(ctx, "mySaga", MySaga.class))) {
                // when
                Configuration module = moduleConfiguration(axonConfiguration(context), MY_SAGA_MODULE);

                // then - Axon Framework 4 derived its Saga processor defaults from single-threaded processing, and
                // a Saga manager sequences as BROADCAST, so extra segments only re-read the whole stream
                assertThat(pooledConfiguration(module).initialSegmentCount()).isEqualTo(1);
            }
        }

        @Test
        void keepsTheSingleSegmentWhenAnExplicitProcessorEntryExists() {
            // given - the properties always carry a segment count, so they cannot express "leave it alone"
            try (GenericApplicationContext context = springContext(ctx -> {
                registrar(ctx, "mySaga", MySaga.class);
                settings(ctx, Map.of("MySagaProcessor", new TestPooledSettings(7)));
            })) {
                // when
                Configuration module = moduleConfiguration(axonConfiguration(context), MY_SAGA_MODULE);

                // then - tuning an unrelated property must not silently fan the Saga out over sixteen segments
                PooledStreamingEventProcessorConfiguration pooled = pooledConfiguration(module);
                assertThat(pooled.initialSegmentCount()).isEqualTo(1);
                assertThat(pooled.batchSize()).isEqualTo(7);
            }
        }

        @Test
        void aProcessorDefinitionCanRaiseTheSegmentCount() {
            // given
            try (GenericApplicationContext context = springContext(ctx -> {
                registrar(ctx, "mySaga", MySaga.class);
                processorDefinition(
                        ctx,
                        EventProcessorDefinition.pooledStreaming("MySagaProcessor")
                                                .assigningHandlers(handler -> false)
                                                .customized(configuration -> configuration.initialSegmentCount(4))
                );
            })) {
                // when
                Configuration module = moduleConfiguration(axonConfiguration(context), MY_SAGA_MODULE);

                // then - raising it stays possible, as a deliberate code-level decision
                assertThat(pooledConfiguration(module).initialSegmentCount()).isEqualTo(4);
            }
        }
    }

    @Nested
    class SagaProcessorDefinitions {

        @Test
        void customizesTheProcessorOfTheNamedSagaType() {
            // given - no handler selector and no mode to state, unlike an EventProcessorDefinition
            try (GenericApplicationContext context = springContext(ctx -> {
                registrar(ctx, "mySaga", MySaga.class);
                ctx.registerBean("mySagaDefinition", SagaProcessorDefinition.class,
                                 () -> SagaProcessorDefinition.forSaga(MySaga.class)
                                                              .whenPooledStreaming(c -> c.batchSize(42)));
            })) {
                // when
                Configuration module = moduleConfiguration(axonConfiguration(context), MY_SAGA_MODULE);

                // then
                assertThat(pooledConfiguration(module).batchSize()).isEqualTo(42);
            }
        }

        @Test
        void appliesOnlyToTheProcessorOfItsOwnSaga() {
            // given
            try (GenericApplicationContext context = springContext(ctx -> {
                registrar(ctx, "mySaga", MySaga.class);
                registrar(ctx, "otherSaga", OtherSaga.class);
                ctx.registerBean("mySagaDefinition", SagaProcessorDefinition.class,
                                 () -> SagaProcessorDefinition.forSaga(MySaga.class)
                                                              .whenPooledStreaming(c -> c.batchSize(42)));
            })) {
                AxonConfiguration configuration = axonConfiguration(context);

                // when
                Configuration mySagaModule = moduleConfiguration(configuration, MY_SAGA_MODULE);
                Configuration otherModule = moduleConfiguration(configuration, "EventProcessor[OtherSagaProcessor]");

                // then
                assertThat(pooledConfiguration(mySagaModule).batchSize()).isEqualTo(42);
                assertThat(pooledConfiguration(otherModule).batchSize()).isNotEqualTo(42);
            }
        }

        @Test
        void followsASagaOntoItsNamespacedProcessor() {
            // given - forSaga does not need to know how the processor name was derived
            try (GenericApplicationContext context = springContext(ctx -> {
                registrar(ctx, "namespacedSaga", NamespacedSaga.class);
                ctx.registerBean("namespacedDefinition", SagaProcessorDefinition.class,
                                 () -> SagaProcessorDefinition.forSaga(NamespacedSaga.class)
                                                              .whenPooledStreaming(c -> c.batchSize(42)));
            })) {
                // when
                Configuration module = moduleConfiguration(axonConfiguration(context), SHARED_MODULE);

                // then
                assertThat(pooledConfiguration(module).batchSize()).isEqualTo(42);
            }
        }

        @Test
        void targetsASharedProcessorByName() {
            // given - one definition configuring the processor two Sagas share
            try (GenericApplicationContext context = springContext(ctx -> {
                registrar(ctx, "namespacedSaga", NamespacedSaga.class);
                registrar(ctx, "otherNamespacedSaga", OtherNamespacedSaga.class);
                ctx.registerBean("sharedDefinition", SagaProcessorDefinition.class,
                                 () -> SagaProcessorDefinition.forProcessor(SHARED_PROCESSOR)
                                                              .whenPooledStreaming(c -> c.batchSize(42)));
            })) {
                // when
                Configuration module = moduleConfiguration(axonConfiguration(context), SHARED_MODULE);

                // then
                assertThat(pooledConfiguration(module).batchSize()).isEqualTo(42);
                assertThat(module.getComponents(EventHandlingComponent.class)).hasSize(2);
            }
        }

        @Test
        void configuresTheWholeProcessorWhenTheSelectedSagaSharesIt() {
            // given - a processor is the unit of configuration, so selecting it by one of its Sagas still
            // configures the processor, and with it every Saga on that processor
            try (GenericApplicationContext context = springContext(ctx -> {
                registrar(ctx, "namespacedSaga", NamespacedSaga.class);
                registrar(ctx, "otherNamespacedSaga", OtherNamespacedSaga.class);
                ctx.registerBean("namespacedDefinition", SagaProcessorDefinition.class,
                                 () -> SagaProcessorDefinition.forSaga(NamespacedSaga.class)
                                                              .whenPooledStreaming(c -> c.batchSize(42)));
            })) {
                // when
                Configuration module = moduleConfiguration(axonConfiguration(context), SHARED_MODULE);

                // then - both Sagas run on the customized processor; the configurer logs that this happened
                assertThat(pooledConfiguration(module).batchSize()).isEqualTo(42);
                assertThat(module.getComponents(EventHandlingComponent.class)).hasSize(2);
            }
        }

        @Test
        void overridesTheHeadTokenWhenItSetsAnInitialToken() {
            // given - the sanctioned way to replay into a Saga
            try (GenericApplicationContext context = springContext(ctx -> {
                registrar(ctx, "mySaga", MySaga.class);
                ctx.registerBean("replayDefinition", SagaProcessorDefinition.class,
                                 () -> SagaProcessorDefinition.forSaga(MySaga.class)
                                                              .whenPooledStreaming(c -> c.initialToken(
                                                                      source -> source.firstToken(null)
                                                              )));
            })) {
                Configuration module = moduleConfiguration(axonConfiguration(context), MY_SAGA_MODULE);

                // when
                RecordingTrackingTokenSource source = new RecordingTrackingTokenSource();
                pooledConfiguration(module).initialToken().apply(source);

                // then - it runs after the Saga defaults
                assertThat(source.invocations()).containsExactly("firstToken");
            }
        }

        @Test
        void doesNotFixTheProcessorMode() {
            // given - unlike an EventProcessorDefinition, which would force the processor back to pooled
            try (GenericApplicationContext context = springContext(ctx -> {
                registrar(ctx, "mySaga", MySaga.class);
                settings(ctx, Map.of("MySagaProcessor", new TestSubscribingSettings()));
                ctx.registerBean("mySagaDefinition", SagaProcessorDefinition.class,
                                 () -> SagaProcessorDefinition.forSaga(MySaga.class)
                                                              .whenPooledStreaming(c -> c.batchSize(42)));
            })) {
                // when
                Configuration module = moduleConfiguration(axonConfiguration(context), MY_SAGA_MODULE);

                // then - naming a mode picks which configuration the customization is written for; the property
                // still decides the processor's mode, and the mismatched definition is skipped with a warning
                assertThat(module.getOptionalComponent(SubscribingEventProcessorConfiguration.class)).isPresent();
                assertThat(module.getOptionalComponent(PooledStreamingEventProcessorConfiguration.class)).isEmpty();
            }
        }

        @Test
        void aModeIndependentCustomizationAppliesInEitherMode() {
            // given - the error handler is shared surface, so it needs no mode
            RecordingErrorHandler errorHandler = new RecordingErrorHandler();
            Consumer<GenericApplicationContext> beans = ctx -> {
                registrar(ctx, "mySaga", MySaga.class);
                ctx.registerBean("mySagaDefinition", SagaProcessorDefinition.class,
                                 () -> SagaProcessorDefinition.forSaga(MySaga.class)
                                                              .customized(c -> c.errorHandler(errorHandler)));
            };

            // when / then - pooled, the Saga default
            try (GenericApplicationContext context = springContext(beans)) {
                Configuration module = moduleConfiguration(axonConfiguration(context), MY_SAGA_MODULE);
                assertThat(pooledConfiguration(module).errorHandler()).isSameAs(errorHandler);
            }

            // when / then - and subscribing, without restating the customization
            try (GenericApplicationContext context = springContext(ctx -> {
                beans.accept(ctx);
                settings(ctx, Map.of("MySagaProcessor", new TestSubscribingSettings()));
            })) {
                Configuration module = moduleConfiguration(axonConfiguration(context), MY_SAGA_MODULE);
                assertThat(module.getComponent(SubscribingEventProcessorConfiguration.class).errorHandler())
                        .isSameAs(errorHandler);
            }
        }

        @Test
        void customizesASubscribingSagaProcessor() {
            // given - a subscribing processor exposes its own configuration, so it takes its own customization
            RecordingSubscribableEventSource eventSource = new RecordingSubscribableEventSource();
            try (GenericApplicationContext context = springContext(ctx -> {
                registrar(ctx, "mySaga", MySaga.class);
                settings(ctx, Map.of("MySagaProcessor", new TestSubscribingSettings()));
                ctx.registerBean("mySagaDefinition", SagaProcessorDefinition.class,
                                 () -> SagaProcessorDefinition.forSaga(MySaga.class)
                                                              .whenSubscribing(c -> c.eventSource(eventSource)));
            })) {
                // when
                Configuration module = moduleConfiguration(axonConfiguration(context), MY_SAGA_MODULE);

                // then
                SubscribingEventProcessorConfiguration subscribing =
                        module.getComponent(SubscribingEventProcessorConfiguration.class);
                assertThat(subscribing.eventSource()).isSameAs(eventSource);
            }
        }
    }

    @Nested
    class AxonFramework4Quirks {

        /**
         * A Saga declared as a singleton bean is registered twice: once as the Saga manager on its own processor, and
         * once more as an ordinary annotated event handling component on the package-derived processor. Every event
         * then also reaches one shared Saga instance, outside any association or lifecycle handling.
         * <p>
         * {@link Saga @Saga} is {@code @Scope("prototype")} and {@code MessageHandlerLookup} skips prototype beans,
         * which is the whole guard. A {@code @Bean} method ignores the scope declared on the class, so the guard does
         * not hold for a Saga declared that way. Axon Framework 4 behaves identically: its own
         * {@code MessageHandlerLookup} selects on {@code bd.isSingleton() && !bd.isAbstract()} just the same. Pinned
         * rather than fixed, since rejecting a singleton Saga would refuse a configuration Axon Framework 4 accepted.
         * Component-scan Saga types instead of declaring them with a {@code @Bean} method.
         */
        @Test
        void aSingletonSagaBeanIsAlsoRegisteredAsAnOrdinaryEventHandler() {
            // given - a discovered Saga that is also a singleton bean, as a @Bean method would declare it
            try (GenericApplicationContext context = springContext(ctx -> {
                registrar(ctx, "mySaga", MySaga.class);
                ctx.registerBean("mySaga", MySaga.class, MySaga::new);
            })) {
                // when
                AxonConfiguration configuration = axonConfiguration(context);

                // then - the Saga manager, on the Saga's own processor
                Configuration sagaModule = moduleConfiguration(configuration, MY_SAGA_MODULE);
                assertThat(sagaModule.getComponents(EventHandlingComponent.class).values())
                        .singleElement()
                        .matches(component -> component.unwrap(AnnotatedSagaManager.class).isPresent());

                // then - and the same type again as a plain handler, on the package-derived processor
                Configuration packageModule = moduleConfiguration(
                        configuration, "EventProcessor[" + MySaga.class.getPackageName() + "]"
                );
                assertThat(packageModule.getComponents(EventHandlingComponent.class).values())
                        .singleElement()
                        .matches(component -> component.unwrap(AnnotatedEventHandlingComponent.class).isPresent());
            }
        }
    }

    @Nested
    class BeanResolution {

        @Test
        void refusesToResolveASagaInstance() {
            // given - a Saga has no single bean instance; the manager creates one per Saga identifier
            SpringSagaConfigurer descriptor = new SpringSagaConfigurer(MySaga.class);

            // when / then
            assertThatThrownBy(descriptor::resolveBean)
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining(MySaga.class.getName())
                    .hasMessageContaining("bean name or bean type");
        }
    }

    @Nested
    class ExtensionCustomizations {

        @Test
        void doesNotApplyCustomizationBeansToTheSagaProcessor() {
            // given - the channel cross-cutting Axon Framework 5 infrastructure attaches itself through, including
            // the dead-lettering a Saga never had in Axon Framework 4
            try (GenericApplicationContext context = springContext(ctx -> {
                registrar(ctx, "mySaga", MySaga.class);
                settings(ctx, Map.of(EventProcessorSettings.DEFAULT, new TestPooledSettings(9)));
                ctx.registerBean("batchSizeCustomization",
                                 PooledStreamingEventProcessorModule.Customization.class,
                                 () -> (axonConfig, processorConfig) -> processorConfig.batchSize(42));
            })) {
                // when
                Configuration module = moduleConfiguration(axonConfiguration(context), MY_SAGA_MODULE);

                // then - the Saga keeps the settings value, untouched by the customization bean
                assertThat(pooledConfiguration(module).batchSize()).isEqualTo(9);
            }
        }

        @Test
        void stillAppliesCustomizationBeansToOrdinaryProcessors() {
            // given - only Sagas opt out; the regular pipeline is untouched
            try (GenericApplicationContext context = springContext(ctx -> {
                ctx.registerBean("namespacedProjection", NamespacedProjection.class);
                settings(ctx, Map.of(EventProcessorSettings.DEFAULT, new TestPooledSettings(9)));
                ctx.registerBean("batchSizeCustomization",
                                 PooledStreamingEventProcessorModule.Customization.class,
                                 () -> (axonConfig, processorConfig) -> processorConfig.batchSize(42));
            })) {
                // when
                Configuration module = moduleConfiguration(axonConfiguration(context), SHARED_MODULE);

                // then
                assertThat(pooledConfiguration(module).batchSize()).isEqualTo(42);
            }
        }
    }

    private static GenericApplicationContext springContext(Consumer<GenericApplicationContext> beans) {
        GenericApplicationContext context = new GenericApplicationContext();
        beans.accept(context);
        if (!context.containsBeanDefinition("eventProcessorSettings")) {
            settings(context, Map.of(EventProcessorSettings.DEFAULT, new TestPooledSettings(1)));
        }
        context.registerBean(
                "processorModuleFactory",
                ProcessorModuleFactory.class,
                () -> new DefaultProcessorModuleFactory(
                        context.getBeanProvider(EventProcessorDefinition.class).orderedStream().toList(),
                        context.getBean(EventProcessorSettings.MapWrapper.class).settings(),
                        context.getBeanProvider(PooledStreamingEventProcessorModule.Customization.class)
                               .orderedStream()
                               .toList()
                )
        );
        context.registerBean(
                "eventHandlerConfigurer",
                MessageHandlerConfigurer.class,
                () -> new MessageHandlerConfigurer(
                        MessageHandlerConfigurer.Type.EVENT,
                        MessageHandlerLookup.messageHandlerBeans(
                                EventMessage.class, context.getDefaultListableBeanFactory()
                        )
                )
        );
        context.registerBean("sagaProcessorConfigurer", SagaProcessorConfigurer.class, SagaProcessorConfigurer::new);
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

    private static void processorDefinition(GenericApplicationContext context,
                                            EventProcessorDefinition definition) {
        context.registerBean("sagaProcessorDefinition", EventProcessorDefinition.class, () -> definition);
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

    /**
     * An {@link ErrorHandler} that only needs an identity, to assert which handler a Saga processor was configured
     * with, whatever mode it runs in.
     */
    private static class RecordingErrorHandler implements ErrorHandler {

        @Override
        public void handleError(ErrorContext errorContext) {
            // Intentionally empty; the test only asserts which instance was configured.
        }
    }

    /**
     * A {@link SubscribableEventSource} that only needs an identity, to assert which source a subscribing Saga
     * processor was configured with.
     */
    private static class RecordingSubscribableEventSource implements SubscribableEventSource {

        @Override
        public Registration subscribe(
                BiFunction<List<? extends EventMessage>, @Nullable ProcessingContext, CompletableFuture<?>> consumer
        ) {
            return () -> true;
        }
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

    private record TestPooledSettings(int batchSize)
            implements EventProcessorSettings.PooledEventProcessorSettings,
            EventProcessorSettings.SubscribingEventProcessorSettings {

        @Override
        public EventProcessorSettings.ProcessorMode processorMode() {
            return EventProcessorSettings.ProcessorMode.POOLED;
        }

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

    @Namespace("shared")
    static class NamespacedProjection {

        @EventHandler
        void on(SagaStarted event) {
            // Intentionally empty; component registration is the behavior under test.
        }
    }

    record SagaStarted(String id) {

    }
}
