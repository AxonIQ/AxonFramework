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

package org.axonframework.eventsourcing.annotation.reflection;

import org.axonframework.conversion.PassThroughConverter;
import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the behavior described on {@link ForcedEntityCreator}.
 * <p>
 * Ano-arguments (or {@link InjectEntityId}-only) factory constructor or method annotated with
 * {@link ForcedEntityCreator} is invoked by {@link AnnotationBasedEventSourcedEntityFactory} even when no first event
 * is present, unlike a plain {@link EntityCreator}. This lets a create-if-missing instance command handler run directly
 * on the entity, append the event that establishes its existence, and let subsequent decisions rely on the resulting
 * state.
 *
 * @author Steven van Beelen
 */
class ForcedEntityCreatorTest {

    private final ParameterResolverFactory parameterResolverFactory =
            ClasspathParameterResolverFactory.forClass(getClass());
    private final MessageTypeResolver messageTypeResolver = new ClassBasedMessageTypeResolver();
    private final EventConverter converter = new DelegatingEventConverter(PassThroughConverter.INSTANCE);

    @Nested
    class ContrastWithPlainEntityCreator {

        @Test
        void forcedNoArgConstructorCreatesEntityWithoutFirstEvent() {
            var factory = new AnnotationBasedEventSourcedEntityFactory<>(
                    ForcedNoArgEntity.class,
                    String.class,
                    parameterResolverFactory,
                    messageTypeResolver,
                    converter
            );

            ForcedNoArgEntity entity = factory.create("entity-id", null, new StubProcessingContext());

            assertThat(entity).isNotNull();
            assertThat(entity.id).isEqualTo("entity-id");
        }

        @Test
        void plainNoArgConstructorReturnsNullWithoutFirstEvent() {
            var factory = new AnnotationBasedEventSourcedEntityFactory<>(
                    PlainNoArgEntity.class,
                    String.class,
                    parameterResolverFactory,
                    messageTypeResolver,
                    converter
            );

            PlainNoArgEntity entity = factory.create("entity-id", null, new StubProcessingContext());

            assertThat(entity).isNull();
        }

        public static class ForcedNoArgEntity {

            private final String id;

            @ForcedEntityCreator
            public ForcedNoArgEntity(@InjectEntityId String id) {
                this.id = id;
            }
        }

        public static class PlainNoArgEntity {

            @EntityCreator
            public PlainNoArgEntity(@InjectEntityId String id) {
            }
        }
    }

    @Nested
    class CreateIfMissingInstanceCommandHandler {

        private AnnotationBasedEventSourcedEntityFactory<Account, String> factory;

        @BeforeEach
        void setUp() {
            factory = new AnnotationBasedEventSourcedEntityFactory<>(
                    Account.class,
                    String.class,
                    parameterResolverFactory,
                    messageTypeResolver,
                    converter
            );
        }

        @Test
        void instanceHandlerCanDecideBasedOnStateEstablishedByItsOwnForcedCreation() {
            // A repository sourcing a never-existing entity would invoke the factory with no first event.
            Account account = factory.create("account-id", null, new StubProcessingContext());
            assertThat(account).isNotNull();
            assertThat(account.exists()).isFalse();

            // The create-if-missing instance handler appends the creation event itself, since it received a
            // non-null entity thanks to the @ForcedEntityCreator constructor.
            String firstOutcome = account.handleOpenAccount(100);
            assertThat(firstOutcome).isEqualTo("opened");
            assertThat(account.exists()).isTrue();
            assertThat(account.balance()).isEqualTo(100);

            // A subsequent decision on the very same instance relies on the state the creation event established.
            String secondOutcome = account.handleWithdraw(40);
            assertThat(secondOutcome).isEqualTo("withdrawn");
            assertThat(account.balance()).isEqualTo(60);
        }

        @Test
        void subsequentDecisionThrowsWithoutPriorCreationEvent() {
            Account account = factory.create("account-id", null, new StubProcessingContext());
            assertThat(account).isNotNull();

            assertThatThrownBy(() -> account.handleWithdraw(10))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not exist");
        }

        /**
         * Simulates an event-sourced entity with a create-if-missing instance command handler. In real usage,
         * {@code handleOpenAccount} and {@code handleWithdraw} would be {@code @CommandHandler}-annotated methods, and
         * {@code apply} would delegate to an {@code EventAppender} whose emitted events are routed back into
         * {@code @EventSourcingHandler}-annotated methods. Here the state transitions are applied directly to keep the
         * test focused on the entity-creation behavior under test.
         */
        public static class Account {

            private final String id;
            private boolean exists;
            private int balance;

            @ForcedEntityCreator
            public Account(@InjectEntityId String id) {
                this.id = id;
                this.exists = false;
            }

            public String handleOpenAccount(int initialBalance) {
                if (exists) {
                    return "already-open";
                }
                exists = true;
                balance = initialBalance;
                return "opened";
            }

            public String handleWithdraw(int amount) {
                if (!exists) {
                    throw new IllegalStateException("Account [%s] does not exist".formatted(id));
                }
                balance -= amount;
                return "withdrawn";
            }

            public boolean exists() {
                return exists;
            }

            public int balance() {
                return balance;
            }
        }
    }
}
