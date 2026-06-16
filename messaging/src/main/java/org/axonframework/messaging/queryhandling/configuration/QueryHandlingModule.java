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

package org.axonframework.messaging.queryhandling.configuration;

import org.axonframework.common.configuration.ApplicationConfigurer;
import org.axonframework.common.configuration.ComponentBuilder;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.Module;
import org.axonframework.common.configuration.ModuleBuilder;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlingExceptionHandler;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.axonframework.messaging.queryhandling.QueryHandler;
import org.axonframework.messaging.queryhandling.QueryHandlingComponent;
import org.axonframework.messaging.queryhandling.QueryHandlingExceptionHandler;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.axonframework.messaging.queryhandling.QueryResponseMessage;
import org.axonframework.messaging.queryhandling.annotation.AnnotatedQueryHandlingComponent;

import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/**
 * A {@link Module} and {@link ModuleBuilder} implementation providing operation to construct a query handling
 * application module.
 * <p>
 * The {@code QueryHandlingModule} follows a builder paradigm, wherein several {@link QueryHandler QueryHandlers} can be
 * registered in either order.
 * <p>
 * To register query handlers, a similar registration phase switch should be made, by invoking
 * {@link SetupPhase#queryHandlers()}.
 * <p>
 * Here's an example of how to register two query handler lambdas:
 * <pre>
 * QueryHandlingModule.named("my-module")
 *                              .queryHandlers()
 *                              .queryHandler(new QualifiedName(FetchClasses.class),
 *                                            new QualifiedName(Classes.class),
 *                                            (query, context) -> { ...query handling logic... })
 *                              .queryHandler(new QualifiedName(FindStudentForName.class),
 *                                            new QualifiedName(Student.class),
 *                                            (query, context) -> { ...query handling logic... });
 * </pre>
 * <p>
 * Note that users do not have to invoke {@link #build()} themselves when using this interface, as the
 * {@link ApplicationConfigurer} takes care of that.
 *
 * @author Steven van Beelen
 * @since 5.0.0
 */
public interface QueryHandlingModule extends Module, ModuleBuilder<QueryHandlingModule> {

    /**
     * Starts a {@code QueryHandlingModule} module with the given {@code moduleName}.
     *
     * @param moduleName The name of the {@code QueryHandlingModule} under construction.
     * @return The setup phase of this module, for a fluent API.
     */
    static QueryHandlingModule.SetupPhase named(String moduleName) {
        return new SimpleQueryHandlingModule(moduleName);
    }

    /**
     * The setup phase of the query handling module.
     * <p>
     * The {@link #queryHandlers()} method allows users to start configuring all the {@link QueryHandler QueryHandlers}
     * for this module.
     */
    interface SetupPhase {

        /**
         * Initiates the query handler configuration phase for this module.
         *
         * @return The query handler phase of this module, for a fluent API.
         */
        QueryHandlingModule.QueryHandlerPhase queryHandlers();

        /**
         * Initiates the query handler configuration phase for this module, as well as performing the given
         * {@code configurationLambda} within this phase.
         *
         * @param configurationLambda A consumer of the query handler phase, performing query handler configuration
         *                            right away.
         * @return The query handler phase of this module, for a fluent API.
         */
        default QueryHandlingModule.QueryHandlerPhase queryHandlers(
                Consumer<QueryHandlingModule.QueryHandlerPhase> configurationLambda
        ) {
            QueryHandlingModule.QueryHandlerPhase queryHandlerPhase = queryHandlers();
            configurationLambda.accept(queryHandlerPhase);
            return queryHandlerPhase;
        }
    }

    /**
     * The query handler configuration phase of the query handling module.
     * <p>
     * Every registered {@link QueryHandler} will be subscribed with the {@link QueryBus} of the
     * {@link ApplicationConfigurer} this module is given to.
     * <p>
     * Provides roughly two options for configuring query handlers. Firstly, a query handler can be registered as is,
     * through the {@link #queryHandler(QualifiedName, QueryHandler)} method. Secondly, if the query handler provides
     * components from the {@link Configuration}, a {@link ComponentBuilder builder} of the query handler can be
     * registered through the {@link #queryHandler(QualifiedName, ComponentBuilder)} method.
     */
    interface QueryHandlerPhase extends ModuleBuilder<QueryHandlingModule> {

        /**
         * Registers the given {@code queryHandler} for the given qualified {@code queryName} and {@code responseName}
         * within this module.
         * <p>
         * Use this query handler registration method when the query handler in question does not require entities or
         * receives entities through another mechanism. Using a {@link MessageTypeResolver} to derive the
         * {@code queryName} is beneficial to ensure consistent naming across handler subscriptions.
         * <p>
         * Once this module is finalized, the query handler will be subscribed with the {@link QueryBus} of the
         * {@link ApplicationConfigurer} the module is registered on.
         *
         * @param queryName    The qualified name of the query the given {@code queryHandler} can handle.
         * @param queryHandler The query handler to register with this module.
         * @return The query handler phase of this builder, for a fluent API.
         */
        default QueryHandlingModule.QueryHandlerPhase queryHandler(QualifiedName queryName,
                                                                   QueryHandler queryHandler) {
            requireNonNull(queryHandler, "The query handler cannot be null.");
            return queryHandler(queryName, c -> queryHandler);
        }

        /**
         * Registers the given {@code queryHandlerBuilder} for the given qualified {@code queryName} within this
         * module.
         * <p>
         * Using a {@link MessageTypeResolver} to derive the {@code queryName} is beneficial to ensure consistent naming
         * across handler subscriptions.
         * <p>
         * Once this module is finalized, the query handler from the {@code queryHandlerBuilder} will be subscribed with
         * the {@link QueryBus} of the {@link ApplicationConfigurer} the module is registered on.
         *
         * @param queryName           The qualified name of the query the {@link QueryHandler} created by the given
         *                            {@code queryHandlerBuilder}.
         * @param queryHandlerBuilder A builder of a {@link QueryHandler}. Provides the {@link Configuration} to
         *                            retrieve components from to use during construction of the query handler.
         * @return The query handler phase of this builder, for a fluent API.
         */
        QueryHandlingModule.QueryHandlerPhase queryHandler(
                QualifiedName queryName,
                ComponentBuilder<QueryHandler> queryHandlerBuilder
        );

        /**
         * Registers the given {@code handlingComponentBuilder} within this module.
         * <p>
         * Use this query handler registration method when the query handling component in question does not require
         * entities or receives entities through another mechanism.
         * <p>
         * Once this module is finalized, the resulting {@link QueryHandlingComponent} from the
         * {@code handlingComponentBuilder} will be subscribed with the {@link QueryBus} of the
         * {@link ApplicationConfigurer} the module is registered on.
         *
         * @param handlingComponentBuilder A builder of a {@link QueryHandlingComponent}. Provides the
         *                                 {@link Configuration} to retrieve components from to use during construction
         *                                 of the query handling component.
         * @return The query handler phase of this builder, for a fluent API.
         */
        QueryHandlingModule.QueryHandlerPhase queryHandlingComponent(
                ComponentBuilder<QueryHandlingComponent> handlingComponentBuilder
        );

        /**
         * Registers an interceptor to be applied to the query handling component assembled by this module. Multiple
         * calls accumulate interceptors in registration order.
         *
         * @param interceptorBuilder builder for the interceptor to apply
         * @return the query handler phase of this builder, for a fluent API
         */
        QueryHandlerPhase intercepted(
                ComponentBuilder<MessageHandlerInterceptor<? super QueryMessage>> interceptorBuilder
        );

        /**
         * Registers the given {@code handlingComponentBuilder} as an {@link AnnotatedQueryHandlingComponent} within
         * this module.
         * <p>
         * This will scan the given {@code handlingComponentBuilder} for methods annotated with {@link QueryHandler} and
         * register them as query handlers for the {@link QueryBus} of the {@link ApplicationConfigurer}.
         *
         * @param handlingComponentBuilder A builder of a {@link QueryHandlingComponent}. Provides the
         *                                 {@link Configuration} to retrieve components from to use during construction
         *                                 of the query handling component.
         * @return The query handler phase of this builder, for a fluent API.
         */
        default QueryHandlingModule.QueryHandlerPhase autodetectedQueryHandlingComponent(
                ComponentBuilder<Object> handlingComponentBuilder
        ) {
            requireNonNull(handlingComponentBuilder, "The handling component builder cannot be null.");
            return queryHandlingComponent(c -> new AnnotatedQueryHandlingComponent<>(
                    handlingComponentBuilder.build(c),
                    c.getComponent(ParameterResolverFactory.class),
                    c.getOptionalComponent(HandlerDefinition.class)
                     .orElse(ClasspathHandlerDefinition.forClass(c.getClass())),
                    c.getComponent(MessageTypeResolver.class),
                    c.getComponent(MessageConverter.class)
            ));
        }

        /**
         * Wraps the query handling component with the exception handler built by the given {@code handlerBuilder}.
         * When a query handler throws, the exception handler is invoked. Return {@link MessageStream#empty()} to
         * suppress the error, {@link MessageStream#failed(Throwable)} to propagate it, or a stream of
         * {@link QueryResponseMessage} items to substitute results.
         * <p>
         * Accepts either a {@link QueryHandlingExceptionHandler} (which narrows the return type for substituting
         * {@link QueryResponseMessage} items) or a generic {@link MessageHandlingExceptionHandler} typed at any
         * supertype of {@link QueryMessage}, allowing a single handler implementation (such as a logger) to be reused
         * across command, event, and query phases.
         * <p>
         * Multiple calls accumulate handlers; later-registered handlers are applied closer to the handler and see
         * exceptions first.
         *
         * @param handlerBuilder builder for the exception handler to apply to the query handling component
         * @return this phase for further configuration
         */
        default QueryHandlerPhase withExceptionHandler(
                ComponentBuilder<? extends MessageHandlingExceptionHandler<? super QueryMessage>> handlerBuilder) {
            requireNonNull(handlerBuilder, "The exception handler builder must not be null.");
            return intercepted(c -> {
                MessageHandlingExceptionHandler<? super QueryMessage> handler = handlerBuilder.build(c);
                return (message, context, chain) ->
                        chain.proceed(message, context)
                             .cast()
                             .onErrorContinue(error -> {
                                 try {
                                     return handler.handle(message, context, error);
                                 } catch (Exception e) {
                                     return MessageStream.failed(e);
                                 }
                             });
            });
        }
    }
}
