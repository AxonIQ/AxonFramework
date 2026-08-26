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

import org.axonframework.messaging.core.GenericResultMessage;
import org.axonframework.messaging.core.ResultMessage;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Rene de Waele
 */
class ExecutionResultTest {

    @Test
    void normalExecutionResult() {
        Object resultPayload = new Object();
        ResultMessage result = GenericResultMessage.asResultMessage(resultPayload);
        ExecutionResult subject = new ExecutionResult(result);
        assertSame(result, subject.getResult());
        assertFalse(subject.isExceptionResult());
        assertNull(subject.getExceptionResult());
    }

    @Test
    void uncheckedExceptionResult() {
        RuntimeException mockException = new RuntimeException();
        ResultMessage resultMessage = GenericResultMessage.asResultMessage(mockException);
        ExecutionResult subject = new ExecutionResult(resultMessage);
        assertTrue(subject.isExceptionResult());
        assertSame(mockException, subject.getExceptionResult());
        assertSame(mockException, subject.getResult().payload());
    }

    @Test
    void checkedExceptionResult() {
        Exception mockException = new Exception();
        ResultMessage resultMessage = GenericResultMessage.asResultMessage(mockException);
        ExecutionResult subject = new ExecutionResult(resultMessage);
        assertTrue(subject.isExceptionResult());
        assertSame(mockException, subject.getExceptionResult());
        assertSame(mockException, subject.getResult().payload());
    }
}