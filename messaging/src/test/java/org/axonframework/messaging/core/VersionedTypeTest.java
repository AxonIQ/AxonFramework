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
 * Test class validating the {@link VersionedType} interface and that {@link MessageType} correctly implements it.
 *
 * @author Josh
 */
class VersionedTypeTest {

    private static final String NAME = "org.axonframework.test.SomeEntity";
    private static final QualifiedName QUALIFIED_NAME = new QualifiedName(NAME);
    private static final String VERSION = "2.0.0";

    @Test
    void messageTypeIsAVersionedType() {
        MessageType messageType = new MessageType(QUALIFIED_NAME, VERSION);

        assertInstanceOf(VersionedType.class, messageType);
    }

    @Test
    void versionedTypeReturnsQualifiedName() {
        VersionedType versionedType = new MessageType(QUALIFIED_NAME, VERSION);

        assertEquals(QUALIFIED_NAME, versionedType.qualifiedName());
    }

    @Test
    void versionedTypeReturnsVersion() {
        VersionedType versionedType = new MessageType(QUALIFIED_NAME, VERSION);

        assertEquals(VERSION, versionedType.version());
    }

    @Test
    void versionedTypeReturnsName() {
        VersionedType versionedType = new MessageType(QUALIFIED_NAME, VERSION);

        assertEquals(NAME, versionedType.name());
    }

    @Test
    void versionedTypeWithDefaultVersion() {
        VersionedType versionedType = new MessageType(QUALIFIED_NAME);

        assertEquals(QUALIFIED_NAME, versionedType.qualifiedName());
        assertEquals(MessageType.DEFAULT_VERSION, versionedType.version());
    }

    @Test
    void versionedTypeFromClassConstructor() {
        VersionedType versionedType = new MessageType(VersionedTypeTest.class, VERSION);

        assertEquals(new QualifiedName(VersionedTypeTest.class), versionedType.qualifiedName());
        assertEquals(VERSION, versionedType.version());
    }
}
