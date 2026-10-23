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

package org.axonframework.modelling;

import org.axonframework.common.ClockUtils;
import org.axonframework.conversion.jackson.JacksonConverter;
import org.axonframework.messaging.core.ClassBasedMessageTypeResolver;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.annotation.AnnotationMessageTypeResolver;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.ClasspathParameterResolverFactory;
import org.axonframework.messaging.core.annotation.MetadataValue;
import org.axonframework.messaging.core.annotation.SourceId;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.annotation.Event;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;
import org.axonframework.messaging.eventhandling.annotation.SequenceNumber;
import org.axonframework.messaging.eventhandling.annotation.Timestamp;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.axonframework.messaging.eventhandling.conversion.EventConverter;
import org.axonframework.modelling.annotation.AnnotationBasedEntityEvolvingComponent;
import org.axonframework.modelling.otherpackage.PackagePrivateHandlerWallet;
import org.axonframework.modelling.otherpackage.ProtectedHandlerWallet;
import org.axonframework.modelling.otherpackage.PublicOverrideWallet;
import org.junit.jupiter.api.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.axonframework.messaging.core.annotation.AnnotatedHandlerInspector.inspectType;
import static org.axonframework.messaging.eventhandling.EventTestUtils.asEventMessage;
import static org.axonframework.messaging.eventhandling.EventTestUtils.createEvent;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link AnnotationBasedEntityEvolvingComponent}.
 *
 * @author Mateusz Nowak
 * @author Jakob Hatzl
 * @since 5.0.0
 */
class AnnotationBasedEntityEvolvingComponentTest {

    private static final EventConverter converter = new DelegatingEventConverter(new JacksonConverter());
    private static final ClassBasedMessageTypeResolver messageTypeResolver = new ClassBasedMessageTypeResolver();
    private static final EntityEvolver<TestState> ENTITY_EVOLVER = new AnnotationBasedEntityEvolvingComponent<>(
            TestState.class,
            converter,
            messageTypeResolver,
            ClasspathParameterResolverFactory.forClass(TestState.class),
            ClasspathHandlerDefinition.forClass(TestState.class)
    );

    @Nested
    class BasicEventHandling {

        @Test
        void mutatesStateOnOriginalInstanceIfEventHandlerDoNotReturnsTheModelType() {
            // given
            var state = new TestState();
            var event = createEvent(0);
            var context = StubProcessingContext.forMessage(event, "id", 0, "test");

            // when
            ENTITY_EVOLVER.evolve(state, event, context);

            // then
            assertEquals("null-0", state.handledPayloads);
        }

        @Test
        void returnsStateAfterHandlingEvent() {
            // given
            var state = new TestState();
            var event = createEvent(0);
            var context = StubProcessingContext.forMessage(event, "id", 0, "test");

            // when
            state = ENTITY_EVOLVER.evolve(state, event, context);

            // then
            assertEquals("null-0", state.handledPayloads);
        }

        @Test
        void handlesSequenceOfEvents() {
            // given
            var state = new TestState();
            EventMessage event0 = createEvent(0);
            var context0 = StubProcessingContext.forMessage(event0, "id", 0, "test");
            EventMessage event1 = createEvent(1);
            var context1 = StubProcessingContext.forMessage(event1, "id", 1, "test");
            EventMessage event2 = createEvent(2);
            var context2 = StubProcessingContext.forMessage(event2, "id", 2, "test");

            // when
            state = ENTITY_EVOLVER.evolve(state, event0, context0);
            state = ENTITY_EVOLVER.evolve(state, event1, context1);
            state = ENTITY_EVOLVER.evolve(state, event2, context2);

            // then
            assertEquals("null-0-1-2", state.handledPayloads);
            assertEquals(3, state.handledCount);
        }
    }

    @Nested
    class ParameterResolution {

        @Test
        void resolvesMetadata() {
            // given
            var state = new TestState();
            var event = new GenericEventMessage(new MessageType(Integer.class),
                                                0,
                                                Metadata.with("sampleKey", "sampleValue"));
            var context = StubProcessingContext.forMessage(event, "id", 0, "test");

            // when
            state = ENTITY_EVOLVER.evolve(state, event, context);

            // then
            assertEquals("null-sampleValue", state.handledMetadata);
        }

        @Test
        void resolvesSequenceNumber() {
            // given
            var state = new TestState();
            var event = createEvent(0);
            var context = StubProcessingContext.forMessage(event, "id", 0, "test");

            // when
            state = ENTITY_EVOLVER.evolve(state, event, context);

            // then
            assertEquals("null-0", state.handledSequences);
        }

        @Test
        void resolvesSources() {
            // given
            var state = new TestState();
            var event = createEvent(0);
            var context = StubProcessingContext.forMessage(event, "id", 0, "test");

            // when
            state = ENTITY_EVOLVER.evolve(state, event, context);

            // then
            assertEquals("null-id", state.handledSources);
        }

        @Test
        void resolvesTimestamps() {
            var timestamp = Instant.now();
            ClockUtils.set(Clock.fixed(timestamp, ZoneId.systemDefault()));

            // given
            var state = new TestState();
            var event = createEvent(0);
            var context = StubProcessingContext.forMessage(event, "id", 0, "test");

            // when
            state = ENTITY_EVOLVER.evolve(state, event, context);

            // then
            assertEquals("null-" + timestamp, state.handledTimestamps);
        }

        @AfterEach
        void afterEach() {
            ClockUtils.reset();
        }
    }

    @Nested
    class HandlerInvocationRules {

        @Test
        void invokesAllEventHandlersResolvingToTheSameEventName() {
            // given
            var sharedEventType = new MessageType("shared-event");
            MessageTypeResolver sharedTypeResolver = payloadType -> Optional.of(sharedEventType);
            var eventSourcedComponent = new AnnotationBasedEntityEvolvingComponent<>(
                    TestState.class,
                    converter,
                    sharedTypeResolver,
                    ClasspathParameterResolverFactory.forClass(TestState.class),
                    ClasspathHandlerDefinition.forClass(TestState.class)
            );
            var state = new TestState();
            var event = new GenericEventMessage(sharedEventType,
                                                0,
                                                Metadata.with("sampleKey", "sampleValue"));
            var context = StubProcessingContext.forMessage(event, "id", 0, "test");

            // when
            state = eventSourcedComponent.evolve(state, event, context);

            // then
            assertTrue(state.objectHandlerInvoked);
            assertEquals(2, state.handledCount);
        }

        @Test
        void doNotHandleNotDeclaredEventType() {
            // given
            var eventSourcedComponent = new AnnotationBasedEntityEvolvingComponent<>(
                    HandlingJustStringState.class,
                    converter,
                    messageTypeResolver,
                    ClasspathParameterResolverFactory.forClass(HandlingJustStringState.class),
                    ClasspathHandlerDefinition.forClass(HandlingJustStringState.class)
            );
            var state = new HandlingJustStringState();
            var event = createEvent(0);

            // when
            state = eventSourcedComponent.evolve(state, event, StubProcessingContext.forMessage(event));

            // then
            assertEquals(0, state.handledCount);
        }

        @Test
        void invokesOnlyMostSpecificHandler() {
            // given
            var state = new TestState();
            var event = createEvent(0);
            var context = StubProcessingContext.forMessage(event, "id", 0, "test");

            // when
            state = ENTITY_EVOLVER.evolve(state, event, context);

            // then
            assertEquals("null-0", state.handledPayloads);
            assertFalse(state.objectHandlerInvoked);
            assertEquals(1, state.handledCount);
        }
    }

    @Nested
    class RecordSupport {

        @SuppressWarnings("unused")
        private record RecordState(String handledPayloads) {

            private static RecordState empty() {
                return new RecordState("null");
            }

            @EventHandler
            RecordState evolve(
                    Integer payload
            ) {
                return new RecordState(handledPayloads + "-" + payload);
            }
        }

        private static final EntityEvolver<RecordState> ENTITY_EVOLVER = new AnnotationBasedEntityEvolvingComponent<>(
                RecordState.class,
                converter,
                messageTypeResolver,
                ClasspathParameterResolverFactory.forClass(RecordState.class),
                ClasspathHandlerDefinition.forClass(RecordState.class)
        );

        @Test
        void doNotMutateGivenStateIfRecord() {
            // given
            var state = RecordState.empty();
            var event = createEvent(0);

            // when
            ENTITY_EVOLVER.evolve(state, event, StubProcessingContext.forMessage(event));

            // then
            assertEquals("null", state.handledPayloads);
        }

        @Test
        void returnsNewObjectIfRecord() {
            // given
            var state = RecordState.empty();
            var event = createEvent(0);

            // when
            state = ENTITY_EVOLVER.evolve(state, event, StubProcessingContext.forMessage(event));

            // then
            assertEquals("null-0", state.handledPayloads);
        }
    }

    @Nested
    class ErrorHandling {

        @Test
        void throwsStateEvolvingExceptionOnExceptionInsideEventHandler() {
            // given
            var testSubject = new AnnotationBasedEntityEvolvingComponent<>(
                    ErrorThrowingState.class,
                    converter,
                    messageTypeResolver,
                    ClasspathParameterResolverFactory.forClass(ErrorThrowingState.class),
                    ClasspathHandlerDefinition.forClass(ErrorThrowingState.class)
            );
            var state = new ErrorThrowingState();
            var event = createEvent(0);

            // when-then
            var exception = assertThrows(StateEvolvingException.class,
                                         () -> testSubject.evolve(state,
                                                                  event,
                                                                  StubProcessingContext.forMessage(event)));
            assertEquals(
                    "Failed to apply event [java.lang.Integer#0.0.1] in order to evolve [class org.axonframework.modelling.AnnotationBasedEntityEvolvingComponentTest$ErrorThrowingState] state",
                    exception.getMessage()
            );
            assertInstanceOf(RuntimeException.class, exception.getCause());
            assertTrue(exception.getCause().getMessage().contains("Simulated error for event: 0"));
        }

        @Test
        void rejectsNullModel() {
            // given
            var event = createEvent(0);

            // when-then
            //noinspection DataFlowIssue
            assertThrows(NullPointerException.class,
                         () -> ENTITY_EVOLVER.evolve(null, event, StubProcessingContext.forMessage(event)),
                         "Model may not be null");
        }
    }

    private static class TestState {

        private String handledPayloads = "null";
        private String handledMetadata = "null";
        private String handledSequences = "null";
        private String handledSources = "null";
        private String handledTimestamps = "null";
        private int handledCount = 0;
        private boolean objectHandlerInvoked = false;

        @EventHandler
        void handle(
                Object payload
        ) {
            this.objectHandlerInvoked = true;
            this.handledCount++;
        }

        @EventHandler
        void handle(
                Integer payload,
                @MetadataValue("sampleKey") String metadata,
                @SequenceNumber Long sequenceNumber,
                @SourceId String source,
                @Timestamp Instant timestamp
        ) {
            this.handledPayloads = handledPayloads + "-" + payload;
            this.handledMetadata = handledMetadata + "-" + metadata;
            this.handledSequences = handledSequences + "-" + sequenceNumber;
            this.handledSources = handledSources + "-" + source;
            this.handledTimestamps = handledTimestamps + "-" + timestamp;
            this.handledCount++;
        }
    }

    @SuppressWarnings("unused")
    private static class ErrorThrowingState {

        @EventHandler
        public void handle(Integer event) {
            throw new RuntimeException("Simulated error for event: " + event);
        }
    }

    @SuppressWarnings("unused")
    private static class HandlingJustStringState {

        private int handledCount = 0;

        @EventHandler
        void handle(String event) {
            this.handledCount++;
        }
    }

    @Nested
    class MessageTypeResolutionCaching {

        @Test
        void resolvesHandlerPayloadTypesOnlyDuringInitialization() {
            // given - TestState declares two handlers (Object and Integer payloads)
            var resolver = spy(new ClassBasedMessageTypeResolver());
            var evolver = new AnnotationBasedEntityEvolvingComponent<>(
                    TestState.class,
                    converter,
                    resolver,
                    ClasspathParameterResolverFactory.forClass(TestState.class),
                    ClasspathHandlerDefinition.forClass(TestState.class)
            );
            var state = new TestState();
            verify(resolver).resolveOrThrow(Object.class);
            verify(resolver).resolveOrThrow(Integer.class);
            clearInvocations(resolver);

            // when - evolving multiple events of the same type
            for (int sequence = 0; sequence < 3; sequence++) {
                var event = createEvent(sequence);
                var context = StubProcessingContext.forMessage(event, "id", sequence, "test");
                state = evolver.evolve(state, event, context);
            }

            // then - all events are handled, while message types were resolved only when building the handler index
            assertThat(state.handledCount).isEqualTo(3);
            verifyNoInteractions(resolver);
        }
    }

    @Nested
    class EventNameResolution {

        @Test
        void usesResolvedMessageTypeForHandlingAndSupportedEvents() {
            // given
            var resolver = new AnnotationMessageTypeResolver();
            var evolver = new AnnotationBasedEntityEvolvingComponent<>(
                    RenamedEventState.class,
                    converter,
                    resolver,
                    ClasspathParameterResolverFactory.forClass(RenamedEventState.class),
                    ClasspathHandlerDefinition.forClass(RenamedEventState.class)
            );
            var state = new RenamedEventState();
            var eventType = resolver.resolveOrThrow(RenamedEvent.class);
            var event = new GenericEventMessage(eventType, new RenamedEvent());

            // when
            evolver.evolve(state, event, StubProcessingContext.forMessage(event));

            // then
            assertThat(state.handledCount).isEqualTo(1);
            assertThat(evolver.supportedEvents()).containsExactly(eventType.qualifiedName());
        }

        @Test
        void explicitHandlerEventNameTakesPrecedenceOverPayloadType() {
            // given
            var evolver = new AnnotationBasedEntityEvolvingComponent<>(
                    ExplicitNameState.class,
                    converter,
                    messageTypeResolver,
                    ClasspathParameterResolverFactory.forClass(ExplicitNameState.class),
                    ClasspathHandlerDefinition.forClass(ExplicitNameState.class)
            );
            var state = new ExplicitNameState();
            var event = new GenericEventMessage(new MessageType("explicit-event"), "payload");

            // when
            evolver.evolve(state, event, StubProcessingContext.forMessage(event));

            // then
            assertThat(state.handledCount).isEqualTo(1);
            assertThat(evolver.supportedEvents()).containsExactly(event.type().qualifiedName());
        }

        @Event(name = "renamed-event", version = "1")
        private record RenamedEvent() {

        }

        private static class RenamedEventState {

            private int handledCount;

            @EventHandler
            void handle(RenamedEvent event) {
                handledCount++;
            }
        }

        private static class ExplicitNameState {

            private int handledCount;

            @EventHandler(eventName = "explicit-event")
            void handle(String event) {
                handledCount++;
            }
        }
    }

    @Nested
    class PolymorphicEntitySupport {

        // Sealed interface where the ENTITY ITSELF is polymorphic (not state inside)
        sealed interface Course permits InitialCourse, CreatedCourse, PublishedCourse {

        }

        @SuppressWarnings("unused")
        private record InitialCourse() implements Course {

            @EventHandler
            CreatedCourse onCreate(String courseCreatedEvent) {
                // Handler returns a different type of entity (sibling type)
                return new CreatedCourse(courseCreatedEvent);
            }
        }

        @SuppressWarnings("unused")
        private record CreatedCourse(String courseName) implements Course {

            @EventHandler
            PublishedCourse onPublish(Integer coursePublishedEvent) {
                // Handler returns a different type of entity (sibling type)
                return new PublishedCourse(courseName, coursePublishedEvent);
            }
        }

        @SuppressWarnings("unused")
        private record PublishedCourse(String courseName, Integer publishedVersion) implements Course {

        }

        private static final EntityEvolvingComponent<Course> COURSE_EVOLVER =
                new AnnotationBasedEntityEvolvingComponent<>(
                        Course.class,
                        inspectType(
                                Course.class,
                                new AnnotationMessageTypeResolver(),
                                ClasspathParameterResolverFactory.forClass(Course.class),
                                ClasspathHandlerDefinition.forClass(Course.class),
                                Set.of(InitialCourse.class, CreatedCourse.class, PublishedCourse.class)
                        ),
                        converter,
                        messageTypeResolver
                );

        @Test
        void supportedEventsIncludesHandlersFromAllPolymorphicEntityTypes() {
            assertThat(COURSE_EVOLVER.supportedEvents())
                    .containsExactlyInAnyOrder(
                            messageTypeResolver.resolveOrThrow(String.class).qualifiedName(),
                            messageTypeResolver.resolveOrThrow(Integer.class).qualifiedName()
                    );
        }

        @Test
        void evolvesPolymorphicEntityFromInitialToCreatedType() {
            // given
            Course course = new InitialCourse();
            var event = asEventMessage("Introduction to Axon");
            var context = StubProcessingContext.forMessage(event);

            // when
            course = COURSE_EVOLVER.evolve(course, event, context);

            // then
            assertInstanceOf(CreatedCourse.class, course);
            assertEquals("Introduction to Axon", ((CreatedCourse) course).courseName());
        }

        @Test
        void evolvesPolymorphicEntityFromCreatedToPublishedType() {
            // given
            Course course = new CreatedCourse("Introduction to Axon");
            var event = asEventMessage(1);
            var context = StubProcessingContext.forMessage(event);

            // when
            course = COURSE_EVOLVER.evolve(course, event, context);

            // then
            assertInstanceOf(PublishedCourse.class, course);
            assertEquals("Introduction to Axon", ((PublishedCourse) course).courseName());
            assertEquals(1, ((PublishedCourse) course).publishedVersion());
        }

        @Test
        void evolvesPolymorphicEntityThroughMultipleTypeTransitions() {
            // given
            Course course = new InitialCourse();
            var createEvent = asEventMessage("Introduction to Axon");
            var publishEvent = asEventMessage(1);
            var createContext = StubProcessingContext.forMessage(createEvent);
            var publishContext = StubProcessingContext.forMessage(publishEvent);

            // when
            course = COURSE_EVOLVER.evolve(course, createEvent, createContext);
            course = COURSE_EVOLVER.evolve(course, publishEvent, publishContext);

            // then
            assertInstanceOf(PublishedCourse.class, course);
            assertEquals("Introduction to Axon", ((PublishedCourse) course).courseName());
            assertEquals(1, ((PublishedCourse) course).publishedVersion());
        }
    }

    @Nested
    class OverriddenHandlers {

        static class Account {

            int balance;
            final List<String> ran = new ArrayList<>();

            @EventHandler
            void on(Deposited event) {
                ran.add("Account.on");
                balance += event.amount();
            }
        }

        static class SavingsAccount extends Account {

            @Override
            @EventHandler
            void on(Deposited event) {
                ran.add("SavingsAccount.on");
                balance += event.amount();
            }
        }

        static class BonusAccount extends Account {

            @Override
            @EventHandler
            void on(Deposited event) {
                ran.add("BonusAccount.on");
                super.on(event);
                balance += event.amount();
            }
        }

        static class PremiumSavings extends SavingsAccount {

            @Override
            @EventHandler
            void on(Deposited event) {
                ran.add("PremiumSavings.on");
                balance += event.amount();
            }
        }

        @Test
        void overridingHandlerWithoutSuperRunsOnceAndReplacesTheOverriddenHandler() {
            // given
            var account = new SavingsAccount();

            // when
            var result = evolve(SavingsAccount.class, account, new Deposited(10));

            // then
            assertThat(result.ran).containsExactly("SavingsAccount.on");
            assertThat(result.balance).isEqualTo(10);
        }

        @Test
        void overridingHandlerCallingSuperRunsOverrideOnceAndOverriddenHandlerOnce() {
            // given
            var account = new BonusAccount();

            // when
            var result = evolve(BonusAccount.class, account, new Deposited(10));

            // then
            assertThat(result.ran).containsExactly("BonusAccount.on", "Account.on");
            assertThat(result.balance).isEqualTo(20);
        }

        @Test
        void overrideAcrossThreeLevelsWithoutSuperRunsOnlyTheMostSpecificMethodOnce() {
            // given
            var account = new PremiumSavings();

            // when
            var result = evolve(PremiumSavings.class, account, new Deposited(10));

            // then
            assertThat(result.ran).containsExactly("PremiumSavings.on");
            assertThat(result.balance).isEqualTo(10);
        }
    }

    @Nested
    class SeparateHandlersInHierarchy {

        static class Wallet {

            int balance;
            final List<String> ran = new ArrayList<>();

            @EventHandler
            void onDeposit(Deposited event) {
                ran.add("Wallet.onDeposit");
                balance += event.amount();
            }
        }

        // sorts before "Wallet" when handlers are ordered by signature
        static class BonusWallet extends Wallet {

            @EventHandler
            void onBonusDeposit(Deposited event) {
                ran.add("BonusWallet.onBonusDeposit");
                balance += 2 * event.amount();
            }
        }

        // sorts after "Wallet" when handlers are ordered by signature
        static class WalletWithBonus extends Wallet {

            @EventHandler
            void onBonusDeposit(Deposited event) {
                ran.add("WalletWithBonus.onBonusDeposit");
                balance += 2 * event.amount();
            }
        }

        static class VipWalletWithBonus extends WalletWithBonus {

            @EventHandler
            void onAVipDeposit(Deposited event) {
                ran.add("VipWalletWithBonus.onAVipDeposit");
                balance += 3 * event.amount();
            }
        }

        static class Pair {

            final List<String> ran = new ArrayList<>();

            @EventHandler
            void first(Deposited event) {
                ran.add("first");
            }

            @EventHandler
            void second(Deposited event) {
                ran.add("second");
            }
        }

        // "GiftBasket" sorts before "ShoppingBasket" by signature, so name order would run the child first
        static class ShoppingBasket {

            final List<String> ran = new ArrayList<>();

            @EventHandler
            private void on(Deposited event) {
                ran.add("ShoppingBasket.on");
            }
        }

        static class GiftBasket extends ShoppingBasket {

            @EventHandler
            private void on(Deposited event) {
                ran.add("GiftBasket.on");
            }
        }

        @Test
        void runsParentHandlerBeforeSubclassHandlerWhenSubclassNameSortsBeforeParent() {
            // given
            var wallet = new BonusWallet();

            // when
            var result = evolve(BonusWallet.class, wallet, new Deposited(10));

            // then
            assertThat(result.ran).containsExactly("Wallet.onDeposit", "BonusWallet.onBonusDeposit");
            assertThat(result.balance).isEqualTo(30);
        }

        @Test
        void runsParentHandlerBeforeSubclassHandlerWhenSubclassNameSortsAfterParent() {
            // given
            var wallet = new WalletWithBonus();

            // when
            var result = evolve(WalletWithBonus.class, wallet, new Deposited(10));

            // then
            assertThat(result.ran).containsExactly("Wallet.onDeposit", "WalletWithBonus.onBonusDeposit");
            assertThat(result.balance).isEqualTo(30);
        }

        @Test
        void runsHandlersOfThreeLevelHierarchyFromLeastToMostSpecificType() {
            // given
            var wallet = new VipWalletWithBonus();

            // when
            var result = evolve(VipWalletWithBonus.class, wallet, new Deposited(10));

            // then
            assertThat(result.ran).containsExactly("Wallet.onDeposit",
                                                   "WalletWithBonus.onBonusDeposit",
                                                   "VipWalletWithBonus.onAVipDeposit");
            assertThat(result.balance).isEqualTo(60);
        }

        @Test
        void runsAllHandlersForTheSameEventDeclaredInOneClass() {
            // given
            var pair = new Pair();

            // when
            var result = evolve(Pair.class, pair, new Deposited(10));

            // then
            assertThat(result.ran).containsExactlyInAnyOrder("first", "second");
        }

        static class WalletInThisPackage extends PackagePrivateHandlerWallet {

            @EventHandler
            void onDeposit(PackagePrivateHandlerWallet.Deposited event) {
                ran.add("WalletInThisPackage.onDeposit");
                balance += 2 * event.amount();
            }
        }

        @Test
        void packagePrivateHandlerOfParentInAnotherPackageIsNotOverriddenAndRunsParentFirst() {
            // given
            var wallet = new WalletInThisPackage();

            // when
            var result = evolve(WalletInThisPackage.class, wallet, new PackagePrivateHandlerWallet.Deposited(10));

            // then
            assertThat(result.ran).containsExactly("PackagePrivateHandlerWallet.onDeposit",
                                                   "WalletInThisPackage.onDeposit");
            assertThat(result.balance).isEqualTo(30);
        }

        @Test
        void privateHandlersWithTheSameSignatureInParentAndChildAreSeparateHandlersRunParentFirst() {
            // given
            var basket = new GiftBasket();

            // when
            var result = evolve(GiftBasket.class, basket, new Deposited(10));

            // then
            assertThat(result.ran).containsExactly("ShoppingBasket.on", "GiftBasket.on");
        }
    }

    @Nested
    class ImmutableEntityChaining {

        // handlers of immutable records cannot record state on the entity, so they report to this trace
        static final List<String> trace = new ArrayList<>();

        @BeforeEach
        void clearTrace() {
            trace.clear();
        }

        sealed interface Card permits VipCard {

            int balance();

            Card withBalance(int balance);

            @EventHandler
            default Card onDeposit(Deposited event) {
                return withBalance(balance() + event.amount());
            }
        }

        private record VipCard(int balance) implements Card {

            @Override
            public Card withBalance(int balance) {
                return new VipCard(balance);
            }

            @EventHandler
            VipCard onVipDeposit(Deposited event) {
                return new VipCard(balance * 2);
            }
        }

        sealed interface Course permits InitialCourse, CreatedCourse {

            int touches();

            Course withTouches(int touches);

            @EventHandler
            default Course countTouch(CourseCreated event) {
                trace.add("countTouch");
                return withTouches(touches() + 1);
            }
        }

        private record InitialCourse(int touches) implements Course {

            @Override
            public Course withTouches(int touches) {
                return new InitialCourse(touches);
            }

            @EventHandler
            CreatedCourse onCreated(CourseCreated event) {
                trace.add("onCreated");
                return new CreatedCourse(event.name(), touches);
            }
        }

        private record CreatedCourse(String name, int touches) implements Course {

            @Override
            public Course withTouches(int touches) {
                return new CreatedCourse(name, touches);
            }
        }

        interface Counting {

            int value();

            Counting withValue(int value);

            @EventHandler
            default Counting onDeposit(Deposited event) {
                return withValue(value() + event.amount());
            }
        }

        private record Tally(int value) implements Counting {

            @Override
            public Counting withValue(int value) {
                return new Tally(value);
            }

            @EventHandler
            Tally doubleValue(Deposited event) {
                return new Tally(value * 2);
            }
        }

        interface Voucher {

            int balance();

            Voucher withBalance(int balance);

            @EventHandler
            default Voucher onDeposit(Deposited event) {
                return withBalance(balance() + event.amount());
            }
        }

        private record BasicVoucher(int balance) implements Voucher {

            @Override
            public Voucher withBalance(int balance) {
                return new BasicVoucher(balance);
            }

            @EventHandler
            void onAudit(Deposited event) {
                trace.add("onAudit");
            }
        }

        abstract static class Till {

            final int value;

            Till(int value) {
                this.value = value;
            }

            abstract Till with(int value);

            @EventHandler
            Till onDeposit(Deposited event) {
                return with(value + event.amount());
            }
        }

        abstract static class TenantTill extends Till {

            TenantTill(int value) {
                super(value);
            }

            @EventHandler
            Till onTenantDeposit(Deposited event, @MetadataValue(value = "tenant", required = true) String tenant) {
                return with(value + 100);
            }
        }

        static class VipTenantTill extends TenantTill {

            VipTenantTill(int value) {
                super(value);
            }

            @Override
            Till with(int value) {
                return new VipTenantTill(value);
            }

            @EventHandler
            VipTenantTill onVipDeposit(Deposited event) {
                return new VipTenantTill(value * 2);
            }
        }

        @Test
        void subclassHandlerReturningNothingKeepsTheEntityReturnedByParentHandler() {
            // given
            var voucher = new BasicVoucher(0);

            // when
            var result = evolve(BasicVoucher.class, voucher, new Deposited(10));

            // then
            assertThat(trace).containsExactly("onAudit");
            assertThat(result).isEqualTo(new BasicVoucher(10));
        }

        @Test
        void skipsHandlerInTheMiddleOfTheChainThatCannotResolveItsParametersAndContinues() {
            // given - the event carries no "tenant" metadata, so onTenantDeposit cannot handle it
            var till = new VipTenantTill(0);

            // when
            var result = evolve(VipTenantTill.class, till, new Deposited(10));

            // then - Till.onDeposit then VipTenantTill.onVipDeposit: (0 + 10) * 2
            assertThat(result.value).isEqualTo(20);
        }

        @Test
        void subclassHandlerReceivesEntityReturnedByParentHandler() {
            // given
            Card card = new VipCard(0);

            // when
            var result = evolve(Card.class, Set.of(VipCard.class), card, new Deposited(10));

            // then - parent first: (0 + 10) * 2; name order would run onVipDeposit first and give 0 * 2 + 10
            assertThat(result).isEqualTo(new VipCard(20));
        }

        @Test
        void interfaceDefaultHandlerRunsBeforeImplementingTypeHandler() {
            // given
            var tally = new Tally(0);

            // when
            var result = evolve(Tally.class, tally, new Deposited(10));

            // then - interface first: (0 + 10) * 2; name order would run doubleValue first and give 0 * 2 + 10
            assertThat(result).isEqualTo(new Tally(20));
        }

        @Test
        void mostSpecificHandlerMayChangeTheConcreteTypeAfterParentHandlerRan() {
            // given
            Course course = new InitialCourse(0);

            // when
            var result = evolve(Course.class,
                                Set.of(InitialCourse.class, CreatedCourse.class),
                                course,
                                new CourseCreated("Axon"));

            // then - the parent handler counted the touch, the most specific handler made the transition
            assertThat(trace).containsExactly("countTouch", "onCreated");
            assertThat(result).isEqualTo(new CreatedCourse("Axon", 1));
        }
    }

    @Nested
    class ChangingTheConcreteType {

        sealed interface Pass permits GiftPass, VipPass {

            int balance();

            @EventHandler
            default Pass onDeposit(Deposited event) {
                return new GiftPass(balance() + event.amount());
            }
        }

        private record GiftPass(int balance) implements Pass {

        }

        private record VipPass(int balance) implements Pass {

            @EventHandler
            VipPass onVipDeposit(Deposited event) {
                return new VipPass(balance * 2);
            }
        }

        @Test
        void failsWhenParentHandlerChangesTheTypeAndHandlerOfThePreviousTypeFollows() {
            // given
            Pass pass = new VipPass(0);

            // when
            var thrown = catchThrowable(
                    () -> evolve(Pass.class, Set.of(GiftPass.class, VipPass.class), pass, new Deposited(10))
            );

            // then
            assertThat(thrown).isInstanceOf(StateEvolvingException.class)
                              .cause()
                              .hasMessageContaining("Deposited")
                              .hasMessageContaining("GiftPass")
                              .hasMessageContaining("onVipDeposit")
                              .hasMessageContaining(VipPass.class.getName());
        }

        static class Base {

            final List<String> trace;

            Base(List<String> trace) {
                this.trace = trace;
            }

            @EventHandler
            Base onBase(Deposited event) {
                trace.add("Base.onBase");
                return new Narrow(trace);
            }
        }

        static class Child extends Base {

            Child(List<String> trace) {
                super(trace);
            }

            @EventHandler
            Child onChild(Deposited event) {
                trace.add("Child.onChild on " + getClass().getSimpleName());
                return this;
            }
        }

        static class Narrow extends Child {

            Narrow(List<String> trace) {
                super(trace);
            }

            @EventHandler
            Narrow onNarrow(Deposited event) {
                trace.add("Narrow.onNarrow");
                return this;
            }
        }

        @Test
        void handlerOfThePreviousTypeMayFollowWhenTheReturnedTypeIsItsSubtype() {
            // given - Base.onBase narrows the Child to a Narrow, a subtype of Child
            var trace = new ArrayList<String>();

            // when
            var result = evolve(Base.class, Set.of(Child.class, Narrow.class), new Child(trace), new Deposited(1));

            // then - Child.onChild still runs, on the Narrow; Narrow.onNarrow does not run for this event
            assertThat(result).isInstanceOf(Narrow.class);
            assertThat(trace).containsExactly("Base.onBase", "Child.onChild on Narrow");
        }
    }

    @Nested
    class OverrideDispatch {

        static final List<String> staticTrace = new ArrayList<>();

        @BeforeEach
        void clearStaticTrace() {
            staticTrace.clear();
        }

        interface Ticket {

            int balance();

            @EventHandler
            default Ticket on(Deposited event) {
                return new BasicTicket(balance() + event.amount());
            }
        }

        private record BasicTicket(int balance) implements Ticket {

        }

        private record VipTicket(int balance) implements Ticket {

            @Override
            @EventHandler
            public VipTicket on(Deposited event) {
                return new VipTicket(balance + event.amount());
            }
        }

        static class Base {

            int balance;
            final List<String> ran = new ArrayList<>();

            @EventHandler
            Base on(Deposited event) {
                ran.add("Base.on");
                balance += event.amount();
                return this;
            }
        }

        static class Derived extends Base {

            @Override
            @EventHandler
            Derived on(Deposited event) {
                ran.add("Derived.on");
                balance += event.amount();
                return this;
            }
        }

        interface Audited {

            List<String> ran();

            @EventHandler
            default void on(Deposited event) {
                ran().add("Audited.on");
            }
        }

        static class Account {

            int balance;
            final List<String> ran = new ArrayList<>();

            public List<String> ran() {
                return ran;
            }

            @EventHandler
            public void on(Deposited event) {
                ran.add("Account.on");
                balance += event.amount();
            }
        }

        static class Savings extends Account implements Audited {

        }

        static class AnnotatedParent {

            int balance;
            final List<String> ran = new ArrayList<>();

            @EventHandler
            void on(Deposited event) {
                ran.add("AnnotatedParent.on");
                balance += event.amount();
            }
        }

        static class UnannotatedChild extends AnnotatedParent {

            @Override
            void on(Deposited event) {
                ran.add("UnannotatedChild.on");
                balance += 2 * event.amount();
            }
        }

        static class ProtectedChild extends ProtectedHandlerWallet {

            @Override
            @EventHandler
            protected void onDeposit(ProtectedHandlerWallet.Deposited event) {
                ran.add("ProtectedChild.onDeposit");
                balance += 2 * event.amount();
            }
        }

        abstract static class AbstractParent {

            int balance;
            final List<String> ran = new ArrayList<>();

            @EventHandler
            abstract void on(Deposited event);
        }

        static class ConcreteChild extends AbstractParent {

            @Override
            @EventHandler
            void on(Deposited event) {
                ran.add("ConcreteChild.on");
                balance += event.amount();
            }
        }

        static class GrandParent {

            int balance;
            final List<String> ran = new ArrayList<>();

            @EventHandler
            void on(Deposited event) {
                ran.add("GrandParent.on");
                balance += event.amount();
            }
        }

        static class AnnotatedMiddle extends GrandParent {

            @Override
            @EventHandler
            void on(Deposited event) {
                ran.add("AnnotatedMiddle.on");
                balance += event.amount();
            }
        }

        static class UnannotatedLeaf extends AnnotatedMiddle {

            @Override
            void on(Deposited event) {
                ran.add("UnannotatedLeaf.on");
                balance += event.amount();
            }
        }

        interface Describing {

            List<String> ran();

            @EventHandler
            default void on(Deposited event) {
                ran().add("Describing.on");
            }
        }

        static class DescribingMiddle implements Describing {

            final List<String> ran = new ArrayList<>();

            @Override
            public List<String> ran() {
                return ran;
            }

            @Override
            @EventHandler
            public void on(Deposited event) {
                ran.add("DescribingMiddle.on");
            }
        }

        static class DescribingLeaf extends DescribingMiddle {

            @Override
            public void on(Deposited event) {
                ran.add("DescribingLeaf.on");
            }
        }

        static class ParentNamed {

            int balance;
            final List<String> ran = new ArrayList<>();

            @EventHandler(eventName = "parent-event")
            void on(Deposited event) {
                ran.add("ParentNamed.on");
                balance += event.amount();
            }
        }

        static class ChildNamed extends ParentNamed {

            @Override
            @EventHandler(eventName = "child-event")
            void on(Deposited event) {
                ran.add("ChildNamed.on");
                balance += 2 * event.amount();
            }
        }

        static class GenericBase<T> {

            int balance;
            final List<String> ran = new ArrayList<>();

            @EventHandler(payloadType = Deposited.class)
            void on(T event) {
                ran.add("GenericBase.on");
                balance += 2;
            }
        }

        static class GenericChild extends GenericBase<Deposited> {

            @EventHandler
            void on(Deposited event) {
                ran.add("GenericChild.on");
                balance += event.amount();
            }

            @EventHandler
            void on(Withdrawn event) {
                ran.add("GenericChild.onWithdrawn");
                balance -= event.amount();
            }
        }

        private record Withdrawn(int amount) {

        }

        static class StaticParent {

            @EventHandler
            static void on(Deposited event) {
                staticTrace.add("StaticParent.on");
            }
        }

        static class StaticChild extends StaticParent {

            @EventHandler
            static void on(Deposited event) {
                staticTrace.add("StaticChild.on");
            }
        }

        @Test
        void immutableOverrideWithNarrowerReturnTypeRunsOnce() {
            // given
            var ticket = new VipTicket(0);

            // when
            var result = evolve(VipTicket.class, ticket, new Deposited(10));

            // then
            assertThat(result).isEqualTo(new VipTicket(10));
        }

        @Test
        void mutableOverrideWithNarrowerReturnTypeRunsOnce() {
            // given
            var derived = new Derived();

            // when
            var result = evolve(Derived.class, derived, new Deposited(10));

            // then
            assertThat(result.ran).containsExactly("Derived.on");
            assertThat(result.balance).isEqualTo(10);
        }

        @Test
        void inheritedImplementationOfInterfaceDefaultHandlerRunsOnce() {
            // given
            var savings = new Savings();

            // when
            var result = evolve(Savings.class, savings, new Deposited(10));

            // then - the inherited class method wins over the interface default
            assertThat(result.ran).containsExactly("Account.on");
            assertThat(result.balance).isEqualTo(10);
        }

        @Test
        void overrideWithoutAnnotationStillRunsOnce() {
            // given
            var child = new UnannotatedChild();

            // when
            var result = evolve(UnannotatedChild.class, child, new Deposited(10));

            // then - the parent's handler dispatches to the child's override
            assertThat(result.ran).containsExactly("UnannotatedChild.on");
            assertThat(result.balance).isEqualTo(20);
        }

        @Test
        void protectedHandlerOverriddenFromAnotherPackageRunsOnce() {
            // given
            var child = new ProtectedChild();

            // when
            var result = evolve(ProtectedChild.class, child, new ProtectedHandlerWallet.Deposited(10));

            // then
            assertThat(result.ran).containsExactly("ProtectedChild.onDeposit");
            assertThat(result.balance).isEqualTo(20);
        }

        @Test
        void abstractParentHandlerWithOverrideRunsOnce() {
            // given
            var child = new ConcreteChild();

            // when
            var result = evolve(ConcreteChild.class, child, new Deposited(10));

            // then
            assertThat(result.ran).containsExactly("ConcreteChild.on");
            assertThat(result.balance).isEqualTo(10);
        }

        @Test
        void unannotatedOverrideOfTwoAnnotatedHandlersRunsOnce() {
            // given
            var leaf = new UnannotatedLeaf();

            // when
            var result = evolve(UnannotatedLeaf.class, leaf, new Deposited(10));

            // then
            assertThat(result.ran).containsExactly("UnannotatedLeaf.on");
            assertThat(result.balance).isEqualTo(10);
        }

        @Test
        void unannotatedOverrideOfInterfaceDefaultAndAnnotatedClassOverrideRunsOnce() {
            // given
            var leaf = new DescribingLeaf();

            // when
            var result = evolve(DescribingLeaf.class, leaf, new Deposited(10));

            // then
            assertThat(result.ran).containsExactly("DescribingLeaf.on");
        }

        @Test
        void overrideDeclaringAnotherEventNameStillHandlesTheEventNameOfTheOverriddenHandler() {
            // given
            var child = new ChildNamed();
            var event = new GenericEventMessage(new MessageType("parent-event"), new Deposited(10));

            // when
            var result = evolve(ChildNamed.class, child, event);

            // then - the parent's handler dispatches to the overriding method
            assertThat(result.ran).containsExactly("ChildNamed.on");
            assertThat(result.balance).isEqualTo(20);
        }

        @Test
        void overrideDeclaringAnotherEventNameHandlesItsOwnEventName() {
            // given
            var child = new ChildNamed();
            var event = new GenericEventMessage(new MessageType("child-event"), new Deposited(10));

            // when
            var result = evolve(ChildNamed.class, child, event);

            // then
            assertThat(result.ran).containsExactly("ChildNamed.on");
            assertThat(result.balance).isEqualTo(20);
        }

        @Test
        void overrideOfGenericParentHandlerReachedThroughBridgeMethodRunsOnce() {
            // given
            var child = new GenericChild();

            // when
            var result = evolve(GenericChild.class, child, new Deposited(10));

            // then
            assertThat(result.ran).containsExactly("GenericChild.on");
            assertThat(result.balance).isEqualTo(10);
        }

        @Test
        void overloadedHandlerOfGenericChildStillHandlesItsOwnEventOnce() {
            // given
            var child = new GenericChild();

            // when
            var result = evolve(GenericChild.class, child, new Withdrawn(4));

            // then
            assertThat(result.ran).containsExactly("GenericChild.onWithdrawn");
            assertThat(result.balance).isEqualTo(-4);
        }

        @Test
        void staticHandlersHideInsteadOfOverrideSoBothRun() {
            // given
            var child = new StaticChild();

            // when
            evolve(StaticChild.class, child, new Deposited(10));

            // then
            assertThat(staticTrace).containsExactlyInAnyOrder("StaticParent.on", "StaticChild.on");
        }

        static class WalletOverridingAcrossPackages extends PublicOverrideWallet {

            @Override
            @EventHandler
            public void onDeposit(PackagePrivateHandlerWallet.Deposited event) {
                ran.add("WalletOverridingAcrossPackages.onDeposit");
                balance += event.amount();
            }
        }

        @Test
        void overrideOfPackagePrivateHandlerThroughPublicOverrideInAnotherPackageRunsOnce() {
            // given
            var wallet = new WalletOverridingAcrossPackages();

            // when
            var result = evolve(WalletOverridingAcrossPackages.class,
                                wallet,
                                new PackagePrivateHandlerWallet.Deposited(10));

            // then
            assertThat(result.ran).containsExactly("WalletOverridingAcrossPackages.onDeposit");
            assertThat(result.balance).isEqualTo(10);
        }

        record Envelope<T>(T content) {

        }

        static class EnvelopeBase<T> {

            final List<String> ran = new ArrayList<>();

            @EventHandler(eventName = "envelope")
            void on(T event) {
                ran.add("EnvelopeBase.on");
            }
        }

        static class EnvelopeChild extends EnvelopeBase<Envelope<String>> {

            @Override
            @EventHandler(eventName = "envelope")
            void on(Envelope<String> event) {
                ran.add("EnvelopeChild.on");
            }
        }

        @Test
        void overrideOfGenericParentHandlerWithParameterizedTypeArgumentRunsOnce() {
            // given
            var child = new EnvelopeChild();
            var event = new GenericEventMessage(new MessageType("envelope"), new Envelope<>("content"));

            // when
            var result = evolve(EnvelopeChild.class, child, event);

            // then
            assertThat(result.ran).containsExactly("EnvelopeChild.on");
        }

        static class BoundedBase<T> {

            final List<String> ran = new ArrayList<>();

            @EventHandler(eventName = "deposited")
            void on(T event) {
                ran.add("BoundedBase.on");
            }
        }

        static class BoundedMid<U extends Deposited> extends BoundedBase<U> {

            @Override
            @EventHandler(eventName = "deposited")
            void on(U event) {
                ran.add("BoundedMid.on");
            }
        }

        static class BoundedChild extends BoundedMid<Deposited> {

        }

        @Test
        void overrideOfGenericParentHandlerThroughTypeVariableOfIntermediateClassRunsOnce() {
            // given
            var child = new BoundedChild();
            var event = new GenericEventMessage(new MessageType("deposited"), new Deposited(10));

            // when
            var result = evolve(BoundedChild.class, child, event);

            // then
            assertThat(result.ran).containsExactly("BoundedMid.on");
        }

        static class ArrayBase<T> {

            final List<String> ran = new ArrayList<>();

            @EventHandler(eventName = "deposits")
            void on(T[] events) {
                ran.add("ArrayBase.on");
            }
        }

        static class ArrayChild extends ArrayBase<Deposited> {

            @Override
            @EventHandler(eventName = "deposits")
            void on(Deposited[] events) {
                ran.add("ArrayChild.on");
            }
        }

        @Test
        void overrideOfGenericParentHandlerWithGenericArrayParameterRunsOnce() {
            // given
            var child = new ArrayChild();
            var event = new GenericEventMessage(new MessageType("deposits"), new Deposited[]{new Deposited(10)});

            // when
            var result = evolve(ArrayChild.class, child, event);

            // then
            assertThat(result.ran).containsExactly("ArrayChild.on");
        }
    }

    @Nested
    class SubclassesUnknownToTheEntityModel {

        abstract static class Account {

            int balance;

            @EventHandler
            void on(Deposited event) {
                balance += event.amount();
            }
        }

        static class Savings extends Account {

            int interest;

            @EventHandler
            void on(InterestPaid event) {
                interest += event.amount();
            }
        }

        static class KnownAccount {

            int balance;

            @EventHandler
            void on(Deposited event) {
                balance += event.amount();
            }
        }

        static class KnownSavings extends KnownAccount {

            int interest;

            @EventHandler
            void on(InterestPaid event) {
                interest += event.amount();
            }
        }

        private record InterestPaid(int amount) {

        }

        @Test
        void ignoresInstanceOfSubclassItHasNoHandlersFor() {
            // given
            var savings = new Savings();

            // when
            var result = evolve(Account.class, savings, new Deposited(10));

            // then - each concrete evolver applies the inherited handlers, so this evolver must not apply them again
            assertThat(result).isSameAs(savings);
            assertThat(result.balance).isEqualTo(0);
        }

        @Test
        void appliesHandlersOfTheEntityTypeItself() {
            // given
            var account = new KnownAccount();

            // when
            var result = evolve(KnownAccount.class, account, new Deposited(10));

            // then
            assertThat(result.balance).isEqualTo(10);
        }

        @Test
        void appliesHandlersOfSubclassListedAsConcreteType() {
            // given
            var savings = new KnownSavings();

            // when
            var result = evolve(KnownAccount.class, Set.of(KnownSavings.class), savings, new Deposited(10));
            result = evolve(KnownAccount.class, Set.of(KnownSavings.class), result, new InterestPaid(3));

            // then
            assertThat(result.balance).isEqualTo(10);
            assertThat(((KnownSavings) result).interest).isEqualTo(3);
        }
    }

    private record Deposited(int amount) {

    }

    private record CourseCreated(String name) {

    }

    private static <E> E evolve(Class<E> type, E entity, Object payload) {
        var evolver = new AnnotationBasedEntityEvolvingComponent<>(
                type,
                converter,
                messageTypeResolver,
                ClasspathParameterResolverFactory.forClass(type),
                ClasspathHandlerDefinition.forClass(type)
        );
        var event = payload instanceof EventMessage message ? message : asEventMessage(payload);
        return evolver.evolve(entity, event, StubProcessingContext.forMessage(event));
    }

    private static <E> E evolve(Class<E> type, Set<Class<? extends E>> concreteTypes, E entity, Object payload) {
        var evolver = new AnnotationBasedEntityEvolvingComponent<>(
                type,
                inspectType(type,
                            messageTypeResolver,
                            ClasspathParameterResolverFactory.forClass(type),
                            ClasspathHandlerDefinition.forClass(type),
                            concreteTypes),
                converter,
                messageTypeResolver
        );
        var event = payload instanceof EventMessage message ? message : asEventMessage(payload);
        return evolver.evolve(entity, event, StubProcessingContext.forMessage(event));
    }
}
