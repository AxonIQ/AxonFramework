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

package org.axonframework.springboot;

import org.axonframework.messaging.commandhandling.tracing.CommandBusSpanFactory;
import org.axonframework.messaging.commandhandling.tracing.DefaultCommandBusSpanFactory;
import org.axonframework.deadline.DeadlineManagerSpanFactory;
import org.axonframework.deadline.DefaultDeadlineManagerSpanFactory;
import org.axonframework.messaging.eventhandling.tracing.DefaultEventBusSpanFactory;
import org.axonframework.messaging.eventhandling.tracing.DefaultEventProcessorSpanFactory;
import org.axonframework.messaging.eventhandling.tracing.EventBusSpanFactory;
import org.axonframework.messaging.eventhandling.tracing.EventProcessorSpanFactory;
import org.axonframework.messaging.eventsourcing.snapshotting.DefaultSnapshotterSpanFactory;
import org.axonframework.messaging.eventsourcing.snapshotting.SnapshotterSpanFactory;
import org.axonframework.messaging.core.annotation.HandlerEnhancerDefinition;
import org.axonframework.modelling.command.DefaultRepositorySpanFactory;
import org.axonframework.modelling.command.RepositorySpanFactory;
import org.axonframework.modelling.saga.DefaultSagaManagerSpanFactory;
import org.axonframework.modelling.saga.SagaManagerSpanFactory;
import org.axonframework.messaging.queryhandling.tracing.DefaultQueryBusSpanFactory;
import org.axonframework.messaging.queryhandling.tracing.DefaultQueryUpdateEmitterSpanFactory;
import org.axonframework.messaging.queryhandling.tracing.QueryBusSpanFactory;
import org.axonframework.messaging.queryhandling.tracing.QueryUpdateEmitterSpanFactory;
import org.axonframework.extension.springboot.autoconfig.AxonTimeoutAutoConfiguration;
import org.axonframework.springboot.autoconfig.OpenTelemetryAutoConfiguration;
import org.axonframework.extension.springboot.autoconfig.SecurityAutoConfiguration;
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
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jmx.JmxAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableMBeanExport;
import org.springframework.jmx.support.RegistrationPolicy;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

@Disabled("TODO #3594")
class AxonAutoConfigurationWithTracingTest {

    @Test
    void spanFactoryDefaultsToNoop() {
        new ApplicationContextRunner()
                .withUserConfiguration(Context.class)
                .run(context -> {
                    assertNotNull(context);

                    assertTrue(context.containsBean("spanFactory"));
                    assertNotNull(context.getBean(SpanFactory.class));
                    assertEquals(NoOpSpanFactory.class, context.getBean(SpanFactory.class).getClass());
                });
    }


    @Test
    void handlerEnhancerDefinitionIsRegistered() {
        new ApplicationContextRunner()
                .withUserConfiguration(Context.class)
                .run(context -> {
                    assertNotNull(context);

                    assertTrue(context.containsBean("tracingHandlerEnhancerDefinition"));
                    assertNotNull(context.getBean(HandlerEnhancerDefinition.class));
                    assertEquals(TracingHandlerEnhancerDefinition.class,
                                 context.getBean(HandlerEnhancerDefinition.class).getClass());
                });
    }


    @Test
    void registersAllAttrributeProvidersByDefault() {
        new ApplicationContextRunner()
                .withUserConfiguration(Context.class)
                .run(context -> {
                    assertNotNull(context);

                    assertTrue(context.containsBean("aggregateIdentifierSpanAttributesProvider"));
                    assertNotNull(context.getBean(AggregateIdentifierSpanAttributesProvider.class));

                    assertTrue(context.containsBean("messageIdSpanAttributesProvider"));
                    assertNotNull(context.getBean(MessageIdSpanAttributesProvider.class));

                    assertTrue(context.containsBean("messageNameSpanAttributesProvider"));
                    assertNotNull(context.getBean(MessageNameSpanAttributesProvider.class));

                    assertTrue(context.containsBean("messageTypeSpanAttributesProvider"));
                    assertNotNull(context.getBean(MessageTypeSpanAttributesProvider.class));

                    assertTrue(context.containsBean("metadataSpanAttributesProvider"));
                    assertNotNull(context.getBean(MetadataSpanAttributesProvider.class));

                    assertTrue(context.containsBean("payloadTypeSpanAttributesProvider"));
                    assertNotNull(context.getBean(AggregateIdentifierSpanAttributesProvider.class));
                });
    }

    @Test
    void registersAllAttrributeProvidersByDefaultAsList() {
        new ApplicationContextRunner()
                .withUserConfiguration(Context.class)
                .run(context -> {
                    assertNotNull(context);

                    Map<String, SpanAttributesProvider> beansOfType = context.getBeansOfType(SpanAttributesProvider.class);

                    assertTrue(beansOfType.containsKey("aggregateIdentifierSpanAttributesProvider"));
                    assertTrue(beansOfType.containsKey("messageIdSpanAttributesProvider"));
                    assertTrue(beansOfType.containsKey("messageNameSpanAttributesProvider"));
                    assertTrue(beansOfType.containsKey("messageTypeSpanAttributesProvider"));
                    assertTrue(beansOfType.containsKey("metadataSpanAttributesProvider"));
                    assertTrue(beansOfType.containsKey("payloadTypeSpanAttributesProvider"));
                });
    }

    @Test
    void aggregateIdAttributeProviderCanBeDisabled() {
        new ApplicationContextRunner()
                .withPropertyValues(
                        "axon.tracing.attribute-providers.aggregate-identifier=false"
                )
                .withUserConfiguration(Context.class)
                .run(context -> {
                    assertNotNull(context);

                    assertFalse(context.containsBean("aggregateIdentifierSpanAttributesProvider"));
                    assertThrows(NoSuchBeanDefinitionException.class,
                                 () -> context.getBean(AggregateIdentifierSpanAttributesProvider.class));
                });
    }


    @Test
    void messageIdAttributeProviderCanBeDisabled() {
        new ApplicationContextRunner()
                .withPropertyValues(
                        "axon.tracing.attribute-providers.message-id=false"
                )
                .withUserConfiguration(Context.class)
                .run(context -> {
                    assertNotNull(context);

                    assertFalse(context.containsBean("messageIdSpanAttributesProvider"));
                    assertThrows(NoSuchBeanDefinitionException.class,
                                 () -> context.getBean(MessageIdSpanAttributesProvider.class));
                });
    }

    @Test
    void messageNameAttributeProviderCanBeDisabled() {
        new ApplicationContextRunner()
                .withPropertyValues(
                        "axon.tracing.attribute-providers.message-name=false"
                )
                .withUserConfiguration(Context.class)
                .run(context -> {
                    assertNotNull(context);

                    assertFalse(context.containsBean("messageNameSpanAttributesProvider"));
                    assertThrows(NoSuchBeanDefinitionException.class,
                                 () -> context.getBean(MessageNameSpanAttributesProvider.class));
                });
    }

    @Test
    void messageTypeAttributeProviderCanBeDisabled() {
        new ApplicationContextRunner()
                .withPropertyValues(
                        "axon.tracing.attribute-providers.message-type=false"
                )
                .withUserConfiguration(Context.class)
                .run(context -> {
                    assertNotNull(context);

                    assertFalse(context.containsBean("messageTypeSpanAttributesProvider"));
                    assertThrows(NoSuchBeanDefinitionException.class,
                                 () -> context.getBean(MessageTypeSpanAttributesProvider.class));
                });
    }

    @Test
    void metadataAttributeProviderCanBeDisabled() {
        new ApplicationContextRunner()
                .withPropertyValues(
                        "axon.tracing.attribute-providers.metadata=false"
                )
                .withUserConfiguration(Context.class)
                .run(context -> {
                    assertNotNull(context);

                    assertFalse(context.containsBean("metadataSpanAttributesProvider"));
                    assertThrows(NoSuchBeanDefinitionException.class,
                                 () -> context.getBean(MetadataSpanAttributesProvider.class));
                });
    }

    @Test
    void payloadTypeAttributeProviderCanBeDisabled() {
        new ApplicationContextRunner()
                .withPropertyValues(
                        "axon.tracing.attribute-providers.payload-type=false"
                )
                .withUserConfiguration(Context.class)
                .run(context -> {
                    assertNotNull(context);

                    assertFalse(context.containsBean("payloadTypeSpanAttributesProvider"));
                    assertThrows(NoSuchBeanDefinitionException.class,
                                 () -> context.getBean(PayloadTypeSpanAttributesProvider.class));
                });
    }

    @Test
    void setsSpanAttributeProviderOnTracer() {
        SpanFactory spanFactory = Mockito.mock(SpanFactory.class);
        new ApplicationContextRunner()
                .withUserConfiguration(Context.class)
                .withBean(SpanFactory.class, () -> spanFactory)
                .run(context -> {
                    assertNotNull(context);
                    assertSame(spanFactory, context.getBean(SpanFactory.class));

                    int numberOfProviders = context.getBeansOfType(SpanAttributesProvider.class).size();
                    Mockito.verify(spanFactory, Mockito.times(numberOfProviders)).registerSpanAttributeProvider(any());
                });
    }


    @Test
    void snapshotterSpanFactoryDefaultsToDefaultSnapshotterSpanFactory() {
        new ApplicationContextRunner()
                .withUserConfiguration(Context.class)
                .run(context -> {
                    assertNotNull(context);

                    assertTrue(context.containsBean("snapshotterSpanFactory"));
                    assertNotNull(context.getBean(SnapshotterSpanFactory.class));
                    assertEquals(DefaultSnapshotterSpanFactory.class,
                                 context.getBean(SnapshotterSpanFactory.class).getClass());
                });
    }

    @Test
    void snapshotterSpanFactoryCanBeOverriddenByUser() {
        SnapshotterSpanFactory mock = Mockito.mock(SnapshotterSpanFactory.class);
        new ApplicationContextRunner()
                .withUserConfiguration(Context.class)
                .withBean(SnapshotterSpanFactory.class, () -> mock)
                .run(context -> {
                    assertNotNull(context);

                    assertTrue(context.containsBean("snapshotterSpanFactory"));
                    assertNotNull(context.getBean(SnapshotterSpanFactory.class));
                    assertSame(context.getBean(SnapshotterSpanFactory.class), mock);
                });
    }

    @Test
    void eventBusSpanFactoryDefaultsToDefaultEventBusSpanFactory() {
        new ApplicationContextRunner()
                .withUserConfiguration(Context.class)
                .run(context -> {
                    assertNotNull(context);

                    assertTrue(context.containsBean("eventBusSpanFactory"));
                    assertNotNull(context.getBean(EventBusSpanFactory.class));
                    assertEquals(DefaultEventBusSpanFactory.class,
                                 context.getBean(EventBusSpanFactory.class).getClass());
                });
    }

    @Test
    void eventBusSpanFactoryCanBeOverriddenByUser() {
        EventBusSpanFactory mock = Mockito.mock(EventBusSpanFactory.class);
        new ApplicationContextRunner()
                .withUserConfiguration(Context.class)
                .withBean(EventBusSpanFactory.class, () -> mock)
                .run(context -> {
                    assertNotNull(context);

                    assertTrue(context.containsBean("eventBusSpanFactory"));
                    assertNotNull(context.getBean(EventBusSpanFactory.class));
                    assertSame(context.getBean(EventBusSpanFactory.class), mock);
                });
    }

    @Test
    void queryBusSpanFactoryDefaultsToDefaultQueryBusSpanFactory() {
        new ApplicationContextRunner()
                .withUserConfiguration(Context.class)
                .run(context -> {
                    assertNotNull(context);

                    assertTrue(context.containsBean("queryBusSpanFactory"));
                    assertNotNull(context.getBean(QueryBusSpanFactory.class));
                    assertEquals(DefaultQueryBusSpanFactory.class,
                                 context.getBean(QueryBusSpanFactory.class).getClass());
                });
    }

    @Test
    void queryBusSpanFactoryCanBeOverriddenByUser() {
        QueryBusSpanFactory mock = Mockito.mock(QueryBusSpanFactory.class);
        new ApplicationContextRunner()
                .withUserConfiguration(Context.class)
                .withBean(QueryBusSpanFactory.class, () -> mock)
                .run(context -> {
                    assertNotNull(context);

                    assertTrue(context.containsBean("queryBusSpanFactory"));
                    assertNotNull(context.getBean(QueryBusSpanFactory.class));
                    assertSame(context.getBean(QueryBusSpanFactory.class), mock);
                });
    }

    @Test
    void queryUpdateEmitterSpanFactoryDefaultsToDefaultQueryUpdateEmitterSpanFactory() {
        new ApplicationContextRunner()
                .withUserConfiguration(Context.class)
                .run(context -> {
                    assertNotNull(context);

                    assertTrue(context.containsBean("queryUpdateEmitterSpanFactory"));
                    assertNotNull(context.getBean(QueryUpdateEmitterSpanFactory.class));
                    assertEquals(DefaultQueryUpdateEmitterSpanFactory.class,
                                 context.getBean(QueryUpdateEmitterSpanFactory.class).getClass());
                });
    }

    @Test
    void queryUpdateEmitterSpanFactoryCanBeOverriddenByUser() {
        QueryUpdateEmitterSpanFactory mock = Mockito.mock(QueryUpdateEmitterSpanFactory.class);
        new ApplicationContextRunner()
                .withUserConfiguration(Context.class)
                .withBean(QueryUpdateEmitterSpanFactory.class, () -> mock)
                .run(context -> {
                    assertNotNull(context);

                    assertTrue(context.containsBean("queryUpdateEmitterSpanFactory"));
                    assertNotNull(context.getBean(QueryUpdateEmitterSpanFactory.class));
                    assertSame(context.getBean(QueryUpdateEmitterSpanFactory.class), mock);
                });
    }

    @Test
    void commandBusSpanFactoryDefaultsToDefaultCommandBusSpanFactory() {
        new ApplicationContextRunner()
                .withUserConfiguration(Context.class)
                .run(context -> {
                    assertNotNull(context);

                    assertTrue(context.containsBean("commandBusSpanFactory"));
                    assertNotNull(context.getBean(CommandBusSpanFactory.class));
                    assertEquals(DefaultCommandBusSpanFactory.class,
                                 context.getBean(CommandBusSpanFactory.class).getClass());
                });
    }

    @Test
    void commandBusSpanFactoryCanBeOverriddenByUser() {
        CommandBusSpanFactory mock = Mockito.mock(CommandBusSpanFactory.class);
        new ApplicationContextRunner()
                .withUserConfiguration(Context.class)
                .withBean(CommandBusSpanFactory.class, () -> mock)
                .run(context -> {
                    assertNotNull(context);

                    assertTrue(context.containsBean("commandBusSpanFactory"));
                    assertNotNull(context.getBean(CommandBusSpanFactory.class));
                    assertSame(context.getBean(CommandBusSpanFactory.class), mock);
                });
    }

    @Test
    void deadlineManagerSpanFactoryDefaultsToDefaultDeadlineManagerSpanFactory() {
        new ApplicationContextRunner()
                .withUserConfiguration(Context.class)
                .run(context -> {
                    assertNotNull(context);

                    assertTrue(context.containsBean("deadlineManagerSpanFactory"));
                    assertNotNull(context.getBean(DeadlineManagerSpanFactory.class));
                    assertEquals(DefaultDeadlineManagerSpanFactory.class,
                                 context.getBean(DeadlineManagerSpanFactory.class).getClass());
                });
    }

    @Test
    void deadlineManagerSpanFactoryCanBeOverriddenByUser() {
        DeadlineManagerSpanFactory mock = Mockito.mock(DeadlineManagerSpanFactory.class);
        new ApplicationContextRunner()
                .withUserConfiguration(Context.class)
                .withBean(DeadlineManagerSpanFactory.class, () -> mock)
                .run(context -> {
                    assertNotNull(context);

                    assertTrue(context.containsBean("deadlineManagerSpanFactory"));
                    assertNotNull(context.getBean(DeadlineManagerSpanFactory.class));
                    assertSame(context.getBean(DeadlineManagerSpanFactory.class), mock);
                });
    }

    @Test
    void sagaManagerSpanFactoryDefaultsToDefaultSagaManagerSpanFactory() {
        new ApplicationContextRunner()
                .withUserConfiguration(Context.class)
                .run(context -> {
                    assertNotNull(context);

                    assertTrue(context.containsBean("sagaManagerSpanFactory"));
                    assertNotNull(context.getBean(SagaManagerSpanFactory.class));
                    assertEquals(DefaultSagaManagerSpanFactory.class,
                                 context.getBean(SagaManagerSpanFactory.class).getClass());
                });
    }

    @Test
    void sagaManagerSpanFactoryCanBeOverriddenByUser() {
        SagaManagerSpanFactory mock = Mockito.mock(SagaManagerSpanFactory.class);
        new ApplicationContextRunner()
                .withUserConfiguration(Context.class)
                .withBean(SagaManagerSpanFactory.class, () -> mock)
                .run(context -> {
                    assertNotNull(context);

                    assertTrue(context.containsBean("sagaManagerSpanFactory"));
                    assertNotNull(context.getBean(SagaManagerSpanFactory.class));
                    assertSame(context.getBean(SagaManagerSpanFactory.class), mock);
                });
    }

    @Test
    void repositorySpanFactoryDefaultsToDefaultRepositorySpanFactory() {
        new ApplicationContextRunner()
                .withUserConfiguration(Context.class)
                .run(context -> {
                    assertNotNull(context);

                    assertTrue(context.containsBean("repositorySpanFactory"));
                    assertNotNull(context.getBean(RepositorySpanFactory.class));
                    assertEquals(DefaultRepositorySpanFactory.class,
                                 context.getBean(RepositorySpanFactory.class).getClass());
                });
    }

    @Test
    void repositorySpanFactoryCanBeOverriddenByUser() {
        RepositorySpanFactory mock = Mockito.mock(RepositorySpanFactory.class);
        new ApplicationContextRunner()
                .withUserConfiguration(Context.class)
                .withBean(RepositorySpanFactory.class, () -> mock)
                .run(context -> {
                    assertNotNull(context);

                    assertTrue(context.containsBean("repositorySpanFactory"));
                    assertNotNull(context.getBean(RepositorySpanFactory.class));
                    assertSame(context.getBean(RepositorySpanFactory.class), mock);
                });
    }

    @Test
    void eventProcessorSpanFactoryDefaultsToDefaultEventProcessorSpanFactory() {
        new ApplicationContextRunner()
                .withUserConfiguration(Context.class)
                .run(context -> {
                    assertNotNull(context);

                    assertTrue(context.containsBean("eventProcessorSpanFactory"));
                    assertNotNull(context.getBean(EventProcessorSpanFactory.class));
                    assertEquals(DefaultEventProcessorSpanFactory.class,
                                 context.getBean(EventProcessorSpanFactory.class).getClass());
                });
    }

    @Test
    void eventProcessorSpanFactoryCanBeOverriddenByUser() {
        EventProcessorSpanFactory mock = Mockito.mock(EventProcessorSpanFactory.class);
        new ApplicationContextRunner()
                .withUserConfiguration(Context.class)
                .withBean(EventProcessorSpanFactory.class, () -> mock)
                .run(context -> {
                    assertNotNull(context);

                    assertTrue(context.containsBean("eventProcessorSpanFactory"));
                    assertNotNull(context.getBean(EventProcessorSpanFactory.class));
                    assertSame(context.getBean(EventProcessorSpanFactory.class), mock);
                });
    }

    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            JmxAutoConfiguration.class,
            OpenTelemetryAutoConfiguration.class,
            WebClientAutoConfiguration.class,
            SecurityAutoConfiguration.class,
            AxonTimeoutAutoConfiguration.class,
    })
    @EnableMBeanExport(registration = RegistrationPolicy.IGNORE_EXISTING)
    @Configuration
    public static class Context {

    }
}
