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

import org.junit.jupiter.api.*;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link MicronautLifecycleStartHandler}.
 *
 * @author Steven van Beelen
 */
//class MicronautLifecycleStartHandlerTest {
//
//    private Supplier<CompletableFuture<?>> action;
//
//    private MicronautLifecycleStartHandler testSubject;
//
//    @BeforeEach
//    void setUp() {
//        //noinspection unchecked
//        action = mock();
//        doReturn(CompletableFuture.completedFuture(null)).when(action)
//                                                         .get();
//
//        testSubject = new MicronautLifecycleStartHandler(42, action);
//    }
//
//    @Test
//    void phaseIsRegisteredCorrectly() {
//        assertEquals(42, testSubject.getPhase());
//    }
//
//    @Test
//    void isRunningReflectsCorrectState() {
//        assertFalse(testSubject.isRunning());
//
//        testSubject.start();
//        assertTrue(testSubject.isRunning());
//
//        testSubject.stop();
//        assertFalse(testSubject.isRunning());
//    }
//
//    @Test
//    void actionIsInvokedOnStart() {
//        testSubject.start();
//
//        verify(action).get();
//    }
//}