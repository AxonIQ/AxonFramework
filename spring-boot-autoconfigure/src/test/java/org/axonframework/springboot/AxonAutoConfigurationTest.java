/*
 * Copyright (c) 2010-2022. Axon Framework
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

import org.axonframework.commandhandling.CommandBus;
import org.axonframework.commandhandling.CommandHandler;
import org.axonframework.commandhandling.SimpleCommandBus;
import org.axonframework.commandhandling.gateway.CommandGateway;
import org.axonframework.commandhandling.gateway.DefaultCommandGateway;
import org.axonframework.common.AxonConfigurationException;
import org.axonframework.config.Configurer;
import org.axonframework.config.EventProcessingConfiguration;
import org.axonframework.config.EventProcessingConfigurer;
import org.axonframework.eventhandling.EventBus;
import org.axonframework.eventhandling.EventHandler;
import org.axonframework.eventhandling.gateway.EventGateway;
import org.axonframework.eventhandling.tokenstore.TokenStore;
import org.axonframework.eventsourcing.AggregateFactory;
import org.axonframework.eventsourcing.EventCountSnapshotTriggerDefinition;
import org.axonframework.eventsourcing.SnapshotTriggerDefinition;
import org.axonframework.eventsourcing.Snapshotter;
import org.axonframework.eventsourcing.eventstore.EmbeddedEventStore;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.lifecycle.Lifecycle;
import org.axonframework.messaging.annotation.FixedValueParameterResolver;
import org.axonframework.messaging.annotation.MultiParameterResolverFactory;
import org.axonframework.messaging.annotation.ParameterResolver;
import org.axonframework.messaging.annotation.ParameterResolverFactory;
import org.axonframework.messaging.annotation.SimpleResourceParameterResolverFactory;
import org.axonframework.messaging.correlation.CorrelationDataProvider;
import org.axonframework.messaging.correlation.SimpleCorrelationDataProvider;
import org.axonframework.modelling.command.Repository;
import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.queryhandling.QueryBus;
import org.axonframework.serialization.Serializer;
import org.axonframework.serialization.xml.XStreamSerializer;
import org.axonframework.spring.stereotype.Aggregate;
import org.axonframework.spring.stereotype.Saga;
import org.axonframework.tracing.SpanFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableMBeanExport;
import org.springframework.jmx.support.RegistrationPolicy;
import org.springframework.stereotype.Component;

import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Collections.singleton;
import static org.axonframework.eventhandling.GenericEventMessage.asEventMessage;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AxonAutoConfigurationTest {

    @Test
    void contextInitialization() {
        new ApplicationContextRunner()
                .withUserConfiguration(AxonAutoConfigurationTest.Context.class)
                .withPropertyValues("axon.axonserver.enabled=false")
                .run(applicationContext -> {
                    assertNotNull(applicationContext);
                    assertNotNull(applicationContext.getBean(Configurer.class));
                    assertNotNull(applicationContext.getBean(Snapshotter.class));

                    assertNotNull(applicationContext.getBean(CommandBus.class));
                    assertNotNull(applicationContext.getBean(EventBus.class));
                    assertNotNull(applicationContext.getBean(QueryBus.class));
                    assertNotNull(applicationContext.getBean(EventStore.class));
                    assertNotNull(applicationContext.getBean(CommandGateway.class));
                    assertNotNull(applicationContext.getBean(EventGateway.class));
                    assertNotNull(applicationContext.getBean(Serializer.class));
                    assertNotNull(applicationContext.getBean(SpanFactory.class));
                    assertEquals(MultiParameterResolverFactory.class,
                                 applicationContext.getBean(ParameterResolverFactory.class).getClass());
                    assertEquals(1, applicationContext.getBeansOfType(EventStorageEngine.class).size());
                    assertEquals(0, applicationContext.getBeansOfType(TokenStore.class).size());
                    assertNotNull(applicationContext.getBean(Context.MySaga.class));
                    assertNotNull(applicationContext.getBean(Context.MyAggregate.class));
                    assertNotNull(applicationContext.getBean(Repository.class));
                    assertNotNull(applicationContext.getBean(AggregateFactory.class));
                    assertNotNull(applicationContext.getBean(EventProcessingConfiguration.class));

                    assertEquals(2,
                                 applicationContext.getBean(org.axonframework.config.Configuration.class)
                                                   .correlationDataProviders().size());

                    Context.SomeComponent someComponent = applicationContext.getBean(Context.SomeComponent.class);
                    assertEquals(0, someComponent.invocations.size());
                    applicationContext.getBean(EventBus.class).publish(asEventMessage("testing"));
                    assertEquals(1, someComponent.invocations.size());

                    assertTrue(applicationContext.getBean(Serializer.class) instanceof XStreamSerializer);
                    assertSame(applicationContext.getBean("eventSerializer"),
                               applicationContext.getBean("messageSerializer"));
                    assertSame(applicationContext.getBean("serializer"),
                               applicationContext.getBean("messageSerializer"));
                });
    }

    @Test
    void ambiguousComponentsThrowExceptionWhenRequestedFromConfiguration() {
        ApplicationContextRunner applicationContextRunner = new ApplicationContextRunner()
                .withUserConfiguration(Context.class)
                .withBean("gatewayOne", CommandGateway.class, () -> DefaultCommandGateway.builder().commandBus(SimpleCommandBus.builder().build()).build())
                .withBean("gatewayTwo", CommandGateway.class, () -> DefaultCommandGateway.builder().commandBus(SimpleCommandBus.builder().build()).build())
                .withPropertyValues("axon.axonserver.enabled=false");

        AxonConfigurationException actual = assertThrows(AxonConfigurationException.class, () -> {
            applicationContextRunner
                    .run(ctx -> ctx.getBean(org.axonframework.config.Configuration.class).commandGateway());
        });
        assertTrue(actual.getMessage().contains("CommandGateway"));
        assertTrue(actual.getMessage().contains("gatewayOne"));
        assertTrue(actual.getMessage().contains("gatewayTwo"));

    }

    @Test
    void beansImplementingLifecycleHaveTheirHandlersRegistered() {
        ApplicationContextRunner applicationContextRunner = new ApplicationContextRunner()
                .withUserConfiguration(Context.class)
                .withBean("lifecycletest", CustomLifecycleBean.class, CustomLifecycleBean::new)
                .withPropertyValues("axon.axonserver.enabled=false");

        applicationContextRunner.run(context -> {
            assertTrue(context.getBean("lifecycletest", CustomLifecycleBean.class).isInvoked());
        });
    }

    @Test
    void shutDownCalledOnEmbeddedEventStoreEngine() {
        ApplicationContextRunner applicationContextRunner = new ApplicationContextRunner()
                .withUserConfiguration(Context.class)
                .withPropertyValues("axon.axonserver.enabled=false");

        AtomicReference<EmbeddedEventStore> eventStore = new AtomicReference<>();

        applicationContextRunner.run(context -> {
            eventStore.set(context.getBean(EmbeddedEventStore.class));
            assertNotNull(eventStore.get());
        });
        verify(eventStore.get(), atLeastOnce()).shutDown();
    }

    @Test
    void ambiguousPrimaryComponentsThrowExceptionWhenRequestedFromConfiguration() {
        ApplicationContextRunner applicationContextRunner = new ApplicationContextRunner()
                .withUserConfiguration(Context.class)
                .withBean("gatewayOne",
                          CommandGateway.class,
                          () -> DefaultCommandGateway.builder().commandBus(SimpleCommandBus.builder().build()).build(),
                          beanDefinition -> beanDefinition.setPrimary(true))
                .withBean("gatewayTwo",
                          CommandGateway.class,
                          () -> DefaultCommandGateway.builder().commandBus(SimpleCommandBus.builder().build()).build(),
                          beanDefinition -> beanDefinition.setPrimary(true))
                .withPropertyValues("axon.axonserver.enabled=false");

        AxonConfigurationException actual = assertThrows(AxonConfigurationException.class, () -> {
            applicationContextRunner
                    .run(ctx -> ctx.getBean(org.axonframework.config.Configuration.class).commandGateway());
        });
        assertTrue(actual.getMessage().contains("CommandGateway"));
        assertTrue(actual.getMessage().contains("gatewayOne"));
        assertTrue(actual.getMessage().contains("gatewayTwo"));
    }

    @Test
    void ambiguousBeanDependenciesThrowException() {
        new ApplicationContextRunner()
                .withUserConfiguration(Context.class)
                .withBean("gatewayOne", CommandBus.class, () -> SimpleCommandBus.builder().build())
                .withBean("gatewayTwo", CommandBus.class, () -> SimpleCommandBus.builder().build())
                .withPropertyValues("axon.axonserver.enabled=false")
                .run(ctx -> {
                    Throwable startupFailure = ctx.getStartupFailure();
                    assertTrue(startupFailure instanceof UnsatisfiedDependencyException);
                    assertTrue(startupFailure.getMessage().contains("CommandBus"), "Expected mention of 'CommandBus' in: " + startupFailure.getMessage());
                    assertTrue(startupFailure.getMessage().contains("gatewayOne"), "Expected mention of 'gatewayOne' in: " + startupFailure.getMessage());
                    assertTrue(startupFailure.getMessage().contains("gatewayTwo"), "Expected mention of 'gatewayTwo' in: " + startupFailure.getMessage());
                });
    }

    @Test
    void embeddedEventStoreDefaultsToOptimizeEventConsumptionEnabled() throws Exception {
        new ApplicationContextRunner()
                .withUserConfiguration(MinimalContext.class)
                .withPropertyValues("axon.axonserver.enabled=false")
                .run(context -> {
                    EmbeddedEventStore eventStore = context.getBean(EmbeddedEventStore.class);
                    java.lang.reflect.Field field = EmbeddedEventStore.class.getDeclaredField("optimizeEventConsumption");
                    field.setAccessible(true);
                    assertTrue((boolean) field.get(eventStore));
                });
    }

    @Test
    void embeddedEventStoreOptimizeEventConsumptionCanBeDisabledViaProperties() throws Exception {
        new ApplicationContextRunner()
                .withUserConfiguration(MinimalContext.class)
                .withPropertyValues("axon.axonserver.enabled=false",
                                    "axon.eventstore.optimize-event-consumption=false")
                .run(context -> {
                    EmbeddedEventStore eventStore = context.getBean(EmbeddedEventStore.class);
                    java.lang.reflect.Field field = EmbeddedEventStore.class.getDeclaredField("optimizeEventConsumption");
                    field.setAccessible(true);
                    assertFalse((boolean) field.get(eventStore));
                });
    }

    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class})
    @EnableMBeanExport(registration = RegistrationPolicy.IGNORE_EXISTING)
    @Configuration
    public static class Context {

        @Autowired
        public void configure(EventProcessingConfigurer eventProcessingConfigurer) {
            eventProcessingConfigurer.usingSubscribingEventProcessors();
        }

        @Bean
        public SnapshotTriggerDefinition snapshotTriggerDefinition(Snapshotter snapshotter) {
            return new EventCountSnapshotTriggerDefinition(snapshotter, 2);
        }

        @Bean
        public ParameterResolverFactory customerParameterResolverFactory() {
            return new SimpleResourceParameterResolverFactory(singleton(new CustomResource()));
        }

        @Bean
        public EventStore eventStore() {
            return spy(EmbeddedEventStore.builder().storageEngine(storageEngine()).build());
        }

        @Bean
        public EventStorageEngine storageEngine() {
            return new InMemoryEventStorageEngine();
        }

        @Bean
        public CorrelationDataProvider correlationData1() {
            return new SimpleCorrelationDataProvider("key1");
        }

        @Bean
        public CorrelationDataProvider correlationData2() {
            return new SimpleCorrelationDataProvider("key2");
        }

        @Aggregate(snapshotTriggerDefinition = "snapshotTriggerDefinition")
        public static class MyAggregate {

            @CommandHandler
            public void handle(String type, SomeComponent test, CustomResource resource) {

            }

            @EventHandler
            public void on(String type, SomeComponent test) {

            }
        }

        @Saga
        public static class MySaga {

            @SagaEventHandler(associationProperty = "toString")
            public void handle(String type, SomeComponent test) {

            }
        }

        @Saga
        public static class MyDefaultConfigSaga {

            @SagaEventHandler(associationProperty = "toString")
            public void handle(String type, SomeComponent test) {

            }
        }

        @Component
        public static class CustomParameterResolverFactory implements ParameterResolverFactory {

            private final EventBus eventBus;

            public CustomParameterResolverFactory(EventBus eventBus) {
                this.eventBus = eventBus;
            }

            @Override
            public ParameterResolver createInstance(Executable executable, Parameter[] parameters, int parameterIndex) {
                if (Integer.class.isAssignableFrom(parameters[parameterIndex].getType())) {
                    return new FixedValueParameterResolver<>(1);
                }
                return null;
            }
        }

        @Component
        public static class SomeComponent {

            private final List<String> invocations = new ArrayList<>();

            public SomeComponent(org.axonframework.config.Configuration axonConfig) {
                // just to see if wiring the configuration causes circular dependencies.
            }

            @EventHandler
            public void handle(String event, SomeOtherComponent test, Integer testing) {
                invocations.add(event);
            }
        }

        @Component
        public static class SomeOtherComponent {

        }
    }

    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class})
    @EnableMBeanExport(registration = RegistrationPolicy.IGNORE_EXISTING)
    @Configuration
    public static class MinimalContext {

        @Bean
        public EventStorageEngine storageEngine() {
            return new InMemoryEventStorageEngine();
        }
    }

    public static class CustomResource {

    }

    private class CustomLifecycleBean implements Lifecycle {
        private boolean invoked;

        @Override
        public void registerLifecycleHandlers(LifecycleRegistry lifecycle) {
            this.invoked = true;
        }

        public boolean isInvoked() {
            return invoked;
        }
    }
}
