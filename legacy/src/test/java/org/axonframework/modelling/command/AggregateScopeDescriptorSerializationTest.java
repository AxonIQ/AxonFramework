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

package org.axonframework.modelling.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.axonframework.conversion.Converter;
import org.axonframework.conversion.jackson2.Jackson2Converter;
import org.axonframework.modelling.OnlyAcceptConstructorPropertiesAnnotation;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests conversion capabilities of {@link AggregateScopeDescriptor}.
 */
class AggregateScopeDescriptorSerializationTest {

    private final String expectedType = "aggregateType";
    private final String expectedIdentifier = "identifier";

    private AggregateScopeDescriptor testSubject;

    @BeforeEach
    void setUp() {
        testSubject = new AggregateScopeDescriptor(expectedType, expectedIdentifier);
    }

    @Test
    void jacksonSerializationWorksAsExpected() {
        Converter converter = new Jackson2Converter();

        byte[] serialized = converter.convert(testSubject, byte[].class);
        AggregateScopeDescriptor result = converter.convert(serialized, AggregateScopeDescriptor.class);

        assertEquals(expectedType, result.getType());
        assertEquals(expectedIdentifier, result.getIdentifier());
    }

    @Test
    void responseTypeShouldBeSerializableWithJacksonUsingConstructorProperties() {
        ObjectMapper objectMapper = OnlyAcceptConstructorPropertiesAnnotation.attachTo(new ObjectMapper());
        Converter converter = new Jackson2Converter(objectMapper);

        byte[] serialized = converter.convert(testSubject, byte[].class);
        AggregateScopeDescriptor result = converter.convert(serialized, AggregateScopeDescriptor.class);

        assertEquals(expectedType, result.getType());
        assertEquals(expectedIdentifier, result.getIdentifier());
    }

    @Test
    void lazyIdentifierSupplierIsOnlyResolvedOnFirstAccess() {
        boolean[] supplierInvoked = {false};
        AggregateScopeDescriptor lazyDescriptor = new AggregateScopeDescriptor(expectedType, () -> {
            supplierInvoked[0] = true;
            return expectedIdentifier;
        });

        assertFalse(supplierInvoked[0]);
        assertEquals(expectedIdentifier, lazyDescriptor.getIdentifier());
        assertTrue(supplierInvoked[0]);
    }
}
