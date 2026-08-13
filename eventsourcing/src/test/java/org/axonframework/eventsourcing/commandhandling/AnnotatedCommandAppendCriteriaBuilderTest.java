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

import org.axonframework.common.configuration.Configuration;
import org.axonframework.conversion.PassThroughConverter;
import org.axonframework.eventsourcing.annotation.AppendCriteriaBuilder;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.messaging.commandhandling.CommandHandlingComponent;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.commandhandling.annotation.AnnotatedCommandHandlingComponent;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.core.FluxUtils;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.core.annotation.MetadataValue;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.conversion.DelegatingMessageConverter;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.annotation.InjectEntity;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.axonframework.common.util.AssertUtils.awaitExceptionalCompletion;
import static org.axonframework.common.util.AssertUtils.awaitSuccessfulCompletion;
import static org.axonframework.messaging.core.unitofwork.UnitOfWorkTestUtils.aUnitOfWork;

/**
 * Test class validating {@link AppendCriteriaBuilder} on external annotated command-handling classes.
 */
class AnnotatedCommandAppendCriteriaBuilderTest {

    private static final Tag ACCOUNT_ONE = Tag.of("accountId", "one");
    private static final EventCriteria ACCOUNT_CRITERIA = EventCriteria.havingTags(ACCOUNT_ONE);

    @Nested
    class Resolving {

        @Test
        void oneBuilderAppliesSeparatelyToEveryHandlerOnTheClass() {
            // given
            CommonBuilderHandler.receivedCommands.clear();
            CommonBuilderHandler.receivedSourcingCriteria.clear();
            Configuration configuration = configuration();
            CommonBuilderHandler target = new CommonBuilderHandler(configuration.getComponent(EventStore.class));
            CommandHandlingComponent component = component(target, configuration);
            CommandMessage use = command(UseCredits.class, new UseCredits("one"), Map.of());
            CommandMessage topUp = command(TopUpCredits.class, new TopUpCredits("one"), Map.of());

            // when
            handleSuccessfully(component, use);
            handleSuccessfully(component, topUp);

            // then
            assertThat(CommonBuilderHandler.receivedCommands)
                    .containsExactly(new UseCredits("one"), new TopUpCredits("one"));
            assertThat(CommonBuilderHandler.receivedSourcingCriteria)
                    .containsExactly(ACCOUNT_CRITERIA, ACCOUNT_CRITERIA);
        }

        @Test
        void commandMessageBuilderDistinguishesQualifiedNamesSharingAPayloadType() {
            // given
            QualifiedBuilderHandler.receivedNames.clear();
            Configuration configuration = configuration();
            CommandHandlingComponent component = component(
                    new QualifiedBuilderHandler(configuration.getComponent(EventStore.class)), configuration
            );
            CommandMessage create = command("credits.Create", new SharedCommand("one"), Map.of());
            CommandMessage archive = command("credits.Archive", new SharedCommand("one"), Map.of());

            // when
            handleSuccessfully(component, create);
            handleSuccessfully(component, archive);

            // then
            assertThat(QualifiedBuilderHandler.receivedNames).containsExactly("credits.Create", "credits.Archive");
        }

        @Test
        void builderReceivesSourcingCriteriaMetadataContextConfigurationAndComponents() {
            // given
            ParameterBuilderHandler.reset();
            CriteriaFactory factory = new CriteriaFactory();
            Configuration configuration = configuration(factory);
            ParameterBuilderHandler target = new ParameterBuilderHandler(
                    configuration.getComponent(EventStore.class)
            );
            CommandHandlingComponent component = component(target, configuration);
            CommandMessage command = command(
                    UseCredits.class,
                    new UseCredits("one"),
                    Map.of("tenantId", "tenant-one")
            );

            // when
            handleSuccessfully(component, command);

            // then
            assertThat(ParameterBuilderHandler.receivedCommand).isSameAs(command);
            assertThat(ParameterBuilderHandler.receivedSourcingCriteria).isEqualTo(ACCOUNT_CRITERIA);
            assertThat(ParameterBuilderHandler.receivedMetadata).isEqualTo(command.metadata());
            assertThat(ParameterBuilderHandler.receivedTenant).isEqualTo("tenant-one");
            assertThat(ParameterBuilderHandler.receivedContext).isNotNull();
            assertThat(ParameterBuilderHandler.receivedConfiguration.getComponent(CriteriaFactory.class))
                    .isSameAs(factory);
            assertThat(ParameterBuilderHandler.receivedFactory).isSameAs(factory);
        }

        @Test
        void classWithoutBuilderRetainsExistingCommandHandlingBehavior() {
            // given
            Configuration configuration = configuration();
            NoBuilderHandler target = new NoBuilderHandler(configuration.getComponent(EventStore.class));
            CommandHandlingComponent component = component(target, configuration);

            // when
            handleSuccessfully(component, command(UseCredits.class, new UseCredits("one"), Map.of()));

            // then
            assertThat(target.handled).isTrue();
        }
    }

    @Nested
    class Validation {

        @Test
        void rejectsMoreThanOneBuilderEvenWhenCommandTypesDoNotOverlap() {
            // given
            Configuration configuration = configuration();

            // when / then
            assertThatThrownBy(() -> component(new TwoBuilderHandler(), configuration))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("more than one @AppendCriteriaBuilder");
        }

        @Test
        void rejectsBuilderThatDoesNotCoverEveryHandlerOnTheClass() {
            // given
            Configuration configuration = configuration();

            // when / then
            assertThatThrownBy(() -> component(new PartialBuilderHandler(), configuration))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot accept handled command payload");
        }

        @Test
        void rejectsNonStaticBuilderInvalidReturnTypeAndUnsupportedParameters() {
            // given
            Configuration configuration = configuration();

            // when / then
            assertThatThrownBy(() -> component(new NonStaticBuilderHandler(), configuration))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be static");
            assertThatThrownBy(() -> component(new InvalidReturnBuilderHandler(), configuration))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must return EventCriteria");
            assertThatThrownBy(() -> component(new EventAppenderBuilderHandler(), configuration))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unsupported parameter type");
            assertThatThrownBy(() -> component(new EntityBuilderHandler(), configuration))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unsupported parameter type");
        }

        @Test
        void nullResultAndMissingRequiredMetadataPreventCommit() {
            // given
            Configuration configuration = configuration();
            EventStorageEngine storageEngine = configuration.getComponent(EventStorageEngine.class);
            CommandHandlingComponent nullComponent = component(
                    new NullBuilderHandler(configuration.getComponent(EventStore.class)), configuration
            );
            CommandHandlingComponent metadataComponent = component(
                    new RequiredMetadataBuilderHandler(configuration.getComponent(EventStore.class)), configuration
            );

            // when / then
            assertCommitFails(nullComponent, "returned null");
            assertCommitFails(metadataComponent, "Required metadata value [tenantId] is missing");
            assertThat(storageEngine.latestToken().join().position()).hasValue(-1);
        }

        @Test
        void annotationAndDeclarativeDefinitionsOnTheSameComponentFailExplicitly() {
            // given
            Configuration configuration = configuration();
            CommandHandlingComponent annotated = component(
                    new QualifiedBuilderHandler(configuration.getComponent(EventStore.class)), configuration
            );
            CommandHandlingComponent duplicate = new CommandAppendCriteriaHandler(
                    annotated,
                    configuration.getComponent(EventStore.class),
                    (command, context, sourcingCriteria) -> sourcingCriteria
            );
            CommandMessage command = command("credits.Create", new SharedCommand("one"), Map.of());

            // when / then
            assertThatThrownBy(() -> duplicate.handle(
                                                     command,
                                                     new org.axonframework.messaging.core.unitofwork.StubProcessingContext()
                                             )
                                             .asCompletableFuture()
                                             .join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining("Append criteria have already been defined");
        }
    }

    private static Configuration configuration() {
        return EventSourcingConfigurer.create().build();
    }

    private static Configuration configuration(CriteriaFactory factory) {
        return EventSourcingConfigurer.create()
                                      .componentRegistry(registry -> registry.registerComponent(
                                              CriteriaFactory.class, ignored -> factory
                                      ))
                                      .build();
    }

    private static <T> AnnotatedCommandHandlingComponent<T> component(T target, Configuration configuration) {
        return new AnnotatedCommandHandlingComponent<>(
                target,
                configuration.getComponent(ParameterResolverFactory.class),
                configuration.getComponent(HandlerDefinition.class),
                configuration.getComponent(org.axonframework.messaging.core.MessageTypeResolver.class),
                configuration.getOptionalComponent(MessageConverter.class)
                             .orElseGet(() -> new DelegatingMessageConverter(PassThroughConverter.INSTANCE))
        );
    }

    private static void handleSuccessfully(CommandHandlingComponent component, CommandMessage command) {
        var uow = aUnitOfWork();
        uow.runOnPreInvocation(context -> component.handle(command, context));
        awaitSuccessfulCompletion(uow.execute());
    }

    private static void assertCommitFails(CommandHandlingComponent component, String message) {
        var uow = aUnitOfWork();
        uow.runOnPreInvocation(context -> component.handle(
                command(UseCredits.class, new UseCredits("one"), Map.of()), context
        ));
        assertThatThrownBy(() -> awaitExceptionalCompletion(uow.execute()))
                .isInstanceOf(CompletionException.class)
                .hasStackTraceContaining(message);
    }

    private static CommandMessage command(Class<?> payloadType, Object payload, Map<String, String> metadata) {
        return new GenericCommandMessage(new MessageType(payloadType), payload, metadata);
    }

    private static CommandMessage command(String commandName, Object payload, Map<String, String> metadata) {
        return new GenericCommandMessage(new MessageType(commandName), payload, metadata);
    }

    private static void sourceAndAppend(EventStore eventStore,
                                        ProcessingContext context,
                                        String accountId) {
        EventCriteria criteria = EventCriteria.havingTags("accountId", accountId);
        FluxUtils.of(eventStore.transaction(context).source(SourcingCondition.conditionFor(criteria))).blockLast();
        eventStore.transaction(context).appendEvent(new GenericEventMessage(
                new MessageType(CreditsChanged.class), new CreditsChanged(accountId)
        ));
    }

    private interface CreditsCommand {

        String accountId();
    }

    private record UseCredits(String accountId) implements CreditsCommand {
    }

    private record TopUpCredits(String accountId) implements CreditsCommand {
    }

    private record SharedCommand(String accountId) {
    }

    private record CreditsChanged(String accountId) {
    }

    private static class CommonBuilderHandler {

        private static final List<CreditsCommand> receivedCommands = new ArrayList<>();
        private static final List<EventCriteria> receivedSourcingCriteria = new ArrayList<>();
        private final EventStore eventStore;

        private CommonBuilderHandler(EventStore eventStore) {
            this.eventStore = eventStore;
        }

        @CommandHandler
        void handle(UseCredits command, ProcessingContext context) {
            sourceAndAppend(eventStore, context, command.accountId());
        }

        @CommandHandler
        void handle(TopUpCredits command, ProcessingContext context) {
            sourceAndAppend(eventStore, context, command.accountId());
        }

        @AppendCriteriaBuilder
        static EventCriteria criteria(CreditsCommand command, EventCriteria sourcingCriteria) {
            receivedCommands.add(command);
            receivedSourcingCriteria.add(sourcingCriteria);
            return command instanceof UseCredits
                    ? sourcingCriteria.replaceEventTypes(CreditsChanged.class)
                    : sourcingCriteria;
        }
    }

    private static class QualifiedBuilderHandler {

        private static final List<String> receivedNames = new ArrayList<>();
        private final EventStore eventStore;

        private QualifiedBuilderHandler(EventStore eventStore) {
            this.eventStore = eventStore;
        }

        @CommandHandler(commandName = "credits.Create")
        void create(SharedCommand command, ProcessingContext context) {
            append(context, command);
        }

        @CommandHandler(commandName = "credits.Archive")
        void archive(SharedCommand command, ProcessingContext context) {
            append(context, command);
        }

        private void append(ProcessingContext context, SharedCommand command) {
            eventStore.transaction(context).appendEvent(new GenericEventMessage(
                    new MessageType(CreditsChanged.class), new CreditsChanged(command.accountId())
            ));
        }

        @AppendCriteriaBuilder
        static EventCriteria criteria(CommandMessage command, EventCriteria sourcingCriteria) {
            String commandName = command.type().qualifiedName().fullName();
            receivedNames.add(commandName);
            return EventCriteria.havingTags("operation", commandName);
        }
    }

    private static class ParameterBuilderHandler {

        private static CommandMessage receivedCommand;
        private static EventCriteria receivedSourcingCriteria;
        private static Metadata receivedMetadata;
        private static String receivedTenant;
        private static ProcessingContext receivedContext;
        private static Configuration receivedConfiguration;
        private static CriteriaFactory receivedFactory;
        private final EventStore eventStore;

        private ParameterBuilderHandler(EventStore eventStore) {
            this.eventStore = eventStore;
        }

        private static void reset() {
            receivedCommand = null;
            receivedSourcingCriteria = null;
            receivedMetadata = null;
            receivedTenant = null;
            receivedContext = null;
            receivedConfiguration = null;
            receivedFactory = null;
        }

        @CommandHandler
        void handle(UseCredits command, ProcessingContext context) {
            sourceAndAppend(eventStore, context, command.accountId());
        }

        @AppendCriteriaBuilder
        static EventCriteria criteria(UseCredits command,
                                      EventCriteria sourcingCriteria,
                                      CommandMessage commandMessage,
                                      Metadata metadata,
                                      @MetadataValue(value = "tenantId", required = true) String tenant,
                                      ProcessingContext context,
                                      Configuration configuration,
                                      CriteriaFactory factory) {
            receivedCommand = commandMessage;
            receivedSourcingCriteria = sourcingCriteria;
            receivedMetadata = metadata;
            receivedTenant = tenant;
            receivedContext = context;
            receivedConfiguration = configuration;
            receivedFactory = factory;
            return factory.criteria(command.accountId(), tenant);
        }
    }

    private static class CriteriaFactory {

        private EventCriteria criteria(String accountId, String tenantId) {
            return EventCriteria.havingTags("accountId", accountId, "tenantId", tenantId);
        }
    }

    private static class NoBuilderHandler {

        private final EventStore eventStore;
        private boolean handled;

        private NoBuilderHandler(EventStore eventStore) {
            this.eventStore = eventStore;
        }

        @CommandHandler
        void handle(UseCredits command, ProcessingContext context) {
            handled = true;
            sourceAndAppend(eventStore, context, command.accountId());
        }
    }

    private static class TwoBuilderHandler {

        @CommandHandler
        void handle(UseCredits command) {
        }

        @AppendCriteriaBuilder
        static EventCriteria use(UseCredits command) {
            return ACCOUNT_CRITERIA;
        }

        @AppendCriteriaBuilder
        static EventCriteria topUp(TopUpCredits command) {
            return ACCOUNT_CRITERIA;
        }
    }

    private static class PartialBuilderHandler {

        @CommandHandler
        void handle(UseCredits command) {
        }

        @CommandHandler
        void handle(TopUpCredits command) {
        }

        @AppendCriteriaBuilder
        static EventCriteria criteria(UseCredits command) {
            return ACCOUNT_CRITERIA;
        }
    }

    private static class NonStaticBuilderHandler {

        @CommandHandler
        void handle(UseCredits command) {
        }

        @AppendCriteriaBuilder
        EventCriteria criteria(UseCredits command) {
            return ACCOUNT_CRITERIA;
        }
    }

    private static class InvalidReturnBuilderHandler {

        @CommandHandler
        void handle(UseCredits command) {
        }

        @AppendCriteriaBuilder
        static String criteria(UseCredits command) {
            return "invalid";
        }
    }

    private static class EventAppenderBuilderHandler {

        @CommandHandler
        void handle(UseCredits command) {
        }

        @AppendCriteriaBuilder
        static EventCriteria criteria(UseCredits command, EventAppender appender) {
            return ACCOUNT_CRITERIA;
        }
    }

    private static class EntityBuilderHandler {

        @CommandHandler
        void handle(UseCredits command) {
        }

        @AppendCriteriaBuilder
        static EventCriteria criteria(UseCredits command, @InjectEntity Object entity) {
            return ACCOUNT_CRITERIA;
        }
    }

    private static class NullBuilderHandler {

        private final EventStore eventStore;

        private NullBuilderHandler(EventStore eventStore) {
            this.eventStore = eventStore;
        }

        @CommandHandler
        void handle(UseCredits command, ProcessingContext context) {
            eventStore.transaction(context).appendEvent(new GenericEventMessage(
                    new MessageType(CreditsChanged.class), new CreditsChanged(command.accountId())
            ));
        }

        @AppendCriteriaBuilder
        static EventCriteria criteria(UseCredits command) {
            return null;
        }
    }

    private static class RequiredMetadataBuilderHandler {

        private final EventStore eventStore;

        private RequiredMetadataBuilderHandler(EventStore eventStore) {
            this.eventStore = eventStore;
        }

        @CommandHandler
        void handle(UseCredits command, ProcessingContext context) {
            eventStore.transaction(context).appendEvent(new GenericEventMessage(
                    new MessageType(CreditsChanged.class), new CreditsChanged(command.accountId())
            ));
        }

        @AppendCriteriaBuilder
        static EventCriteria criteria(UseCredits command,
                                      @MetadataValue(value = "tenantId", required = true) String tenantId) {
            return EventCriteria.havingTags("tenantId", tenantId);
        }
    }
}
