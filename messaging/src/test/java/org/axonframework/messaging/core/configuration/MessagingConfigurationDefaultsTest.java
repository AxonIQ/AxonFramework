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

import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.DefaultAxonApplication;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.conversion.Converter;
import org.axonframework.conversion.DelegatingGeneralConverter;
import org.axonframework.conversion.GeneralConverter;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.CommandHandler;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandPriorityCalculator;
import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.commandhandling.RoutingStrategy;
import org.axonframework.messaging.commandhandling.annotation.AnnotationRoutingStrategy;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.commandhandling.gateway.ConvertingCommandGateway;
import org.axonframework.messaging.commandhandling.interception.CommandSequencingInterceptor;
import org.axonframework.messaging.commandhandling.interception.InterceptingCommandBus;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.SubscribableEventSource;
import org.axonframework.messaging.core.annotation.AnnotationMessageTypeResolver;
import org.axonframework.messaging.core.conversion.DelegatingMessageConverter;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.core.correlation.CorrelationDataProvider;
import org.axonframework.messaging.core.correlation.CorrelationDataProviderRegistry;
import org.axonframework.messaging.core.correlation.DefaultCorrelationDataProviderRegistry;
import org.axonframework.messaging.core.correlation.MessageOriginProvider;
import org.axonframework.messaging.core.interception.DefaultDispatchInterceptorRegistry;
import org.axonframework.messaging.core.interception.DefaultHandlerInterceptorRegistry;
import org.axonframework.messaging.core.interception.DispatchInterceptorRegistry;
import org.axonframework.messaging.core.interception.HandlerInterceptorRegistry;
import org.axonframework.messaging.core.sequencing.NoOpSequencingPolicy;
import org.axonframework.messaging.core.sequencing.RoutingKeySequencingPolicy;
import org.axonframework.messaging.core.sequencing.SequencingPolicy;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.TransactionalUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.messaging.eventhandling.InterceptingEventBus;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.messaging.eventhandling.gateway.DefaultEventGateway;
import org.axonframework.messaging.eventhandling.gateway.EventGateway;
import org.axonframework.messaging.monitoring.configuration.DefaultMessageMonitorRegistry;
import org.axonframework.messaging.monitoring.configuration.MessageMonitorRegistry;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.axonframework.messaging.queryhandling.QueryPriorityCalculator;
import org.axonframework.messaging.queryhandling.gateway.DefaultQueryGateway;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.axonframework.messaging.queryhandling.interception.InterceptingQueryBus;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link MessagingConfigurationDefaults}.
 *
 * @author Steven van Beelen
 */
class MessagingConfigurationDefaultsTest {

    @Test
    void orderEqualsMaxInteger() {
        assertEquals(Integer.MAX_VALUE, new MessagingConfigurationDefaults().order());
    }

    @Test
    void enhanceSetsExpectedDefaultsInAbsenceOfTheseComponents() {
        ApplicationConfigurer configurer = MessagingConfigurer.enhance(new DefaultAxonApplication());
        Configuration resultConfig = configurer.build();

        assertInstanceOf(AnnotationMessageTypeResolver.class, resultConfig.getComponent(MessageTypeResolver.class));
        GeneralConverter generalConverter = resultConfig.getComponent(GeneralConverter.class);
        assertInstanceOf(DelegatingGeneralConverter.class, generalConverter);
        Converter generalConverterDelegate = ((DelegatingGeneralConverter) generalConverter).delegate();
        assertInstanceOf(JacksonConverter.class, generalConverterDelegate);
        MessageConverter messageConverter = resultConfig.getComponent(MessageConverter.class);
        assertInstanceOf(DelegatingMessageConverter.class, messageConverter);
        EventConverter eventConverter = resultConfig.getComponent(EventConverter.class);
        assertInstanceOf(DelegatingEventConverter.class, eventConverter);
        Converter messageConverterDelegate = ((DelegatingMessageConverter) messageConverter).delegate();
        assertThat(generalConverter).isEqualTo(messageConverterDelegate);
        MessageConverter eventConverterDelegate = ((DelegatingEventConverter) eventConverter).delegate();
        assertThat(messageConverter).isEqualTo(eventConverterDelegate);
        assertThat(resultConfig.getComponent(CorrelationDataProviderRegistry.class))
                .isInstanceOf(DefaultCorrelationDataProviderRegistry.class);
        assertThat(resultConfig.getComponent(SequencingPolicy.class,
                                             MessagingConfigurationDefaults.COMMAND_SEQUENCING_POLICY))
                .isInstanceOf(RoutingKeySequencingPolicy.class);
        assertThat(resultConfig.getComponent(DispatchInterceptorRegistry.class))
                .isInstanceOf(DefaultDispatchInterceptorRegistry.class);
        assertThat(resultConfig.getComponent(HandlerInterceptorRegistry.class))
                .isInstanceOf(DefaultHandlerInterceptorRegistry.class);
        assertThat(resultConfig.getComponent(MessageMonitorRegistry.class))
                .isInstanceOf(DefaultMessageMonitorRegistry.class);
        assertInstanceOf(TransactionalUnitOfWorkFactory.class, resultConfig.getComponent(UnitOfWorkFactory.class));
        // Intercepting at all times, since we have a MessageOriginProvider that leads to the CorrelationDataInterceptor
        assertInstanceOf(InterceptingCommandBus.class, resultConfig.getComponent(CommandBus.class));
        assertEquals(CommandPriorityCalculator.defaultCalculator(),
                     resultConfig.getComponent(CommandPriorityCalculator.class));
        assertInstanceOf(AnnotationRoutingStrategy.class, resultConfig.getComponent(RoutingStrategy.class));
        // The specific CommandGateway-implementation registered by default may be overridden by the serviceloader-mechanism.
        // So we just check if _any_ CommandGateway has been added to the configuration.
        assertTrue(resultConfig.hasComponent(CommandGateway.class));

        assertInstanceOf(DefaultEventGateway.class, resultConfig.getComponent(EventGateway.class));
        EventBus eventBus = resultConfig.getComponent(EventBus.class);
        assertInstanceOf(InterceptingEventBus.class, eventBus);
        assertThat(resultConfig.getComponent(SubscribableEventSource.class)).isSameAs(eventBus);

        // Intercepting at all times, since we have a MessageOriginProvider that leads to the CorrelationDataInterceptor
        assertInstanceOf(InterceptingQueryBus.class, resultConfig.getComponent(QueryBus.class));
        assertEquals(QueryPriorityCalculator.defaultCalculator(),
                     resultConfig.getComponent(QueryPriorityCalculator.class));
        assertInstanceOf(DefaultQueryGateway.class, resultConfig.getComponent(QueryGateway.class));
    }

    @Test
    void registersMessageOriginProviderInCorrelationDataProviderRegistryByDefault() {
        ApplicationConfigurer configurer = MessagingConfigurer.enhance(new DefaultAxonApplication());
        Configuration resultConfig = configurer.build();

        List<CorrelationDataProvider> providers = resultConfig.getComponent(CorrelationDataProviderRegistry.class)
                                                              .correlationDataProviders(resultConfig);

        assertThat(providers).size().isEqualTo(1);
        assertThat(providers.getFirst()).isInstanceOf(MessageOriginProvider.class);
    }

    @Test
    void registersCorrelationDataInterceptorInInterceptorRegistriesWhenSingleCorrelationDataProviderIsPresent() {
        ApplicationConfigurer configurer = MessagingConfigurer.enhance(new DefaultAxonApplication());
        configurer.componentRegistry(cr -> {
            cr.registerComponent(CorrelationDataProviderRegistry.class,
                                 config -> {
                                     DefaultCorrelationDataProviderRegistry providerRegistry =
                                             new DefaultCorrelationDataProviderRegistry();
                                     return providerRegistry.registerProvider(c -> mock());
                                 });
            cr.registerComponent(SequencingPolicy.class,
                                 MessagingConfigurationDefaults.COMMAND_SEQUENCING_POLICY,
                                 c -> NoOpSequencingPolicy.INSTANCE);
        });
        Configuration resultConfig = configurer.build();

        // Generic interception are wrapped for type safety and as such we cannot validate if the single interceptor is a CorrelationDataInterceptor
        DispatchInterceptorRegistry dispatchInterceptorRegistry =
                resultConfig.getComponent(DispatchInterceptorRegistry.class);
        assertThat(dispatchInterceptorRegistry.commandInterceptors(resultConfig, CommandBus.class, null))
                .size().isEqualTo(1);
        assertThat(dispatchInterceptorRegistry.eventInterceptors(resultConfig, EventSink.class, null))
                .size().isEqualTo(1);
        assertThat(dispatchInterceptorRegistry.queryInterceptors(resultConfig, QueryBus.class, null))
                .size().isEqualTo(1);
        assertThat(dispatchInterceptorRegistry.subscriptionQueryUpdateInterceptors(resultConfig, QueryBus.class, null))
                .size().isEqualTo(1);

        HandlerInterceptorRegistry handlerInterceptorRegistry =
                resultConfig.getComponent(HandlerInterceptorRegistry.class);
        assertThat(handlerInterceptorRegistry.commandInterceptors(resultConfig, CommandBus.class, null))
                .size().isEqualTo(1);
        assertThat(handlerInterceptorRegistry.eventInterceptors(resultConfig, EventSink.class, null))
                .size().isEqualTo(1);
        assertThat(handlerInterceptorRegistry.queryInterceptors(resultConfig, QueryBus.class, null))
                .size().isEqualTo(1);
    }

    @Test
    void doesNotRegisterCorrelationDataInterceptorInInterceptorRegistriesWhenTheAreNoCorrelationDataProviders() {
        ApplicationConfigurer configurer = MessagingConfigurer.enhance(new DefaultAxonApplication());
        configurer.componentRegistry(cr -> {
            cr.registerComponent(CorrelationDataProviderRegistry.class,
                                 config -> new DefaultCorrelationDataProviderRegistry());
            cr.registerComponent(SequencingPolicy.class,
                                 MessagingConfigurationDefaults.COMMAND_SEQUENCING_POLICY,
                                 c -> NoOpSequencingPolicy.INSTANCE);
        });
        Configuration resultConfig = configurer.build();

        DispatchInterceptorRegistry dispatchInterceptorRegistry =
                resultConfig.getComponent(DispatchInterceptorRegistry.class);
        assertThat(dispatchInterceptorRegistry.commandInterceptors(resultConfig, CommandBus.class, null))
                .size().isEqualTo(0);
        assertThat(dispatchInterceptorRegistry.eventInterceptors(resultConfig, EventSink.class, null))
                .size().isEqualTo(0);
        assertThat(dispatchInterceptorRegistry.queryInterceptors(resultConfig, QueryBus.class, null))
                .size().isEqualTo(0);
        assertThat(dispatchInterceptorRegistry.subscriptionQueryUpdateInterceptors(resultConfig, QueryBus.class, null))
                .size().isEqualTo(0);

        HandlerInterceptorRegistry handlerInterceptorRegistry =
                resultConfig.getComponent(HandlerInterceptorRegistry.class);
        assertThat(handlerInterceptorRegistry.commandInterceptors(resultConfig, CommandBus.class, null))
                .size().isEqualTo(0);
        assertThat(handlerInterceptorRegistry.eventInterceptors(resultConfig, EventSink.class, null))
                .size().isEqualTo(0);
        assertThat(handlerInterceptorRegistry.queryInterceptors(resultConfig, QueryBus.class, null))
                .size().isEqualTo(0);
    }

    @Test
    void registersMonitorInterceptorInInterceptorRegistriesWhenSingleMessageMonitorIsPresent() {
        ApplicationConfigurer configurer = MessagingConfigurer.enhance(new DefaultAxonApplication());

        // Registers default provider registry to remove MessageOriginProvider, thus removing CorrelationDataInterceptor.
        // Registers NoOpSequencingPolicy to remove the CommandSequencingInterceptor.
        configurer.componentRegistry(
                          cr -> cr.registerComponent(CorrelationDataProviderRegistry.class,
                                                     c -> new DefaultCorrelationDataProviderRegistry())
                  ).componentRegistry(
                          cr -> cr.registerComponent(MessageMonitorRegistry.class,
                                                     config -> {
                                                         MessageMonitorRegistry monitorRegistry =
                                                                 new DefaultMessageMonitorRegistry();
                                                         return monitorRegistry.registerMonitor(c -> mock());
                                                     })
                  )
                  .componentRegistry(cr -> cr.registerComponent(SequencingPolicy.class,
                                                                MessagingConfigurationDefaults.COMMAND_SEQUENCING_POLICY,
                                                                c -> NoOpSequencingPolicy.INSTANCE));
        Configuration resultConfig = configurer.build();

        // Generic interception are wrapped for type safety and as such we cannot validate if the single interceptor is a CorrelationDataInterceptor
        DispatchInterceptorRegistry dispatchInterceptorRegistry =
                resultConfig.getComponent(DispatchInterceptorRegistry.class);
        // Ignoring command and query dispatch interceptors as those aren't monitored out of the box.
        assertThat(dispatchInterceptorRegistry.eventInterceptors(resultConfig, EventSink.class, null))
                .size().isEqualTo(1);
        assertThat(dispatchInterceptorRegistry.subscriptionQueryUpdateInterceptors(resultConfig, QueryBus.class, null))
                .size().isEqualTo(1);

        HandlerInterceptorRegistry handlerInterceptorRegistry =
                resultConfig.getComponent(HandlerInterceptorRegistry.class);
        assertThat(handlerInterceptorRegistry.commandInterceptors(resultConfig, CommandBus.class, null))
                .size().isEqualTo(1);
        assertThat(handlerInterceptorRegistry.eventInterceptors(resultConfig, EventSink.class, null))
                .size().isEqualTo(1);
        assertThat(handlerInterceptorRegistry.queryInterceptors(resultConfig, QueryBus.class, null))
                .size().isEqualTo(1);
    }

    @Test
    void doesNotRegisterMonitorInterceptorInInterceptorRegistriesWhenTheAreNoMessageMonitors() {
        ApplicationConfigurer configurer = MessagingConfigurer.enhance(new DefaultAxonApplication());
        // Registers default provider registry to remove MessageOriginProvider, thus removing CorrelationDataInterceptor
        // Registers NoOpSequencingPolicy to remove the CommandSequencingInterceptor.
        configurer.componentRegistry(
                cr -> cr.registerComponent(CorrelationDataProviderRegistry.class,
                                           c -> new DefaultCorrelationDataProviderRegistry())
        ).componentRegistry(
                cr -> cr.registerComponent(MessageMonitorRegistry.class,
                                           c -> new DefaultMessageMonitorRegistry())
        ).componentRegistry(cr -> cr.registerComponent(SequencingPolicy.class,
                                                       MessagingConfigurationDefaults.COMMAND_SEQUENCING_POLICY,
                                                       c -> NoOpSequencingPolicy.INSTANCE));
        Configuration resultConfig = configurer.build();

        DispatchInterceptorRegistry dispatchInterceptorRegistry =
                resultConfig.getComponent(DispatchInterceptorRegistry.class);
        // Ignoring command and query dispatch interceptors as those aren't monitored out of the box.
        assertThat(dispatchInterceptorRegistry.eventInterceptors(resultConfig, EventSink.class, null))
                .size().isEqualTo(0);
        assertThat(dispatchInterceptorRegistry.subscriptionQueryUpdateInterceptors(resultConfig, QueryBus.class, null))
                .size().isEqualTo(0);

        HandlerInterceptorRegistry handlerInterceptorRegistry =
                resultConfig.getComponent(HandlerInterceptorRegistry.class);
        assertThat(handlerInterceptorRegistry.commandInterceptors(resultConfig, CommandBus.class, null))
                .size().isEqualTo(0);
        assertThat(handlerInterceptorRegistry.eventInterceptors(resultConfig, EventSink.class, null))
                .size().isEqualTo(0);
        assertThat(handlerInterceptorRegistry.queryInterceptors(resultConfig, QueryBus.class, null))
                .size().isEqualTo(0);
    }

    @Test
    void enhanceOnlySetsDefaultsThatAreNotPresentYet() {
        TestCommandBus testCommandBus = new TestCommandBus();

        // Registers default provider registry to remove MessageOriginProvider, thus removing CorrelationDataInterceptor
        // Registers NoOpSequencingPolicy to remove the CommandSequencingInterceptor.
        ApplicationConfigurer configurer = MessagingConfigurer.enhance(new DefaultAxonApplication()).componentRegistry(
                cr -> cr.registerComponent(CommandBus.class, c -> testCommandBus)
                        .registerComponent(CorrelationDataProviderRegistry.class,
                                           c -> new DefaultCorrelationDataProviderRegistry())
                        .registerComponent(SequencingPolicy.class,
                                           MessagingConfigurationDefaults.COMMAND_SEQUENCING_POLICY,
                                           c -> NoOpSequencingPolicy.INSTANCE)
        );

        CommandBus configuredCommandBus = configurer.build()
                                                    .getComponent(CommandBus.class);

        assertEquals(testCommandBus, configuredCommandBus);
    }

    @Test
    void registersCommandSequencingInterceptorInHandlerInterceptorRegistryWithDefaultSequencingPolicy() {
        ApplicationConfigurer configurer = MessagingConfigurer.enhance(new DefaultAxonApplication());
        Configuration resultConfig = configurer.build();

        HandlerInterceptorRegistry handlerInterceptorRegistry =
                resultConfig.getComponent(HandlerInterceptorRegistry.class);
        assertThat(handlerInterceptorRegistry.commandInterceptors(resultConfig, CommandBus.class, null)
                                             .stream()
                                             .filter(CommandSequencingInterceptor.class::isInstance)
                                             .count()).isEqualTo(1);
    }

    @Test
    void doesntRegisterCommandSequencingInterceptorInHandlerInterceptorRegistryWithNoOpSequencingPolicy() {
        ApplicationConfigurer configurer = MessagingConfigurer.enhance(new DefaultAxonApplication());
        configurer.componentRegistry(cr -> cr.registerComponent(SequencingPolicy.class,
                                                                MessagingConfigurationDefaults.COMMAND_SEQUENCING_POLICY,
                                                                c -> NoOpSequencingPolicy.INSTANCE));
        Configuration resultConfig = configurer.build();

        HandlerInterceptorRegistry handlerInterceptorRegistry =
                resultConfig.getComponent(HandlerInterceptorRegistry.class);
        assertThat(handlerInterceptorRegistry.commandInterceptors(resultConfig, CommandBus.class, null)
                                             .stream()
                                             .filter(CommandSequencingInterceptor.class::isInstance)
                                             .count()).isZero();
    }

    @Test
    void enhancesComponentRegistryWithConvertingCommandGateway() {
        MessagingConfigurer configurer = MessagingConfigurer.create();
        Configuration resultConfig = configurer.build();
        assertInstanceOf(ConvertingCommandGateway.class, resultConfig.getComponent(CommandGateway.class));
    }

    @Test
    void decoratorsCommandBusAsInterceptorCommandBusWhenGenericHandlerInterceptorIsPresent() {
        //noinspection unchecked
        MessagingConfigurer configurer =
                MessagingConfigurer.create()
                                   .registerMessageHandlerInterceptor(c -> mock(MessageHandlerInterceptor.class));

        Configuration resultConfig = configurer.build();

        assertThat(resultConfig.getComponent(CommandBus.class)).isInstanceOf(InterceptingCommandBus.class);
    }

    @Test
    void decoratorsEventBusAsInterceptorEventBusWhenGenericHandlerInterceptorIsPresent() {
        //noinspection unchecked
        MessagingConfigurer configurer =
                MessagingConfigurer.create()
                                   .registerMessageHandlerInterceptor(c -> mock(MessageHandlerInterceptor.class));

        Configuration resultConfig = configurer.build();

        assertThat(resultConfig.getComponent(EventBus.class)).isInstanceOf(InterceptingEventBus.class);
    }

    @Test
    void decoratorsCommandBusAsInterceptorCommandBusWhenCommandHandlerInterceptorIsPresent() {
        // Registers default provider registry to remove MessageOriginProvider, thus removing CorrelationDataInterceptor.
        //noinspection unchecked
        MessagingConfigurer configurer =
                MessagingConfigurer.create()
                                   .componentRegistry(cr -> cr.registerComponent(
                                           CorrelationDataProviderRegistry.class,
                                           c -> new DefaultCorrelationDataProviderRegistry()
                                   ))
                                   .registerCommandHandlerInterceptor(c -> mock(MessageHandlerInterceptor.class));

        Configuration resultConfig = configurer.build();

        assertThat(resultConfig.getComponent(CommandBus.class)).isInstanceOf(InterceptingCommandBus.class);
    }

    @Test
    void decoratorsCommandBusAsInterceptorCommandBusWhenDispatchInterceptorIsPresent() {
        // Registers default provider registry to remove MessageOriginProvider, thus removing CorrelationDataInterceptor.
        //noinspection unchecked
        MessagingConfigurer configurer =
                MessagingConfigurer.create()
                                   .componentRegistry(cr -> cr.registerComponent(
                                           CorrelationDataProviderRegistry.class,
                                           c -> new DefaultCorrelationDataProviderRegistry()
                                   ))
                                   .registerDispatchInterceptor(c -> mock(MessageDispatchInterceptor.class));

        Configuration resultConfig = configurer.build();

        assertThat(resultConfig.getComponent(CommandBus.class)).isInstanceOf(InterceptingCommandBus.class);
    }

    @Test
    void decoratorsEventSinkAsInterceptorEventSinkWhenDispatchInterceptorIsPresent() {
        //noinspection unchecked
        MessagingConfigurer configurer =
                MessagingConfigurer.create()
                                   .componentRegistry(cr -> cr.registerComponent(
                                           CorrelationDataProviderRegistry.class,
                                           c -> new DefaultCorrelationDataProviderRegistry()
                                   ))
                                   .registerDispatchInterceptor(c -> mock(MessageDispatchInterceptor.class));

        Configuration resultConfig = configurer.build();

        assertThat(resultConfig.getComponent(EventSink.class)).isInstanceOf(InterceptingEventBus.class);
    }

    private static class TestCommandBus implements CommandBus {

        @Override
        public @NonNull CompletableFuture<CommandResultMessage> dispatch(@NonNull CommandMessage command,
                                                                @Nullable ProcessingContext processingContext) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @NonNull CommandBus subscribe(@NonNull QualifiedName name, @NonNull CommandHandler commandHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void describeTo(@NonNull ComponentDescriptor descriptor) {
            throw new UnsupportedOperationException();
        }
    }
}