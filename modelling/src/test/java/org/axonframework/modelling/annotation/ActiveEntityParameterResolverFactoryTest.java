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

package org.axonframework.modelling.annotation;

import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.ParameterResolver;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class validating the {@link ActiveEntityParameterResolverFactory}: which parameters it resolves to the active
 * entity state, which it leaves to other resolvers, and that it is registered like any other resolver factory.
 *
 * @author Mateusz Nowak
 */
class ActiveEntityParameterResolverFactoryTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    private final ActiveEntityParameterResolverFactory testSubject = new ActiveEntityParameterResolverFactory();

    private record Happened() {

    }

    @SuppressWarnings("unused")
    private static class Shape {

        @EventHandler
        static Shape onHappened(Happened event, @Nullable Shape state) {
            return state;
        }

        @EventHandler
        static Circle onHappenedWithSubtype(Happened event, @Nullable Circle state) {
            return state;
        }

        @EventHandler
        static Shape onHappenedWithEventFirstOnly(Shape event) {
            return event;
        }

        @EventHandler
        static Shape onHappenedWithUnrelatedParameter(Happened event, String unrelated) {
            return null;
        }

        @EventHandler
        static Shape onHappenedWithObjectParameter(Happened event, Object anything) {
            return null;
        }

        @EventHandler
        static Shape onHappenedWithMessageParameter(Happened event, EventMessage message) {
            return null;
        }

        @EventHandler
        Shape onHappenedAsInstance(Happened event, Shape other) {
            return other;
        }

        static Shape notAHandler(Happened event, Shape state) {
            return state;
        }
    }

    private static class Circle extends Shape {

    }

    private static Method method(String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        return Shape.class.getDeclaredMethod(name, parameterTypes);
    }

    private ParameterResolver<?> resolverFor(Method method, int parameterIndex) {
        return testSubject.createInstance(method, method.getParameters(), parameterIndex);
    }

    @Nested
    class ResolvedParameters {

        @Test
        void resolvesEntityTypedParameterAfterThePayloadOfStaticEventHandler() throws Exception {
            // given
            Method handler = method("onHappened", Happened.class, Shape.class);

            // when / then
            assertThat(resolverFor(handler, 1)).isNotNull();
        }

        @Test
        void resolvesParameterTypedAsSubtypeOfTheDeclaringEntity() throws Exception {
            // given
            Method handler = method("onHappenedWithSubtype", Happened.class, Circle.class);

            // when / then
            assertThat(resolverFor(handler, 1)).isNotNull();
        }

        @Test
        void resolvesTheActiveEntityWhileItIsBeingEvolved() throws Exception {
            // given
            Shape active = new Shape();
            ProcessingContext context = ActiveEntity.set(new StubProcessingContext(), active);
            ParameterResolver<?> resolver = resolverFor(method("onHappened", Happened.class, Shape.class), 1);

            // when / then
            assertThat(resolver.matches(context)).isTrue();
            assertThat(resolver.resolveParameterValue(context)).succeedsWithin(TIMEOUT).isEqualTo(active);
        }

        @Test
        void resolvesNullWhileTheEntityDoesNotExistYet() throws Exception {
            // given
            ProcessingContext context = ActiveEntity.set(new StubProcessingContext(), null);
            ParameterResolver<?> resolver = resolverFor(method("onHappened", Happened.class, Shape.class), 1);

            // when / then
            assertThat(resolver.matches(context)).isTrue();
            assertThat(resolver.resolveParameterValue(context)).succeedsWithin(TIMEOUT).isNull();
        }

        @Test
        void doesNotMatchOutsideOfEntityEvolution() throws Exception {
            // given
            ParameterResolver<?> resolver = resolverFor(method("onHappened", Happened.class, Shape.class), 1);

            // when / then
            assertThat(resolver.matches(new StubProcessingContext())).isFalse();
        }
    }

    @Nested
    class IgnoredParameters {

        @Test
        void ignoresThePayloadParameter() throws Exception {
            assertThat(resolverFor(method("onHappenedWithEventFirstOnly", Shape.class), 0)).isNull();
        }

        @Test
        void ignoresParameterOfUnrelatedType() throws Exception {
            assertThat(resolverFor(method("onHappenedWithUnrelatedParameter", Happened.class, String.class), 1))
                    .isNull();
        }

        @Test
        void ignoresObjectTypedParameter() throws Exception {
            assertThat(resolverFor(method("onHappenedWithObjectParameter", Happened.class, Object.class), 1))
                    .isNull();
        }

        @Test
        void ignoresMessageTypedParameter() throws Exception {
            assertThat(resolverFor(method("onHappenedWithMessageParameter", Happened.class, EventMessage.class), 1))
                    .isNull();
        }

        @Test
        void ignoresInstanceEventHandlers() throws Exception {
            assertThat(resolverFor(method("onHappenedAsInstance", Happened.class, Shape.class), 1)).isNull();
        }

        @Test
        void ignoresStaticMethodsThatAreNoEventHandlers() throws Exception {
            assertThat(resolverFor(method("notAHandler", Happened.class, Shape.class), 1)).isNull();
        }
    }

    @Nested
    class Registration {

        @Test
        void isRegisteredLikeAnyOtherParameterResolverFactory() throws Exception {
            // given the resolver factories discovered on the classpath
            var classpathFactories = ClasspathParameterResolverFactory.forClass(Shape.class);
            Method handler = method("onHappened", Happened.class, Shape.class);

            // when
            ParameterResolver<?> resolver = classpathFactories.createInstance(handler, handler.getParameters(), 1);

            // then the entity-typed parameter is resolved to the active entity
            Shape active = new Shape();
            ProcessingContext context = ActiveEntity.set(new StubProcessingContext(), active);
            assertThat(resolver).isNotNull();
            assertThat(resolver.resolveParameterValue(context)).succeedsWithin(TIMEOUT).isEqualTo(active);
        }
    }
}
