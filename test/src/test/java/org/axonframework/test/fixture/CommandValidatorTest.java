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

package org.axonframework.test.fixture;

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.test.AxonAssertionError;
import org.axonframework.test.matchers.AllFieldsFilter;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the {@link CommandValidator}.
 *
 * @author Tom Soete
 */
class CommandValidatorTest {

    private final List<CommandMessage> dispatched = new ArrayList<>();

    private CommandValidator testSubject;

    @BeforeEach
    void setUp() {
        testSubject = new CommandValidator(() -> dispatched, dispatched::clear, AllFieldsFilter.instance());
    }

    @Test
    void assertEmptyDispatchedEqualTo() {
        dispatched.addAll(emptyCommandMessageList());

        testSubject.assertDispatchedEqualTo();
    }

    @Test
    void assertNonEmptyDispatchedEqualTo() {
        dispatched.addAll(listOfOneCommandMessage("command"));

        testSubject.assertDispatchedEqualTo("command");
    }

    @Test
    void matchWithUnexpectedNullValue() {
        dispatched.addAll(listOfOneCommandMessage(new SomeCommand(null)));

        assertThrows(AxonAssertionError.class, () -> testSubject.assertDispatchedEqualTo(new SomeCommand("test")));
    }

    @Test
    void matchPrimitiveTypedCommands() {
        dispatched.addAll(listOfOneCommandMessage("some-string"));

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
