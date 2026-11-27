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

import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.junit.jupiter.api.*;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the {@link AnnotatedHandlerElement} and the creation of a
 * {@link MessageHandlingMember} per occurrence by the {@link AnnotatedMessageHandlingMemberDefinition}.
 *
 * @author Allard Buijze
 */
class AnnotatedHandlerElementTest {

    @Nested
    class Of {

        @Test
        void returnsTheMethodItselfForASingleHandlerAnnotation() throws NoSuchMethodException {
            // given
            Method method = Handlers.class.getDeclaredMethod("single", String.class);

            // when
            List<AnnotatedElement> result = AnnotatedHandlerElement.of(method);

            // then
            assertThat(result).containsExactly(method);
        }

        @Test
        void returnsAnOccurrencePerRepeatedHandlerAnnotationInDeclarationOrder() throws NoSuchMethodException {
            // given
            Method method = Handlers.class.getDeclaredMethod("repeated", String.class);

            // when
            List<AnnotatedElement> result = AnnotatedHandlerElement.of(method);

            // then
            assertThat(result).hasSize(2)
                              .allSatisfy(occurrence -> assertThat(occurrence)
                                      .isInstanceOf(AnnotatedHandlerElement.class));
            assertThat(result).extracting(occurrence -> occurrence.getAnnotation(EventHandler.class).name())
                              .containsExactly("First", "Second");
        }

        @Test
        void retainsTheOtherAnnotationsOfTheMethodOnEveryOccurrence() throws NoSuchMethodException {
            // given
            Method method = Handlers.class.getDeclaredMethod("repeated", String.class);

            // when
            List<AnnotatedElement> result = AnnotatedHandlerElement.of(method);

            // then
            assertThat(result).allSatisfy(occurrence -> {
                assertThat(occurrence.isAnnotationPresent(Marker.class)).isTrue();
                assertThat(occurrence.getAnnotationsByType(EventHandler.class)).hasSize(1);
            });
        }
    }

    @Nested
    class CreateHandlers {

        private final AnnotatedMessageHandlingMemberDefinition testSubject =
                new AnnotatedMessageHandlingMemberDefinition();

        @Test
        void createsAMemberPerRepeatedHandlerAnnotation() throws NoSuchMethodException {
            // given
            Method method = Handlers.class.getDeclaredMethod("repeated", String.class);

            // when
            List<MessageHandlingMember<Handlers>> result = createHandlers(method);

            // then
            assertThat(result).extracting(member -> member.<String>attribute("EventHandler.name").orElseThrow())
                              .containsExactly("First", "Second");
        }

        @Test
        void createsAMemberPerHandlerAnnotationRepeatedOnACustomAnnotation() throws NoSuchMethodException {
            // given
            Method method = Handlers.class.getDeclaredMethod("composed", String.class);

            // when
            List<MessageHandlingMember<Handlers>> result = createHandlers(method);

            // then
            assertThat(result).extracting(member -> member.<String>attribute("EventHandler.name").orElseThrow())
                              .containsExactly("First", "Second");
            assertThat(result).allSatisfy(member -> assertThat(member.<Boolean>attribute("Composed.enabled"))
                    .contains(true));
        }

        @Test
        void createsASingleMemberForASingleHandlerAnnotation() throws NoSuchMethodException {
            // given
            Method method = Handlers.class.getDeclaredMethod("single", String.class);

            // when
            List<MessageHandlingMember<Handlers>> result = createHandlers(method);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().unwrap(Method.class)).contains(method);
        }

        @Test
        void appliesThePayloadTypeOfEachOccurrence() throws NoSuchMethodException {
            // given
            Method method = Handlers.class.getDeclaredMethod("perOccurrencePayloadType", Object.class);

            // when
            List<MessageHandlingMember<Handlers>> result = createHandlers(method);

            // then
            List<Class<?>> payloadTypes = result.stream()
                                                .<Class<?>>map(MessageHandlingMember::payloadType)
                                                .toList();
            assertThat(payloadTypes).containsExactly(String.class, Integer.class);
        }

        private List<MessageHandlingMember<Handlers>> createHandlers(Method method) {
            return testSubject.createHandlers(Handlers.class,
                                              method,
                                              ClasspathParameterResolverFactory.forClass(Handlers.class),
                                              noStreamResolver());
        }

        private static Function<Object, MessageStream<?>> noStreamResolver() {
            return result -> MessageStream.empty();
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface Marker {

    }

    @Retention(RetentionPolicy.RUNTIME)
    @EventHandler(name = "First")
    @EventHandler(name = "Second")
    @interface Composed {

        boolean enabled() default true;
    }

    @SuppressWarnings("unused")
    static class Handlers {

        @Composed
        void composed(String event) {
        }

        @EventHandler(name = "Single")
        void single(String event) {
        }

        @Marker
        @EventHandler(name = "First")
        @EventHandler(name = "Second")
        void repeated(String event) {
        }

        @EventHandler(name = "Text", payloadType = String.class)
        @EventHandler(name = "Number", payloadType = Integer.class)
        void perOccurrencePayloadType(Object event) {
        }
    }
}
