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
 * Test class validating the {@link QualifiedName}.
 *
 * @author Steven van Beelen
 */
class QualifiedNameTest {

    private static final String NAMESPACE = "namespace";
    private static final String LOCAL_NAME = "localName";
    private static final String FULL_NAME = NAMESPACE + "." + LOCAL_NAME;

    @Test
    void nameConstructorContainsDataAsExpected() {
        QualifiedName testSubject = new QualifiedName(FULL_NAME);

        assertEquals(FULL_NAME, testSubject.name());
        assertEquals(NAMESPACE, testSubject.namespace());
        assertEquals(LOCAL_NAME, testSubject.localName());
    }

    @Test
    void namespaceAndLocalNameConstructorCombinesBothToName() {
        QualifiedName testSubject = new QualifiedName(NAMESPACE, LOCAL_NAME);

        assertEquals(FULL_NAME, testSubject.name());
        assertEquals(NAMESPACE, testSubject.namespace());
        assertEquals(LOCAL_NAME, testSubject.localName());
    }

    @Test
    void namespaceAndLocalNameConstructorIgnoresNullNamespace() {
        QualifiedName testSubject = new QualifiedName(null, LOCAL_NAME);

        assertEquals(LOCAL_NAME, testSubject.name());
        assertNull(testSubject.namespace());
        assertEquals(LOCAL_NAME, testSubject.localName());
    }

    @Test
    void namespaceAndLocalNameConstructorIgnoresEmptyNamespace() {
        QualifiedName testSubject = new QualifiedName("", LOCAL_NAME);

        assertEquals(LOCAL_NAME, testSubject.name());
        assertNull(testSubject.namespace());
        assertEquals(LOCAL_NAME, testSubject.localName());
    }

    @Test
    void throwsNullPointerExceptionForNullName() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> new QualifiedName((String) null));
    }

    @Test
    void throwsIllegalArgumentExceptionForEmptyName() {
        assertThrows(IllegalArgumentException.class, () -> new QualifiedName(""));
    }

    @Test
    void throwsNullPointerExceptionForNullLocalName() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> new QualifiedName(NAMESPACE, null));
    }

    @Test
    void throwsIllegalArgumentExceptionForEmptyLocalName() {
        assertThrows(IllegalArgumentException.class, () -> new QualifiedName(NAMESPACE, ""));
    }

    @Test
    void throwsNullPointerExceptionForNullClass() {
        //noinspection DataFlowIssue
        assertThrows(NullPointerException.class, () -> new QualifiedName((Class<?>) null));
    }

    @Test
    void toStringReturnsJustTheName() {
        QualifiedName testSubject = new QualifiedName(FULL_NAME);

        assertEquals(FULL_NAME, testSubject.toString());
    }

    @Test
    void anonymousClassReturnsGetNameAsName() {
        Class<?> testClass = new Object() {}.getClass();

        assertEquals(testClass.getName(), new QualifiedName(testClass).name());
    }
}