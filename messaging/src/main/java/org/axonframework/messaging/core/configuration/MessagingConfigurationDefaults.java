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

import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.conversion.Converter;
import org.axonframework.conversion.DelegatingGeneralConverter;
import org.axonframework.conversion.GeneralConverter;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.CommandPriorityCalculator;
import org.axonframework.messaging.commandhandling.RoutingStrategy;
import org.axonframework.messaging.commandhandling.SimpleCommandBus;
import org.axonframework.messaging.commandhandling.annotation.AnnotationRoutingStrategy;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.commandhandling.gateway.ConvertingCommandGateway;
import org.axonframework.messaging.commandhandling.gateway.DefaultCommandGateway;
import org.axonframework.messaging.commandhandling.interception.CommandSequencingInterceptor;
import org.axonframework.messaging.commandhandling.interception.InterceptingCommandBus;
import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.ConfigurationApplicationContext;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.annotation.AnnotationMessageTypeResolver;
import org.axonframework.messaging.core.configuration.reflection.ParameterResolverFactoryUtils;
import org.axonframework.messaging.core.conversion.DelegatingMessageConverter;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.core.correlation.CorrelationDataProvider;
import org.axonframework.messaging.core.correlation.CorrelationDataProviderRegistry;
import org.axonframework.messaging.core.correlation.DefaultCorrelationDataProviderRegistry;
import org.axonframework.messaging.core.correlation.MessageOriginProvider;
import org.axonframework.messaging.core.interception.CorrelationDataInterceptor;
import org.axonframework.messaging.core.interception.DefaultDispatchInterceptorRegistry;
import org.axonframework.messaging.core.interception.DefaultHandlerInterceptorRegistry;
import org.axonframework.messaging.core.interception.DispatchInterceptorRegistry;
import org.axonframework.messaging.core.interception.HandlerInterceptorRegistry;
import org.axonframework.messaging.core.sequencing.NoOpSequencingPolicy;
import org.axonframework.messaging.core.sequencing.RoutingKeySequencingPolicy;
import org.axonframework.messaging.core.sequencing.SequencingPolicy;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.TransactionalUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.transaction.NoTransactionManager;
import org.axonframework.messaging.core.unitofwork.transaction.TransactionManager;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.messaging.eventhandling.gateway.DefaultEventGateway;
import org.axonframework.messaging.eventhandling.gateway.EventGateway;
import org.axonframework.messaging.monitoring.MessageMonitor;
import org.axonframework.messaging.monitoring.NoOpMessageMonitor;
import org.axonframework.messaging.monitoring.configuration.DefaultMessageMonitorRegistry;
import org.axonframework.messaging.monitoring.configuration.MessageMonitorRegistry;
import org.axonframework.messaging.monitoring.interception.MonitoringCommandHandlerInterceptor;
import org.axonframework.messaging.monitoring.interception.MonitoringEventDispatchInterceptor;
import org.axonframework.messaging.monitoring.interception.MonitoringEventHandlerInterceptor;
import org.axonframework.messaging.monitoring.interception.MonitoringQueryHandlerInterceptor;
import org.axonframework.messaging.monitoring.interception.MonitoringSubscriptionQueryUpdateDispatchInterceptor;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.axonframework.messaging.queryhandling.QueryPriorityCalculator;
import org.axonframework.messaging.queryhandling.QueryUpdateEmitter;
import org.axonframework.messaging.queryhandling.QueryUpdateEmitterParameterResolverFactory;
import org.axonframework.messaging.queryhandling.SimpleQueryBus;
import org.axonframework.messaging.queryhandling.SimpleQueryUpdateEmitter;
import org.axonframework.messaging.queryhandling.SubscriptionQueryUpdateMessage;
import org.axonframework.messaging.queryhandling.gateway.DefaultQueryGateway;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.axonframework.messaging.queryhandling.interception.InterceptingQueryBus;

import java.util.List;

/**
 * A {@link ConfigurationEnhancer} registering the default components of the {@link MessagingConfigurer}.
 * <p>
 * Will only register the following components <b>if</b> there is no component registered for the given class yet:
 * <ul>
 *     <li>Registers a {@link ClassBasedMessageTypeResolver} for class {@link MessageTypeResolver}</li>
 *     <li>Registers a {@link JacksonConverter} for class {@link Converter}</li>
 *     <li>Registers a {@link DelegatingMessageConverter} using the default {@link JacksonConverter}.</li>
 *     <li>Registers a {@link DelegatingEventConverter} using the default {@link JacksonConverter}.</li>
 *     <li>Registers a {@link DefaultCorrelationDataProviderRegistry} for class {@link CorrelationDataProviderRegistry} containing the {@link MessageOriginProvider}.</li>
 *     <li>Registers a {@link RoutingKeySequencingPolicy} for class {@link SequencingPolicy}</li>
 *     <li>Registers a {@link DefaultDispatchInterceptorRegistry} for class {@link DispatchInterceptorRegistry} containing an {@link CorrelationDataInterceptor} if there are {@link CorrelationDataProvider CorrelationDataProviders} present.</li>
 *     <li>Registers a {@link DefaultHandlerInterceptorRegistry} for class {@link HandlerInterceptorRegistry} containing an {@link CorrelationDataInterceptor} if there are {@link CorrelationDataProvider CorrelationDataProviders} present.</li>
 *     <li>Registers a {@link TransactionalUnitOfWorkFactory} for class {@link UnitOfWorkFactory}</li>
 *     <li>Registers a {@link SimpleCommandBus} for class {@link CommandBus}</li>
 *     <li>Registers a {@link CommandPriorityCalculator#defaultCalculator()} for class {@link CommandPriorityCalculator}</li>
 *     <li>Registers a {@link AnnotationRoutingStrategy} for class {@link RoutingStrategy}</li>
 *     <li>Registers a {@link DefaultCommandGateway} for class {@link CommandGateway}</li>
 *     <li>Registers a {@link DefaultEventGateway} for class {@link EventGateway}</li>
 *     <li>Registers a {@link SimpleQueryBus} for class {@link QueryBus}</li>
 *     <li>Registers a {@link SimpleQueryUpdateEmitter} for class {@link QueryUpdateEmitter}</li>
 *     <li>Registers a {@link QueryPriorityCalculator#defaultCalculator()} for class {@link QueryPriorityCalculator}</li>
 *     <li>Registers a {@link DefaultQueryGateway} for class {@link QueryGateway}</li>
 *     <li>Registers a {@link DefaultMessageMonitorRegistry} for class {@link MessageMonitorRegistry}</li>
 * </ul>
 * <p>
 * Furthermore, this enhancer will decorate the:
 * <ul>
 *     <li>The {@link CommandGateway} in a {@link ConvertingCommandGateway} with the present {@link MessageConverter}.</li>
 *     <li>The {@link CommandBus} in a {@link InterceptingCommandBus} <b>if</b> there are any
 *     {@link MessageDispatchInterceptor MessageDispatchInterceptors} present in the {@link DispatchInterceptorRegistry} or
 *     {@link MessageHandlerInterceptor MessageHandlerInterceptors} present in the {@link HandlerInterceptorRegistry}.</li>
 *     <li>The {@link QueryBus} in a {@link InterceptingQueryBus} <b>if</b> there are any
 *     {@link MessageDispatchInterceptor MessageDispatchInterceptors} present in the {@link DispatchInterceptorRegistry} or
 *     {@link MessageHandlerInterceptor MessageHandlerInterceptors} present in the {@link HandlerInterceptorRegistry}.</li>
 * </ul>
 *
 * @author Steven van Beelen
 * @since 5.0.0
 */
public class MessagingConfigurationDefaults implements ConfigurationEnhancer {

    /**
     * The order of {@code this} enhancer compared to others, equal to {@link Integer#MAX_VALUE}.
     */
    public static final int ENHANCER_ORDER = Integer.MAX_VALUE;
    /**
     * The order in which the {@link ConvertingCommandGateway} is applied to the {@link CommandGateway} in the
     * {@link ComponentRegistry}. As such, any decorator with a lower value will be applied to the delegate, and any
     * higher value will be applied to the {@link ConvertingCommandGateway} itself. Using the same value can either lead
     * to application of the decorator to the delegate or the converting command gateway, depending on the order of
     * registration.
     * <p>
     * The order of the {@link ConvertingCommandGateway} is set to {@code Integer.MIN_VALUE + 100} to ensure it is
     * applied very early in the configuration process, but not the earliest to allow for other decorators to be
     * applied.
     */
    public static final int CONVERTING_COMMAND_GATEWAY_ORDER = Integer.MIN_VALUE + 100;
    /**
     * The component name for the default command sequencing policy.
     */
    public static final String COMMAND_SEQUENCING_POLICY = "commandSequencingPolicy";

    @Override
    public int order() {
        return ENHANCER_ORDER;
    }

    @Override
    public void enhance(ComponentRegistry registry) {
        registerComponents(registry);
        registerDecorators(registry);
    }

    private static void registerComponents(ComponentRegistry registry) {
        registry.registerIfNotPresent(MessageTypeResolver.class,
                                      MessagingConfigurationDefaults::defaultMessageTypeResolver)
                .registerIfNotPresent(GeneralConverter.class, c -> defaultGeneralConverter())
                .registerIfNotPresent(MessageConverter.class, MessagingConfigurationDefaults::defaultMessageConverter)
                .registerIfNotPresent(EventConverter.class, MessagingConfigurationDefaults::defaultEventConverter)
                .registerIfNotPresent(UnitOfWorkFactory.class, MessagingConfigurationDefaults::defaultUnitOfWorkFactory)
                .registerIfNotPresent(CorrelationDataProviderRegistry.class,
                                      MessagingConfigurationDefaults::defaultCorrelationDataProviderRegistry)
                .registerIfNotPresent(SequencingPolicy.class,
                                      COMMAND_SEQUENCING_POLICY,
                                      MessagingConfigurationDefaults::defaultCommandSequencingPolicy)
                .registerIfNotPresent(DispatchInterceptorRegistry.class,
                                      MessagingConfigurationDefaults::defaultDispatchInterceptorRegistry)
                .registerIfNotPresent(HandlerInterceptorRegistry.class,
                                      MessagingConfigurationDefaults::defaultHandlerInterceptorRegistry)
                .registerIfNotPresent(CommandBus.class, MessagingConfigurationDefaults::defaultCommandBus)
                .registerIfNotPresent(CommandPriorityCalculator.class,
                                      c -> CommandPriorityCalculator.defaultCalculator())
                .registerIfNotPresent(RoutingStrategy.class, MessagingConfigurationDefaults::defaultRoutingStrategy)
                .registerIfNotPresent(CommandGateway.class, MessagingConfigurationDefaults::defaultCommandGateway)
                .registerIfNotPresent(EventGateway.class, MessagingConfigurationDefaults::defaultEventGateway)
                .registerIfNotPresent(QueryBus.class, MessagingConfigurationDefaults::defaultQueryBus)
                .registerIfNotPresent(QueryPriorityCalculator.class,
                                      c -> QueryPriorityCalculator.defaultCalculator())
                .registerIfNotPresent(QueryGateway.class, MessagingConfigurationDefaults::defaultQueryGateway)
                .registerIfNotPresent(MessageMonitorRegistry.class,
                                      MessagingConfigurationDefaults::defaultMessageMonitorRegistry);

        ParameterResolverFactoryUtils.registerToComponentRegistry(
                registry, config -> new QueryUpdateEmitterParameterResolverFactory()
        );
    }

    private static MessageTypeResolver defaultMessageTypeResolver(Configuration config) {
        return new AnnotationMessageTypeResolver();
    }

    private static DelegatingGeneralConverter defaultGeneralConverter() {
        return new DelegatingGeneralConverter(new JacksonConverter());
    }

    private static DelegatingMessageConverter defaultMessageConverter(Configuration c) {
        return new DelegatingMessageConverter(c.getComponent(GeneralConverter.class));
    }

    private static DelegatingEventConverter defaultEventConverter(Configuration c) {
        return c.getOptionalComponent(MessageConverter.class)
                .map(DelegatingEventConverter::new)
                .orElse(new DelegatingEventConverter(c.getComponent(GeneralConverter.class)));
    }

    private static UnitOfWorkFactory defaultUnitOfWorkFactory(Configuration config) {
        return new TransactionalUnitOfWorkFactory(
                config.getComponent(
                        TransactionManager.class,
                        NoTransactionManager::instance
                ),
                new SimpleUnitOfWorkFactory(new ConfigurationApplicationContext(config))
        );
    }

    private static CorrelationDataProviderRegistry defaultCorrelationDataProviderRegistry(Configuration config) {
        return new DefaultCorrelationDataProviderRegistry().registerProvider(c -> new MessageOriginProvider());
    }

    private static SequencingPolicy<? super CommandMessage> defaultCommandSequencingPolicy(Configuration config) {
        return RoutingKeySequencingPolicy.INSTANCE;
    }

    private static DispatchInterceptorRegistry defaultDispatchInterceptorRegistry(Configuration config) {
        DispatchInterceptorRegistry dispatchInterceptorRegistry = new DefaultDispatchInterceptorRegistry();
        dispatchInterceptorRegistry = registerMonitoringDispatchInterceptors(dispatchInterceptorRegistry, config);
        dispatchInterceptorRegistry = registerCorrelationInterceptor(config, dispatchInterceptorRegistry);
        return dispatchInterceptorRegistry;
    }

    private static DispatchInterceptorRegistry registerMonitoringDispatchInterceptors(
            DispatchInterceptorRegistry dispatchInterceptorRegistry,
            Configuration config
    ) {
        var messageMonitorRegistry = config.getComponent(MessageMonitorRegistry.class);
        return dispatchInterceptorRegistry.registerEventInterceptor(
                (c, componentType, componentName) -> {
                    MessageMonitor<? super EventMessage> monitor =
                            messageMonitorRegistry.eventMonitor(c, componentType, componentName);
                    if (NoOpMessageMonitor.INSTANCE.equals(monitor)) {
                        return null;
                    }
                    return new MonitoringEventDispatchInterceptor(monitor);
                }
        ).registerSubscriptionQueryUpdateInterceptor(
                (c, componentType, componentName) -> {
                    MessageMonitor<? super SubscriptionQueryUpdateMessage> monitor =
                            messageMonitorRegistry.subscriptionQueryUpdateMonitor(c, componentType, componentName);
                    if (NoOpMessageMonitor.INSTANCE.equals(monitor)) {
                        return null;
                    }
                    return new MonitoringSubscriptionQueryUpdateDispatchInterceptor(monitor);
                }
        );
    }

    private static DispatchInterceptorRegistry registerCorrelationInterceptor(
            Configuration config,
            DispatchInterceptorRegistry dispatchInterceptorRegistry
    ) {
        List<CorrelationDataProvider> providers = config
                .getComponent(CorrelationDataProviderRegistry.class)
                .correlationDataProviders(config);
        if (!providers.isEmpty()) {
            dispatchInterceptorRegistry = dispatchInterceptorRegistry
                    .registerInterceptor(c -> new CorrelationDataInterceptor<>(providers));
        }
        return dispatchInterceptorRegistry;
    }

    private static HandlerInterceptorRegistry defaultHandlerInterceptorRegistry(Configuration config) {
        HandlerInterceptorRegistry handlerInterceptorRegistry = new DefaultHandlerInterceptorRegistry();
        handlerInterceptorRegistry = registerMonitoringHandlerInterceptors(handlerInterceptorRegistry, config);
        handlerInterceptorRegistry = registerCorrelationDataInterceptor(config, handlerInterceptorRegistry);
        return handlerInterceptorRegistry;
    }

    private static HandlerInterceptorRegistry registerMonitoringHandlerInterceptors(
            HandlerInterceptorRegistry handlerInterceptorRegistry,
            Configuration config
    ) {
        final var messageMonitorRegistry = config.getComponent(MessageMonitorRegistry.class);
        return handlerInterceptorRegistry.registerCommandInterceptor(
                (c, componentType, componentName) -> {
                    MessageMonitor<? super CommandMessage> monitor =
                            messageMonitorRegistry.commandMonitor(c, componentType, componentName);
                    if (NoOpMessageMonitor.INSTANCE.equals(monitor)) {
                        return null;
                    }
                    return new MonitoringCommandHandlerInterceptor(monitor);
                }
        ).registerEventInterceptor(
                (c, componentType, componentName) -> {
                    MessageMonitor<? super EventMessage> monitor =
                            messageMonitorRegistry.eventMonitor(c, componentType, componentName);
                    if (NoOpMessageMonitor.INSTANCE.equals(monitor)) {
                        return null;
                    }
                    return new MonitoringEventHandlerInterceptor(monitor);
                }
        ).registerQueryInterceptor(
                (c, componentType, componentName) -> {
                    MessageMonitor<? super QueryMessage> monitor =
                            messageMonitorRegistry.queryMonitor(c, componentType, componentName);
                    if (NoOpMessageMonitor.INSTANCE.equals(monitor)) {
                        return null;
                    }
                    return new MonitoringQueryHandlerInterceptor(monitor);
                }
        );
    }

    private static HandlerInterceptorRegistry registerCorrelationDataInterceptor(
            Configuration config,
            HandlerInterceptorRegistry handlerInterceptorRegistry
    ) {
        List<CorrelationDataProvider> providers = config
                .getComponent(CorrelationDataProviderRegistry.class)
                .correlationDataProviders(config);
        if (!providers.isEmpty()) {
            handlerInterceptorRegistry = handlerInterceptorRegistry
                    .registerInterceptor(c -> new CorrelationDataInterceptor<>(providers));
        }

        @SuppressWarnings("unchecked")
        SequencingPolicy<? super CommandMessage> commandSequencingPolicy = config.getComponent(SequencingPolicy.class,
                                                                                               COMMAND_SEQUENCING_POLICY);
        if (!(commandSequencingPolicy instanceof NoOpSequencingPolicy)) {
            handlerInterceptorRegistry = handlerInterceptorRegistry
                    .registerCommandInterceptor(c -> new CommandSequencingInterceptor<>(commandSequencingPolicy));
        }
        return handlerInterceptorRegistry;
    }

    private static CommandBus defaultCommandBus(Configuration config) {
        return new SimpleCommandBus(config.getComponent(UnitOfWorkFactory.class));
    }

    private static RoutingStrategy defaultRoutingStrategy(Configuration config) {
        return new AnnotationRoutingStrategy();
    }

    private static CommandGateway defaultCommandGateway(Configuration config) {
        return new DefaultCommandGateway(
                config.getComponent(CommandBus.class),
                config.getComponent(MessageTypeResolver.class),
                config.getComponent(CommandPriorityCalculator.class),
                config.getComponent(RoutingStrategy.class)
        );
    }

    private static EventGateway defaultEventGateway(Configuration config) {
        return new DefaultEventGateway(
                config.getComponent(EventSink.class),
                config.getComponent(MessageTypeResolver.class)
        );
    }

    private static QueryGateway defaultQueryGateway(Configuration config) {
        return new DefaultQueryGateway(
                config.getComponent(QueryBus.class),
                config.getComponent(MessageTypeResolver.class),
                config.getComponent(QueryPriorityCalculator.class),
                config.getComponent(MessageConverter.class)
        );
    }

    private static QueryBus defaultQueryBus(Configuration config) {
        return new SimpleQueryBus(config.getComponent(UnitOfWorkFactory.class));
    }

    private static MessageMonitorRegistry defaultMessageMonitorRegistry(Configuration config) {
        return new DefaultMessageMonitorRegistry();
    }

    private static void registerDecorators(ComponentRegistry registry) {
        registry.registerDecorator(
                CommandGateway.class,
                CONVERTING_COMMAND_GATEWAY_ORDER,
                (config, name, delegate) -> new ConvertingCommandGateway(
                        delegate,
                        config.getComponent(MessageConverter.class)
                )
        );
        registry.registerDecorator(
                CommandBus.class,
                InterceptingCommandBus.DECORATION_ORDER,
                (config, name, delegate) -> {
                    List<MessageHandlerInterceptor<? super CommandMessage>> handlerInterceptors =
                            config.getComponent(HandlerInterceptorRegistry.class)
                                  .commandInterceptors(config, CommandBus.class, name);
                    List<MessageDispatchInterceptor<? super CommandMessage>> dispatchInterceptors =
                            config.getComponent(DispatchInterceptorRegistry.class)
                                  .commandInterceptors(config, CommandBus.class, name);

                    return handlerInterceptors.isEmpty() && dispatchInterceptors.isEmpty()
                            ? delegate
                            : new InterceptingCommandBus(delegate, handlerInterceptors, dispatchInterceptors);
                }
        );
        registry.registerDecorator(
                QueryBus.class,
                InterceptingQueryBus.DECORATION_ORDER,
                (config, name, delegate) -> {
                    List<MessageHandlerInterceptor<? super QueryMessage>> handlerInterceptors =
                            config.getComponent(HandlerInterceptorRegistry.class)
                                  .queryInterceptors(config, QueryBus.class, name);
                    List<MessageDispatchInterceptor<? super QueryMessage>> dispatchInterceptors =
                            config.getComponent(DispatchInterceptorRegistry.class)
                                  .queryInterceptors(config, QueryBus.class, name);
                    List<MessageDispatchInterceptor<? super SubscriptionQueryUpdateMessage>> updateDispatchInterceptors =
                            config.getComponent(DispatchInterceptorRegistry.class)
                                  .subscriptionQueryUpdateInterceptors(config, QueryBus.class, name);

                    return handlerInterceptors.isEmpty() && dispatchInterceptors.isEmpty()
                            && updateDispatchInterceptors.isEmpty()
                            ? delegate
                            : new InterceptingQueryBus(delegate,
                                                       handlerInterceptors,
                                                       dispatchInterceptors,
                                                       updateDispatchInterceptors);
                }
        );
    }
}
