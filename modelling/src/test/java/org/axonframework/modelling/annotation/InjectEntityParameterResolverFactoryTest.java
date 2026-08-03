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

import org.jspecify.annotations.Nullable;
import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.configuration.ComponentDefinition;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.common.configuration.DefaultComponentRegistry;
import org.axonframework.common.configuration.StubLifecycleRegistry;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.annotation.ParameterResolver;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.modelling.EntityIdResolver;
import org.axonframework.modelling.SimpleStateManager;
import org.axonframework.modelling.StateManager;
import org.axonframework.modelling.repository.EntityNotFoundException;
import org.axonframework.modelling.repository.ManagedEntity;
import org.axonframework.modelling.repository.SimpleRepositoryEntityLoader;
import org.junit.jupiter.api.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test class validating the {@link InjectEntityParameterResolverFactory} and the {@link InjectEntityParameterResolver}
 * it creates, in particular the resolution behavior for a missing entity: propagating an exception, resolving to
 * {@code null}, or resolving to an {@link Optional}.
 *
 * @author Steven van Beelen
 */
class InjectEntityParameterResolverFactoryTest {

    private static final String ENTITY_ID = "gift-card-42";

    @Test
    void returnsNullForParameterWithoutInjectEntityAnnotation() throws NoSuchMethodException {
        Method method = Handlers.class.getDeclaredMethod("notAnnotated", GiftCard.class);
        Configuration configuration = configurationWithStateManager(SimpleStateManager.named("unused"));

        ParameterResolver<?> resolver = new InjectEntityParameterResolverFactory(configuration)
                .createInstance(method, method.getParameters(), 0);

        assertThat(resolver).isNull();
    }

    @Nested
    class PlainEntityParameter {

        @Test
        void resolvesToTheEntityWhenFound() throws NoSuchMethodException {
            // given
            GiftCard entity = new GiftCard();
            Method method = Handlers.class.getDeclaredMethod("plainEntity", GiftCard.class);
            StateManager stateManager = stateManagerLoading(
                    (id, context) -> CompletableFuture.completedFuture(entity)
            );

            // when
            Object result = resolve(method, stateManager);

            // then
            assertThat(result).isSameAs(entity);
        }

        @Test
        void propagatesExceptionWhenMissingAndNotNullable() throws NoSuchMethodException {
            // given
            Method method = Handlers.class.getDeclaredMethod("plainEntity", GiftCard.class);
            StateManager stateManager = stateManagerLoading(
                    (id, context) -> CompletableFuture.failedFuture(new EntityNotFoundException(id))
            );

            // when & then
            assertThatThrownBy(() -> resolve(method, stateManager))
                    .isInstanceOf(CompletionException.class)
                    .cause()
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        void resolvesToNullWhenMissingAndNullable() throws NoSuchMethodException {
            // given
            Method method = Handlers.class.getDeclaredMethod("plainEntityNullable", GiftCard.class);
            StateManager stateManager = stateManagerLoading(
                    (id, context) -> CompletableFuture.failedFuture(new EntityNotFoundException(id))
            );

            // when
            Object result = resolve(method, stateManager);

            // then
            assertThat(result).isNull();
        }

        @Test
        void resolvesToNullWhenLoaderReturnsNullAndNullable() throws NoSuchMethodException {
            // given
            Method method = Handlers.class.getDeclaredMethod("plainEntityNullable", GiftCard.class);
            StateManager stateManager = stateManagerLoading((id, context) -> CompletableFuture.completedFuture(null));

            // when
            Object result = resolve(method, stateManager);

            // then
            assertThat(result).isNull();
        }
    }

    @Nested
    class ManagedEntityParameter {

        @Test
        void resolvesToTheManagedEntityWhenFound() throws NoSuchMethodException {
            // given
            GiftCard entity = new GiftCard();
            Method method = Handlers.class.getDeclaredMethod("managedEntity", ManagedEntity.class);
            StateManager stateManager = stateManagerLoading(
                    (id, context) -> CompletableFuture.completedFuture(entity)
            );

            // when
            ManagedEntity<?, ?> result = (ManagedEntity<?, ?>) resolve(method, stateManager);

            // then
            assertThat(result.entity()).isSameAs(entity);
        }

        @Test
        void propagatesExceptionWhenMissingAndNotNullable() throws NoSuchMethodException {
            // given
            Method method = Handlers.class.getDeclaredMethod("managedEntity", ManagedEntity.class);
            StateManager stateManager = stateManagerLoading(
                    (id, context) -> CompletableFuture.failedFuture(new EntityNotFoundException(id))
            );

            // when & then
            assertThatThrownBy(() -> resolve(method, stateManager))
                    .isInstanceOf(CompletionException.class)
                    .cause()
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        void resolvesToNullWhenMissingAndNullable() throws NoSuchMethodException {
            // given: this used to be ignored entirely - a @Nullable ManagedEntity parameter always propagated the
            // EntityNotFoundException regardless of nullability
            Method method = Handlers.class.getDeclaredMethod("managedEntityNullable", ManagedEntity.class);
            StateManager stateManager = stateManagerLoading(
                    (id, context) -> CompletableFuture.failedFuture(new EntityNotFoundException(id))
            );

            // when
            Object result = resolve(method, stateManager);

            // then
            assertThat(result).isNull();
        }
    }

    @Nested
    class OptionalEntityParameter {

        @Test
        void resolvesToOptionalOfEntityWhenFound() throws NoSuchMethodException {
            // given
            GiftCard entity = new GiftCard();
            Method method = Handlers.class.getDeclaredMethod("optionalEntity", Optional.class);
            StateManager stateManager = stateManagerLoading(
                    (id, context) -> CompletableFuture.completedFuture(entity)
            );

            // when
            Object result = resolve(method, stateManager);

            // then
            assertThat(result).isEqualTo(Optional.of(entity));
        }

        @Test
        void resolvesToEmptyOptionalWhenNotFound() throws NoSuchMethodException {
            // given
            Method method = Handlers.class.getDeclaredMethod("optionalEntity", Optional.class);
            StateManager stateManager = stateManagerLoading(
                    (id, context) -> CompletableFuture.failedFuture(new EntityNotFoundException(id))
            );

            // when
            Object result = resolve(method, stateManager);

            // then
            assertThat(result).isEqualTo(Optional.empty());
        }

        @Test
        void resolvesToEmptyOptionalWhenLoaderReturnsNull() throws NoSuchMethodException {
            // given
            Method method = Handlers.class.getDeclaredMethod("optionalEntity", Optional.class);
            StateManager stateManager = stateManagerLoading((id, context) -> CompletableFuture.completedFuture(null));

            // when
            Object result = resolve(method, stateManager);

            // then
            assertThat(result).isEqualTo(Optional.empty());
        }
    }

    @Nested
    class OptionalManagedEntityParameter {

        @Test
        void resolvesToOptionalOfManagedEntityWhenFound() throws NoSuchMethodException {
            // given
            GiftCard entity = new GiftCard();
            Method method = Handlers.class.getDeclaredMethod("optionalManagedEntity", Optional.class);
            StateManager stateManager = stateManagerLoading(
                    (id, context) -> CompletableFuture.completedFuture(entity)
            );

            // when
            @SuppressWarnings("unchecked")
            Optional<ManagedEntity<?, ?>> result = (Optional<ManagedEntity<?, ?>>) resolve(method, stateManager);

            // then
            assertThat(result).isPresent();
            assertThat(result.get().entity()).isSameAs(entity);
        }

        @Test
        void resolvesToEmptyOptionalWhenNotFound() throws NoSuchMethodException {
            // given
            Method method = Handlers.class.getDeclaredMethod("optionalManagedEntity", Optional.class);
            StateManager stateManager = stateManagerLoading(
                    (id, context) -> CompletableFuture.failedFuture(new EntityNotFoundException(id))
            );

            // when
            Object result = resolve(method, stateManager);

            // then
            assertThat(result).isEqualTo(Optional.empty());
        }

        @Test
        void resolvesToOptionalOfManagedEntityWithNullStateWhenLoaderReturnsNull() throws NoSuchMethodException {
            // given: the create-or-update pattern relies on the ManagedEntity wrapper itself never being empty, only
            // its wrapped state, so the handler can still call applyStateChange(...) on it
            Method method = Handlers.class.getDeclaredMethod("optionalManagedEntity", Optional.class);
            StateManager stateManager = stateManagerLoading((id, context) -> CompletableFuture.completedFuture(null));

            // when
            @SuppressWarnings("unchecked")
            Optional<ManagedEntity<?, ?>> result = (Optional<ManagedEntity<?, ?>>) resolve(method, stateManager);

            // then
            assertThat(result).isPresent();
            assertThat(result.get().entity()).isNull();
        }
    }

    @Nested
    class RedundantNullableOnOptionalParameter {

        @Test
        void resolvesToEmptyOptionalInsteadOfNullWhenMissing() throws NoSuchMethodException {
            // given
            Method method =
                    Handlers.class.getDeclaredMethod("optionalEntityWithRedundantNullable", Optional.class);
            StateManager stateManager = stateManagerLoading(
                    (id, context) -> CompletableFuture.failedFuture(new EntityNotFoundException(id))
            );

            // when
            Object result = resolve(method, stateManager);

            // then
            assertThat(result).isEqualTo(Optional.empty());
        }
    }

    @Nested
    class DeclarationOnlyNullableParameter {

        @Test
        void resolvesToNullWhenMissingAndDeclarationOnlyNullable() throws NoSuchMethodException {
            // given: a JSR-305-style @Nullable, declaration-only (no TYPE_USE target), unlike jspecify's @Nullable
            Method method =
                    DeclarationOnlyNullableHandlers.class.getDeclaredMethod("plainEntityNullable", GiftCard.class);
            StateManager stateManager = stateManagerLoading(
                    (id, context) -> CompletableFuture.failedFuture(new EntityNotFoundException(id))
            );

            // when
            Object result = resolve(method, stateManager);

            // then
            assertThat(result).isNull();
        }
    }

    @Nested
    class ContributedNullabilityResolver {

        @Test
        void anUnannotatedParameterResolvesToNullWhenAResolverReportsItNullable() throws NoSuchMethodException {
            // given a parameter carrying no @Nullable annotation and no Optional, which a registered
            // NullabilityResolver reports as nullable, exactly as the Kotlin extension does for 'MyEntity?'
            Method method = Handlers.class.getDeclaredMethod(
                    StubNullabilityResolvers.NULLABLE_MARKER, GiftCard.class
            );
            StateManager stateManager = stateManagerLoading(
                    (id, context) -> CompletableFuture.failedFuture(new EntityNotFoundException(id))
            );

            // when
            Object result = resolve(method, stateManager);

            // then the missing entity resolves to null rather than failing the message
            assertThat(result).isNull();
        }

        @Test
        void theHighestPriorityResolverWithAnOpinionDecides() throws NoSuchMethodException {
            // given a parameter both stubs answer for, with conflicting answers
            Method method = Handlers.class.getDeclaredMethod(
                    StubNullabilityResolvers.CONTESTED_MARKER, GiftCard.class
            );
            StateManager stateManager = stateManagerLoading(
                    (id, context) -> CompletableFuture.failedFuture(new EntityNotFoundException(id))
            );

            // when / then the @Priority(HIGH) stub wins, so the parameter is nullable rather than failing
            assertThat(resolve(method, stateManager)).isNull();
        }

        @Test
        void aResolverThatCannotBeInstantiatedIsSkippedRatherThanFailingTheLookup() throws NoSuchMethodException {
            // given: StubNullabilityResolvers.UninstantiableStub is registered and throws from its constructor,
            // standing in for a resolver whose optional dependency is absent
            Method method = Handlers.class.getDeclaredMethod(
                    StubNullabilityResolvers.NULLABLE_MARKER, GiftCard.class
            );
            StateManager stateManager = stateManagerLoading(
                    (id, context) -> CompletableFuture.failedFuture(new EntityNotFoundException(id))
            );

            // when / then resolution still completes using the resolvers that did load
            assertThat(resolve(method, stateManager)).isNull();
        }

        @Test
        void anUnannotatedParameterNoResolverAnswersForStillFails() throws NoSuchMethodException {
            // given the same shape of parameter, on a method the stub resolver does not answer for
            Method method = Handlers.class.getDeclaredMethod("plainEntity", GiftCard.class);
            StateManager stateManager = stateManagerLoading(
                    (id, context) -> CompletableFuture.failedFuture(new EntityNotFoundException(id))
            );

            // when & then
            assertThatThrownBy(() -> resolve(method, stateManager))
                    .isInstanceOf(CompletionException.class)
                    .cause()
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    @Nested
    class Misconfiguration {

        @Test
        void rejectsRawOptionalParameter() throws NoSuchMethodException {
            // given
            Method method = Handlers.class.getDeclaredMethod("rawOptional", Optional.class);
            Configuration configuration = configurationWithStateManager(SimpleStateManager.named("unused"));

            // when & then
            assertThatThrownBy(() -> new InjectEntityParameterResolverFactory(configuration)
                    .createInstance(method, method.getParameters(), 0))
                    .isInstanceOf(AxonConfigurationException.class);
        }

        @Test
        void rejectsRawManagedEntityParameter() throws NoSuchMethodException {
            // given
            Method method = Handlers.class.getDeclaredMethod("rawManagedEntity", ManagedEntity.class);
            Configuration configuration = configurationWithStateManager(SimpleStateManager.named("unused"));

            // when & then
            assertThatThrownBy(() -> new InjectEntityParameterResolverFactory(configuration)
                    .createInstance(method, method.getParameters(), 0))
                    .isInstanceOf(AxonConfigurationException.class);
        }
    }

    private static Configuration configurationWithStateManager(StateManager stateManager) {
        DefaultComponentRegistry registry = new DefaultComponentRegistry();
        registry.disableEnhancerScanning()
                .registerComponent(ComponentDefinition.ofType(StateManager.class).withInstance(stateManager));
        return registry.build(new StubLifecycleRegistry());
    }

    private static StateManager stateManagerLoading(SimpleRepositoryEntityLoader<String, GiftCard> loader) {
        return SimpleStateManager.named("test")
                                 .register(String.class,
                                           GiftCard.class,
                                           loader,
                                           (id, entity, context) -> CompletableFuture.completedFuture(null));
    }

    private static Object resolve(Method method, StateManager stateManager) {
        Configuration configuration = configurationWithStateManager(stateManager);
        ParameterResolver<?> resolver =
                new InjectEntityParameterResolverFactory(configuration).createInstance(method,
                                                                                        method.getParameters(),
                                                                                        0);
        Message message = new GenericCommandMessage(new MessageType("test-command"), new Object());
        ProcessingContext context = StubProcessingContext.forMessage(message);
        return resolver.resolveParameterValue(context).join();
    }

    private static class GiftCard {

    }

    static class FixedIdResolver implements EntityIdResolver<String> {

        @Override
        public String resolve(Message message, ProcessingContext context) {
            return ENTITY_ID;
        }
    }

    @SuppressWarnings({"OptionalUsedAsFieldOrParameterType", "rawtypes", "unused"})
    private static class Handlers {

        void plainEntity(@InjectEntity(idResolver = FixedIdResolver.class) GiftCard card) {
        }

        void resolvedNullableByStubResolver(@InjectEntity(idResolver = FixedIdResolver.class) GiftCard card) {
        }

        void contestedByStubResolvers(@InjectEntity(idResolver = FixedIdResolver.class) GiftCard card) {
        }

        void plainEntityNullable(@InjectEntity(idResolver = FixedIdResolver.class) @Nullable GiftCard card) {
        }

        void managedEntity(
                @InjectEntity(idResolver = FixedIdResolver.class) ManagedEntity<String, GiftCard> card
        ) {
        }

        void managedEntityNullable(
                @InjectEntity(idResolver = FixedIdResolver.class) @Nullable ManagedEntity<String, GiftCard> card
        ) {
        }

        void optionalEntity(@InjectEntity(idResolver = FixedIdResolver.class) Optional<GiftCard> card) {
        }

        void optionalManagedEntity(
                @InjectEntity(idResolver = FixedIdResolver.class) Optional<ManagedEntity<String, GiftCard>> card
        ) {
        }

        void optionalEntityWithRedundantNullable(
                @InjectEntity(idResolver = FixedIdResolver.class) @Nullable Optional<GiftCard> card
        ) {
        }

        void rawOptional(@InjectEntity(idResolver = FixedIdResolver.class) Optional card) {
        }

        void rawManagedEntity(@InjectEntity(idResolver = FixedIdResolver.class) ManagedEntity card) {
        }

        void notAnnotated(GiftCard card) {
        }
    }

    @SuppressWarnings("unused")
    private static class DeclarationOnlyNullableHandlers {

        void plainEntityNullable(@Nullable @InjectEntity(idResolver = FixedIdResolver.class) GiftCard card) {
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.PARAMETER)
        @interface Nullable {

        }
    }
}
