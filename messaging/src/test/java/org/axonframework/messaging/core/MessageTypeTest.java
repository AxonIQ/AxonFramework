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

package org.axonframework.messaging.core;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the {@link MessageType}.
 *
 * @author Steven van Beelen
 */
class MessageTypeTest {

    private static final String NAMESPACE = MessageTypeTest.class.getPackageName();
    private static final String LOCAL_NAME = MessageTypeTest.class.getSimpleName();
    private static final String NAME = NAMESPACE + "." + LOCAL_NAME;
    private static final QualifiedName QUALIFIED_NAME = new QualifiedName(NAME);
    private static final String VERSION = "5.0.0";

    @Test
    void throwsNullPointerExceptionForNullQualifiedName() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> new MessageType((QualifiedName) null));
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> new MessageType((QualifiedName) null, VERSION));
    }

    @Test
    void qualifiedNameAndVersionConstructorContainsDataAsExpected() {
        MessageType testSubject = new MessageType(QUALIFIED_NAME, VERSION);

        assertEquals(QUALIFIED_NAME, testSubject.qualifiedName());
        assertEquals(VERSION, testSubject.version());
    }

    @Test
    void qualifiedNameConstructorContainsDataAsExpected() {
        MessageType testSubject = new MessageType(QUALIFIED_NAME);

        assertEquals(QUALIFIED_NAME, testSubject.qualifiedName());
        assertEquals(MessageType.DEFAULT_VERSION, testSubject.version());
    }

    @Test
    void throwsNullPointerExceptionForNullQualifiedNameString() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> new MessageType((String) null));
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> new MessageType((String) null, VERSION));
    }

    @Test
    void nameStringConstructorContainsDataAsExpected() {
        MessageType testSubject = new MessageType(NAME);

        assertEquals(QUALIFIED_NAME, testSubject.qualifiedName());
        assertEquals(MessageType.DEFAULT_VERSION, testSubject.version());
    }

    @Test
    void nameStringAndVersionConstructorContainsDataAsExpected() {
        MessageType testSubject = new MessageType(NAME, VERSION);

        assertEquals(QUALIFIED_NAME, testSubject.qualifiedName());
        assertEquals(VERSION, testSubject.version());
    }

    @Test
    void throwsNullPointerExceptionForNullLocalName() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> new MessageType(NAMESPACE, null, VERSION));
    }

    @Test
    void namespaceLocalNameAndVersionConstructorContainsDataAsExpected() {
        MessageType testSubject = new MessageType(NAMESPACE, LOCAL_NAME, VERSION);

        assertEquals(QUALIFIED_NAME, testSubject.qualifiedName());
        assertEquals(VERSION, testSubject.version());
    }

    @Test
    void throwsNullPointerExceptionForNullClass() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> new MessageType((Class<?>) null));
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> new MessageType((Class<?>) null, VERSION));
    }

    @Test
    void classAndVersionConstructorContainsDataAsExpected() {
        MessageType testSubject = new MessageType(this.getClass(), VERSION);

        assertEquals(QUALIFIED_NAME, testSubject.qualifiedName());
        assertEquals(VERSION, testSubject.version());
    }

    @Test
    void classConstructorContainsDataAsExpected() {
        MessageType testSubject = new MessageType(this.getClass());

        assertEquals(QUALIFIED_NAME, testSubject.qualifiedName());
        assertEquals(MessageType.DEFAULT_VERSION, testSubject.version());
    }

    @Test
    void toStringDelimitsQualifiedNameAndVersionWithHashtag() {
        String expectedToString = NAME + "#" + VERSION;

        MessageType testSubject = new MessageType(QUALIFIED_NAME, VERSION);

        assertEquals(expectedToString, testSubject.toString());
    }

    @Test
    void fromStringThrowsNullPointerExceptionForNullFromString() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> MessageType.fromString(null));
    }

    @Test
    void fromStringThrowsIllegalArgumentExceptionForEmptyString() {
        assertThrows(IllegalArgumentException.class, () -> MessageType.fromString(""));
    }

    @Test
    void fromStringSplitsFullQualifiedTypeAsExpected() {
        String testName = NAME;
        QualifiedName expectedName = new QualifiedName(testName);
        String expectedVersion = VERSION;
        String testString = testName + "#" + expectedVersion;

        MessageType testSubject = MessageType.fromString(testString);

        assertEquals(expectedName, testSubject.qualifiedName());
        assertEquals(expectedVersion, testSubject.version());
    }

    @Test
    void fromStringRejectsMissingVersion() {
        assertThrows(IllegalArgumentException.class, () -> MessageType.fromString(NAME));
    }

    /**
     * Reproduction of <a href="https://github.com/AxonIQ/AxonFramework/issues/4625">#4625</a>.
     * <p>
     * Axon Framework 4 events and snapshots may be stored without a revision (the revision was nullable, and Axon
     * Server stores it as an empty {@code String}). Reconstructing a {@link MessageType} from such stored data - as the
     * Axon Server connector and the JPA event storage engine do via {@code new MessageType(payloadType, payloadRevision)}
     * - currently fails because the compact constructor rejects an empty or {@code null} version.
     * <p>
     * These tests pin that current (broken) behavior. The chosen fix will determine whether and how these expectations
     * change.
     */
    @Nested
    class GivenStoredMessageWithoutVersion {

        @Test
        void rejectsEmptyVersion() {
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> new MessageType(QUALIFIED_NAME, "")
            );

            assertEquals("The given version is unsupported because it is empty.", exception.getMessage());
        }

        @Test
        void rejectsNullVersion() {
            //noinspection DataFlowIssue
            assertThrows(NullPointerException.class, () -> new MessageType(QUALIFIED_NAME, null));
        }

        @Test
        void rejectsEmptyVersionThroughStringConstructorAsUsedByStorageEngines() {
            // Exactly the call a storage engine makes when reading a stored event/snapshot with an absent revision.
            assertThrows(IllegalArgumentException.class, () -> new MessageType(NAME, ""));
        }
    }
}