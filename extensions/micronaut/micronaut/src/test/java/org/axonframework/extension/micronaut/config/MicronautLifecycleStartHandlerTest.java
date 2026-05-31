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
import io.micronaut.context.BeanProvider;
import io.micronaut.context.RuntimeBeanDefinition;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.LifecycleHandler;
import org.junit.jupiter.api.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link MicronautLifecycleStartHandler}.
 *
 * @author Daniel Karapishchenko
 */
@MicronautTest(startApplication = false)
class MicronautLifecycleStartHandlerTest {

    private LifecycleHandler action;

    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private MicronautLifecycleStartHandler testSubject;

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
        testSubject = beanContext.createBean(MicronautLifecycleStartHandler.class, 42, action);
    }

    @Test
    void phaseIsRegisteredCorrectly() {
        assertEquals(42, testSubject.getOrder());
    }

    @Test
    void isCanceledWhenBeanIsDestroyed() throws InterruptedException {
        CountDownLatch lifecycleHandlerStartedLatch = new CountDownLatch(1);
        CompletableFuture<?> lifecycleHandlerFuture = CompletableFuture.runAsync(() -> {
            lifecycleHandlerStartedLatch.countDown();
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        doReturn(lifecycleHandlerFuture).when(action).run(any());
        Future<?> eventHandlerFuture = EXECUTOR.submit(() -> {
            try {
                testSubject.on(new StartupEvent(beanContext));
            } catch (ExecutionException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });

        lifecycleHandlerStartedLatch.await();

        assertFalse(lifecycleHandlerFuture.isDone());
        assertFalse(eventHandlerFuture.isDone());
        Thread.sleep(10);
        beanContext.destroyBean(testSubject);

        assertTrue(lifecycleHandlerFuture.isCancelled());
        assertTrue(eventHandlerFuture.isDone());
    }

    @Test
    void actionIsInvokedOnStartupEvent() throws ExecutionException, InterruptedException {
        testSubject.on(new StartupEvent(beanContext));
        verify(action).run(any());
    }
}