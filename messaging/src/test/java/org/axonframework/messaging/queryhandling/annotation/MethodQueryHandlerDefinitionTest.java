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

package org.axonframework.messaging.queryhandling.annotation;

import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.annotation.AnnotatedMessageHandlingMemberDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.annotation.UnsupportedHandlerException;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.queryhandling.GenericQueryMessage;
import org.junit.jupiter.api.*;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.axonframework.messaging.core.annotation.MessageStreamResolverUtils.resolveToStream;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test whether the {@link MethodQueryHandlerDefinition} correctly deals with return types, as well as for example
 * {@link java.util.concurrent.Future} and {@link Optional} which contain a generic type.
 *
 * @author Allard Buijze
 */
class MethodQueryHandlerDefinitionTest {

    private MethodQueryHandlerDefinition testSubject;
    private AnnotatedMessageHandlingMemberDefinition handlerDefinition;
    private ParameterResolverFactory parameterResolver;

    @BeforeEach
    void setUp() {
        parameterResolver = ClasspathParameterResolverFactory.forClass(getClass());
        testSubject = new MethodQueryHandlerDefinition();
        handlerDefinition = new AnnotatedMessageHandlingMemberDefinition();
    }

    @Test
    void voidNotAcceptedAsReturnType() {
        assertThrows(UnsupportedHandlerException.class, () -> messageHandler("illegalQueryResponseType"));
    }

    @Test
    void futureResponseTypeUnwrapped() {
        QueryHandlingMember<?> handler = messageHandler("futureReturnType");
        assertEquals(String.class, handler.resultType());
    }

    @Test
    void optionalResponseTypeUnwrapped() throws Exception {
        QueryHandlingMember<MethodQueryHandlerDefinitionTest> handler = messageHandler("optionalReturnType");
        assertEquals(String.class, handler.resultType());

        GenericQueryMessage message = new GenericQueryMessage(new MessageType(String.class), "mock");

        ProcessingContext context = StubProcessingContext.forMessage(message);
        assertTrue(handler.canHandle(message, context));

        var invocationResult = handler.handle(message, context, this);
        assertThat(invocationResult.hasNextAvailable()).isFalse();
        assertThat(invocationResult.isCompleted()).isTrue();
    }

    @Test
    void unspecifiedOptionalResponseTypeUnwrapped() {
        QueryHandlingMember<?> handler = messageHandler("unspecifiedOptionalType");
        assertEquals(Object.class, handler.resultType());
    }

    @Test
    void wildcardOptionalResponseTypeUnwrapped() {
        QueryHandlingMember<?> handler = messageHandler("wildcardOptionalType");
        assertEquals(Object.class, handler.resultType());
    }

    @Test
    void upperBoundWildcardOptionalResponseTypeUnwrapped() {
        QueryHandlingMember<?> handler = messageHandler("upperBoundWildcardOptionalType");
        assertEquals(CharSequence.class, handler.resultType());
    }

    private <R> QueryHandlingMember<R> messageHandler(String methodName) {
        try {
            MessageHandlingMember<MethodQueryHandlerDefinitionTest> handler = handlerDefinition.createHandler(
                    MethodQueryHandlerDefinitionTest.class,
                    MethodQueryHandlerDefinitionTest.class.getDeclaredMethod(methodName, String.class),
                    parameterResolver,
                    result -> resolveToStream(result, new ClassBasedMessageTypeResolver())
            ).orElseThrow(IllegalArgumentException::new);
            //noinspection unchecked
            return testSubject.wrapHandler(handler)
                              .unwrap(QueryHandlingMember.class)
                              .orElseThrow(() -> new RuntimeException("Method not recognized as Query Definition"));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Method used to build query handler does not exist", e);
        }
    }

    @SuppressWarnings("unused")
    @QueryHandler
    private void illegalQueryResponseType(String string) {
    }

    @SuppressWarnings("unused")
    @QueryHandler
    private CompletableFuture<String> futureReturnType(String string) {
        return null;
    }

    @SuppressWarnings("unused")
    @QueryHandler
    private Optional<String> optionalReturnType(String string) {
        return Optional.empty();
    }

    @SuppressWarnings({"unused", "rawtypes"})
    @QueryHandler
    private Optional unspecifiedOptionalType(String string) {
        return Optional.empty();
    }

    @SuppressWarnings("unused")
    @QueryHandler
    private Optional<?> wildcardOptionalType(String string) {
        return Optional.empty();
    }

    @SuppressWarnings("unused")
    @QueryHandler
    private Optional<? extends CharSequence> upperBoundWildcardOptionalType(String string) {
        return Optional.empty();
    }
}
