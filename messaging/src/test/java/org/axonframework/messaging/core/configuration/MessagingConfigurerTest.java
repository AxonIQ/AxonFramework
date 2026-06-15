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

package org.axonframework.messaging.core.configuration;

import org.axonframework.messaging.queryhandling.configuration.QueryGatewayConfigurer;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.axonframework.common.FutureUtils;
import org.axonframework.common.configuration.ApplicationConfigurerTestSuite;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.ModuleBuilder;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.commandhandling.gateway.ConvertingCommandGateway;
import org.axonframework.messaging.commandhandling.interception.InterceptingCommandBus;
import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.NamespaceMessageTypeResolver;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.annotation.AnnotationMessageTypeResolver;
import org.axonframework.messaging.core.correlation.CorrelationDataProvider;
import org.axonframework.messaging.core.correlation.CorrelationDataProviderRegistry;
import org.axonframework.messaging.core.correlation.DefaultCorrelationDataProviderRegistry;
import org.axonframework.messaging.core.interception.DispatchInterceptorRegistry;
import org.axonframework.messaging.core.interception.HandlerInterceptorRegistry;
import org.axonframework.messaging.core.sequencing.NoOpSequencingPolicy;
import org.axonframework.messaging.core.sequencing.SequencingPolicy;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.messaging.eventhandling.InterceptingEventBus;
import org.axonframework.messaging.eventhandling.gateway.DefaultEventGateway;
import org.axonframework.messaging.eventhandling.gateway.EventGateway;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.axonframework.messaging.queryhandling.QueryBusTestUtils;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.axonframework.messaging.queryhandling.configuration.QueryHandlingModule;
import org.axonframework.messaging.queryhandling.QueryShutdownManager;
import org.axonframework.messaging.queryhandling.gateway.DefaultQueryGateway;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.axonframework.messaging.queryhandling.gateway.ShutdownTrackingQueryGateway;
import org.axonframework.messaging.queryhandling.interception.InterceptingQueryBus;
import org.junit.jupiter.api.*;


import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.axonframework.messaging.commandhandling.CommandBusTestUtils.aCommandBus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link MessagingConfigurer}.
 *
 * @author Steven van Beelen
 */
class MessagingConfigurerTest extends ApplicationConfigurerTestSuite<MessagingConfigurer> {

    @Override
    public MessagingConfigurer createConfigurer() {
        return testSubject == null ? MessagingConfigurer.create() : testSubject;
    }

    @Test
    void defaultComponents() {
        Configuration result = testSubject.build();

        Optional<MessageTypeResolver> messageTypeResolver = result.getOptionalComponent(MessageTypeResolver.class);
        assertTrue(messageTypeResolver.isPresent());
        assertInstanceOf(AnnotationMessageTypeResolver.class, messageTypeResolver.get());

        Optional<CommandGateway> commandGateway = result.getOptionalComponent(CommandGateway.class);
        assertTrue(commandGateway.isPresent());
        assertInstanceOf(ConvertingCommandGateway.class, commandGateway.get());

        Optional<CommandBus> commandBus = result.getOptionalComponent(CommandBus.class);
        assertTrue(commandBus.isPresent());
        // Intercepting at all times, since we have a MessageOriginProvider that leads to the CorrelationDataInterceptor
        // and a CommandSequencingPolicy that leads to CommandSequencingInterceptor
        assertInstanceOf(InterceptingCommandBus.class, commandBus.get());

        Optional<EventGateway> eventGateway = result.getOptionalComponent(EventGateway.class);
        assertTrue(eventGateway.isPresent());
        assertInstanceOf(DefaultEventGateway.class, eventGateway.get());

        Optional<EventSink> eventSink = result.getOptionalComponent(EventSink.class);
        assertTrue(eventSink.isPresent());

        Optional<EventBus> eventBus = result.getOptionalComponent(EventBus.class);
        assertTrue(eventBus.isPresent());
        // Intercepting at all times, since we have a MessageOriginProvider that leads to the CorrelationDataInterceptor
        assertInstanceOf(InterceptingEventBus.class, eventBus.get());

        Optional<QueryGateway> queryGateway = result.getOptionalComponent(QueryGateway.class);
        assertTrue(queryGateway.isPresent());
        assertInstanceOf(DefaultQueryGateway.class, queryGateway.get());

        Optional<QueryBus> queryBus = result.getOptionalComponent(QueryBus.class);
        assertTrue(queryBus.isPresent());
        // Intercepting at all times, since we have a MessageOriginProvider that leads to the CorrelationDataInterceptor
        assertInstanceOf(InterceptingQueryBus.class, queryBus.get());
    }

    @Test
    void registerMessageTypeResolverOverridesDefault() {
        MessageTypeResolver expected = NamespaceMessageTypeResolver
                .namespace("namespace")
                .message(String.class, "test.message", "1.0.0")
                .noFallback();

        Configuration result = testSubject.registerMessageTypeResolver(c -> expected)
                                          .build();

        assertEquals(expected, result.getComponent(MessageTypeResolver.class));
    }

    @Test
    void messageTypeResolverBuilderShouldBeMutable() {
        NamespaceMessageTypeResolver.Builder builder1 = NamespaceMessageTypeResolver
                .namespace("namespace")
                .message(String.class, "test.string", "1.0.0");
        NamespaceMessageTypeResolver.Builder builder2 = builder1
                .message(Integer.class, "test.integer", "1.0.0");

        var instance1 = builder1.noFallback();
        var instance2 = builder2.fallback(new ClassBasedMessageTypeResolver());

        assertTrue(instance1.resolve(String.class).isPresent());
        assertTrue(instance1.resolve(Integer.class).isPresent());
        assertTrue(instance2.resolve(String.class).isPresent());
        assertTrue(instance2.resolve(Integer.class).isPresent());
    }

    @Test
    void registerCommandBusOverridesDefault() {
        CommandBus expected = aCommandBus();

        // Overriding CorrelationDataProviderRegistry ensures CorrelationDataInterceptor is not built.
        // Setting NoOpSequencingPolicy ensures CommandSequencingInterceptor is not built.
        // This otherwise leads to the InterceptingCommandBus
        Configuration result = testSubject.componentRegistry(cr -> cr.registerComponent(
                                                  CorrelationDataProviderRegistry.class,
                                                  c -> new DefaultCorrelationDataProviderRegistry()
                                          ))
                                          .componentRegistry(cr -> cr.registerComponent(SequencingPolicy.class,
                                                                                        MessagingConfigurationDefaults.COMMAND_SEQUENCING_POLICY,
                                                                                        c -> NoOpSequencingPolicy.INSTANCE))
                                          .registerCommandBus(c -> expected)
                                          .build();

        assertEquals(expected, result.getComponent(CommandBus.class));
    }

    @Test
    void registerEventSinkOverridesDefault() {
        EventSink expected = new EventSink() {
            @Override
            public @NonNull CompletableFuture<Void> publish(@Nullable ProcessingContext context,
                                                            @NonNull List<? extends EventMessage> events) {
                return FutureUtils.emptyCompletedFuture();
            }

            @Override
            public void describeTo(@NonNull ComponentDescriptor descriptor) {
                throw new UnsupportedOperationException("Unimportant for this test case");
            }
        };

        // Overriding CorrelationDataProviderRegistry ensures CorrelationDataInterceptor is not build.
        // This otherwise leads to the InterceptingCommandBus
        Configuration result = testSubject.componentRegistry(cr -> cr.registerComponent(
                                                  CorrelationDataProviderRegistry.class,
                                                  c -> new DefaultCorrelationDataProviderRegistry()
                                          )).registerEventSink(c -> expected)
                                          .build();

        assertEquals(expected, result.getComponent(EventSink.class));
    }

    @Test
    void registerQueryBusOverridesDefault() {
        QueryBus expected = QueryBusTestUtils.aQueryBus();

        // Overriding CorrelationDataProviderRegistry ensures CorrelationDataInterceptor is not built.
        // This otherwise leads to the InterceptingQueryBus
        Configuration result = testSubject.componentRegistry(cr -> cr.registerComponent(
                                                  CorrelationDataProviderRegistry.class,
                                                  c -> new DefaultCorrelationDataProviderRegistry()
                                          ))
                                          .registerQueryBus(c -> expected)
                                          .build();

        assertEquals(expected, result.getComponent(QueryBus.class));
    }

    @Test
    void registerCorrelationDataProviderMakesProviderRetrievableThroughProviderRegistry() {
        CorrelationDataProvider provider = mock(CorrelationDataProvider.class);

        Configuration result = testSubject.registerCorrelationDataProvider(c -> provider)
                                          .build();

        List<CorrelationDataProvider> interceptors =
                result.getComponent(CorrelationDataProviderRegistry.class)
                      .correlationDataProviders(result);
        assertThat(interceptors).contains(provider);
    }

    @Test
    void registerDispatchInterceptorMakesInterceptorRetrievableThroughTheInterceptorRegistryForAllTypes() {
        AtomicInteger counter = new AtomicInteger();
        MessageDispatchInterceptor<Message> dispatchInterceptor = (message, context, interceptorChain) -> {
            counter.incrementAndGet();
            //noinspection DataFlowIssue | Result is not important to validate invocation
            return null;
        };

        // Overriding CorrelationDataProviderRegistry ensures CorrelationDataInterceptor is not present.
        Configuration result = testSubject.componentRegistry(cr -> cr.registerComponent(
                                                  CorrelationDataProviderRegistry.class,
                                                  c -> new DefaultCorrelationDataProviderRegistry()
                                          ))
                                          .registerDispatchInterceptor(c -> dispatchInterceptor)
                                          .build();
        DispatchInterceptorRegistry interceptorRegistry = result.getComponent(DispatchInterceptorRegistry.class);

        List<MessageDispatchInterceptor<? super CommandMessage>> commandInterceptors =
                interceptorRegistry.commandInterceptors(result, CommandBus.class, null);
        assertThat(commandInterceptors).hasSize(1);
        //noinspection DataFlowIssue | Input is not important to validate invocation
        commandInterceptors.getFirst().interceptOnDispatch(null, null, null);
        assertThat(counter).hasValue(1);

        List<MessageDispatchInterceptor<? super EventMessage>> eventInterceptors =
                interceptorRegistry.eventInterceptors(result, EventSink.class, null);
        assertThat(eventInterceptors).hasSize(1);
        //noinspection DataFlowIssue | Input is not important to validate invocation
        eventInterceptors.getFirst().interceptOnDispatch(null, null, null);
        assertThat(counter).hasValue(2);

        List<MessageDispatchInterceptor<? super QueryMessage>> queryInterceptors =
                interceptorRegistry.queryInterceptors(result, QueryBus.class, null);
        assertThat(queryInterceptors).hasSize(1);
        //noinspection DataFlowIssue | Input is not important to validate invocation
        queryInterceptors.getFirst().interceptOnDispatch(null, null, null);
        assertThat(counter).hasValue(3);
    }

    @Test
    void registerCommandDispatchInterceptorMakesInterceptorRetrievableThroughTheInterceptorRegistry() {
        //noinspection unchecked
        MessageDispatchInterceptor<CommandMessage> handlerInterceptor = mock(MessageDispatchInterceptor.class);

        Configuration result = testSubject.registerCommandDispatchInterceptor(c -> handlerInterceptor)
                                          .build();

        List<MessageDispatchInterceptor<? super CommandMessage>> interceptors =
                result.getComponent(DispatchInterceptorRegistry.class)
                      .commandInterceptors(result, CommandBus.class, null);
        assertThat(interceptors).contains(handlerInterceptor);
    }

    @Test
    void registerEventDispatchInterceptorMakesInterceptorRetrievableThroughTheInterceptorRegistry() {
        //noinspection unchecked
        MessageDispatchInterceptor<EventMessage> handlerInterceptor = mock(MessageDispatchInterceptor.class);

        Configuration result = testSubject.registerEventDispatchInterceptor(c -> handlerInterceptor)
                                          .build();

        List<MessageDispatchInterceptor<? super EventMessage>> interceptors =
                result.getComponent(DispatchInterceptorRegistry.class)
                      .eventInterceptors(result, EventSink.class, null);
        assertThat(interceptors).contains(handlerInterceptor);
    }

    @Test
    void registerQueryDispatchInterceptorMakesInterceptorRetrievableThroughTheInterceptorRegistry() {
        //noinspection unchecked
        MessageDispatchInterceptor<QueryMessage> handlerInterceptor = mock(MessageDispatchInterceptor.class);

        Configuration result = testSubject.registerQueryDispatchInterceptor(c -> handlerInterceptor)
                                          .build();

        List<MessageDispatchInterceptor<? super QueryMessage>> interceptors =
                result.getComponent(DispatchInterceptorRegistry.class)
                      .queryInterceptors(result, QueryBus.class, null);
        assertThat(interceptors).contains(handlerInterceptor);
    }

    @Test
    void registerMessageHandlerInterceptorMakesInterceptorRetrievableThroughTheInterceptorRegistryForAllTypes() {
        AtomicInteger counter = new AtomicInteger();
        MessageHandlerInterceptor<Message> handlerInterceptor = (message, context, interceptorChain) -> {
            counter.incrementAndGet();
            //noinspection DataFlowIssue | Result is not important to validate invocation
            return null;
        };

        // Overriding CorrelationDataProviderRegistry ensures CorrelationDataInterceptor is not present.
        // Overriding CommandSequencingPolicy ensures CommandSequencingInterceptor is not present.
        Configuration result = testSubject.componentRegistry(cr -> cr.registerComponent(
                                                  CorrelationDataProviderRegistry.class,
                                                  c -> new DefaultCorrelationDataProviderRegistry()
                                          ))
                                          .componentRegistry(cr -> cr.registerComponent(SequencingPolicy.class,
                                                                                        MessagingConfigurationDefaults.COMMAND_SEQUENCING_POLICY,
                                                                                        c -> NoOpSequencingPolicy.INSTANCE))
                                          .registerMessageHandlerInterceptor(c -> handlerInterceptor)
                                          .build();
        HandlerInterceptorRegistry handlerInterceptorRegistry = result.getComponent(HandlerInterceptorRegistry.class);

        List<MessageHandlerInterceptor<? super CommandMessage>> commandInterceptors =
                handlerInterceptorRegistry.commandInterceptors(result, CommandBus.class, null);
        assertThat(commandInterceptors).hasSize(1);
        //noinspection DataFlowIssue | Input is not important to validate invocation
        commandInterceptors.getFirst().interceptOnHandle(null, null, null);
        assertThat(counter).hasValue(1);

        List<MessageHandlerInterceptor<? super EventMessage>> eventInterceptors =
                handlerInterceptorRegistry.eventInterceptors(result, EventSink.class, null);
        assertThat(eventInterceptors).hasSize(1);
        //noinspection DataFlowIssue | Input is not important to validate invocation
        eventInterceptors.getFirst().interceptOnHandle(null, null, null);
        assertThat(counter).hasValue(2);

        List<MessageHandlerInterceptor<? super QueryMessage>> queryInterceptors =
                handlerInterceptorRegistry.queryInterceptors(result, QueryBus.class, null);
        assertThat(queryInterceptors).hasSize(1);
        //noinspection DataFlowIssue | Input is not important to validate invocation
        queryInterceptors.getFirst().interceptOnHandle(null, null, null);
        assertThat(counter).hasValue(3);
    }

    @Test
    void registerCommandHandlerInterceptorMakesInterceptorRetrievableThroughTheInterceptorRegistry() {
        //noinspection unchecked
        MessageHandlerInterceptor<CommandMessage> handlerInterceptor = mock(MessageHandlerInterceptor.class);

        Configuration result = testSubject.registerCommandHandlerInterceptor(c -> handlerInterceptor)
                                          .build();

        List<MessageHandlerInterceptor<? super CommandMessage>> interceptors =
                result.getComponent(HandlerInterceptorRegistry.class)
                      .commandInterceptors(result, CommandBus.class, null);
        assertThat(interceptors).contains(handlerInterceptor);
    }

    @Test
    void registerEventHandlerInterceptorMakesInterceptorRetrievableThroughTheInterceptorRegistry() {
        //noinspection unchecked
        MessageHandlerInterceptor<EventMessage> handlerInterceptor = mock(MessageHandlerInterceptor.class);

        Configuration result = testSubject.registerEventHandlerInterceptor(c -> handlerInterceptor)
                                          .build();

        List<MessageHandlerInterceptor<? super EventMessage>> interceptors =
                result.getComponent(HandlerInterceptorRegistry.class)
                      .eventInterceptors(result, EventSink.class, null);
        assertThat(interceptors).contains(handlerInterceptor);
    }

    @Test
    void registerQueryHandlerInterceptorMakesInterceptorRetrievableThroughTheInterceptorRegistry() {
        //noinspection unchecked
        MessageHandlerInterceptor<QueryMessage> handlerInterceptor = mock(MessageHandlerInterceptor.class);

        Configuration result = testSubject.registerQueryHandlerInterceptor(c -> handlerInterceptor)
                                          .build();

        List<MessageHandlerInterceptor<? super QueryMessage>> interceptors =
                result.getComponent(HandlerInterceptorRegistry.class)
                      .queryInterceptors(result, QueryBus.class, null);
        assertThat(interceptors).contains(handlerInterceptor);
    }

    @Test
    void registerCommandHandlingModuleAddsAModuleConfiguration() {
        ModuleBuilder<CommandHandlingModule> statelessCommandHandlingModule =
                CommandHandlingModule.named("test")
                                     .commandHandlers(commandHandlerPhase -> commandHandlerPhase.commandHandler(
                                             new QualifiedName(String.class),
                                             (command, context) -> MessageStream.empty().cast()
                                     ));

        Configuration configuration =
                testSubject.registerCommandHandlingModule(statelessCommandHandlingModule)
                           .build();

        assertThat(configuration.getModuleConfiguration("test")).isPresent();
    }

    @Test
    void registerQueryHandlingModuleAddsAModuleConfiguration() {
        ModuleBuilder<QueryHandlingModule> statefulCommandHandlingModule =
                QueryHandlingModule.named("test")
                                   .queryHandlers(handlerPhase -> handlerPhase.queryHandler(
                                           new QualifiedName(String.class),
                                           (query, context) -> MessageStream.empty().cast()
                                   ));

        Configuration configuration =
                testSubject.registerQueryHandlingModule(statefulCommandHandlingModule)
                           .build();

        assertThat(configuration.getModuleConfiguration("test")).isPresent();
    }

    @Test
    void applicationDelegatesTasks() {
        TestComponent tc = new TestComponent();
        TestComponent result =
                testSubject.componentRegistry(axon -> axon.registerComponent(TestComponent.class, c -> tc))
                           .build()
                           .getComponent(TestComponent.class);

        assertEquals(tc, result);
    }

    @Nested
    class QueryGatewayMethod {

        @Test
        void queryGateway_registersNamedGatewayAsComponent() {
            // given / when
            Configuration result = testSubject.queryGateway("reporting", QueryGatewayConfigurer::withDefaults)
                                              .build();

            // then: named gateway is present and is a plain DefaultQueryGateway
            assertThat(result.getOptionalComponent(QueryGateway.class, "reporting")).isPresent();
            assertThat(result.getComponent(QueryGateway.class, "reporting"))
                    .isInstanceOf(DefaultQueryGateway.class);
        }

        @Test
        void queryGateway_withShutdownSpec_wrapsInShutdownTrackingGateway() {
            // given / when
            Configuration result = testSubject.queryGateway("reporting", g ->
                    g.cancellingSubscriptionQueryOnShutdown(QueryShutdownManager.Spec::closeImmediately)
            ).build();

            // then: gateway is wrapped with shutdown tracking
            assertThat(result.getComponent(QueryGateway.class, "reporting"))
                    .isInstanceOf(ShutdownTrackingQueryGateway.class);
        }

        @Test
        void queryGateway_namedGatewayDoesNotAppearAsUnnamedDefault() {
            // given / when: a named gateway is registered; no unnamed default is registered
            // because MessagingConfigurationDefaults.registerIfNotPresent skips registration
            // when any QueryGateway (including named) is already present
            Configuration result = testSubject.queryGateway("reporting", g -> {
            }).build();

            // then: named gateway is accessible by name
            assertThat(result.getOptionalComponent(QueryGateway.class, "reporting")).isPresent();
            // then: unnamed lookup returns nothing (the named gateway does not register as unnamed)
            assertThat(result.getOptionalComponent(QueryGateway.class)).isEmpty();
        }

        @Test
        void queryGateway_multipleCallsRegisterMultipleDistinctComponents() {
            // given / when: two separate named gateways registered
            Configuration result = testSubject
                    .queryGateway("reporting", g -> {
                    })
                    .queryGateway("internal", g -> {
                    })
                    .build();

            // then: both are present and are distinct instances
            QueryGateway reporting = result.getComponent(QueryGateway.class, "reporting");
            QueryGateway internal = result.getComponent(QueryGateway.class, "internal");
            assertThat(reporting).isNotNull();
            assertThat(internal).isNotNull();
            assertThat(reporting).isNotSameAs(internal);
        }
    }

    private static class TestComponent {

    }
}