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

package org.axonframework.eventsourcing.commandhandling;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.eventsourcing.annotation.EventTag;
import org.axonframework.eventsourcing.commandhandling.annotation.AppendCriteriaBuilder;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.eventstore.AppendCondition;
import org.axonframework.eventsourcing.eventstore.AppendEventsTransactionRejectedException;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.messaging.commandhandling.CommandBus;
import org.axonframework.messaging.commandhandling.CommandHandlingComponent;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.commandhandling.annotation.AnnotatedCommandHandlingComponent;
import org.axonframework.messaging.commandhandling.annotation.Command;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.gateway.CommandDispatcher;
import org.axonframework.messaging.commandhandling.interception.InterceptingCommandHandlingComponent;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.annotation.AnnotatedHandlerInspector;
import org.axonframework.messaging.core.annotation.AnnotatedMessageHandlingMemberDefinition;
import org.axonframework.messaging.core.annotation.ClasspathHandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.annotation.MessageIdentifier;
import org.axonframework.messaging.core.annotation.InjectComponent;
import org.axonframework.messaging.core.annotation.MetadataValue;
import org.axonframework.messaging.core.annotation.MultiHandlerDefinition;
import org.axonframework.messaging.core.annotation.MultiHandlerEnhancerDefinition;
import org.axonframework.messaging.core.annotation.MultiParameterResolverFactory;
import org.axonframework.messaging.core.annotation.ParameterResolver;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.core.unitofwork.UnitOfWorkFactory;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.annotation.InjectEntity;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.axonframework.messaging.eventhandling.EventTestUtils.asEventMessage;

/**
 * Test class validating the {@link AppendCriteriaBuilder} on annotated command-handling components.
 */
class AppendCriteriaBuilderTest {

    private static final EventCriteria ACCOUNT_ONE = EventCriteria.havingTags(Tag.of("accountId", "one"));
    private static final QualifiedName CREDITS_USED = new QualifiedName(CreditsUsed.class);

    private static final List<Object> invocations = new ArrayList<>();
    private static final List<AppendCondition> appendConditions = new ArrayList<>();

    private Configuration configuration;

    @BeforeEach
    void setUp() {
        invocations.clear();
        appendConditions.clear();
        configuration = EventSourcingConfigurer.create().build();
    }

    @Nested
    class Matching {

        @Test
        void builderResultReplacesTheSourcingCriteriaOfTheCommandItAccepts() {
            // given
            var component = component(new CreditsHandler());

            // when
            handle(component, command(new UseCredits("one"))).join();

            // then
            assertThat(appendConditions).singleElement()
                                        .extracting(AppendCondition::criteria)
                                        .isEqualTo(ACCOUNT_ONE.withEventTypes(CREDITS_USED));
        }

        @Test
        void commandWithoutAMatchingBuilderKeepsItsSourcingCriteria() {
            // given a class with a builder for UseCredits only
            var component = component(new CreditsHandler());

            // when
            handle(component, command(new TopUpCredits("one"))).join();

            // then
            assertThat(invocations).isEmpty();
            assertThat(appendConditions).singleElement()
                                        .extracting(AppendCondition::criteria)
                                        .isEqualTo(ACCOUNT_ONE);
        }

        @Test
        void builderForASupertypeAppliesToEveryCommandOfThatType() {
            // given
            var component = component(new SupertypeBuilderHandler());

            // when
            handle(component, command(new UseCredits("one"))).join();
            handle(component, command(new TopUpCredits("one"))).join();

            // then
            assertThat(invocations).containsExactly(new UseCredits("one"), new TopUpCredits("one"));
        }

        @Test
        void commandMessageBuilderAppliesToEveryCommand() {
            // given
            var component = component(new CatchAllBuilderHandler());

            // when
            handle(component, command(new UseCredits("one"))).join();
            handle(component, command(new TopUpCredits("one"))).join();

            // then
            assertThat(invocations).containsExactly(new UseCredits("one"), new TopUpCredits("one"));
        }

        @Test
        void builderMatchesACommandWithACustomName() {
            // given a command whose name differs from its payload class
            var component = component(new RenamedCommandHandler());
            var renamed =
                    new GenericCommandMessage(new MessageType("credits.UseRenamed"), new UseRenamedCredits("one"));

            // when
            handle(component, renamed).join();

            // then
            assertThat(invocations).containsExactly(new UseRenamedCredits("one"));
        }

        @Test
        void builderDecidesItsCommandOnceWhenTheHandlerEnhancersAreComposedTwice() {
            // given the classpath enhancers twice, as when a framework composes them from several sources
            MultiHandlerEnhancerDefinition classpathEnhancers = ClasspathHandlerEnhancerDefinition.forClass(getClass());
            var handlerDefinition = MultiHandlerDefinition.ordered(
                    List.of(new AnnotatedMessageHandlingMemberDefinition()),
                    MultiHandlerEnhancerDefinition.ordered(classpathEnhancers, classpathEnhancers)
            );
            var component = new AnnotatedCommandHandlingComponent<>(
                    new CreditsHandler(),
                    configuration.getComponent(ParameterResolverFactory.class),
                    handlerDefinition,
                    configuration.getComponent(MessageTypeResolver.class),
                    configuration.getComponent(MessageConverter.class)
            );

            // when
            handle(component, command(new UseCredits("one"))).join();

            // then the builder is wrapped once, so it does not count as a second decision for the command
            assertThat(invocations).hasSize(1);
            assertThat(appendConditions).singleElement()
                                        .extracting(AppendCondition::criteria)
                                        .isEqualTo(ACCOUNT_ONE.withEventTypes(CREDITS_USED));
        }

        @Test
        void builderReceivesTheSerializedPayloadConvertedForTheHandler() {
            // given a payload as it arrives from a distributed command bus
            var component = component(new CreditsHandler());
            byte[] json = "{\"accountId\":\"one\"}".getBytes(StandardCharsets.UTF_8);
            var serialized = new GenericCommandMessage(new MessageType(UseCredits.class), json);

            // when
            handle(component, serialized).join();

            // then
            assertThat(invocations).containsExactly(new UseCredits("one"));
        }
    }

    @Nested
    class Parameters {

        @Test
        void builderReceivesSourcingCriteriaMetadataValuesAndComponents() {
            // given
            var component = component(new ParameterHandler());
            var command = command(new UseCredits("one")).andMetadata(Map.of("tenantId", "acme"));

            // when
            handle(component, command).join();

            // then
            assertThat(invocations).containsExactly(
                    ACCOUNT_ONE,
                    "acme",
                    configuration.getComponent(MessageTypeResolver.class),
                    configuration.getComponent(EventStore.class)
            );
        }
    }

    @Nested
    class CommandThatSourcesNothing {

        @Test
        void criteriaRejectASecondRegistrationOfTheSameKey() {
            // given
            var component = component(new RegistrationHandler());
            handle(component, command(new RegisterUsername("john"))).join();

            // when
            CompletableFuture<?> second = handle(component, command(new RegisterUsername("john")));

            // then
            assertThatThrownBy(second::join).hasRootCauseInstanceOf(AppendEventsTransactionRejectedException.class);
        }

        @Test
        void criteriaAcceptADifferentKey() {
            // given
            var component = component(new RegistrationHandler());
            handle(component, command(new RegisterUsername("john"))).join();

            // when
            CompletableFuture<?> other = handle(component, command(new RegisterUsername("jane")));

            // then
            assertThat(other).succeedsWithin(5, TimeUnit.SECONDS);
        }
    }

    @Nested
    class WithOtherDecisions {

        @Test
        void interceptorForAnotherCommandLeavesTheBuilderToDecide() {
            // given an interceptor with criteria for TopUpCredits only, around a builder for UseCredits
            CommandHandlingComponent component = new InterceptingCommandHandlingComponent(
                    List.of(AppendCriteriaInterceptor.create()
                                                     .forCommand(new QualifiedName(TopUpCredits.class), s -> s)),
                    component(new CreditsHandler())
            );

            // when
            CompletableFuture<?> use = handle(component, command(new UseCredits("one")));
            CompletableFuture<?> topUp = handle(component, command(new TopUpCredits("two")));

            // then both commands are decided once, so neither fails as a duplicate
            assertThat(use).succeedsWithin(5, TimeUnit.SECONDS);
            assertThat(topUp).succeedsWithin(5, TimeUnit.SECONDS);
            assertThat(invocations).containsExactly(new UseCredits("one"));
        }

        @Test
        void commandDispatchedFromAHandlerIsDecidedSeparately() {
            // given a catch-all builder on a handler that dispatches a second command while handling the first
            CommandBus commandBus = configuration.getComponent(CommandBus.class);
            commandBus.subscribe(component(new NestedDispatchHandler()));

            // when
            CompletableFuture<?> result = commandBus.dispatch(command(new UseCredits("outer")), null)
                                                    .orTimeout(5, TimeUnit.SECONDS);

            // then each dispatch gets its own unit of work, so the builder decides both without a duplicate failure
            assertThat(result).succeedsWithin(5, TimeUnit.SECONDS);
            assertThat(invocations).containsExactlyInAnyOrder(new UseCredits("outer"), new TopUpCredits("nested"));
        }
    }

    @Nested
    class Failures {

        @Test
        void twoBuildersMatchingOneCommandFailTheCommand() {
            // given
            var component = component(new OverlappingBuildersHandler());

            // when
            CompletableFuture<?> result = handle(component, command(new UseCredits("one")));

            // then
            assertThatThrownBy(result::join).hasMessageContaining("already defined them for this command");
        }

        @Test
        void superclassAndSubclassBuildersMatchingOneCommandFailTheCommand() {
            // given
            var component = component(new SubclassBuilderHandler());

            // when
            CompletableFuture<?> result = handle(component, command(new UseCredits("one")));

            // then
            assertThatThrownBy(result::join).hasMessageContaining("already defined them for this command");
        }

        @Test
        void interceptorAndBuilderDecidingOneCommandFailTheCommand() {
            // given a module-style interceptor deciding every command, around a component with a builder
            CommandHandlingComponent component = new InterceptingCommandHandlingComponent(
                    List.of(new AppendCriteriaInterceptor((command, sourcingCriteria, context) -> sourcingCriteria)),
                    component(new CreditsHandler())
            );

            // when
            CompletableFuture<?> result = handle(component, command(new UseCredits("one")));

            // then
            assertThatThrownBy(result::join).hasMessageContaining("already defined them for this command");
        }

        @Test
        void builderFailureFailsTheCommandWithItsOwnException() {
            // given
            var component = component(new FailingBuilderHandler());

            // when
            CompletableFuture<?> result = handle(component, command(new UseCredits("one")));

            // then
            assertThatThrownBy(result::join).hasRootCauseInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void builderReturningNullFailsTheCommand() {
            // given
            var component = component(new NullBuilderHandler());

            // when
            CompletableFuture<?> result = handle(component, command(new UseCredits("one")));

            // then
            assertThatThrownBy(result::join).hasMessageContaining("returned null");
        }

        @Test
        void builderWithAParameterThatIsNotAvailableYetFailsTheCommand() {
            // given a builder parameter whose value only arrives later, while builders run synchronously on append
            var component = component(new PendingParameterBuilderHandler(),
                                      new PendingValueParameterResolverFactory());

            // when
            CompletableFuture<?> result = handle(component, command(new UseCredits("one")));

            // then
            assertThatThrownBy(result::join).hasMessageContaining("must have parameters available without waiting");
        }

        @Test
        void builderInvokedOutsideAnInterceptorChainFails() {
            // given the builder as inspected, invoked without the interceptor chain that normally runs it
            MessageHandlingMember<? super CreditsHandler> builder =
                    AnnotatedHandlerInspector.inspectType(CreditsHandler.class,
                                                          configuration.getComponent(MessageTypeResolver.class),
                                                          configuration.getComponent(ParameterResolverFactory.class),
                                                          configuration.getComponent(HandlerDefinition.class),
                                                          Set.of())
                                             .getAllInterceptors()
                                             .values()
                                             .stream()
                                             .flatMap(Collection::stream)
                                             .findFirst()
                                             .orElseThrow();

            // when
            CompletableFuture<?> result = builder.handle(command(new UseCredits("one")),
                                                         new StubProcessingContext(),
                                                         null)
                                                 .first()
                                                 .asCompletableFuture();

            // then
            assertThatThrownBy(result::join)
                    .hasMessageContaining("only intercepts commands within an interceptor chain");
        }

        @Test
        void builderInjectingAnEntityIsRejectedAtStartup() {
            assertThatThrownBy(() -> component(new EntityInjectingBuilderHandler()))
                    .isInstanceOf(AxonConfigurationException.class)
                    .hasMessageContaining("must resolve without waiting");
        }

        @Test
        void builderWithAnAnnotatedParameterOutsideTheAllowListIsRejectedAtStartup() {
            // the allow-list starts strict on purpose: allowing more later is not a breaking change
            assertThatThrownBy(() -> component(new MessageIdentifierBuilderHandler()))
                    .isInstanceOf(AxonConfigurationException.class)
                    .hasMessageContaining("must resolve without waiting");
        }

        @Test
        void nonStaticBuilderIsRejectedAtStartup() {
            assertThatThrownBy(() -> component(new NonStaticBuilderHandler()))
                    .isInstanceOf(AxonConfigurationException.class)
                    .hasMessageContaining("must be static and return EventCriteria");
        }

        @Test
        void builderNotReturningEventCriteriaIsRejectedAtStartup() {
            assertThatThrownBy(() -> component(new StringBuilderHandler()))
                    .isInstanceOf(AxonConfigurationException.class)
                    .hasMessageContaining("must be static and return EventCriteria");
        }
    }

    private <T> AnnotatedCommandHandlingComponent<T> component(T target) {
        return component(target, configuration.getComponent(ParameterResolverFactory.class));
    }

    private <T> AnnotatedCommandHandlingComponent<T> component(T target, ParameterResolverFactory additionalFactory) {
        return component(target, MultiParameterResolverFactory.ordered(
                additionalFactory, configuration.getComponent(ParameterResolverFactory.class)
        ));
    }

    private <T> AnnotatedCommandHandlingComponent<T> component(T target, MultiParameterResolverFactory factory) {
        return new AnnotatedCommandHandlingComponent<>(
                target,
                factory,
                configuration.getComponent(HandlerDefinition.class),
                configuration.getComponent(MessageTypeResolver.class),
                configuration.getComponent(MessageConverter.class)
        );
    }

    private CompletableFuture<?> handle(CommandHandlingComponent component, CommandMessage command) {
        return configuration.getComponent(UnitOfWorkFactory.class)
                            .create()
                            .executeWithResult(context -> component.handle(command, context)
                                                                   .first()
                                                                   .asCompletableFuture())
                            .orTimeout(5, TimeUnit.SECONDS);
    }

    private static CommandMessage command(Object payload) {
        return new GenericCommandMessage(new MessageType(payload.getClass()), payload);
    }

    /**
     * Sources the account's events and appends one, recording the append condition the transaction ends up with.
     */
    private static void sourceAndAppend(ProcessingContext context, String accountId) {
        var transaction = context.component(EventStore.class).transaction(context);
        transaction.source(SourcingCondition.conditionFor(EventCriteria.havingTags("accountId", accountId)))
                   .reduce(0, (count, entry) -> count + 1)
                   .orTimeout(5, TimeUnit.SECONDS)
                   .join();
        // registered after the builder's override, so it observes the condition that override produced
        transaction.overrideAppendCondition(condition -> {
            appendConditions.add(condition);
            return condition;
        });
        transaction.appendEvent(asEventMessage(new CreditsUsed(accountId)));
    }

    sealed interface CreditsCommand permits UseCredits, TopUpCredits {

    }

    record UseCredits(String accountId) implements CreditsCommand {

    }

    record TopUpCredits(String accountId) implements CreditsCommand {

    }

    @Command(namespace = "credits", name = "UseRenamed")
    record UseRenamedCredits(String accountId) {

    }

    record CreditsUsed(@EventTag(key = "accountId") String accountId) {

    }

    record RegisterUsername(String username) {

    }

    record UsernameRegistered(@EventTag(key = "username") String username) {

    }

    static class CreditsHandler {

        @CommandHandler
        void handle(UseCredits command, ProcessingContext context) {
            sourceAndAppend(context, command.accountId());
        }

        @CommandHandler
        void handle(TopUpCredits command, ProcessingContext context) {
            sourceAndAppend(context, command.accountId());
        }

        @AppendCriteriaBuilder
        static EventCriteria use(UseCredits command, EventCriteria sourcingCriteria) {
            invocations.add(command);
            return sourcingCriteria.withEventTypes(CREDITS_USED);
        }
    }

    static class RenamedCommandHandler {

        @CommandHandler
        void handle(UseRenamedCredits command, ProcessingContext context) {
            sourceAndAppend(context, command.accountId());
        }

        @AppendCriteriaBuilder
        static EventCriteria use(UseRenamedCredits command, EventCriteria sourcingCriteria) {
            invocations.add(command);
            return sourcingCriteria;
        }
    }

    static class SupertypeBuilderHandler extends CreditsHandlerWithoutBuilder {

        @AppendCriteriaBuilder
        static EventCriteria credits(CreditsCommand command, EventCriteria sourcingCriteria) {
            invocations.add(command);
            return sourcingCriteria;
        }
    }

    static class CatchAllBuilderHandler extends CreditsHandlerWithoutBuilder {

        @AppendCriteriaBuilder
        static EventCriteria all(CommandMessage command, EventCriteria sourcingCriteria) {
            invocations.add(command.payload());
            return sourcingCriteria;
        }
    }

    static class CreditsHandlerWithoutBuilder {

        @CommandHandler
        void handle(UseCredits command, ProcessingContext context) {
            sourceAndAppend(context, command.accountId());
        }

        @CommandHandler
        void handle(TopUpCredits command, ProcessingContext context) {
            sourceAndAppend(context, command.accountId());
        }
    }

    static class ParameterHandler {

        @CommandHandler
        void handle(UseCredits command, ProcessingContext context) {
            sourceAndAppend(context, command.accountId());
        }

        @AppendCriteriaBuilder
        static EventCriteria criteria(UseCredits command,
                                      EventCriteria sourcingCriteria,
                                      @MetadataValue("tenantId") String tenantId,
                                      MessageTypeResolver messageTypeResolver,
                                      @InjectComponent EventStore eventStore) {
            invocations.addAll(List.of(sourcingCriteria, tenantId, messageTypeResolver, eventStore));
            return sourcingCriteria;
        }
    }

    static class RegistrationHandler {

        @CommandHandler
        void handle(RegisterUsername command, EventAppender appender) {
            appender.append(new UsernameRegistered(command.username()));
        }

        @AppendCriteriaBuilder
        static EventCriteria uniqueUsername(RegisterUsername command) {
            return EventCriteria.havingTags("username", command.username());
        }
    }

    static class OverlappingBuildersHandler extends CreditsHandlerWithoutBuilder {

        @AppendCriteriaBuilder
        static EventCriteria use(UseCredits command, EventCriteria sourcingCriteria) {
            return sourcingCriteria;
        }

        @AppendCriteriaBuilder
        static EventCriteria all(CommandMessage command, EventCriteria sourcingCriteria) {
            return sourcingCriteria;
        }
    }

    static class FailingBuilderHandler extends CreditsHandlerWithoutBuilder {

        @AppendCriteriaBuilder
        static EventCriteria use(UseCredits command) {
            throw new UnsupportedOperationException("builder failed");
        }
    }

    static class NullBuilderHandler extends CreditsHandlerWithoutBuilder {

        @AppendCriteriaBuilder
        static EventCriteria use(UseCredits command) {
            return null;
        }
    }

    static class NonStaticBuilderHandler extends CreditsHandlerWithoutBuilder {

        @AppendCriteriaBuilder
        EventCriteria use(UseCredits command) {
            return EventCriteria.havingAnyTag();
        }
    }

    static class PendingParameterBuilderHandler extends CreditsHandlerWithoutBuilder {

        @AppendCriteriaBuilder
        static EventCriteria use(UseCredits command, PendingValue value) {
            return EventCriteria.havingAnyTag();
        }
    }

    record PendingValue() {

    }

    /**
     * Resolves a {@link PendingValue} parameter to a value that never arrives.
     */
    static class PendingValueParameterResolverFactory implements ParameterResolverFactory {

        @Nullable
        @Override
        public ParameterResolver<PendingValue> createInstance(Executable executable,
                                                              Parameter[] parameters,
                                                              int parameterIndex) {
            if (parameters[parameterIndex].getType() != PendingValue.class) {
                return null;
            }
            return new ParameterResolver<>() {
                @Override
                public CompletableFuture<PendingValue> resolveParameterValue(ProcessingContext context) {
                    return new CompletableFuture<>();
                }

                @Override
                public boolean matches(ProcessingContext context) {
                    return true;
                }
            };
        }
    }

    static class SubclassBuilderHandler extends SupertypeBuilderHandler {

        @AppendCriteriaBuilder
        static EventCriteria use(UseCredits command, EventCriteria sourcingCriteria) {
            return sourcingCriteria;
        }
    }

    static class NestedDispatchHandler {

        @CommandHandler
        void handle(UseCredits command, ProcessingContext context, CommandDispatcher dispatcher) {
            dispatcher.send(new TopUpCredits("nested")).getResultMessage().orTimeout(5, TimeUnit.SECONDS).join();
            sourceAndAppend(context, command.accountId());
        }

        @CommandHandler
        void handle(TopUpCredits command, ProcessingContext context) {
            sourceAndAppend(context, command.accountId());
        }

        @AppendCriteriaBuilder
        static EventCriteria all(CommandMessage command, EventCriteria sourcingCriteria) {
            invocations.add(command.payload());
            return sourcingCriteria;
        }
    }

    static class EntityInjectingBuilderHandler extends CreditsHandlerWithoutBuilder {

        @AppendCriteriaBuilder
        static EventCriteria use(UseCredits command, @InjectEntity(idProperty = "accountId") Object account) {
            return EventCriteria.havingAnyTag();
        }
    }

    static class MessageIdentifierBuilderHandler extends CreditsHandlerWithoutBuilder {

        @AppendCriteriaBuilder
        static EventCriteria use(UseCredits command, @MessageIdentifier String identifier) {
            return EventCriteria.havingAnyTag();
        }
    }

    static class StringBuilderHandler extends CreditsHandlerWithoutBuilder {

        @AppendCriteriaBuilder
        static String use(UseCredits command) {
            return "not criteria";
        }
    }
}
