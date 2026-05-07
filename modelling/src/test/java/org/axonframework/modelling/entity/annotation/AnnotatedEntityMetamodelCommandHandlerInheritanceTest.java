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

import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.messaging.commandhandling.DuplicateCommandHandlerSubscriptionException;
import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.MultiParameterResolverFactory;
import org.axonframework.messaging.core.annotation.SimpleResourceParameterResolverFactory;
import org.axonframework.messaging.core.conversion.DelegatingMessageConverter;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.modelling.entity.annotation.inheritance.SubtypePackagePrivateBasePackagePrivate;
import org.axonframework.modelling.entity.annotation.inheritance.SubtypePackagePrivateOtherPackagePrivate;
import org.axonframework.modelling.entity.annotation.inheritance.SubtypePackagePrivateOtherPrivate;
import org.axonframework.modelling.entity.annotation.inheritance.SubtypePrivateBasePrivate;
import org.axonframework.modelling.entity.annotation.inheritance.SubtypePrivateOtherPackagePrivate;
import org.axonframework.modelling.entity.annotation.inheritance.SubtypeProtectedBasePackagePrivate;
import org.axonframework.modelling.entity.annotation.inheritance.SubtypeProtectedBasePrivate;
import org.axonframework.modelling.entity.annotation.inheritance.SubtypeProtectedBaseProtected;
import org.axonframework.modelling.entity.annotation.inheritance.SubtypeProtectedOtherPackagePrivate;
import org.axonframework.modelling.entity.annotation.inheritance.SubtypePublicBasePackagePrivate;
import org.axonframework.modelling.entity.annotation.inheritance.SubtypePublicBasePrivate;
import org.axonframework.modelling.entity.annotation.inheritance.SubtypePublicBaseProtected;
import org.axonframework.modelling.entity.annotation.inheritance.SubtypePublicBasePublic;
import org.axonframework.modelling.entity.annotation.inheritance.SubtypePublicOtherPackagePrivate;
import org.axonframework.modelling.entity.domain.todo.commands.CreateTodoItem;
import org.axonframework.modelling.entity.domain.todo.commands.FinishTodoItem;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests the {@link AnnotatedEntityMetamodel} command handler inheritance behavior: when a subtype overrides a
 * {@link org.axonframework.messaging.commandhandling.annotation.CommandHandler}-annotated method from a parent class,
 * the subtype's handler must take priority and the parent handler must not be registered as a duplicate. The tests
 * cover all combinations of method visibility (public, protected, package-private, private) in both the base class and
 * the overriding subtype.
 *
 * @author Jakob Hatzl
 */
class AnnotatedEntityMetamodelCommandHandlerInheritanceTest {

    /**
     * Test that for all acceptable cases (supertype handler method visible to subtype) building the metamodel does not
     * throw any error.
     *
     * @param entityType the entity type fixture to test
     */
    @ParameterizedTest
    @ValueSource(classes = {
            SubtypePublicBasePublic.class,
            SubtypePublicBaseProtected.class,
            SubtypeProtectedBaseProtected.class,
            SubtypePublicBasePackagePrivate.class,
            SubtypeProtectedBasePackagePrivate.class,
            SubtypePackagePrivateBasePackagePrivate.class,
    })
    void buildingMetamodelDoesNotThrow(Class<?> entityType) {
        MultiParameterResolverFactory parameterResolverFactory = new MultiParameterResolverFactory(
                ClasspathParameterResolverFactory.forClass(
                        getClass()),
                new SimpleResourceParameterResolverFactory(Set.of())
        );

        assertThatCode(() -> AnnotatedEntityMetamodel.forConcreteType(
                entityType,
                parameterResolverFactory,
                new ClassBasedMessageTypeResolver(),
                new DelegatingMessageConverter(new JacksonConverter()),
                new DelegatingEventConverter(new JacksonConverter())
        )).doesNotThrowAnyException();
    }

    /**
     * Test that for all invalid cases (supertype handler method not visible to subtype) building the metamodel does
     * throw an error.
     * <p>
     * TODO enable when implementing <a href="https://github.com/AxonIQ/AxonFramework/issues/4561">#4561</a>
     * @param entityType the entity type fixture to test
     */
    @ParameterizedTest
    @ValueSource(classes = {
            SubtypePublicOtherPackagePrivate.class,
            SubtypePackagePrivateOtherPackagePrivate.class,
            SubtypeProtectedOtherPackagePrivate.class,
            SubtypePrivateOtherPackagePrivate.class,
            SubtypePublicBasePrivate.class,
            SubtypePackagePrivateBasePackagePrivate.class,
            SubtypePackagePrivateOtherPrivate.class,
            SubtypeProtectedBasePrivate.class,
            SubtypePrivateBasePrivate.class
    })
    @Disabled("TODO enable with #4561 - currently not covered by AnnotatedEntityMetamodel#initializeDetectedHandlers")
    void buildingMetamodelThrows(Class<?> entityType) {
        MultiParameterResolverFactory parameterResolverFactory = new MultiParameterResolverFactory(
                ClasspathParameterResolverFactory.forClass(
                        getClass()),
                new SimpleResourceParameterResolverFactory(Set.of())
        );

        assertThatThrownBy(() -> AnnotatedEntityMetamodel.forConcreteType(
                entityType,
                parameterResolverFactory,
                new ClassBasedMessageTypeResolver(),
                new DelegatingMessageConverter(new JacksonConverter()),
                new DelegatingEventConverter(new JacksonConverter())
        )).isInstanceOf(DuplicateCommandHandlerSubscriptionException.class);
    }

    /**
     * Verifies override semantics when the subtype handler is {@code public} and the base handler it overrides is also
     * {@code public}.
     */
    @Nested
    class SubtypeOverridesForSubtypePublicBasePublic
            extends AbstractAnnotatedEntityMetamodelTest<SubtypePublicBasePublic> {

        @Override
        protected AnnotatedEntityMetamodel<SubtypePublicBasePublic> getMetamodel() {
            return AnnotatedEntityMetamodel.forConcreteType(
                    SubtypePublicBasePublic.class,
                    parameterResolverFactory,
                    messageTypeResolver,
                    messageConverter,
                    eventConverter
            );
        }

        @Test
        void subtypeHandlerIsInvoked() {
            // given
            entityState = new SubtypePublicBasePublic();

            // when
            dispatchInstanceCommand(new CreateTodoItem("id-1", "desc"));

            // then — subtype sets description to a prefixed value, parent would set it plain
            assertThat(entityState.getHandledBy()).isEqualTo("subtype");
        }

        @Test
        void parentHandlerIsUsedForCommandsNotOverridden() {
            // given
            entityState = new SubtypePublicBasePublic();
            dispatchInstanceCommand(new CreateTodoItem("af-5", "desc"));

            // when
            dispatchInstanceCommand(new FinishTodoItem("af-5"));

            // then — FinishTodoItem is only handled by the parent; completed flag proves it ran
            assertThat(entityState.isFinished()).isTrue();
            assertThat(entityState.getHandledBy()).isEqualTo("parent");
        }
    }


    /**
     * Verifies override semantics when the subtype handler is {@code public} and the base handler it overrides is
     * {@code protected}.
     */
    @Nested
    class SubtypeOverridesForSubtypePublicBaseProtected
            extends AbstractAnnotatedEntityMetamodelTest<SubtypePublicBaseProtected> {

        @Override
        protected AnnotatedEntityMetamodel<SubtypePublicBaseProtected> getMetamodel() {
            return AnnotatedEntityMetamodel.forConcreteType(
                    SubtypePublicBaseProtected.class,
                    parameterResolverFactory,
                    messageTypeResolver,
                    messageConverter,
                    eventConverter
            );
        }

        @Test
        void subtypeHandlerIsInvoked() {
            // given
            entityState = new SubtypePublicBaseProtected();

            // when
            dispatchInstanceCommand(new CreateTodoItem("id-1", "desc"));

            // then — subtype sets description to a prefixed value, parent would set it plain
            assertThat(entityState.getHandledBy()).isEqualTo("subtype");
        }

        @Test
        void parentHandlerIsUsedForCommandsNotOverridden() {
            // given
            entityState = new SubtypePublicBaseProtected();
            dispatchInstanceCommand(new CreateTodoItem("af-5", "desc"));

            // when
            dispatchInstanceCommand(new FinishTodoItem("af-5"));

            // then — FinishTodoItem is only handled by the parent; completed flag proves it ran
            assertThat(entityState.isFinished()).isTrue();
            assertThat(entityState.getHandledBy()).isEqualTo("parent");
        }
    }

    /**
     * Verifies override semantics when the subtype handler is {@code protected} and the base handler it overrides is
     * also {@code protected}.
     */
    @Nested
    class SubtypeOverridesForSubtypeProtectedBaseProtected
            extends AbstractAnnotatedEntityMetamodelTest<SubtypeProtectedBaseProtected> {

        @Override
        protected AnnotatedEntityMetamodel<SubtypeProtectedBaseProtected> getMetamodel() {
            return AnnotatedEntityMetamodel.forConcreteType(
                    SubtypeProtectedBaseProtected.class,
                    parameterResolverFactory,
                    messageTypeResolver,
                    messageConverter,
                    eventConverter
            );
        }

        @Test
        void subtypeHandlerIsInvoked() {
            // given
            entityState = new SubtypeProtectedBaseProtected();

            // when
            dispatchInstanceCommand(new CreateTodoItem("id-1", "desc"));

            // then — subtype sets description to a prefixed value, parent would set it plain
            assertThat(entityState.getHandledBy()).isEqualTo("subtype");
        }

        @Test
        void parentHandlerIsUsedForCommandsNotOverridden() {
            // given
            entityState = new SubtypeProtectedBaseProtected();
            dispatchInstanceCommand(new CreateTodoItem("af-5", "desc"));

            // when
            dispatchInstanceCommand(new FinishTodoItem("af-5"));

            // then — FinishTodoItem is only handled by the parent; completed flag proves it ran
            assertThat(entityState.isFinished()).isTrue();
            assertThat(entityState.getHandledBy()).isEqualTo("parent");
        }
    }

    /**
     * Verifies override semantics when the subtype handler is {@code public} and the base handler it overrides is
     * package-private.
     */
    @Nested
    class SubtypeOverridesForSubtypePublicBasePackagePrivate
            extends AbstractAnnotatedEntityMetamodelTest<SubtypePublicBasePackagePrivate> {

        @Override
        protected AnnotatedEntityMetamodel<SubtypePublicBasePackagePrivate> getMetamodel() {
            return AnnotatedEntityMetamodel.forConcreteType(
                    SubtypePublicBasePackagePrivate.class,
                    parameterResolverFactory,
                    messageTypeResolver,
                    messageConverter,
                    eventConverter
            );
        }

        @Test
        void subtypeHandlerIsInvoked() {
            // given
            entityState = new SubtypePublicBasePackagePrivate();

            // when
            dispatchInstanceCommand(new CreateTodoItem("id-1", "desc"));

            // then — subtype sets description to a prefixed value, parent would set it plain
            assertThat(entityState.getHandledBy()).isEqualTo("subtype");
        }

        @Test
        void parentHandlerIsUsedForCommandsNotOverridden() {
            // given
            entityState = new SubtypePublicBasePackagePrivate();
            dispatchInstanceCommand(new CreateTodoItem("af-5", "desc"));

            // when
            dispatchInstanceCommand(new FinishTodoItem("af-5"));

            // then — FinishTodoItem is only handled by the parent; completed flag proves it ran
            assertThat(entityState.isFinished()).isTrue();
            assertThat(entityState.getHandledBy()).isEqualTo("parent");
        }
    }

    /**
     * Verifies override semantics when the subtype handler is {@code protected} and the base handler it overrides is
     * package-private.
     */
    @Nested
    class SubtypeOverridesForSubtypeProtectedBasePackagePrivate
            extends AbstractAnnotatedEntityMetamodelTest<SubtypeProtectedBasePackagePrivate> {

        @Override
        protected AnnotatedEntityMetamodel<SubtypeProtectedBasePackagePrivate> getMetamodel() {
            return AnnotatedEntityMetamodel.forConcreteType(
                    SubtypeProtectedBasePackagePrivate.class,
                    parameterResolverFactory,
                    messageTypeResolver,
                    messageConverter,
                    eventConverter
            );
        }

        @Test
        void subtypeHandlerIsInvoked() {
            // given
            entityState = new SubtypeProtectedBasePackagePrivate();

            // when
            dispatchInstanceCommand(new CreateTodoItem("id-1", "desc"));

            // then — subtype sets description to a prefixed value, parent would set it plain
            assertThat(entityState.getHandledBy()).isEqualTo("subtype");
        }

        @Test
        void parentHandlerIsUsedForCommandsNotOverridden() {
            // given
            entityState = new SubtypeProtectedBasePackagePrivate();
            dispatchInstanceCommand(new CreateTodoItem("af-5", "desc"));

            // when
            dispatchInstanceCommand(new FinishTodoItem("af-5"));

            // then — FinishTodoItem is only handled by the parent; completed flag proves it ran
            assertThat(entityState.isFinished()).isTrue();
            assertThat(entityState.getHandledBy()).isEqualTo("parent");
        }
    }

    /**
     * Verifies override semantics when both the subtype handler and the base handler it overrides are package-private.
     */
    @Nested
    class SubtypeOverridesForSubtypePackagePrivateBasePackagePrivate
            extends AbstractAnnotatedEntityMetamodelTest<SubtypePackagePrivateBasePackagePrivate> {

        @Override
        protected AnnotatedEntityMetamodel<SubtypePackagePrivateBasePackagePrivate> getMetamodel() {
            return AnnotatedEntityMetamodel.forConcreteType(
                    SubtypePackagePrivateBasePackagePrivate.class,
                    parameterResolverFactory,
                    messageTypeResolver,
                    messageConverter,
                    eventConverter
            );
        }

        @Test
        void subtypeHandlerIsInvoked() {
            // given
            entityState = new SubtypePackagePrivateBasePackagePrivate();

            // when
            dispatchInstanceCommand(new CreateTodoItem("id-1", "desc"));

            // then — subtype sets description to a prefixed value, parent would set it plain
            assertThat(entityState.getHandledBy()).isEqualTo("subtype");
        }

        @Test
        void parentHandlerIsUsedForCommandsNotOverridden() {
            // given
            entityState = new SubtypePackagePrivateBasePackagePrivate();
            dispatchInstanceCommand(new CreateTodoItem("af-5", "desc"));

            // when
            dispatchInstanceCommand(new FinishTodoItem("af-5"));

            // then — FinishTodoItem is only handled by the parent; completed flag proves it ran
            assertThat(entityState.isFinished()).isTrue();
            assertThat(entityState.getHandledBy()).isEqualTo("parent");
        }
    }
}
