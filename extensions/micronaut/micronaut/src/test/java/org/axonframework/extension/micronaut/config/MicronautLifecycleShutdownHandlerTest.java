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

package org.axonframework.extension.micronaut.config;

import io.micronaut.context.BeanContext;
import io.micronaut.context.event.ShutdownEvent;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.LifecycleHandler;
import org.junit.jupiter.api.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link MicronautLifecycleShutdownHandler}.
 *
 * @author Daniel Karapishchenko
 */
@MicronautTest(startApplication = false)
class MicronautLifecycleShutdownHandlerTest {

    private LifecycleHandler action;

    private MicronautLifecycleShutdownHandler testSubject;
    @Inject
    private BeanContext beanContext;


    @MockBean(Configuration.class)
    Configuration configuration() {
        return mock();
    }

    @BeforeEach
    void setUp() {
        action = mock();
        doReturn(CompletableFuture.completedFuture(null)).when(action).run(any());
        testSubject = beanContext.createBean(MicronautLifecycleShutdownHandler.class, 42, action);
        beanContext.registerSingleton(testSubject);
    }

    @Test
    void phaseIsRegisteredCorrectly() {
        assertEquals(42, testSubject.getOrder());
    }
    @Test
    void actionIsInvokedOnStartupEvent() throws ExecutionException, InterruptedException {
        testSubject.on(new ShutdownEvent(beanContext));
        verify(action).run(any());
    }
}
