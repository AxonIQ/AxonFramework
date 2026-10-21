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
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.conversion.DelegatingMessageConverter;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.axonframework.messaging.eventhandling.EventTestUtils.asEventMessage;

/**
 * Tests that the {@link AnnotatedEntityMetamodel} of a polymorphic entity applies each event to the entity exactly once.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
class PolymorphicEntityEventApplicationTest {

    private record Deposited(int amount) {

    }

    private record Enrolled(String student) {

    }

    private record Withdraw() {

    }

    private record Rename(String name) {

    }

    private record Relabeled(String name) {

    }

    private record CourseCreated(String name) {

    }

    private record CourseRenamed(String name) {

    }

    private static <E> AnnotatedEntityMetamodel<E> polymorphic(Class<E> type, Set<Class<? extends E>> concreteTypes) {
        return AnnotatedEntityMetamodel.forPolymorphicType(
                type,
                concreteTypes,
                ClasspathParameterResolverFactory.forClass(PolymorphicEntityEventApplicationTest.class),
                ClasspathHandlerDefinition.forClass(PolymorphicEntityEventApplicationTest.class),
                new ClassBasedMessageTypeResolver(),
                new DelegatingMessageConverter(new JacksonConverter()),
                new DelegatingEventConverter(new JacksonConverter())
        );
    }

    private static <E> AnnotatedEntityMetamodel<E> polymorphicSealed(Class<E> type) {
        return AnnotatedEntityMetamodel.forPolymorphicSealedType(
                type,
                ClasspathParameterResolverFactory.forClass(PolymorphicEntityEventApplicationTest.class),
                ClasspathHandlerDefinition.forClass(PolymorphicEntityEventApplicationTest.class),
                new ClassBasedMessageTypeResolver(),
                new DelegatingMessageConverter(new JacksonConverter()),
                new DelegatingEventConverter(new JacksonConverter())
        );
    }

    private static <E> E evolve(AnnotatedEntityMetamodel<E> metamodel, E entity, Object payload) {
        var event = asEventMessage(payload);
        return metamodel.evolve(entity, event, StubProcessingContext.forMessage(event));
    }

    @Nested
    class ClassHierarchies {

        abstract static class Registry {

            int balance;
            final List<String> students = new ArrayList<>();

            @EventHandler
            void on(Deposited event) {
                balance += event.amount();
            }

            @EventHandler
            void on(Enrolled event) {
                students.add(event.student());
            }
        }

        static class BasicRegistry extends Registry {

        }

        abstract static class Wallet {

            int balance;

            @EventHandler
            void on(Deposited event) {
                balance += event.amount();
            }
        }

        static class SavingsWallet extends Wallet {

            @Override
            @EventHandler
            void on(Deposited event) {
                balance += event.amount();
            }
        }

        @Test
        void appliesHandlerDeclaredOnTheParentOnce() {
            // given
            var metamodel = polymorphic(Registry.class, Set.of(BasicRegistry.class));
            var registry = new BasicRegistry();

            // when
            var result = evolve(metamodel, registry, new Deposited(10));

            // then
            assertThat(result.balance).isEqualTo(10);
        }

        @Test
        void appliesCollectionAppendingHandlerDeclaredOnTheParentOnce() {
            // given
            var metamodel = polymorphic(Registry.class, Set.of(BasicRegistry.class));
            var registry = new BasicRegistry();

            // when
            var result = evolve(metamodel, registry, new Enrolled("s1"));

            // then
            assertThat(result.students).containsExactly("s1");
        }

        @Test
        void appliesOverridingHandlerOnce() {
            // given
            var metamodel = polymorphic(Wallet.class, Set.of(SavingsWallet.class));
            var wallet = new SavingsWallet();

            // when
            var result = evolve(metamodel, wallet, new Deposited(10));

            // then
            assertThat(result.balance).isEqualTo(10);
        }
    }

    @Nested
    class SealedHierarchies {

        sealed interface Ledger permits OpenLedger {

            int balance();

            Ledger withBalance(int balance);

            @EventHandler
            default Ledger on(Deposited event) {
                return withBalance(balance() + event.amount());
            }
        }

        private record OpenLedger(int balance) implements Ledger {

            @Override
            public Ledger withBalance(int balance) {
                return new OpenLedger(balance);
            }
        }

        sealed interface Course permits InitialCourse, CreatedCourse {

        }

        private record InitialCourse() implements Course {

            @EventHandler
            CreatedCourse on(CourseCreated event) {
                return new CreatedCourse(event.name(), 0);
            }
        }

        private record CreatedCourse(String name, int renames) implements Course {

            @EventHandler
            CreatedCourse on(CourseRenamed event) {
                return new CreatedCourse(event.name(), renames + 1);
            }
        }

        @Test
        void appliesImmutableParentHandlerOnce() {
            // given
            var metamodel = polymorphicSealed(Ledger.class);

            // when
            var result = evolve(metamodel, new OpenLedger(0), new Deposited(10));

            // then
            assertThat(result).isEqualTo(new OpenLedger(10));
        }

        @Test
        void appliesEventHandledOnlyByTheTypeReachedThroughATransitionOnce() {
            // given
            var metamodel = polymorphicSealed(Course.class);
            Course course = new InitialCourse();

            // when
            course = evolve(metamodel, course, new CourseCreated("Axon"));
            course = evolve(metamodel, course, new CourseRenamed("Axon 5"));

            // then
            assertThat(course).isEqualTo(new CreatedCourse("Axon 5", 1));
        }
    }

    @Nested
    class SubclassesNotListedAsConcreteType {

        abstract static class Wallet {

            int balance;

            @EventHandler
            void on(Deposited event) {
                balance += event.amount();
            }

            @CommandHandler
            void handle(Withdraw command) {
                balance -= 1;
            }
        }

        static class ListedWallet extends Wallet {

        }

        static class UnlistedWallet extends ListedWallet {

        }

        static class HandlerDeclaringWallet extends ListedWallet {

            @EventHandler
            void on(Enrolled event) {
                balance += 1;
            }
        }

        static class OrphanWallet extends Wallet {

        }

        @Test
        void failsClearlyForUnlistedSubclassWithoutHandlers() {
            // given
            var metamodel = polymorphic(Wallet.class, Set.of(ListedWallet.class));
            var wallet = new UnlistedWallet();

            // when
            var thrown = catchThrowable(() -> evolve(metamodel, wallet, new Deposited(10)));

            // then
            assertThat(thrown).hasMessageContaining(UnlistedWallet.class.getName())
                              .hasMessageContaining("concreteTypes");
        }

        @Test
        void failsClearlyWhenUnlistedSubclassDeclaresHandlers() {
            // given
            var metamodel = polymorphic(Wallet.class, Set.of(ListedWallet.class));
            var wallet = new HandlerDeclaringWallet();

            // when
            var thrown = catchThrowable(() -> evolve(metamodel, wallet, new Deposited(10)));

            // then
            assertThat(thrown).hasMessageContaining(HandlerDeclaringWallet.class.getName())
                              .hasMessageContaining("concreteTypes");
        }

        @Test
        void failsCommandHandlingAsAFailedStreamWhenNoListedConcreteTypeIsAnAncestor() {
            // given
            var metamodel = polymorphic(Wallet.class, Set.of(ListedWallet.class));
            var wallet = new OrphanWallet();
            var command = new GenericCommandMessage(new MessageType(Withdraw.class), new Withdraw());

            // when
            var stream = metamodel.handleInstance(command, wallet, StubProcessingContext.forMessage(command));

            // then
            assertThat(stream.first().asCompletableFuture())
                    .failsWithin(Duration.ofSeconds(5))
                    .withThrowableOfType(ExecutionException.class)
                    .havingRootCause()
                    .withMessageContaining(OrphanWallet.class.getName());
        }

        @Test
        void failsClearlyWhenNoListedConcreteTypeIsAnAncestor() {
            // given
            var metamodel = polymorphic(Wallet.class, Set.of(ListedWallet.class));
            var wallet = new OrphanWallet();

            // when
            var thrown = catchThrowable(() -> evolve(metamodel, wallet, new Deposited(10)));

            // then
            assertThat(thrown).isNotInstanceOf(NullPointerException.class)
                              .hasMessageContaining(OrphanWallet.class.getName())
                              .hasMessageContaining(Wallet.class.getName());
        }
    }

    @Nested
    class WithEntityMembers {

        static class Label {

            final List<String> names = new ArrayList<>();

            @EventHandler
            void on(Relabeled event) {
                names.add(event.name());
            }

            @CommandHandler
            String handle(Rename command) {
                names.add(command.name());
                return "renamed";
            }
        }

        abstract static class Vault {

            int balance;

            @EventHandler
            void on(Deposited event) {
                balance += event.amount();
            }
        }

        static class LabelledVault extends Vault {

            @EntityMember
            private Label label = new Label();
        }

        abstract static class Shelf {

            @EntityMember
            private Label label = new Label();
        }

        static class OpenShelf extends Shelf {

        }

        abstract static class Locker {

        }

        static class StampedLocker extends Locker {

            int stamps;

            @EntityMember
            private Label label = new Label();

            @EventHandler
            void on(Deposited event) {
                stamps++;
            }
        }

        @Test
        void routesCommandToChildEntityDeclaredOnTheSuperTypeOnce() {
            // given
            var metamodel = polymorphic(Shelf.class, Set.of(OpenShelf.class));
            var shelf = new OpenShelf();
            var command = new GenericCommandMessage(new MessageType(Rename.class), new Rename("fiction"));

            // when
            var result = metamodel.handleInstance(command, shelf, StubProcessingContext.forMessage(command))
                                  .first()
                                  .asCompletableFuture()
                                  .orTimeout(5, TimeUnit.SECONDS)
                                  .join();

            // then
            assertThat(result.message().payload()).isEqualTo("renamed");
            assertThat(((Shelf) shelf).label.names).containsExactly("fiction");
        }

        @Test
        void appliesHandlerDeclaredOnlyOnAConcreteTypeOnceWhenItHasEntityMembers() {
            // given
            var metamodel = polymorphic(Locker.class, Set.of(StampedLocker.class));
            var locker = new StampedLocker();

            // when
            var result = evolve(metamodel, locker, new Deposited(10));

            // then
            assertThat(((StampedLocker) result).stamps).isEqualTo(1);
        }

        @Test
        void appliesParentHandlerOnceWhenAConcreteTypeHasEntityMembers() {
            // given
            var metamodel = polymorphic(Vault.class, Set.of(LabelledVault.class));
            var vault = new LabelledVault();

            // when
            var result = evolve(metamodel, vault, new Deposited(10));

            // then
            assertThat(result.balance).isEqualTo(10);
        }

        @Test
        void evolvesChildEntityDeclaredOnTheSuperTypeOnce() {
            // given
            var metamodel = polymorphic(Shelf.class, Set.of(OpenShelf.class));
            var shelf = new OpenShelf();

            // when
            var result = evolve(metamodel, shelf, new Relabeled("fiction"));

            // then
            assertThat(result.label.names).containsExactly("fiction");
        }
    }
}
