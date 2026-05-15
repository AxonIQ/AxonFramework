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

package org.axonframework.springboot.autoconfig;

import org.axonframework.common.configuration.ConfigurationEnhancer;
import org.axonframework.deadline.DeadlineManagerSpanFactory;
import org.axonframework.deadline.DefaultDeadlineManagerSpanFactory;
import org.axonframework.messaging.commandhandling.tracing.CommandBusSpanFactory;
import org.axonframework.messaging.commandhandling.tracing.DefaultCommandBusSpanFactory;
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.messaging.eventhandling.tracing.DefaultEventBusSpanFactory;
import org.axonframework.messaging.eventhandling.tracing.DefaultEventProcessorSpanFactory;
import org.axonframework.messaging.eventhandling.tracing.EventBusSpanFactory;
import org.axonframework.messaging.eventhandling.tracing.EventProcessorSpanFactory;
import org.axonframework.messaging.eventsourcing.snapshotting.DefaultSnapshotterSpanFactory;
import org.axonframework.messaging.eventsourcing.snapshotting.SnapshotterSpanFactory;
import org.axonframework.messaging.queryhandling.tracing.DefaultQueryBusSpanFactory;
import org.axonframework.messaging.queryhandling.tracing.DefaultQueryUpdateEmitterSpanFactory;
import org.axonframework.messaging.queryhandling.tracing.QueryBusSpanFactory;
import org.axonframework.messaging.queryhandling.tracing.QueryUpdateEmitterSpanFactory;
import org.axonframework.messaging.tracing.NoOpSpanFactory;
import org.axonframework.messaging.tracing.SpanAttributesProvider;
import org.axonframework.messaging.tracing.SpanFactory;
import org.axonframework.messaging.tracing.TracingHandlerEnhancerDefinition;
import org.axonframework.messaging.tracing.attributes.AggregateIdentifierSpanAttributesProvider;
import org.axonframework.messaging.tracing.attributes.MessageIdSpanAttributesProvider;
import org.axonframework.messaging.tracing.attributes.MessageNameSpanAttributesProvider;
import org.axonframework.messaging.tracing.attributes.MessageTypeSpanAttributesProvider;
import org.axonframework.messaging.tracing.attributes.MetadataSpanAttributesProvider;
import org.axonframework.messaging.tracing.attributes.PayloadTypeSpanAttributesProvider;
import org.axonframework.modelling.command.DefaultRepositorySpanFactory;
import org.axonframework.modelling.command.RepositorySpanFactory;
import org.axonframework.modelling.saga.DefaultSagaManagerSpanFactory;
import org.axonframework.modelling.saga.SagaManagerSpanFactory;
import org.axonframework.springboot.TracingProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Configures common tracing components for Axon Framework. Defaults to the {@link NoOpSpanFactory} if no other
 * {@link SpanFactory} bean is configured.
 * <p>
 * You can define additional {@link SpanAttributesProvider}s by defining your own implementations as a bean or a
 * {@link org.springframework.stereotype.Component}. These will be picked up automatically.
 *
 * @author Mitchell Herrijgers
 * @see OpenTelemetryAutoConfiguration
 * @since 4.6.0
 */
@AutoConfiguration
@EnableConfigurationProperties(TracingProperties.class)
public class AxonTracingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SpanFactory.class)
    public SpanFactory spanFactory() {
        return NoOpSpanFactory.INSTANCE;
    }

    @Bean
    @ConditionalOnMissingBean(SnapshotterSpanFactory.class)
    public SnapshotterSpanFactory snapshotterSpanFactory(SpanFactory spanFactory, TracingProperties properties) {
        TracingProperties.SnapshotterProperties snapshotterProps = properties.getSnapshotter();
        return DefaultSnapshotterSpanFactory.builder()
                                            .spanFactory(spanFactory)
                                            .aggregateTypeInSpanName(snapshotterProps.isAggregateTypeInSpanName())
                                            .separateTrace(snapshotterProps.isSeparateTrace())
                                            .build();
    }

    @Bean
    @ConditionalOnMissingBean(CommandBusSpanFactory.class)
    public CommandBusSpanFactory commandBusSpanFactory(SpanFactory spanFactory, TracingProperties properties) {
        TracingProperties.CommandBusProperties commandBusProps = properties.getCommandBus();
        return DefaultCommandBusSpanFactory.builder()
                                           .spanFactory(spanFactory)
                                           .distributedInSameTrace(commandBusProps.isDistributedInSameTrace())
                                           .build();
    }

    @Bean
    @ConditionalOnMissingBean(QueryBusSpanFactory.class)
    public QueryBusSpanFactory queryBusSpanFactory(SpanFactory spanFactory, TracingProperties properties) {
        TracingProperties.QueryBusProperties queryBusProps = properties.getQueryBus();
        return DefaultQueryBusSpanFactory.builder()
                                         .spanFactory(spanFactory)
                                         .distributedInSameTrace(queryBusProps.isDistributedInSameTrace())
                                         .build();
    }

    @Bean
    @ConditionalOnMissingBean(QueryUpdateEmitterSpanFactory.class)
    public QueryUpdateEmitterSpanFactory queryUpdateEmitterSpanFactory(SpanFactory spanFactory) {
        return DefaultQueryUpdateEmitterSpanFactory.builder()
                                                   .spanFactory(spanFactory)
                                                   .build();
    }

    @Bean
    @ConditionalOnMissingBean(EventBusSpanFactory.class)
    public EventBusSpanFactory eventBusSpanFactory(SpanFactory spanFactory) {
        return DefaultEventBusSpanFactory.builder()
                                         .spanFactory(spanFactory)
                                         .build();
    }

    @Bean
    @ConditionalOnMissingBean(DeadlineManagerSpanFactory.class)
    public DeadlineManagerSpanFactory deadlineManagerSpanFactory(SpanFactory spanFactory,
                                                                 TracingProperties properties) {
        TracingProperties.DeadlineManagerProperties deadlineManagerProps = properties.getDeadlineManager();
        return DefaultDeadlineManagerSpanFactory.builder()
                                                .spanFactory(spanFactory)
                                                .scopeAttribute(deadlineManagerProps.getDeadlineScopeAttributeName())
                                                .deadlineIdAttribute(deadlineManagerProps.getDeadlineIdAttributeName())
                                                .build();
    }

    @Bean
    @ConditionalOnMissingBean(SagaManagerSpanFactory.class)
    public SagaManagerSpanFactory sagaManagerSpanFactory(SpanFactory spanFactory,
                                                         TracingProperties properties) {
        TracingProperties.SagaManagerProperties sagaManagerProps = properties.getSagaManager();
        return DefaultSagaManagerSpanFactory.builder()
                                            .spanFactory(spanFactory)
                                            .sagaIdentifierAttribute(sagaManagerProps.getSagaIdentifierAttributeName())
                                            .build();
    }

    @Bean
    @ConditionalOnMissingBean(RepositorySpanFactory.class)
    public RepositorySpanFactory repositorySpanFactory(SpanFactory spanFactory,
                                                       TracingProperties properties) {
        TracingProperties.DeadlineManagerProperties repositoryProps = properties.getDeadlineManager();
        return DefaultRepositorySpanFactory.builder()
                                           .spanFactory(spanFactory)
                                           .aggregateIdAttribute(repositoryProps.getDeadlineScopeAttributeName())
                                           .build();
    }

    @Bean
    @ConditionalOnMissingBean(EventProcessorSpanFactory.class)
    public EventProcessorSpanFactory eventProcessorSpanFactory(SpanFactory spanFactory,
                                                               TracingProperties properties) {
        TracingProperties.EventProcessorProperties repositoryProps = properties.getEventProcessor();
        return DefaultEventProcessorSpanFactory.builder()
                                               .spanFactory(spanFactory)
                                               .disableBatchTrace(repositoryProps.isDisableBatchTrace())
                                               .distributedInSameTrace(repositoryProps.isDistributedInSameTrace())
                                               .distributedInSameTraceTimeLimit(repositoryProps.getDistributedInSameTraceTimeLimit())
                                               .build();
    }

    @Bean
    public HandlerEnhancerDefinition tracingHandlerEnhancerDefinition(SpanFactory spanFactory,
                                                                      TracingProperties properties) {
        return TracingHandlerEnhancerDefinition.builder()
                                               .spanFactory(spanFactory)
                                               .showEventSourcingHandlers(properties.isShowEventSourcingHandlers())
                                               .build();
    }

    @Bean
    public ConfigurationEnhancer configurerModuleForTracing(List<SpanAttributesProvider> spanAttributesProviders) {
        return componentRegistry -> {
//            configurer.onInitialize(config -> {
//                SpanFactory spanFactory = config.spanFactory();
//                spanAttributesProviders.forEach(spanFactory::registerSpanAttributeProvider);
//            });
        };
    }

    @Bean
    @ConditionalOnProperty(value = "axon.tracing.attribute-providers.aggregate-identifier", havingValue = "true", matchIfMissing = true)
    public SpanAttributesProvider aggregateIdentifierSpanAttributesProvider() {
        return new AggregateIdentifierSpanAttributesProvider();
    }

    @Bean
    @ConditionalOnProperty(value = "axon.tracing.attribute-providers.message-id", havingValue = "true", matchIfMissing = true)
    public SpanAttributesProvider messageIdSpanAttributesProvider() {
        return new MessageIdSpanAttributesProvider();
    }

    @Bean
    @ConditionalOnProperty(value = "axon.tracing.attribute-providers.message-name", havingValue = "true", matchIfMissing = true)
    public SpanAttributesProvider messageNameSpanAttributesProvider() {
        return new MessageNameSpanAttributesProvider();
    }

    @Bean
    @ConditionalOnProperty(value = "axon.tracing.attribute-providers.message-type", havingValue = "true", matchIfMissing = true)
    public SpanAttributesProvider messageTypeSpanAttributesProvider() {
        return new MessageTypeSpanAttributesProvider();
    }

    @Bean
    @ConditionalOnProperty(value = "axon.tracing.attribute-providers.metadata", havingValue = "true", matchIfMissing = true)
    public SpanAttributesProvider metadataSpanAttributesProvider() {
        return new MetadataSpanAttributesProvider();
    }

    @Bean
    @ConditionalOnProperty(value = "axon.tracing.attribute-providers.payload-type", havingValue = "true", matchIfMissing = true)
    public SpanAttributesProvider payloadTypeSpanAttributesProvider() {
        return new PayloadTypeSpanAttributesProvider();
    }
}
