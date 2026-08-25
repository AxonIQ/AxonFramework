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

import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.GenericResultMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.ResultMessage;
import org.axonframework.common.util.MockException;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.axonframework.messaging.unitofwork.LegacyUnitOfWork.Phase.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the {@link LegacyBatchingUnitOfWork}.
 *
 * @author Rene de Waele
 */
class BatchingUnitOfWorkTest {

    private List<PhaseTransition> transitions;
    private LegacyBatchingUnitOfWork<?> subject;

    @BeforeEach
    void setUp() {
        transitions = new ArrayList<>();
    }

    @Test
    void executeTask() {
        List<Message> messages = Arrays.asList(toMessage(0), toMessage(1), toMessage(2));
        subject = new LegacyBatchingUnitOfWork<>(messages);
        subject.executeWithResult((ctx) -> {
            registerListeners(subject);
            return resultFor(subject.getMessage());
        });
        validatePhaseTransitions(Arrays.asList(PREPARE_COMMIT, COMMIT, AFTER_COMMIT, CLEANUP), messages);
        Map<Message, ExecutionResult> expectedResults = new HashMap<>();
        messages.forEach(m -> expectedResults.put(m, new ExecutionResult(GenericResultMessage.asResultMessage(resultFor(m)))));
        assertExecutionResults(expectedResults, subject.getExecutionResults());
    }

    @Test
    void rollback() {
        List<Message> messages = Arrays.asList(toMessage(0), toMessage(1), toMessage(2));
        subject = new LegacyBatchingUnitOfWork<>(messages);
        MockException e = new MockException();
        try {
            subject.executeWithResult((ctx) -> {
                registerListeners(subject);
                if (subject.getMessage().payload().equals(1)) {
                    throw e;
                }
                return resultFor(subject.getMessage());
            });
        } catch (Exception ignored) {
        }
        validatePhaseTransitions(Arrays.asList(ROLLBACK, CLEANUP), messages.subList(0, 2));
        Map<Message, ExecutionResult> expectedResult = new HashMap<>();
        messages.forEach(m -> expectedResult.put(m, new ExecutionResult(GenericResultMessage.asResultMessage(e))));
        assertExecutionResults(expectedResult, subject.getExecutionResults());
    }

    @Test
    void suppressedExceptionOnRollback() {
        List<Message> messages = Arrays.asList(toMessage(0), toMessage(1), toMessage(2));
        AtomicInteger cleanupCounter = new AtomicInteger();
        subject = new LegacyBatchingUnitOfWork<>(messages);
        MockException taskException = new MockException("task exception");
        MockException commitException = new MockException("commit exception");
        MockException cleanupException = new MockException("cleanup exception");
        subject.onCleanup(u -> cleanupCounter.incrementAndGet());
        subject.onCleanup(u -> {
            throw cleanupException;
        });
        subject.onCleanup(u -> cleanupCounter.incrementAndGet());

        try {
            subject.executeWithResult((ctx) -> {
                registerListeners(subject);
                if (subject.getMessage().payload().equals(2)) {
                    subject.addHandler(PREPARE_COMMIT, u -> {
                        throw commitException;
                    });
                    throw taskException;
                }
                return resultFor(subject.getMessage());
            }, e -> false);
        } catch (Exception ignored) {
        }
        validatePhaseTransitions(Arrays.asList(PREPARE_COMMIT, ROLLBACK, CLEANUP), messages);
        Map<Message, ExecutionResult> expectedResult = new HashMap<>();
        expectedResult.put(messages.get(0), new ExecutionResult(GenericResultMessage.asResultMessage(commitException)));
        expectedResult.put(messages.get(1), new ExecutionResult(GenericResultMessage.asResultMessage(commitException)));
        expectedResult.put(messages.get(2), new ExecutionResult(GenericResultMessage.asResultMessage(taskException)));
        assertExecutionResults(expectedResult, subject.getExecutionResults());
        assertSame(commitException, taskException.getSuppressed()[0]);
        assertEquals(2, cleanupCounter.get());
    }

    private void registerListeners(LegacyUnitOfWork<?> unitOfWork) {
        unitOfWork.onPrepareCommit(u -> transitions.add(new PhaseTransition(u.getMessage(), PREPARE_COMMIT)));
        unitOfWork.onCommit(u -> transitions.add(new PhaseTransition(u.getMessage(), COMMIT)));
        unitOfWork.afterCommit(u -> transitions.add(new PhaseTransition(u.getMessage(), AFTER_COMMIT)));
        unitOfWork.onRollback(u -> transitions.add(new PhaseTransition(u.getMessage(), ROLLBACK)));
        unitOfWork.onCleanup(u -> transitions.add(new PhaseTransition(u.getMessage(), CLEANUP)));
    }

    private static Message toMessage(Object payload) {
        return new GenericMessage(new MessageType(payload.getClass()), payload);
    }

    public static Object resultFor(Message message) {
        return "Result for: " + message.payload();
    }

    private void validatePhaseTransitions(List<LegacyUnitOfWork.Phase> phases, List<Message> messages) {
        Iterator<PhaseTransition> iterator = transitions.iterator();
        for (LegacyUnitOfWork.Phase phase : phases) {
            Iterator<Message> messageIterator = phase.isReverseCallbackOrder()
                    ? new LinkedList<>(messages).descendingIterator() : messages.iterator();
            messageIterator.forEachRemaining(message -> {
                PhaseTransition expected = new PhaseTransition(message, phase);
                assertTrue(iterator.hasNext());
                PhaseTransition actual = iterator.next();
                assertEquals(expected, actual);
            });
        }
    }

    private void assertExecutionResults(Map<Message, ExecutionResult> expected,
                                        Map<Message, ExecutionResult> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        List<ResultMessage> expectedMessages = expected.values()
                                                          .stream()
                                                          .map(ExecutionResult::getResult)
                                                          .collect(Collectors.toList());

        List<ResultMessage> actualMessages = actual.values()
                                                      .stream()
                                                      .map(ExecutionResult::getResult)
                                                      .collect(Collectors.toList());
        List<?> expectedPayloads = expectedMessages.stream()
                                                   .filter(crm -> !(crm.payload() instanceof Throwable))
                                                   .map(Message::payload)
                                                   .collect(Collectors.toList());
        List<?> actualPayloads = actualMessages.stream()
                                               .filter(crm -> !(crm.payload() instanceof Throwable))
                                               .map(Message::payload)
                                               .collect(Collectors.toList());
        List<Throwable> expectedExceptions = expectedMessages.stream()
                                                             .filter(crm -> (crm.payload() instanceof Throwable))
                                                             .map(crm -> (Throwable) crm.payload())
                                                             .collect(Collectors.toList());
        List<Throwable> actualExceptions = actualMessages.stream()
                                                         .filter(crm -> (crm.payload() instanceof Throwable))
                                                         .map(crm -> (Throwable) crm.payload())
                                                         .collect(Collectors.toList());
        List<Metadata> expectedMetadata = expectedMessages.stream()
                                                          .map(Message::metadata)
                                                          .collect(Collectors.toList());
        List<Metadata> actualMetadata = actualMessages.stream()
                                                      .map(Message::metadata)
                                                      .collect(Collectors.toList());
        assertEquals(expectedPayloads.size(), actualPayloads.size());
        //noinspection SuspiciousMethodCalls
        assertTrue(expectedPayloads.containsAll(actualPayloads));
        assertEquals(expectedExceptions.size(), actualExceptions.size());
        assertTrue(expectedExceptions.containsAll(actualExceptions));
        assertTrue(expectedMetadata.containsAll(actualMetadata));
    }

    private static class PhaseTransition {

        private final LegacyUnitOfWork.Phase phase;
        private final Message message;

        public PhaseTransition(Message message, LegacyUnitOfWork.Phase phase) {
            this.message = message;
            this.phase = phase;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PhaseTransition that = (PhaseTransition) o;
            return phase == that.phase &&
                    Objects.equals(message, that.message);
        }

        @Override
        public int hashCode() {
            return Objects.hash(phase, message);
        }

        @Override
        public String toString() {
            return phase + " -> " + message.payload();
        }
    }
}
