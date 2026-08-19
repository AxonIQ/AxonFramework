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

package org.axonframework.modelling;

import org.axonframework.common.infra.MockComponentDescriptor;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.junit.jupiter.api.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the {@link SimpleEntityEvolvingComponent}.
 *
 * @author Mitchll Herrijgers
 * @author Steven van Beelen
 */
class SimpleEntityEvolvingComponentTest {

    private static final String ENTITY = "entity";
    private static final EventMessage STRING_EVENT =
            new GenericEventMessage(new MessageType(String.class), "string");
    private static final EventMessage INT_EVENT =
            new GenericEventMessage(new MessageType(Integer.class), 42);

    private AtomicBoolean stringEvolverInvoked;
    private AtomicBoolean integerEvolverInvoked;

    private SimpleEntityEvolvingComponent<String> testSubject;

    @BeforeEach
    void setUp() {
        stringEvolverInvoked = new AtomicBoolean(false);
        integerEvolverInvoked = new AtomicBoolean(false);

        EntityEvolver<String> stringBasedEntityEvolver = (entity, event, context) -> {
            stringEvolverInvoked.set(true);
            return entity + "-" + event.payload();
        };
        EntityEvolver<String> integerBasedEntityEvolver = (entity, event, context) -> {
            integerEvolverInvoked.set(true);
            return entity + "-" + event.payload();
        };

        Map<QualifiedName, EntityEvolver<String>> evolvers = new HashMap<>();
        evolvers.put(STRING_EVENT.type().qualifiedName(), stringBasedEntityEvolver);
        evolvers.put(INT_EVENT.type().qualifiedName(), integerBasedEntityEvolver);

        testSubject = new SimpleEntityEvolvingComponent<>(evolvers);
    }

    @Test
    void constructorThrowsNullPointerExceptionForNullEntityEvolverCollection() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> new SimpleEntityEvolvingComponent<>(null));
    }

    @Test
    void evolveThrowsNullPointerExceptionForNullEventMessage() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> testSubject.evolve(ENTITY, null, null));
    }

    @Test
    void evolveInvokesQualifiedNameMatchingEntityEvolver() {
        String result = testSubject.evolve(ENTITY, STRING_EVENT, StubProcessingContext.forMessage(STRING_EVENT));

        assertNotEquals(ENTITY, result);
        assertEquals(ENTITY + "-string", result);

        assertTrue(stringEvolverInvoked.get());
        assertFalse(integerEvolverInvoked.get());
    }

    @Test
    void subsequentEvolveInvocations() {
        String result = testSubject.evolve(ENTITY, STRING_EVENT, StubProcessingContext.forMessage(STRING_EVENT));

        assertNotEquals(ENTITY, result);
        assertEquals(ENTITY + "-string", result);

        assertTrue(stringEvolverInvoked.get());
        assertFalse(integerEvolverInvoked.get());

        result = testSubject.evolve(result, INT_EVENT, StubProcessingContext.forMessage(INT_EVENT));

        assertNotEquals(ENTITY + "-string", result);
        assertEquals(ENTITY + "-string-42", result);

        assertTrue(integerEvolverInvoked.get());
    }

    @Test
    void evolveReturnsEntityAsIsForEventWithUnknownQualifiedName() {
        GenericEventMessage eventWithUnknownName =
                new GenericEventMessage(new MessageType(Boolean.class), true);

        String result = testSubject.evolve(ENTITY, eventWithUnknownName, StubProcessingContext.forMessage(eventWithUnknownName));

        assertEquals(ENTITY, result);
    }

    @Test
    void supportedEventsReturnsGivenQualifiedNamesDuringConstruction() {
        Set<QualifiedName> result = testSubject.supportedEvents();

        assertEquals(2, result.size());
        assertTrue(result.contains(STRING_EVENT.type().qualifiedName()));
        assertTrue(result.contains(INT_EVENT.type().qualifiedName()));
    }

    @Test
    void supportedEventsReturnsPrecomputedSet() {
        assertSame(testSubject.supportedEvents(), testSubject.supportedEvents());
    }

    @Test
    void describeToThrowsNullPointerExceptionForNullComponentDescriptor() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> testSubject.describeTo(null));
    }

    @Test
    void describeToDescribesEntityEvolvers() {
        MockComponentDescriptor testDescriptor = new MockComponentDescriptor();

        testSubject.describeTo(testDescriptor);

        Map<QualifiedName, Object> result = testDescriptor.getProperty("delegates");
        assertNotNull(result);
        assertEquals(2, result.size());
        Set<QualifiedName> resultKeys = result.keySet();
        assertTrue(resultKeys.contains(STRING_EVENT.type().qualifiedName()));
        assertTrue(resultKeys.contains(INT_EVENT.type().qualifiedName()));
    }
}
