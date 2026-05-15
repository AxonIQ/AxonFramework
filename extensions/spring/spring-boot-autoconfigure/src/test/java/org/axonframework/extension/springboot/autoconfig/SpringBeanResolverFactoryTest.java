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

package org.axonframework.extension.springboot.autoconfig;

import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.axonframework.extension.spring.config.annotation.SpringBeanDependencyResolverFactory;
import org.axonframework.extension.spring.config.annotation.SpringBeanParameterResolverFactory;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.SimpleCommandBus;
import org.axonframework.messaging.core.EmptyApplicationContext;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageStream.Entry;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.SimpleUnitOfWorkFactory;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.annotation.AnnotatedEventHandlingComponent;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.test.FixtureExecutionException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.test.annotation.DirtiesContext;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the functionality of Spring dependency resolution at the application context level. This covers both
 * {@link SpringBeanDependencyResolverFactory} and {@link SpringBeanParameterResolverFactory}, which behave differently
 * but exist within the same application context.
 *
 * @author Allard Buijze
 * @author Joel Feinstein
 * @see SpringBeanDependencyResolverFactory
 * @see SpringBeanParameterResolverFactory
 */
class SpringBeanResolverFactoryTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final String EVENT_QUALIFIER = "test-event";
    private static final EventMessage EVENT_MESSAGE = asEventMessage("Hi there");

    private ProcessingContext processingContext;

    private ApplicationContextRunner testApplicationContext;

    @BeforeEach
    void setUp() {
        COUNTER.set(0);
        processingContext = new StubProcessingContext().withMessage(EVENT_MESSAGE);

        testApplicationContext = new ApplicationContextRunner().withUserConfiguration(TestContext.class);
    }

    @Test
    void methodsAreProperlyInjected() {
        testApplicationContext.run(context -> {
            assertThat(context).hasSingleBean(AnnotatedEventHandlerWithResources.class);
            AnnotatedEventHandlerWithResources handler =
                    context.getBean(AnnotatedEventHandlerWithResources.class);

            AnnotatedEventHandlingComponent<Object> ehc = constructHandlingComponent(handler, context);

            ehc.handle(EVENT_MESSAGE, processingContext)
               .asCompletableFuture().join();

            assertThat(COUNTER).hasValue(1);
        });
    }

    @Test
    void newInstanceIsCreatedEachTimePrototypeResourceIsInjected() {
        testApplicationContext.run(context -> {
            assertThat(context).hasBean("prototypeResourceHandler");
            Object handler = context.getBean("prototypeResourceHandler");

            AnnotatedEventHandlingComponent<Object> ehc = constructHandlingComponent(handler, context);

            ehc.handle(EVENT_MESSAGE, processingContext)
               .asCompletableFuture().join();
            ehc.handle(asEventMessage("Hello2"), processingContext)
               .asCompletableFuture().join();

            assertThat(COUNTER).hasValue(2);
        });
    }

    @Test
    void methodsAreProperlyInjected_ErrorOnMissingParameterType() {
        testApplicationContext.run(context -> {
            assertThat(context).hasBean("missingResourceHandler");
            Object handler = context.getBean("missingResourceHandler");

            AnnotatedEventHandlingComponent<Object> ehc = constructHandlingComponent(handler, context);

            // Generates a FixtureExecutionException due to the inclusion of axon-test,
            //  which includes the FixtureResourceParameterResolverFactory
            CompletableFuture<Message> result = ehc.handle(EVENT_MESSAGE, processingContext)
                                                   .first()
                                                   .asCompletableFuture()
                                                   .thenApply(Entry::message);

            assertThat(result).isCompletedExceptionally();
            assertThatThrownBy(() -> {
                try {
                    result.get();
                } catch (ExecutionException e) {
                    throw e.getCause();
                }
            }).isInstanceOf(FixtureExecutionException.class);
        });
    }

    @Test
    void methodsAreProperlyInjected_NullableParameterType() {
        testApplicationContext.run(context -> {
            assertThat(context).hasBean("nullableResourceHandler");
            Object handler = context.getBean("nullableResourceHandler");

            AnnotatedEventHandlingComponent<Object> ehc = constructHandlingComponent(handler, context);

            ehc.handle(EVENT_MESSAGE, processingContext);

            assertThat(COUNTER).hasValue(1);
        });
    }

    @Test
    void methodsAreProperlyInjected_ErrorOnDuplicateParameterType() {
        testApplicationContext.run(context -> {
            assertThat(context).hasBean("duplicateResourceHandler");
            Object handler = context.getBean("duplicateResourceHandler");

            AnnotatedEventHandlingComponent<Object> ehc = constructHandlingComponent(handler, context);

            // Generates a FixtureExecutionException due to the inclusion of axon-test,
            //  which includes the FixtureResourceParameterResolverFactory
            CompletableFuture<Message> result = ehc.handle(EVENT_MESSAGE, processingContext)
                                                   .first()
                                                   .asCompletableFuture()
                                                   .thenApply(Entry::message);

            assertThat(result).isCompletedExceptionally();
            assertThatThrownBy(() -> {
                try {
                    result.get();
                } catch (ExecutionException e) {
                    throw e.getCause();
                }
            }).isInstanceOf(FixtureExecutionException.class);
        });
    }

    @Test
    @DirtiesContext
    void methodsAreProperlyInjected_DuplicateParameterTypeWithPrimary() {
        testApplicationContext.run(context -> {
            assertThat(context).hasBean("duplicateResourceHandlerWithPrimary");
            Object handler = context.getBean("duplicateResourceHandlerWithPrimary");

            AnnotatedEventHandlingComponent<Object> ehc = constructHandlingComponent(handler, context);

            ehc.handle(EVENT_MESSAGE, processingContext);

            assertThat(COUNTER).hasValue(1);
        });
    }

    @Test
    @DirtiesContext
    void methodsAreProperlyInjected_DuplicateParameterTypeWithQualifier() {
        testApplicationContext.run(context -> {
            assertThat(context).hasBean("duplicateResourceHandlerWithQualifier");
            Object handler = context.getBean("duplicateResourceHandlerWithQualifier");

            AnnotatedEventHandlingComponent<Object> ehc = constructHandlingComponent(handler, context);

            ehc.handle(EVENT_MESSAGE, processingContext);

            assertThat(COUNTER).hasValue(1);
        });
    }

    @Test
    @DirtiesContext
    void methodsAreProperlyInjected_QualifierPrecedesPrimary() {
        testApplicationContext.run(context -> {
            assertThat(context).hasBean("duplicateResourceHandlerWithQualifierAndPrimary");
            Object handler = context.getBean("duplicateResourceHandlerWithQualifierAndPrimary");

            AnnotatedEventHandlingComponent<Object> ehc = constructHandlingComponent(handler, context);

            ehc.handle(EVENT_MESSAGE, processingContext);

            assertThat(COUNTER).hasValue(1);
        });
    }

    @Test
    @DirtiesContext
    void methodsAreProperlyInjected_DuplicateParameterWithAutowired() {
        testApplicationContext.run(context -> {
            assertThat(context).hasBean("duplicateResourceHandlerWithAutowired");
            Object handler = context.getBean("duplicateResourceHandlerWithAutowired");

            AnnotatedEventHandlingComponent<Object> ehc = constructHandlingComponent(handler, context);

            // Spring dependency resolution will resolve at time of execution
            CompletableFuture<Message> result = ehc.handle(EVENT_MESSAGE, processingContext)
                                                   .first()
                                                   .asCompletableFuture()
                                                   .thenApply(Entry::message);

            assertThat(result).isCompletedExceptionally();
            assertThatThrownBy(() -> {
                try {
                    result.get();
                } catch (ExecutionException e) {
                    throw e.getCause();
                }
            }).isInstanceOf(NoUniqueBeanDefinitionException.class);
        });
    }

    @Test
    @DirtiesContext
    void methodsAreProperlyInjectedForDuplicateResourceHandlerWithAutowiredAndQualifier() {
        testApplicationContext.run(context -> {
            assertThat(context).hasBean("duplicateResourceHandlerWithAutowiredAndQualifier");
            Object handler = context.getBean("duplicateResourceHandlerWithAutowiredAndQualifier");

            AnnotatedEventHandlingComponent<Object> ehc = constructHandlingComponent(handler, context);

            ehc.handle(EVENT_MESSAGE, processingContext);

            assertThat(COUNTER).hasValue(1);
        });
    }

    @Test
    void validatePrimaryBeans() {
        testApplicationContext.run(context -> {
            assertThat(
                    context.getBean("duplicateResourceWithPrimary1", DuplicateResourceWithPrimary.class)
                           .isPrimary()
            ).isTrue();
            assertThat(
                    context.getBean("duplicateResourceWithPrimary2", DuplicateResourceWithPrimary.class)
                           .isPrimary()
            ).isFalse();
        });
    }

    private static AnnotatedEventHandlingComponent<Object> constructHandlingComponent(
            Object annotatedHandler,
            AssertableApplicationContext context
    ) {
        return new AnnotatedEventHandlingComponent<>(
                annotatedHandler,
                context.getBean(ParameterResolverFactory.class),
                context.getBean(HandlerDefinition.class),
                context.getBean(MessageTypeResolver.class),
                context.getBean(EventConverter.class)
        );
    }

    private static EventMessage asEventMessage(Object payload) {
        return new GenericEventMessage(new MessageType(EVENT_QUALIFIER), payload);
    }

    public interface DuplicateResourceWithPrimary {

        boolean isPrimary();
    }

    public interface DuplicateResource {

    }

    public interface DuplicateResourceWithQualifier {

    }

    @Configuration
    @EnableAutoConfiguration
    public static class TestContext {

        @Bean(name = "annotatedHandler")
        public AnnotatedEventHandlerWithResources createHandler() {
            return new AnnotatedEventHandlerWithResources();
        }

        @Lazy
        @Bean
        public MissingResourceHandler missingResourceHandler() {
            return new MissingResourceHandler();
        }

        @Lazy
        @Bean
        public NullableResourceHandler nullableResourceHandler() {
            return new NullableResourceHandler();
        }

        @Lazy
        @Bean
        public DuplicateResourceHandler duplicateResourceHandler() {
            return new DuplicateResourceHandler();
        }

        @Lazy
        @Bean
        public DuplicateResourceHandlerWithPrimary duplicateResourceHandlerWithPrimary() {
            return new DuplicateResourceHandlerWithPrimary();
        }

        @Lazy
        @Bean
        public DuplicateResourceHandlerWithQualifier duplicateResourceHandlerWithQualifier() {
            return new DuplicateResourceHandlerWithQualifier();
        }

        @Lazy
        @Bean
        public DuplicateResourceHandlerWithQualifierAndPrimary duplicateResourceHandlerWithQualifierAndPrimary() {
            return new DuplicateResourceHandlerWithQualifierAndPrimary();
        }

        @Lazy
        @Bean
        public DuplicateResourceHandlerWithAutowired duplicateResourceHandlerWithAutowired() {
            return new DuplicateResourceHandlerWithAutowired();
        }

        @Lazy
        @Bean
        public DuplicateResourceHandlerWithAutowiredAndQualifier duplicateResourceHandlerWithAutowiredAndQualifier() {
            return new DuplicateResourceHandlerWithAutowiredAndQualifier();
        }

        @Lazy
        @Bean
        public PrototypeResourceHandler prototypeResourceHandler() {
            return new PrototypeResourceHandler();
        }

        @Bean
        public DuplicateResource duplicateResource1() {
            return new DuplicateResource() {
            };
        }

        @Bean
        public DuplicateResource duplicateResource2() {
            return new DuplicateResource() {
            };
        }

        @Primary
        @Bean
        public DuplicateResourceWithPrimary duplicateResourceWithPrimary1() {
            return () -> true;
        }

        @Bean
        public DuplicateResourceWithPrimary duplicateResourceWithPrimary2() {
            return () -> false;
        }

        @Bean
        public DuplicateResourceWithQualifier duplicateResourceWithQualifier1() {
            return new DuplicateResourceWithQualifier() {
            };
        }

        @Bean("qualifiedByName")
        public DuplicateResourceWithQualifier duplicateResourceWithQualifier2() {
            return new DuplicateResourceWithQualifier() {
            };
        }

        @Bean
        @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
        public PrototypeResource prototypeResource() {
            return new PrototypeResource() {
            };
        }

        @Bean
        public CommandBus commandBus() {
            return new SimpleCommandBus(new SimpleUnitOfWorkFactory(EmptyApplicationContext.INSTANCE));
        }

        @Bean
        public EventStorageEngine eventStorageEngine() {
            return new InMemoryEventStorageEngine();
        }
    }

    public interface PrototypeResource {

    }

    @SuppressWarnings("unused")
    public static class MissingResourceHandler {

        @EventHandler(eventName = "test-event")
        public void handle(String message, ThisResourceReallyDoesntExist dataSource) {
            COUNTER.incrementAndGet();
        }
    }

    @SuppressWarnings("unused")
    public static class NullableResourceHandler {

        @EventHandler(eventName = "test-event")
        public void handle(String message,
                           @Autowired(required = false) ThisResourceReallyDoesntExist dataSource) {
            COUNTER.incrementAndGet();
        }
    }

    @SuppressWarnings("unused")
    public static class DuplicateResourceHandler {

        @EventHandler(eventName = "test-event")
        public void handle(String message, DuplicateResource dataSource) {
            COUNTER.incrementAndGet();
        }
    }

    @SuppressWarnings("unused")
    public static class DuplicateResourceHandlerWithPrimary {

        @EventHandler(eventName = "test-event")
        public void handle(String message, DuplicateResourceWithPrimary dataSource) {
            COUNTER.incrementAndGet();
        }
    }

    @SuppressWarnings("unused")
    public static class DuplicateResourceHandlerWithQualifier {

        @EventHandler(eventName = "test-event")
        public void handle(String message, @Qualifier("qualifiedByName") DuplicateResourceWithQualifier resource) {
            COUNTER.incrementAndGet();
        }
    }

    @SuppressWarnings("unused")
    public static class DuplicateResourceHandlerWithQualifierAndPrimary {

        @EventHandler(eventName = "test-event")
        public void handle(String message,
                           @Qualifier("duplicateResourceWithPrimary2") DuplicateResourceWithPrimary resource) {
            assertFalse(resource.isPrimary(), "expect the non-primary bean to be autowired here");
            COUNTER.incrementAndGet();
        }
    }

    @SuppressWarnings("unused")
    public static class DuplicateResourceHandlerWithAutowired {

        @EventHandler(eventName = "test-event")
        public void handle(String message, @Autowired DuplicateResource resource) {
            COUNTER.incrementAndGet();
        }
    }

    @SuppressWarnings("unused")
    public static class DuplicateResourceHandlerWithAutowiredAndQualifier {

        @EventHandler(eventName = "test-event")
        public void handle(String message,
                           @Autowired @Qualifier("qualifiedByName") DuplicateResourceWithQualifier resource) {
            COUNTER.incrementAndGet();
        }
    }

    @SuppressWarnings("unused")
    public static class PrototypeResourceHandler {

        private PrototypeResource resource;

        @EventHandler(eventName = "test-event")
        public void handle(String message, PrototypeResource resource) {
            assertThat(this.resource).isNotEqualTo(resource);
            this.resource = resource;
            COUNTER.incrementAndGet();
        }
    }

    public static class AnnotatedEventHandlerWithResources {

        @EventHandler(eventName = "test-event")
        public void handle(String message, CommandBus commandBus, EventBus eventBus) {
            assertThat(message).isNotNull();
            assertThat(commandBus).isNotNull();
            assertThat(eventBus).isNotNull();
            COUNTER.incrementAndGet();
        }
    }

    private interface ThisResourceReallyDoesntExist {

    }
}
