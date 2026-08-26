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

package org.axonframework.spring.saga;

import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.eventhandling.EventBus;
import org.axonframework.modelling.saga.ResourceInjector;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.axonframework.messaging.commandhandling.CommandBusTestUtils.aCommandBus;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Allard Buijze
 */
public class SpringResourceInjectorTest {

    private static ResourceInjector testSubject;

    @BeforeAll
    static void beforeClass() {
        ApplicationContext appCtx = new AnnotationConfigApplicationContext(Context.class);
        testSubject = appCtx.getBean(ResourceInjector.class);
    }

    @Test
    void injectSaga() {
        InjectableSaga injectableSaga = new InjectableSaga();
        testSubject.injectResources(injectableSaga);
        assertNotNull(injectableSaga.getCommandBus());
        assertNull(injectableSaga.getNonAnnotatedCommandBus());
        assertNull(injectableSaga.getEventBus());
    }

    @Test
    void resourcesNotAvailable() {
        ProblematicInjectableSaga injectableSaga = new ProblematicInjectableSaga();
        assertThrows(BeanCreationException.class, () -> testSubject.injectResources(injectableSaga));
    }

    public static class InjectableSaga {

        @Autowired
        private CommandBus commandBus1;
        @SuppressWarnings({"UnusedDeclaration"})
        private CommandBus commandBus2;

        private EventBus eventBus;

        public CommandBus getCommandBus() {
            return commandBus1;
        }

        public CommandBus getNonAnnotatedCommandBus() {
            return commandBus2;
        }

        public EventBus getEventBus() {
            return eventBus;
        }

        @Autowired(required = false)
        public void setEventBus(EventBus eventBus) {
            this.eventBus = eventBus;
        }
    }

    public static class ProblematicInjectableSaga extends InjectableSaga {

        @Override
        @Autowired(required = true)
        public void setEventBus(EventBus eventBus) {
            throw new UnsupportedOperationException("Method not implemented");
        }
    }

    @Configuration
    public static class Context {

        @Bean
        public ResourceInjector resourceInjector() {
            return new SpringResourceInjector();
        }

        @Bean
        public CommandBus commandBus() {
            return aCommandBus();
        }
    }
}
