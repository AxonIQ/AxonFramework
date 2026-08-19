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

package org.axonframework.modelling.entity.annotation;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.messaging.core.GenericMessage;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.conversion.DelegatingMessageConverter;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.modelling.EntityIdResolutionException;
import org.axonframework.modelling.annotation.AnnotationBasedEntityIdResolver;
import org.axonframework.modelling.annotation.TargetEntityId;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;
import org.mockito.*;
import org.mockito.junit.jupiter.*;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("deprecation") // Intentionally tests the deprecated AnnotatedEntityIdResolver.
class AnnotatedEntityIdResolverTest {

    @Mock
    private AnnotatedEntityMetamodel<String> metamodel;

    private AnnotatedEntityIdResolver<String> resolver;

    @BeforeEach
    void setUp() {
        resolver = new AnnotatedEntityIdResolver<>(metamodel,
                                                   String.class,
                                                   new DelegatingMessageConverter(new JacksonConverter()),
                                                   new AnnotationBasedEntityIdResolver<>());
    }

    @Test
    void canResolveIdFromSerializedMessage() throws EntityIdResolutionException {
        MessageType messageType = new MessageType(MyIdHoldingObject.class);
        var serializedMessage = new GenericMessage(messageType, """
                {"identifier": "test5362"}""");

        QualifiedName qualifiedName = messageType.qualifiedName();
        Mockito.doReturn(MyIdHoldingObject.class)
               .when(metamodel)
               .getExpectedRepresentation(qualifiedName);


        String resolvedId = resolver.resolve(serializedMessage, new StubProcessingContext());
        assertEquals("test5362", resolvedId);
    }

    @Test
    void shouldThrowExceptionWhenUnableToResolveId() {
        MessageType messageType = new MessageType(MyIdHoldingObject.class);
        var serializedMessage = new GenericMessage(messageType, "{}");

        QualifiedName qualifiedName = messageType.qualifiedName();
        Mockito.doReturn(MyIdHoldingObject.class)
               .when(metamodel)
               .getExpectedRepresentation(qualifiedName);

        assertThatThrownBy(() -> resolver.resolve(serializedMessage, new StubProcessingContext()))
                .isInstanceOf(EntityIdResolutionException.class);
    }

    @Test
    void throwsExceptionWhenExpectedRepresentationIsMissing() {
        MessageType messageType = new MessageType(MyIdHoldingObject.class);
        var serializedMessage = new GenericMessage(messageType, """
                {"identifier": "test5362"}""");

        assertThrows(AxonConfigurationException.class, () -> {
            resolver.resolve(serializedMessage, new StubProcessingContext());
        });
    }


    private record MyIdHoldingObject(
            @TargetEntityId String identifier
    ) {

    }
}