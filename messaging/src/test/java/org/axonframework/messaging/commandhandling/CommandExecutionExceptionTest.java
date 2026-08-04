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

import org.axonframework.conversion.ChainingContentTypeConverter;
import org.axonframework.conversion.Converter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Test class validating the {@link CommandExecutionException}.
 */
class CommandExecutionExceptionTest {

    @Test
    void constructorWithDetailsExposesThemUnconverted() {
        CommandExecutionException exception = new CommandExecutionException("message", null, "details");

        assertThat(exception.getDetails(String.class)).contains("details");
    }

    @Test
    void constructorWithConverterAppliesItLazilyOnTypeMismatch() {
        // given
        String stringDetails = "details";
        byte[] byteDetails = stringDetails.getBytes(StandardCharsets.UTF_8);
        Converter converter = spy(new ChainingContentTypeConverter());

        // when
        CommandExecutionException exception =
                new CommandExecutionException("message", null, byteDetails, converter, false);
        String result = exception.getDetails(String.class).orElse(null);

        // then
        assertThat(result).isEqualTo(stringDetails);
        verify(converter).convert(eq(byteDetails), eq((Type) String.class));
    }
}
