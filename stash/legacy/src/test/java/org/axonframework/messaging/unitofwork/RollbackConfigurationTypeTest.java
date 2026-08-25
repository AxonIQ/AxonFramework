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

package org.axonframework.messaging.unitofwork;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Allard Buijze
 */
class RollbackConfigurationTypeTest {

    @Test
    void anyExceptionsRollback() {
        RollbackConfiguration testSubject = RollbackConfigurationType.ANY_THROWABLE;
        assertTrue(testSubject.rollBackOn(new RuntimeException()));
        assertTrue(testSubject.rollBackOn(new Exception()));
        assertTrue(testSubject.rollBackOn(new AssertionError()));
    }

    @Test
    void uncheckedExceptionsRollback() {
        RollbackConfiguration testSubject = RollbackConfigurationType.UNCHECKED_EXCEPTIONS;
        assertTrue(testSubject.rollBackOn(new RuntimeException()));
        assertFalse(testSubject.rollBackOn(new Exception()));
        assertTrue(testSubject.rollBackOn(new AssertionError()));
    }

    @Test
    void runtimeExceptionsRollback() {
        RollbackConfiguration testSubject = RollbackConfigurationType.RUNTIME_EXCEPTIONS;
        assertTrue(testSubject.rollBackOn(new RuntimeException()));
        assertFalse(testSubject.rollBackOn(new Exception()));
        assertFalse(testSubject.rollBackOn(new AssertionError()));
    }
}
