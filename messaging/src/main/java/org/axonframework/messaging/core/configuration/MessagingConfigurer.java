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
import org.axonframework.common.configuration.AxonConfiguration;
import org.axonframework.common.configuration.Component;
import org.axonframework.common.configuration.ComponentBuilder;
import org.axonframework.common.configuration.ComponentDecorator;
import org.axonframework.common.configuration.ComponentDefinition;
import org.axonframework.common.configuration.ComponentRegistry;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.common.configuration.DefaultAxonApplication;
import org.axonframework.common.configuration.LifecycleRegistry;
import org.axonframework.common.configuration.Module;
import org.axonframework.common.configuration.ModuleBuilder;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.configuration.CommandHandlingModule;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageDispatchInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.correlation.CorrelationDataProvider;
import org.axonframework.messaging.core.correlation.CorrelationDataProviderRegistry;
import org.axonframework.messaging.core.interception.DispatchInterceptorRegistry;
import org.axonframework.messaging.core.interception.HandlerInterceptorRegistry;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.EventSink;
import org.axonframework.messaging.eventhandling.configuration.EventBusConfigurationDefaults;
import org.axonframework.messaging.eventhandling.configuration.EventProcessingConfigurer;
import org.axonframework.messaging.eventhandling.processing.EventProcessor;
import org.axonframework.messaging.monitoring.MessageMonitor;
import org.axonframework.messaging.monitoring.configuration.MessageMonitorFactory;
import org.axonframework.messaging.monitoring.configuration.MessageMonitorRegistry;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.axonframework.messaging.queryhandling.SubscriptionQueryUpdateMessage;
import org.axonframework.messaging.queryhandling.configuration.QueryGatewayConfigurer;
import org.axonframework.messaging.queryhandling.configuration.QueryHandlingModule;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static java.util.Objects.requireNonNull;
import static org.axonframework.messaging.core.configuration.reflection.ParameterResolverFactoryUtils.registerToComponentRegistry;

/**
 * The messaging {@link ApplicationConfigurer} of Axon Framework's configuration API.
 * <p>
 * Provides register operations for {@link #registerCommandBus(ComponentBuilder) command},
 * {@link #registerEventSink(ComponentBuilder) event}, and {@link #registerQueryBus(ComponentBuilder) query}
 * infrastructure components.
 * <p>
 * This configurer registers several defaults, provided by class {@link MessagingConfigurationDefaults}.<br/> To replace
 * or decorate any of these defaults, use their respective interfaces as the identifier. For example, to adjust the
 * {@code CommandBus}, invoke {@link #componentRegistry(Consumer)} and
 * {@link ComponentRegistry#registerComponent(Class, ComponentBuilder)} with {@code CommandBus.class} to replace it.
 * <p>
 * <pre><code>
 *     MessagingConfigurer.create()
 *                        .componentRegistry(cr -> cr.registerEnhancer(CommandBus.class, (config, component) -> ...));
 * </code></pre>
 *
 * @author Allard Buijze
 * @author Steven van Beelen
 * @since 5.0.0
 */
public class MessagingConfigurer implements ApplicationConfigurer {

    private final ApplicationConfigurer delegate;
    private final EventProcessingConfigurer eventProcessing;

    /**
     * Constructs a {@code MessagingConfigurer} based on the given {@code delegate}.
     *
     * @param delegate The delegate {@code ApplicationConfigurer} the {@code MessagingConfigurer} is based on.
     */
    private MessagingConfigurer(ApplicationConfigurer delegate) {
        this.delegate =
                requireNonNull(delegate, "The Application Configurer cannot be null.");
        this.eventProcessing = new EventProcessingConfigurer(this);
    }

    /**
     * Creates a MessagingConfigurer that enhances an existing {@code ApplicationConfigurer}. This method is useful when
     * applying multiple specialized Configurers to configure a single application.
     *
     * @param applicationConfigurer The {@code ApplicationConfigurer} to enhance with configuration of messaging
     *                              components.
     * @return The current instance of the {@code Configurer} for a fluent API.
     * @see #create()
     */
    public static MessagingConfigurer enhance(ApplicationConfigurer applicationConfigurer) {
        return new MessagingConfigurer(applicationConfigurer)
                .componentRegistry(cr -> cr
                        .registerEnhancer(new EventBusConfigurationDefaults())
                        .registerEnhancer(new MessagingConfigurationDefaults())
                );
    }

    /**
     * Build a default {@code MessagingConfigurer} instance with several messaging defaults, as well as methods to
     * register (e.g.) a {@link #registerCommandBus(ComponentBuilder) command bus}.
     * <p>
     * Besides the specific operations, the {@code MessagingConfigurer} allows for configuring generic
     * {@link Component components}, {@link ComponentDecorator component decorators},
     * {@link ConfigurationEnhancer enhancers}, and {@link org.axonframework.common.configuration.Module modules} for a
     * message-driven application.
     *
     * @return A {@code MessagingConfigurer} instance for further configuring.
     * @see #enhance(ApplicationConfigurer)
     */
    public static MessagingConfigurer create() {
        return enhance(new DefaultAxonApplication());
    }

    /**
     * Registers the given {@link MessageTypeResolver} factory in this {@code Configurer}. This is the global
     * {@link MessageTypeResolver}, whose mappings can be accessed by all Modules and Components within the
     * application.
     * <p>
     * The {@code commandBusFactory} receives the {@link Configuration} as input and is expected to return a
     * {@link MessageTypeResolver} instance.
     *
     * @param messageTypeResolverFactory The factory building the {@link MessageTypeResolver}.
     * @return The current instance of the {@code Configurer} for a fluent API.
     */
    public MessagingConfigurer registerMessageTypeResolver(
            ComponentBuilder<MessageTypeResolver> messageTypeResolverFactory
    ) {
        delegate.componentRegistry(cr -> cr.registerComponent(
                MessageTypeResolver.class, messageTypeResolverFactory
        ));
        return this;
    }

    /**
     * Registers the given {@link CommandBus} factory in this {@code Configurer}.
     * <p>
     * The {@code commandBusBuilder} receives the {@link Configuration} as input and is expected to return a
     * {@link CommandBus} instance.
     *
     * @param commandBusBuilder The builder constructing the {@link CommandBus}.
     * @return The current instance of the {@code Configurer} for a fluent API.
     */
    public MessagingConfigurer registerCommandBus(ComponentBuilder<CommandBus> commandBusBuilder) {
        delegate.componentRegistry(cr -> cr.registerComponent(CommandBus.class, commandBusBuilder));
        return this;
    }

    /**
     * Registers the given {@link EventSink} factory in this {@code Configurer}.
     * <p>
     * The {@code eventSinkBuilder} receives the {@link Configuration} as input and is expected to return a
     * {@link EventSink} instance.
     *
     * @param eventSinkBuilder The builder constructing the {@link EventSink}.
     * @return The current instance of the {@code Configurer} for a fluent API.
     */
    public MessagingConfigurer registerEventSink(ComponentBuilder<EventSink> eventSinkBuilder) {
        delegate.componentRegistry(cr -> cr.registerComponent(EventSink.class, eventSinkBuilder));
        return this;
    }

    /**
     * Registers the given {@link QueryBus} factory in this {@code Configurer}.
     * <p>
     * The {@code queryBusBuilder} receives the {@link Configuration} as input and is expected to return a
     * {@link QueryBus} instance.
     *
     * @param queryBusBuilder The builder constructing the {@link QueryBus}.
     * @return The current instance of the {@code Configurer} for a fluent API.
     */
    public MessagingConfigurer registerQueryBus(ComponentBuilder<QueryBus> queryBusBuilder) {
        delegate.componentRegistry(cr -> cr.registerComponent(QueryBus.class, queryBusBuilder));
        return this;
    }

    /**
     * Registers the given {@link ParameterResolverFactory} factory in this {@code Configurer}.
     * <p>
     * The {@code parameterResolverFactoryBuilder} receives the {@link Configuration} as input and is expected to return
     * a {@link ParameterResolverFactory} instance.
     *
     * @param parameterResolverFactoryBuilder The builder constructing the {@link ParameterResolverFactory}.
     * @return The current instance of the {@code Configurer} for a fluent API.
     */
    public MessagingConfigurer registerParameterResolverFactory(
            ComponentBuilder<ParameterResolverFactory> parameterResolverFactoryBuilder
    ) {
        delegate.componentRegistry(registry -> registerToComponentRegistry(
                registry,
                parameterResolverFactoryBuilder::build
        ));
        return this;
    }

    /**
     * Registers the given {@link UnitOfWorkFactory} factory in this {@code Configurer}.
     * <p>
     * The {@code unitOfWorkFactoryBuilder} receives the {@link Configuration} as input and is expected to return a
     * {@link UnitOfWorkFactory} instance.
     *
     * @param unitOfWorkFactoryBuilder The builder constructing the {@link UnitOfWorkFactory}.
     * @return The current instance of the {@code Configurer} for a fluent API.
     */
    public MessagingConfigurer registerUnitOfWorkFactory(
            ComponentBuilder<UnitOfWorkFactory> unitOfWorkFactoryBuilder
    ) {
        delegate.componentRegistry(
                cr -> cr.registerComponent(UnitOfWorkFactory.class, unitOfWorkFactoryBuilder)
        );
        return this;
    }

    /**
     * Registers the given {@link CorrelationDataProvider} factory in this {@code providerBuilder}.
     * <p>
     * The {@code interceptorBuilder} receives the {@link Configuration} as input and is expected to return a
     * {@code CorrelationDataProvider} instance.
     * <p>
     * {@code CorrelationDataProviders} are typically automatically registered with all applicable infrastructure
     * components through the {@link CorrelationDataProviderRegistry}.
     *
     * @param providerBuilder The builder constructing the {@link CorrelationDataProvider}.
     * @return The current instance of the {@code Configurer} for a fluent API
     */
    public MessagingConfigurer registerCorrelationDataProvider(
            ComponentBuilder<CorrelationDataProvider> providerBuilder
    ) {
        delegate.componentRegistry(cr -> cr.registerDecorator(
                CorrelationDataProviderRegistry.class,
                0,
                (config, name, delegate) -> delegate.registerProvider(providerBuilder)
        ));
        return this;
    }

    /**
     * Registers the given generic {@link Message} {@link MessageDispatchInterceptor} builder in this
     * {@code Configurer}.
     * <p>
     * The {@code interceptorBuilder} receives the {@link Configuration} as input and is expected to return a generic
     * {@code Message} {@code MessageDispatchInterceptor} instance.
     * <p>
     * Generic {@code MessageDispatchInterceptors} are typically automatically registered with all applicable
     * infrastructure components through the {@link DispatchInterceptorRegistry}.
     *
     * @param interceptorBuilder The builder constructing the generic {@link Message}
     *                           {@link MessageDispatchInterceptor}.
     * @return The current instance of the {@code Configurer} for a fluent API
     */
    public MessagingConfigurer registerDispatchInterceptor(
            ComponentBuilder<MessageDispatchInterceptor<Message>> interceptorBuilder
    ) {
        delegate.componentRegistry(cr -> cr.registerDecorator(
                DispatchInterceptorRegistry.class,
                0,
                (config, name, delegate) -> delegate.registerInterceptor(interceptorBuilder)
        ));
        return this;
    }

    /**
     * Registers the given {@link CommandMessage}-specific {@link MessageDispatchInterceptor} builder in this
     * {@code Configurer}.
     * <p>
     * The {@code interceptorBuilder} receives the {@link Configuration} as input and is expected to return a
     * {@code CommandMessage}-specific {@code MessageDispatchInterceptor} instance.
     * <p>
     * {@code CommandMessage} {@code MessageDispatchInterceptors} are typically automatically registered with all
     * applicable infrastructure components through the {@link DispatchInterceptorRegistry}.
     *
     * @param interceptorBuilder The builder constructing the {@link CommandMessage}-specific
     *                           {@link MessageDispatchInterceptor}.
     * @return The current instance of the {@code Configurer} for a fluent API
     */
    public MessagingConfigurer registerCommandDispatchInterceptor(
            ComponentBuilder<MessageDispatchInterceptor<? super CommandMessage>> interceptorBuilder
    ) {
        delegate.componentRegistry(cr -> cr.registerDecorator(
                DispatchInterceptorRegistry.class,
                0,
                (config, name, delegate) -> delegate.registerCommandInterceptor(interceptorBuilder)
        ));
        return this;
    }

    /**
     * Registers the given {@link EventMessage}-specific {@link MessageDispatchInterceptor} builder in this
     * {@code Configurer}.
     * <p>
     * The {@code interceptorBuilder} receives the {@link Configuration} as input and is expected to return a
     * {@code EventMessage}-specific {@code MessageDispatchInterceptor} instance.
     * <p>
     * {@code EventMessage}-specific {@code MessageDispatchInterceptors} are typically automatically registered with all
     * applicable infrastructure components through the {@link DispatchInterceptorRegistry}.
     *
     * @param interceptorBuilder The builder constructing the {@link EventMessage}-specific
     *                           {@link MessageDispatchInterceptor}.
     * @return The current instance of the {@code Configurer} for a fluent API
     */
    public MessagingConfigurer registerEventDispatchInterceptor(
            ComponentBuilder<MessageDispatchInterceptor<? super EventMessage>> interceptorBuilder
    ) {
        delegate.componentRegistry(cr -> cr.registerDecorator(
                DispatchInterceptorRegistry.class,
                0,
                (config, name, delegate) -> delegate.registerEventInterceptor(interceptorBuilder)
        ));
        return this;
    }

    /**
     * Registers the given {@link QueryMessage}-specific {@link MessageDispatchInterceptor} builder in this
     * {@code Configurer}.
     * <p>
     * The {@code interceptorBuilder} receives the {@link Configuration} as input and is expected to return a
     * {@code QueryMessage}-specific {@code MessageDispatchInterceptor} instance.
     * <p>
     * {@code QueryMessage}-specific {@code MessageDispatchInterceptors} are typically automatically registered with all
     * applicable infrastructure components through the {@link DispatchInterceptorRegistry}.
     *
     * @param interceptorBuilder The builder constructing the {@link QueryMessage}-specific
     *                           {@link MessageDispatchInterceptor}.
     * @return The current instance of the {@code Configurer} for a fluent API
     */
    public MessagingConfigurer registerQueryDispatchInterceptor(
            ComponentBuilder<MessageDispatchInterceptor<? super QueryMessage>> interceptorBuilder
    ) {
        delegate.componentRegistry(cr -> cr.registerDecorator(
                DispatchInterceptorRegistry.class,
                0,
                (config, name, delegate) -> delegate.registerQueryInterceptor(interceptorBuilder)
        ));
        return this;
    }

    /**
     * Registers the given generic {@link Message} {@link MessageHandlerInterceptor} builder in this
     * {@code Configurer}.
     * <p>
     * The {@code interceptorBuilder} receives the {@link Configuration} as input and is expected to return a generic
     * {@code Message} {@code MessageHandlerInterceptor} instance.
     * <p>
     * Generic {@code MessageHandlerInterceptors} are typically automatically registered with all applicable
     * infrastructure components through the {@link HandlerInterceptorRegistry}.
     *
     * @param interceptorBuilder The builder constructing the generic {@link Message}
     *                           {@link MessageHandlerInterceptor}.
     * @return The current instance of the {@code Configurer} for a fluent API
     */
    public MessagingConfigurer registerMessageHandlerInterceptor(
            ComponentBuilder<MessageHandlerInterceptor<Message>> interceptorBuilder
    ) {
        delegate.componentRegistry(cr -> cr.registerDecorator(
                HandlerInterceptorRegistry.class,
                0,
                (config, name, delegate) -> delegate.registerInterceptor(interceptorBuilder)
        ));
        return this;
    }

    /**
     * Registers the given {@link CommandMessage} {@link MessageHandlerInterceptor} builder in this {@code Configurer}.
     * <p>
     * The {@code interceptorBuilder} receives the {@link Configuration} as input and is expected to return a
     * {@code CommandMessage} {@code MessageHandlerInterceptor} instance.
     * <p>
     * {@code CommandMessage} {@code MessageHandlerInterceptors} are typically automatically registered with all
     * applicable infrastructure components through the {@link HandlerInterceptorRegistry}.
     *
     * @param interceptorBuilder The builder constructing the {@link CommandMessage} {@link MessageHandlerInterceptor}.
     * @return The current instance of the {@code Configurer} for a fluent API
     */
    public MessagingConfigurer registerCommandHandlerInterceptor(
            ComponentBuilder<MessageHandlerInterceptor<? super CommandMessage>> interceptorBuilder
    ) {
        delegate.componentRegistry(cr -> cr.registerDecorator(
                HandlerInterceptorRegistry.class,
                0,
                (config, name, delegate) -> delegate.registerCommandInterceptor(interceptorBuilder)
        ));
        return this;
    }

    /**
     * Registers the given {@link EventMessage} {@link MessageHandlerInterceptor} builder in this {@code Configurer}.
     * <p>
     * The {@code interceptorBuilder} receives the {@link Configuration} as input and is expected to return a
     * {@code EventMessage} {@code MessageHandlerInterceptor} instance.
     * <p>
     * {@code EventMessage} {@code MessageHandlerInterceptors} are typically automatically registered with all
     * applicable infrastructure components through the {@link HandlerInterceptorRegistry}.
     *
     * @param interceptorBuilder The builder constructing the {@link EventMessage} {@link MessageHandlerInterceptor}.
     * @return The current instance of the {@code Configurer} for a fluent API
     */
    public MessagingConfigurer registerEventHandlerInterceptor(
            ComponentBuilder<MessageHandlerInterceptor<? super EventMessage>> interceptorBuilder
    ) {
        delegate.componentRegistry(cr -> cr.registerDecorator(
                HandlerInterceptorRegistry.class,
                0,
                (config, name, delegate) -> delegate.registerEventInterceptor(interceptorBuilder)
        ));
        return this;
    }

    /**
     * Registers the given {@link QueryMessage} {@link MessageHandlerInterceptor} builder in this {@code Configurer}.
     * <p>
     * The {@code interceptorBuilder} receives the {@link Configuration} as input and is expected to return a
     * {@code QueryMessage} {@code MessageHandlerInterceptor} instance.
     * <p>
     * {@code QueryMessage} {@code MessageHandlerInterceptors} are typically automatically registered with all
     * applicable infrastructure components through the {@link HandlerInterceptorRegistry}.
     *
     * @param interceptorBuilder The builder constructing the {@link QueryMessage} {@link MessageHandlerInterceptor}.
     * @return The current instance of the {@code Configurer} for a fluent API
     */
    public MessagingConfigurer registerQueryHandlerInterceptor(
            ComponentBuilder<MessageHandlerInterceptor<? super QueryMessage>> interceptorBuilder
    ) {
        delegate.componentRegistry(cr -> cr.registerDecorator(
                HandlerInterceptorRegistry.class,
                0,
                (config, name, delegate) -> delegate.registerQueryInterceptor(interceptorBuilder)
        ));
        return this;
    }

    /**
     * Registers the given {@link ModuleBuilder builder} for a {@link CommandHandlingModule} to use in this
     * configuration.
     * <p>
     * As a {@link org.axonframework.common.configuration.Module} implementation, any components registered with the
     * result of the given {@code moduleBuilder} will not be accessible from other {@code Modules} to enforce
     * encapsulation. The sole exception to this, are {@code Modules} registered with the resulting
     * {@link CommandHandlingModule} itself.
     *
     * @param moduleBuilder The builder returning a command handling module to register with
     *                      {@code this MessagingConfigurer}.
     * @return The current instance of the {@code Configurer} for a fluent API
     */
    public MessagingConfigurer registerCommandHandlingModule(ModuleBuilder<CommandHandlingModule> moduleBuilder) {
        Objects.requireNonNull(moduleBuilder, "The moduleBuilder cannot be null.");
        delegate.componentRegistry(cr -> cr.registerModule(moduleBuilder.build()));
        return this;
    }

    /**
     * Registers the given {@link ModuleBuilder builder} for a {@link QueryHandlingModule} to use in this
     * configuration.
     * <p>
     * As a {@link Module} implementation, any components registered with the result of the given {@code moduleBuilder}
     * will not be accessible from other {@code Modules} to enforce encapsulation. The sole exception to this, are
     * {@code Modules} registered with the resulting {@link QueryHandlingModule} itself.
     *
     * @param moduleBuilder The builder returning a query handling module to register with
     *                      {@code this MessagingConfigurer}.
     * @return The current instance of the {@code Configurer} for a fluent API
     */
    public MessagingConfigurer registerQueryHandlingModule(ModuleBuilder<QueryHandlingModule> moduleBuilder) {
        Objects.requireNonNull(moduleBuilder, "The moduleBuilder cannot be null.");
        delegate.componentRegistry(cr -> cr.registerModule(moduleBuilder.build()));
        return this;
    }

    /**
     * Registers a {@link MessageMonitor} builder for any type of messaging component in this configuration.
     * <p>
     * If more fine-grained control is needed how and when a monitor should be created, use the
     * {@link #registerMessageMonitor(MessageMonitorFactory)} operation instead.
     *
     * @param monitorBuilder a builder for creating a {@link MessageMonitor} instance to monitor messages.
     * @return the current instance of the {@code Configurer} for a fluent API
     */
    public MessagingConfigurer registerMessageMonitor(ComponentBuilder<MessageMonitor<Message>> monitorBuilder) {
        return registerWithRegistry(registry -> registry.registerMonitor(monitorBuilder));
    }

    /**
     * Registers a {@link MessageMonitorFactory message monitor factory} for any type of messaging component in this
     * configuration.
     * <p>
     * The factory receives the component's type and name for which it is invoked, ensuring unique
     * {@link MessageMonitor MessageMonitors} can be constructed for the component at hand.
     *
     * @param monitorFactory a factory for creating a {@link MessageMonitor} instance to monitor messages
     * @return the current instance of the {@code Configurer} for a fluent API
     */
    public MessagingConfigurer registerMessageMonitor(MessageMonitorFactory<Message> monitorFactory) {
        return registerWithRegistry(registry -> registry.registerMonitor(monitorFactory));
    }

    /**
     * Registers a {@link CommandMessage command} {@link MessageMonitor} builder for all command-specific infrastructure
     * components component in this configuration.
     * <p>
     * If more fine-grained control is needed how and when a monitor should be created, use the
     * {@link #registerCommandMonitor(MessageMonitorFactory)} operation instead.
     *
     * @param monitorBuilder a builder for creating a {@link CommandMessage command-specific} {@link MessageMonitor}
     *                       instance
     * @return the current instance of the {@code Configurer} for a fluent API
     */
    public MessagingConfigurer registerCommandMonitor(
            ComponentBuilder<MessageMonitor<? super CommandMessage>> monitorBuilder
    ) {
        return registerWithRegistry(registry -> registry.registerCommandMonitor(monitorBuilder));
    }

    /**
     * Registers a {@link MessageMonitorFactory message monitor factory} for {@link CommandMessage command-specific}
     * infrastructure components in this configuration.
     * <p>
     * The factory receives the component's type and name for which it is invoked, ensuring unique
     * {@link MessageMonitor MessageMonitors} can be constructed for the component at hand.
     *
     * @param monitorFactory a factory for creating a {@link CommandMessage command-specific} {@link MessageMonitor}
     *                       instance
     * @return the current instance of the {@code Configurer} for a fluent API
     */
    public MessagingConfigurer registerCommandMonitor(MessageMonitorFactory<? super CommandMessage> monitorFactory) {
        return registerWithRegistry(registry -> registry.registerCommandMonitor(monitorFactory));
    }

    /**
     * Registers a {@link EventMessage event} {@link MessageMonitor} builder for all event-specific infrastructure
     * components component in this configuration.
     * <p>
     * If more fine-grained control is needed how and when a monitor should be created, use the
     * {@link #registerEventMonitor(MessageMonitorFactory)} operation instead.
     *
     * @param monitorBuilder a builder for creating a {@link EventMessage event-specific} {@link MessageMonitor}
     *                       instance
     * @return the current instance of the {@code Configurer} for a fluent API
     */
    public MessagingConfigurer registerEventMonitor(
            ComponentBuilder<MessageMonitor<? super EventMessage>> monitorBuilder
    ) {
        return registerWithRegistry(registry -> registry.registerEventMonitor(monitorBuilder));
    }

    /**
     * Registers a {@link MessageMonitorFactory message monitor factory} for {@link EventMessage event-specific}
     * infrastructure components in this configuration.
     * <p>
     * The factory receives the component's type and name for which it is invoked, ensuring unique
     * {@link MessageMonitor MessageMonitors} can be constructed for the component at hand.
     *
     * @param monitorFactory a factory for creating a {@link EventMessage event-specific} {@link MessageMonitor}
     *                       instance
     * @return the current instance of the {@code Configurer} for a fluent API
     */
    public MessagingConfigurer registerEventMonitor(MessageMonitorFactory<? super EventMessage> monitorFactory) {
        return registerWithRegistry(registry -> registry.registerEventMonitor(monitorFactory));
    }

    /**
     * Registers a query monitor using the specified {@code monitorBuilder}.
     * <p>
     * If more fine-grained control is needed how and when a monitor should be created, use the
     * {@link #registerQueryMonitor(MessageMonitorFactory)} operation instead.
     *
     * @param monitorBuilder a builder for creating a {@link QueryMessage query-specific} {@link MessageMonitor}
     *                       instance
     * @return the current instance of the {@code Configurer} for a fluent API
     */
    public MessagingConfigurer registerQueryMonitor(
            ComponentBuilder<MessageMonitor<? super QueryMessage>> monitorBuilder
    ) {
        return registerWithRegistry(registry -> registry.registerQueryMonitor(monitorBuilder));
    }

    /**
     * Registers a {@link MessageMonitorFactory message monitor factory} for {@link QueryMessage query-specific}
     * infrastructure components in this configuration.
     * <p>
     * The factory receives the component's type and name for which it is invoked, ensuring unique
     * {@link MessageMonitor MessageMonitors} can be constructed for the component at hand.
     *
     * @param monitorFactory a factory for creating a {@link QueryMessage query-specific} {@link MessageMonitor}
     *                       instance
     * @return the current instance of the {@code Configurer} for a fluent API
     */
    public MessagingConfigurer registerQueryMonitor(
            MessageMonitorFactory<? super QueryMessage> monitorFactory
    ) {
        return registerWithRegistry(registry -> registry.registerQueryMonitor(monitorFactory));
    }

    /**
     * Registers a subscription query update monitor using the specified {@code monitorBuilder}.
     * <p>
     * If more fine-grained control is needed how and when a monitor should be created, use the
     * {@link #registerSubscriptionQueryUpdateMonitor(MessageMonitorFactory)} operation instead.
     *
     * @param monitorBuilder a builder for creating a
     *                       {@link SubscriptionQueryUpdateMessage subscription-update-specific} {@link MessageMonitor}
     *                       instance
     * @return the current instance of the {@code Configurer} for a fluent API
     */
    public MessagingConfigurer registerSubscriptionQueryUpdateMonitor(
            ComponentBuilder<MessageMonitor<? super SubscriptionQueryUpdateMessage>> monitorBuilder
    ) {
        return registerWithRegistry(registry -> registry.registerSubscriptionQueryUpdateMonitor(monitorBuilder));
    }

    /**
     * Registers a {@link MessageMonitorFactory message monitor factory} for
     * {@link SubscriptionQueryUpdateMessage subscription-update-specific} infrastructure components in this
     * configuration.
     * <p>
     * The factory receives the component's type and name for which it is invoked, ensuring unique
     * {@link MessageMonitor MessageMonitors} can be constructed for the component at hand.
     *
     * @param monitorFactory a factory for creating a
     *                       {@link SubscriptionQueryUpdateMessage subscription-update-specific} {@link MessageMonitor}
     *                       instance
     * @return the current instance of the {@code Configurer} for a fluent API
     */
    public MessagingConfigurer registerSubscriptionQueryUpdateMonitor(
            MessageMonitorFactory<? super SubscriptionQueryUpdateMessage> monitorFactory
    ) {
        return registerWithRegistry(registry -> registry.registerSubscriptionQueryUpdateMonitor(monitorFactory));
    }

    private MessagingConfigurer registerWithRegistry(UnaryOperator<MessageMonitorRegistry> registration) {
        delegate.componentRegistry(cr -> cr.registerDecorator(
                MessageMonitorRegistry.class, 0, (config, name, delegate) -> registration.apply(delegate)
        ));
        return this;
    }

    @Override
    public MessagingConfigurer componentRegistry(Consumer<ComponentRegistry> componentRegistrar) {
        delegate.componentRegistry(
                requireNonNull(componentRegistrar, "The configure task must no be null.")
        );
        return this;
    }

    @Override
    public MessagingConfigurer lifecycleRegistry(Consumer<LifecycleRegistry> lifecycleRegistrar) {
        delegate.lifecycleRegistry(
                requireNonNull(lifecycleRegistrar, "The lifecycle registrar must not be null.")
        );
        return this;
    }

    /**
     * Delegates given {@code configurerTask} to the {@link EventProcessingConfigurer}.
     * <p>
     * Use this operation to configure defaults and register {@link EventProcessor}s.
     *
     * @param configurerTask Lambda consuming the {@link EventProcessingConfigurer}.
     * @return The current instance of the {@code Configurer} for a fluent API.
     */
    public MessagingConfigurer eventProcessing(Consumer<EventProcessingConfigurer> configurerTask) {
        Objects.requireNonNull(configurerTask, "The configurerTask may not be null");
        configurerTask.accept(eventProcessing);
        return this;
    }

    /**
     * Defines a named {@link QueryGateway} component using a {@link QueryGatewayConfigurer}.
     * <p>
     * Each call registers an independently configured {@link QueryGateway} under the given {@code name}. The gateway
     * starts from a bare {@link org.axonframework.messaging.queryhandling.gateway.DefaultQueryGateway} sharing
     * infrastructure with the main configuration (same {@link QueryBus},
     * {@link org.axonframework.messaging.core.MessageTypeResolver},
     * {@link org.axonframework.messaging.queryhandling.QueryPriorityCalculator}, and
     * {@link org.axonframework.messaging.core.conversion.MessageConverter}), and is optionally wrapped with a
     * {@link org.axonframework.messaging.queryhandling.gateway.ShutdownTrackingQueryGateway} when shutdown specs are
     * configured on the provided {@link QueryGatewayConfigurer}.
     * <p>
     * Example:
     * <pre>{@code
     * MessagingConfigurer.create()
     *     .queryGateway("reporting", g -> g
     *         .cancellingSubscriptionQueryOnShutdown(c -> c.withGracePeriod(Duration.ofSeconds(10)))
     *         .cancellingStreamingQueryOnShutdown(c -> c.withGracePeriod(Duration.ofSeconds(5)))
     *     );
     * }</pre>
     *
     * @param name           the name under which the {@link QueryGateway} is registered
     * @param configurerTask lambda consuming the {@link QueryGatewayConfigurer}
     * @return the current instance of the {@code Configurer} for a fluent API
     */
    public MessagingConfigurer queryGateway(String name, Consumer<QueryGatewayConfigurer> configurerTask) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(configurerTask, "configurerTask must not be null");
        QueryGatewayConfigurer configurer = new QueryGatewayConfigurer(name);
        configurerTask.accept(configurer);
        ComponentDefinition<QueryGateway> definition = configurer.buildDefinition();
        delegate.componentRegistry(cr -> cr.registerComponent(definition));
        return this;
    }

    @Override
    public AxonConfiguration build() {
        eventProcessing.build();
        return delegate.build();
    }
}
