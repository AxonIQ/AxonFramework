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

package org.axonframework.messaging.core.annotation;

import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.axonframework.messaging.core.annotation.MessageStreamResolverUtils.resolveToSingleStream;
import static org.axonframework.messaging.core.annotation.MessageStreamResolverUtils.resolveToStream;

/**
 * Test class validating how {@link MessageStreamResolverUtils} maps a handler's return value onto a
 * {@link MessageStream}, for handlers that may produce several results as well as for handlers that produce exactly
 * one.
 *
 * @author Mitchell Herrijgers
 */
class MessageStreamResolverUtilsTest {

    private static final List<String> ELEMENTS = List.of("first", "second", "third");

    private final MessageTypeResolver typeResolver = new ClassBasedMessageTypeResolver();

    @Nested
    class SeveralResultsExpected {

        @Test
        void collectionIsSpreadOverOneMessagePerElement() {
            // when
            List<Object> payloads = payloadsOf(resolveToStream(ELEMENTS, typeResolver));

            // then
            assertThat(payloads).isEqualTo(ELEMENTS);
        }

        @Test
        void collectionInsideFutureIsSpreadOverOneMessagePerElement() {
            // when
            List<Object> payloads = payloadsOf(resolveToStream(completedFuture(ELEMENTS), typeResolver));

            // then
            assertThat(payloads).isEqualTo(ELEMENTS);
        }

        @Test
        void collectionInsideOptionalIsSpreadOverOneMessagePerElement() {
            // when
            List<Object> payloads = payloadsOf(resolveToStream(Optional.of(ELEMENTS), typeResolver));

            // then
            assertThat(payloads).isEqualTo(ELEMENTS);
        }

        @Test
        void collectionInsideOptionalInsideFutureIsSpreadOverOneMessagePerElement() {
            // when
            List<Object> payloads = payloadsOf(resolveToStream(completedFuture(Optional.of(ELEMENTS)), typeResolver));

            // then
            assertThat(payloads).isEqualTo(ELEMENTS);
        }

        @Test
        void streamIsSpreadOverOneMessagePerElement() {
            // when
            List<Object> payloads = payloadsOf(resolveToStream(completedFuture(ELEMENTS.stream()), typeResolver));

            // then
            assertThat(payloads).isEqualTo(ELEMENTS);
        }

        @Test
        void nonCollectionInsideFutureBecomesASingleMessage() {
            // when
            List<Object> payloads = payloadsOf(resolveToStream(completedFuture("only"), typeResolver));

            // then
            assertThat(payloads).containsExactly("only");
        }

        @Test
        void emptyResultsYieldNoMessages() {
            // when / then
            assertThat(payloadsOf(resolveToStream(null, typeResolver))).isEmpty();
            assertThat(payloadsOf(resolveToStream(completedFuture(null), typeResolver))).isEmpty();
            assertThat(payloadsOf(resolveToStream(Optional.empty(), typeResolver))).isEmpty();
            assertThat(payloadsOf(resolveToStream(completedFuture(Optional.empty()), typeResolver))).isEmpty();
            assertThat(payloadsOf(resolveToStream(List.of(), typeResolver))).isEmpty();
        }
    }

    @Nested
    class SingleResultExpected {

        @Test
        void collectionBecomesThePayloadOfASingleMessage() {
            // when
            List<Object> payloads = payloadsOf(resolveToSingleStream(ELEMENTS, typeResolver));

            // then
            assertThat(payloads).containsExactly(ELEMENTS);
        }

        @Test
        void collectionInsideFutureBecomesThePayloadOfASingleMessage() {
            // when
            List<Object> payloads = payloadsOf(resolveToSingleStream(completedFuture(ELEMENTS), typeResolver));

            // then
            assertThat(payloads).containsExactly(ELEMENTS);
        }

        @Test
        void collectionInsideOptionalBecomesThePayloadOfASingleMessage() {
            // when
            List<Object> payloads = payloadsOf(resolveToSingleStream(Optional.of(ELEMENTS), typeResolver));

            // then
            assertThat(payloads).containsExactly(ELEMENTS);
        }

        @Test
        void collectionInsideOptionalInsideFutureBecomesThePayloadOfASingleMessage() {
            // when
            List<Object> payloads =
                    payloadsOf(resolveToSingleStream(completedFuture(Optional.of(ELEMENTS)), typeResolver));

            // then
            assertThat(payloads).containsExactly(ELEMENTS);
        }

        @Test
        void nonCollectionInsideFutureBecomesASingleMessage() {
            // when
            List<Object> payloads = payloadsOf(resolveToSingleStream(completedFuture("only"), typeResolver));

            // then
            assertThat(payloads).containsExactly("only");
        }

        @Test
        void emptyResultsYieldNoMessages() {
            // when / then
            assertThat(payloadsOf(resolveToSingleStream(null, typeResolver))).isEmpty();
            assertThat(payloadsOf(resolveToSingleStream(completedFuture(null), typeResolver))).isEmpty();
            assertThat(payloadsOf(resolveToSingleStream(Optional.empty(), typeResolver))).isEmpty();
            assertThat(payloadsOf(resolveToSingleStream(completedFuture(Optional.empty()), typeResolver))).isEmpty();
        }
    }

    private static <T> CompletableFuture<T> completedFuture(@Nullable T value) {
        return CompletableFuture.completedFuture(value);
    }

    private static List<Object> payloadsOf(MessageStream<?> stream) {
        return stream.<List<Object>>collect(ArrayList::new, (payloads, message) -> payloads.add(message.payload()))
                     .orTimeout(5, TimeUnit.SECONDS)
                     .join();
    }
}
