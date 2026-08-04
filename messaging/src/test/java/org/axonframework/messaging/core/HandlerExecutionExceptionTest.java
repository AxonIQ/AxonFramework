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

import org.assertj.core.api.Assertions;
import org.axonframework.common.TypeReference;
import org.axonframework.conversion.ChainingContentTypeConverter;
import org.axonframework.conversion.ConversionException;
import org.axonframework.conversion.Converter;
import org.junit.jupiter.api.*;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link HandlerExecutionException}.
 */
class HandlerExecutionExceptionTest {

    @Test
    void resolveDetailsFromNestedExecutionException() {
        Exception exception = new RuntimeException(new StubHandlerExecutionException("test", null, "Details!"));

        assertEquals("Details!", HandlerExecutionException.resolveDetails(exception).orElse(null));
    }

    @Test
    void resolveDetailsFromExecutionException() {
        Exception exception = new StubHandlerExecutionException("test", null, "Details!");

        assertEquals("Details!", HandlerExecutionException.resolveDetails(exception).orElse(null));
    }

    @Test
    void resolveDetailsFromNull() {
        assertFalse(HandlerExecutionException.resolveDetails(null).isPresent());
    }

    @Test
    void resolveDetailsFromRuntimeException() {
        assertFalse(HandlerExecutionException.resolveDetails(new RuntimeException()).isPresent());
    }

    @Test
    void validatePresenceOfStackTraceWithWritableStackTraceSetting() {
        Exception exception = new StubHandlerExecutionException("Some message");
        assertEquals(0, exception.getStackTrace().length);

        exception = new StubHandlerExecutionException("Some message", new RuntimeException());
        assertEquals(0, exception.getStackTrace().length);

        exception = new StubHandlerExecutionException("Some message", new RuntimeException(), "Some details");
        assertEquals(0, exception.getStackTrace().length);

        exception = new StubHandlerExecutionException("Some message", new RuntimeException(), "Some details", false);
        assertEquals(0, exception.getStackTrace().length);

        exception = new StubHandlerExecutionException("Some message", new RuntimeException(), "Some details", true);
        assertTrue(exception.getStackTrace().length > 0);
    }

    @Nested
    class TypedDetails {

        private Converter converter;
        private final String exStringDetails = "details";
        private final byte[] exByteDetails = exStringDetails.getBytes(StandardCharsets.UTF_8);

        @BeforeEach
        void setUp() {
            converter = spy(new ChainingContentTypeConverter());
        }

        @Test
        void getDetailsClassReturnsDetailsWithoutConversionOnSameType() {
            StubHandlerExecutionException exception =
                    new StubHandlerExecutionException("test", null, exStringDetails, converter, false);

            String result = exception.getDetails(String.class).orElse(null);

            assertThat(result).isEqualTo(exStringDetails);
        }

        @Test
        void getDetailsClassInvokesConverterOnDifferentType() {
            StubHandlerExecutionException exception =
                    new StubHandlerExecutionException("test", null, exByteDetails, converter, false);

            String result = exception.getDetails(String.class).orElse(null);

            assertThat(result).isEqualTo(exStringDetails);
            verify(converter).convert(eq(exByteDetails), eq((Type) String.class));
        }

        @Test
        void getDetailsClassFailsWithConversionExceptionWithoutConverter() {
            StubHandlerExecutionException exception =
                    new StubHandlerExecutionException("test", null, exByteDetails);

            Assertions.assertThatThrownBy(() -> exception.getDetails(Integer.class))
                      .isInstanceOf(ConversionException.class);
        }

        @Test
        void getDetailsClassReturnsEmptyWhenNoDetailsPresent() {
            StubHandlerExecutionException exception = new StubHandlerExecutionException("test");

            assertThat(exception.getDetails(String.class)).isEmpty();
        }

        @Test
        void getDetailsTypeReferenceInvokesConverter() {
            StubHandlerExecutionException exception =
                    new StubHandlerExecutionException("test", null, exByteDetails, converter, false);

            String result = exception.getDetails(new TypeReference<String>() {
            }).orElse(null);

            assertThat(result).isEqualTo(exStringDetails);
            verify(converter).convert(eq(exByteDetails), eq((Type) String.class));
        }

        @Test
        void getDetailsTypeReferenceFailsWithConversionExceptionWithoutConverter() {
            StubHandlerExecutionException exception =
                    new StubHandlerExecutionException("test", null, exByteDetails);

            Assertions.assertThatThrownBy(() -> exception.getDetails(new TypeReference<Integer>() {
                      }))
                      .isInstanceOf(ConversionException.class);
        }
    }

    private static class StubHandlerExecutionException extends HandlerExecutionException {

        public StubHandlerExecutionException(String message) {
            super(message);
        }

        public StubHandlerExecutionException(String message, Throwable cause) {
            super(message, cause);
        }

        public StubHandlerExecutionException(String message, Throwable cause, Object details) {
            super(message, cause, details);
        }

        public StubHandlerExecutionException(String message,
                                             Throwable cause,
                                             Object details,
                                             boolean writableStackTrace) {
            super(message, cause, details, writableStackTrace);
        }

        public StubHandlerExecutionException(String message,
                                             Throwable cause,
                                             Object details,
                                             Converter converter,
                                             boolean writableStackTrace) {
            super(message, cause, details, converter, writableStackTrace);
        }
    }
}