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

import org.axonframework.common.FutureUtils;
import org.axonframework.common.configuration.BaseModule;
import org.axonframework.common.configuration.ComponentBuilder;
import org.axonframework.common.configuration.ComponentDefinition;
import org.axonframework.common.lifecycle.Phase;
import org.axonframework.messaging.core.MessageHandlerInterceptor;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.queryhandling.QueryBus;
import org.axonframework.messaging.queryhandling.QueryHandler;
import org.axonframework.messaging.queryhandling.QueryHandlingComponent;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.axonframework.messaging.queryhandling.SimpleQueryHandlingComponent;
import org.axonframework.messaging.queryhandling.interception.InterceptingQueryHandlingComponent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;
import static org.axonframework.common.configuration.ComponentDefinition.ofTypeAndName;

/**
 * Simple implementation of the {@link SimpleQueryHandlingModule}.
 *
 * @author Steven van Beelen
 * @since 5.0.0
 */
class SimpleQueryHandlingModule extends BaseModule<SimpleQueryHandlingModule>
        implements QueryHandlingModule,
        QueryHandlingModule.SetupPhase,
        QueryHandlingModule.QueryHandlerPhase {

    private final String queryHandlingComponentName;
    private final Map<QualifiedName, ComponentBuilder<QueryHandler>> handlerBuilders;
    private final List<ComponentBuilder<QueryHandlingComponent>> handlingComponentBuilders;
    private final List<ComponentBuilder<MessageHandlerInterceptor<? super QueryMessage>>> interceptorBuilders;

    SimpleQueryHandlingModule(String moduleName) {
        super(requireNonNull(moduleName, "The module name cannot be null."));
        this.queryHandlingComponentName = "QueryHandlingComponent[" + moduleName + "]";
        this.handlerBuilders = new HashMap<>();
        this.handlingComponentBuilders = new ArrayList<>();
        this.interceptorBuilders = new ArrayList<>();
    }

    @Override
    public QueryHandlerPhase queryHandlers() {
        return this;
    }

    @Override
    public QueryHandlerPhase queryHandler(QualifiedName queryName,
                                          ComponentBuilder<QueryHandler> queryHandlerBuilder) {
        handlerBuilders.put(requireNonNull(queryName, "queryName must not be null"),
                            requireNonNull(queryHandlerBuilder, "The query handler builder cannot be null."));
        return this;
    }

    @Override
    public QueryHandlerPhase queryHandlingComponent(
            ComponentBuilder<QueryHandlingComponent> handlingComponentBuilder
    ) {
        handlingComponentBuilders.add(
                requireNonNull(handlingComponentBuilder, "The query handling component builder cannot be null.")
        );
        return this;
    }

    @Override
    public QueryHandlerPhase intercepted(
            ComponentBuilder<MessageHandlerInterceptor<? super QueryMessage>> interceptorBuilder
    ) {
        interceptorBuilders.add(requireNonNull(interceptorBuilder, "interceptorBuilder must not be null"));
        return this;
    }

    @Override
    public QueryHandlingModule build() {
        registerQueryHandlingComponent();
        return this;
    }

    private void registerQueryHandlingComponent() {
        componentRegistry(cr -> cr.registerComponent(queryHandlingComponentComponentDefinition()));
    }

    private ComponentDefinition<QueryHandlingComponent> queryHandlingComponentComponentDefinition() {
        return ofTypeAndName(QueryHandlingComponent.class, queryHandlingComponentName)
                .withBuilder(c -> {
                    SimpleQueryHandlingComponent queryHandlingComponent = SimpleQueryHandlingComponent.create(
                            queryHandlingComponentName
                    );
                    handlingComponentBuilders.forEach(handlingComponent -> queryHandlingComponent.subscribe(
                            handlingComponent.build(c)));
                    handlerBuilders.forEach((key, value) -> queryHandlingComponent.subscribe(key, value.build(c)));
                    QueryHandlingComponent result = queryHandlingComponent;
                    if (!interceptorBuilders.isEmpty()) {
                        result = new InterceptingQueryHandlingComponent(
                                interceptorBuilders.stream().map(b -> b.build(c)).collect(Collectors.toList()),
                                result
                        );
                    }
                    return result;
                })
                .onStart(Phase.LOCAL_MESSAGE_HANDLER_REGISTRATIONS, (configuration, component) -> {
                    configuration.getComponent(QueryBus.class)
                                 .subscribe(configuration.getComponent(QueryHandlingComponent.class,
                                                                       queryHandlingComponentName));
                    return FutureUtils.emptyCompletedFuture();
                });
    }
}
