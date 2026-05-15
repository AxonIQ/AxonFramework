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

package org.axonframework.messaging.commandhandling;

import org.axonframework.common.configuration.Component;
import org.axonframework.common.configuration.Components;
import org.axonframework.common.configuration.InstantiatedComponentDefinition;
import org.axonframework.messaging.commandhandling.interception.InterceptingCommandBus;
import org.axonframework.messaging.commandhandling.retry.RetryingCommandBus;
import org.axonframework.messaging.commandhandling.tracing.TracingCommandBus;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;
import org.mockito.junit.jupiter.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;


/**
 * Test class validating the {@link Components}.
 *
 * @author Steven van Beelen
 */
@ExtendWith(MockitoExtension.class)
public class CommandHandlingComponentsTest {

    private static final Component.Identifier<String> IDENTIFIER = new Component.Identifier<>(String.class, "id");

    private Components testSubject;

    @BeforeEach
    void setUp() {
        testSubject = new Components();
    }

    @Test
    void containsMatchesWithAssignableFromTypesWhenNoNameIsGiven() {
        // given...
        Component.Identifier<InterceptingCommandBus> idOne = new Component.Identifier<>(InterceptingCommandBus.class,
                                                                                        null);
        InterceptingCommandBus mockOne = mock(InterceptingCommandBus.class);
        testSubject.put(new InstantiatedComponentDefinition<>(idOne, mockOne));

        Component.Identifier<RetryingCommandBus> idTwo = new Component.Identifier<>(RetryingCommandBus.class, null);
        RetryingCommandBus mockTwo = mock(RetryingCommandBus.class);
        testSubject.put(new InstantiatedComponentDefinition<>(idTwo, mockTwo));

        // when/then...
        // exact type match succeeds...
        assertTrue(testSubject.contains(new Component.Identifier<>(InterceptingCommandBus.class, null)));
        assertTrue(testSubject.contains(new Component.Identifier<>(RetryingCommandBus.class, null)));
        // assignable from type match succeeds...
        assertTrue(testSubject.contains(new Component.Identifier<>(CommandBus.class, null)));
        // non-existent type match fails...
        assertFalse(testSubject.contains(new Component.Identifier<>(TracingCommandBus.class, null)));
    }
}
