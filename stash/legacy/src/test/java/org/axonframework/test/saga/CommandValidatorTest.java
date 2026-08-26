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

package org.axonframework.test.saga;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.test.AxonAssertionError;
import org.axonframework.test.fixture.CommandValidator;
import org.axonframework.test.matchers.AllFieldsFilter;
import org.axonframework.test.util.RecordingCommandBus;
import org.junit.jupiter.api.*;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link CommandValidator}.
 *
 * @author Tom Soete
 */
class CommandValidatorTest {

    private CommandValidator testSubject;

    private RecordingCommandBus commandBus;

    @BeforeEach
    void setUp() {
        commandBus = mock(RecordingCommandBus.class);
        testSubject = new CommandValidator(commandBus, AllFieldsFilter.instance());
    }

    @Test
    void assertEmptyDispatchedEqualTo() {
        when(commandBus.getDispatchedCommands()).thenReturn(emptyCommandMessageList());

        testSubject.assertDispatchedEqualTo();
    }

    @Test
    void assertNonEmptyDispatchedEqualTo() {
        when(commandBus.getDispatchedCommands()).thenReturn(listOfOneCommandMessage("command"));

        testSubject.assertDispatchedEqualTo("command");
    }

    @Test
    void matchWithUnexpectedNullValue() {
        when(commandBus.getDispatchedCommands()).thenReturn(listOfOneCommandMessage(new SomeCommand(null)));

        assertThrows(AxonAssertionError.class, () -> testSubject.assertDispatchedEqualTo(new SomeCommand("test")));
    }

    @Test
    void matchPrimitiveTypedCommands() {
        when(commandBus.getDispatchedCommands()).thenReturn(listOfOneCommandMessage("some-string"));

        assertThrows(AxonAssertionError.class, () -> testSubject.assertDispatchedEqualTo("some-other-string"));
    }

    private List<CommandMessage> emptyCommandMessageList() {
        return Collections.emptyList();
    }

    private List<CommandMessage> listOfOneCommandMessage(Object msg) {
        return Collections.singletonList(
                new GenericCommandMessage(new MessageType("command"), msg)
        );
    }

    private record SomeCommand(Object value) {

    }
}
