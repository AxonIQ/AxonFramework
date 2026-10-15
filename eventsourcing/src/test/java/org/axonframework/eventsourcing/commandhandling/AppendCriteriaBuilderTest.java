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
import org.axonframework.conversion.PassThroughConverter;
import org.axonframework.eventsourcing.commandhandling.annotation.AppendCriteriaBuilder;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;
import org.axonframework.eventsourcing.eventstore.AppendCondition;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.EventStorageEngine;
import org.axonframework.eventsourcing.eventstore.SourcingCondition;
import org.axonframework.messaging.commandhandling.CommandHandlingComponent;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.GenericCommandMessage;
import org.axonframework.messaging.commandhandling.annotation.AnnotatedCommandHandlingComponent;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.interception.InterceptingCommandHandlingComponent;
import org.axonframework.messaging.core.FluxUtils;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.Metadata;
import org.axonframework.messaging.core.annotation.ClasspathHandlerDefinition;
import org.axonframework.messaging.core.annotation.HandlerDefinition;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;
import org.axonframework.messaging.core.annotation.MetadataValue;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.core.annotation.WrappedMessageHandlingMember;
import org.axonframework.messaging.core.conversion.DelegatingMessageConverter;
import org.axonframework.messaging.core.conversion.MessageConverter;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.core.unitofwork.StubProcessingContext;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.eventstreaming.Tag;
import org.axonframework.modelling.annotation.InjectEntity;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.axonframework.common.util.AssertUtils.awaitExceptionalCompletion;
import static org.axonframework.common.util.AssertUtils.awaitSuccessfulCompletion;
import static org.axonframework.messaging.core.unitofwork.UnitOfWorkTestUtils.aUnitOfWork;

/**
 * Test class validating {@link AppendCriteriaBuilder} on external annotated command-handling classes.
 */
class AppendCriteriaBuilderTest {

    private static final Tag ACCOUNT_ONE = Tag.of("accountId", "one");
    private static final EventCriteria ACCOUNT_CRITERIA = EventCriteria.havingTags(ACCOUNT_ONE);

    @Nested
    class Resolving {

        @Test
        void builderForASharedSupertypeAppliesSeparatelyToEveryHandlerOnTheClass() {
            // given
            CommonBuilderHandler.receivedCommands.clear();
            CommonBuilderHandler.receivedSourcingCriteria.clear();
            CommonBuilderHandler.resolvedCriteria.clear();
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
            // the sealed switch narrows only UseCredits, TopUpCredits keeps its sourcing criteria
            assertThat(CommonBuilderHandler.resolvedCriteria)
                    .containsExactly(ACCOUNT_CRITERIA.replaceEventTypes(CreditsChanged.class), ACCOUNT_CRITERIA);
        }

        @Test
        void eachBuilderAppliesToTheCommandTypeItDeclares() {
            // given a class declaring one builder per command type
            PerCommandBuilderHandler.receivedCommands.clear();
            Configuration configuration = configuration();
            CommandHandlingComponent component = component(
                    new PerCommandBuilderHandler(configuration.getComponent(EventStore.class)), configuration
            );

            // when
            handleSuccessfully(component, command(UseCredits.class, new UseCredits("one"), Map.of()));
            handleSuccessfully(component, command(TopUpCredits.class, new TopUpCredits("one"), Map.of()));

            // then
            assertThat(PerCommandBuilderHandler.receivedCommands)
                    .containsExactly("use:" + new UseCredits("one"), "topUp:" + new TopUpCredits("one"));
        }

        @Test
        void mostSpecificBuilderWinsOverABuilderForASupertype() {
            // given a builder for UseCredits next to one for the CreditsCommand supertype
            SpecificAndGeneralBuilderHandler.receivedCommands.clear();
            Configuration configuration = configuration();
            CommandHandlingComponent component = component(
                    new SpecificAndGeneralBuilderHandler(configuration.getComponent(EventStore.class)), configuration
            );

            // when
            handleSuccessfully(component, command(UseCredits.class, new UseCredits("one"), Map.of()));
            handleSuccessfully(component, command(TopUpCredits.class, new TopUpCredits("one"), Map.of()));

            // then UseCredits gets its own builder, TopUpCredits the supertype's
            assertThat(SpecificAndGeneralBuilderHandler.receivedCommands)
                    .containsExactly("specific:" + new UseCredits("one"), "general:" + new TopUpCredits("one"));
        }

        @Test
        void commandMessageBuilderCoversCommandsWithoutAPayloadTypedBuilder() {
            // given
            TypedAndCatchAllBuilderHandler.receivedCommands.clear();
            Configuration configuration = configuration();
            CommandHandlingComponent component = component(
                    new TypedAndCatchAllBuilderHandler(configuration.getComponent(EventStore.class)), configuration
            );

            // when
            handleSuccessfully(component, command(UseCredits.class, new UseCredits("one"), Map.of()));
            handleSuccessfully(component, command(TopUpCredits.class, new TopUpCredits("one"), Map.of()));

            // then
            assertThat(TypedAndCatchAllBuilderHandler.receivedCommands)
                    .containsExactly("typed:" + new UseCredits("one"), "catchAll:" + new TopUpCredits("one"));
        }

        @Test
        void builderResultBecomesTheAppendCriteriaOfTheTransaction() {
            // given
            AtomicReference<AppendCondition> finalCondition = new AtomicReference<>();
            Configuration configuration = configuration();
            CommandHandlingComponent component = component(
                    new ObservingBuilderHandler(configuration.getComponent(EventStore.class), finalCondition),
                    configuration
            );

            // when
            handleSuccessfully(component, command(UseCredits.class, new UseCredits("one"), Map.of()));

            // then
            assertThat(finalCondition.get().criteria())
                    .isEqualTo(ACCOUNT_CRITERIA.replaceEventTypes(CreditsChanged.class));
        }

        @Test
        void effectivePayloadTypeDecidesTheBuilderWhenTheAnnotationDeclaresABroaderType() {
            // given a handler annotated with payloadType CreditsCommand, while its parameter only accepts UseCredits
            BroaderAnnotatedPayloadHandler.receivedCommands.clear();
            Configuration configuration = configuration();
            CommandHandlingComponent component = component(
                    new BroaderAnnotatedPayloadHandler(configuration.getComponent(EventStore.class)), configuration
            );

            // when
            handleSuccessfully(component, command(UseCredits.class, new UseCredits("one"), Map.of()));

            // then the builder for UseCredits applies, as UseCredits is what the handler accepts
            assertThat(BroaderAnnotatedPayloadHandler.receivedCommands).containsExactly("specific");
        }

        @Test
        void annotatedPayloadTypeOfACommandMessageHandlerSelectsTheTypedBuilder() {
            // given
            TypedCommandMessageHandler.receivedCommands.clear();
            Configuration configuration = configuration();
            CommandHandlingComponent component = component(
                    new TypedCommandMessageHandler(configuration.getComponent(EventStore.class)), configuration
            );

            // when
            handleSuccessfully(component, command(UseCredits.class, new UseCredits("one"), Map.of()));

            // then
            assertThat(TypedCommandMessageHandler.receivedCommands).containsExactly(new UseCredits("one"));
        }

        @Test
        void bridgeMethodOfAGenericHandlerDoesNotNeedABuilderOfItsOwn() {
            // given a handler implementing a generic method, for which the compiler adds an annotated bridge method
            BridgedHandler.receivedCommands.clear();
            Configuration configuration = configuration();
            CommandHandlingComponent component = component(
                    new BridgedHandler(configuration.getComponent(EventStore.class)), configuration
            );

            // when
            handleSuccessfully(component, command(UseCredits.class, new UseCredits("one"), Map.of()));

            // then
            assertThat(BridgedHandler.receivedCommands).containsExactly(new UseCredits("one"));
        }

        @Test
        void builderAppliesWhenAnotherEnhancerAlreadyWrappedTheCommandHandler() {
            // given a handler definition whose members are wrapped before this one decorates them
            PerCommandBuilderHandler.receivedCommands.clear();
            Configuration configuration = configuration();
            HandlerDefinition base = ClasspathHandlerDefinition.forClass(AppendCriteriaBuilderTest.class);
            HandlerDefinition wrapping = new HandlerDefinition() {
                @Override
                public <T> Optional<MessageHandlingMember<T>> createHandler(
                        Class<T> declaringType,
                        Method method,
                        ParameterResolverFactory parameterResolverFactory,
                        Function<Object, MessageStream<?>> messageStreamResolver
                ) {
                    return base.createHandler(declaringType, method, parameterResolverFactory, messageStreamResolver)
                               .map(member -> new WrappedMessageHandlingMember<>(member) {
                               });
                }
            };
            CommandHandlingComponent component = new AnnotatedCommandHandlingComponent<>(
                    new PerCommandBuilderHandler(configuration.getComponent(EventStore.class)),
                    configuration.getComponent(ParameterResolverFactory.class),
                    new AppendCriteriaHandlerDefinition(wrapping, configuration),
                    configuration.getComponent(org.axonframework.messaging.core.MessageTypeResolver.class),
                    new DelegatingMessageConverter(PassThroughConverter.INSTANCE)
            );

            // when
            handleSuccessfully(component, command(UseCredits.class, new UseCredits("one"), Map.of()));

            // then
            assertThat(PerCommandBuilderHandler.receivedCommands).containsExactly("use:" + new UseCredits("one"));
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
        void rejectsTwoBuildersForTheSameCommandTypeOnOneClass() {
            // given
            Configuration configuration = configuration();

            // when / then
            assertThatThrownBy(() -> component(new DuplicateTypeBuilderHandler(), configuration))
                    .isInstanceOf(AxonConfigurationException.class)
                    .hasMessageContaining("more than one @AppendCriteriaBuilder for command type")
                    .hasMessageContaining(UseCredits.class.getName());
        }

        @Test
        void rejectsCommandMessageHandlerWithoutAPayloadTypeWhenOnlyTypedBuildersExist() {
            // given
            Configuration configuration = configuration();

            // when / then
            assertThatThrownBy(() -> component(new UntypedCommandMessageHandler(), configuration))
                    .isInstanceOf(AxonConfigurationException.class)
                    .hasMessageContaining("No @AppendCriteriaBuilder accepts handled command payload [java.lang.Object]");
        }

        @Test
        void rejectsPayloadAcceptedByBuildersOfWhichNoneIsMoreSpecific() {
            // given a payload implementing two unrelated interfaces, each with its own builder
            Configuration configuration = configuration();

            // when / then
            assertThatThrownBy(() -> component(new AmbiguousBuilderHandler(), configuration))
                    .isInstanceOf(AxonConfigurationException.class)
                    .hasMessageContaining("none of which is more specific")
                    .hasMessageContaining(AuditedAndBilled.class.getName());
        }

        @Test
        void rejectsBuilderThatDoesNotCoverEveryHandlerOnTheClass() {
            // given
            Configuration configuration = configuration();

            // when / then
            assertThatThrownBy(() -> component(new PartialBuilderHandler(), configuration))
                    .isInstanceOf(AxonConfigurationException.class)
                    .hasMessageContaining("No @AppendCriteriaBuilder accepts handled command payload")
                    .hasMessageContaining(TopUpCredits.class.getName());
        }

        @Test
        void rejectsNonStaticBuilderInvalidReturnTypeAndUnsupportedParameters() {
            // given
            Configuration configuration = configuration();

            // when / then
            assertThatThrownBy(() -> component(new NonStaticBuilderHandler(), configuration))
                    .isInstanceOf(AxonConfigurationException.class)
                    .hasMessageContaining("must be static");
            assertThatThrownBy(() -> component(new InvalidReturnBuilderHandler(), configuration))
                    .isInstanceOf(AxonConfigurationException.class)
                    .hasMessageContaining("must return EventCriteria");
            assertThatThrownBy(() -> component(new EventAppenderBuilderHandler(), configuration))
                    .isInstanceOf(AxonConfigurationException.class)
                    .hasMessageContaining("unsupported parameter type");
            assertThatThrownBy(() -> component(new EntityBuilderHandler(), configuration))
                    .isInstanceOf(AxonConfigurationException.class)
                    .hasMessageContaining("unsupported parameter type");
        }

        @Test
        void unresolvableParameterKeepsTheLookupFailureAsCause() {
            // given a parameter type that matches several configured components
            Configuration configuration = configuration();

            // when / then
            assertThatThrownBy(() -> component(new AmbiguousParameterBuilderHandler(), configuration))
                    .isInstanceOf(AxonConfigurationException.class)
                    .hasMessageContaining("unsupported parameter type [java.lang.Object]")
                    .cause()
                    .isNotNull();
        }

        @Test
        void nullResultFailsTheCommitWithAnIllegalState() {
            // given
            Configuration configuration = configuration();
            CommandHandlingComponent component = component(
                    new NullBuilderHandler(configuration.getComponent(EventStore.class)), configuration
            );
            var uow = aUnitOfWork();
            uow.runOnPreInvocation(context -> component.handle(
                    command(UseCredits.class, new UseCredits("one"), Map.of()), context
            ));

            // when / then
            assertThatThrownBy(() -> awaitExceptionalCompletion(uow.execute()))
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining("returned null");
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
        void rejectsInheritedBuilderThatCannotCoverCommandsDeclaredBySubclass() {
            // given a subclass adding a handler the builder it inherits cannot accept
            Configuration configuration = configuration();

            // when / then the failure names the class that declared the builder, which is not the violating class
            assertThatThrownBy(() -> component(
                    new IncompatibleChildHandler(configuration.getComponent(EventStore.class)), configuration
            ))
                    .isInstanceOf(AxonConfigurationException.class)
                    .hasMessageContaining("No @AppendCriteriaBuilder accepts handled command payload")
                    .hasMessageContaining(NarrowBuilderParent.class.getName());
        }

        @Test
        void annotationAndDeclarativeDefinitionsOnTheSameComponentFailExplicitly() {
            // given
            Configuration configuration = configuration();
            CommandHandlingComponent annotated = component(
                    new QualifiedBuilderHandler(configuration.getComponent(EventStore.class)), configuration
            );
            CommandHandlingComponent duplicate = new InterceptingCommandHandlingComponent(
                    List.of(new AppendCriteriaInterceptor(
                            configuration.getComponent(EventStore.class),
                            (command, context, sourcingCriteria) -> sourcingCriteria
                    )),
                    annotated
            );
            CommandMessage command = command("credits.Create", new SharedCommand("one"), Map.of());

            // when / then
            assertThatThrownBy(() -> duplicate.handle(
                                                     command,
                                                     new StubProcessingContext()
                                             )
                                             .asCompletableFuture()
                                             .join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining("Append criteria have already been defined");
        }
    }

    @Nested
    class Inheritance {

        @Test
        void builderOnSubclassMayBeNarrowedToTheCommandsThatSubclassDeclares() {
            // given a subclass declaring a builder for its own command only, while the parent declares another handler
            NarrowChildHandler.receivedCommands.clear();
            Configuration configuration = configuration();
            NarrowChildHandler target = new NarrowChildHandler(configuration.getComponent(EventStore.class));
            CommandHandlingComponent component = component(target, configuration);

            // when the subclass' own command is handled
            handleSuccessfully(component, command(TopUpCredits.class, new TopUpCredits("one"), Map.of()));

            // then the narrowed builder applied, rather than being rejected for not covering the inherited handler
            assertThat(NarrowChildHandler.receivedCommands).containsExactly(new TopUpCredits("one"));
        }

        @Test
        void builderOnSubclassDoesNotApplyToHandlersInheritedFromItsParent() {
            // given
            NarrowChildHandler.receivedCommands.clear();
            Configuration configuration = configuration();
            NarrowChildHandler target = new NarrowChildHandler(configuration.getComponent(EventStore.class));
            CommandHandlingComponent component = component(target, configuration);

            // when the command handled by the inherited handler is dispatched
            handleSuccessfully(component, command(UseCredits.class, new UseCredits("one"), Map.of()));

            // then the subclass' builder was not consulted for it
            assertThat(NarrowChildHandler.receivedCommands).isEmpty();
        }

        @Test
        void builderOnParentAppliesToTheHandlersThatParentDeclares() {
            // given a parent declaring both a builder and a handler, extended by a subclass declaring another handler
            BuilderParent.receivedCommands.clear();
            Configuration configuration = configuration();
            CommandHandlingComponent component = component(
                    new PlainChildHandler(configuration.getComponent(EventStore.class)), configuration
            );

            // when
            handleSuccessfully(component, command(UseCredits.class, new UseCredits("one"), Map.of()));

            // then
            assertThat(BuilderParent.receivedCommands).containsExactly(new UseCredits("one"));
        }

        @Test
        void builderOnParentAlsoAppliesToHandlersDeclaredBySubclass() {
            // given a subclass that adds a handler without declaring a builder of its own
            BuilderParent.receivedCommands.clear();
            Configuration configuration = configuration();
            CommandHandlingComponent component = component(
                    new PlainChildHandler(configuration.getComponent(EventStore.class)), configuration
            );

            // when the command the subclass declares is handled
            handleSuccessfully(component, command(TopUpCredits.class, new TopUpCredits("one"), Map.of()));

            // then the inherited builder was consulted, rather than the subclass silently keeping the default
            assertThat(BuilderParent.receivedCommands).containsExactly(new TopUpCredits("one"));
        }

        @Test
        void builderDeclaredBySubclassOverridesTheBuilderItWouldInherit() {
            // given both the parent and the subclass declare a builder
            BuilderParent.receivedCommands.clear();
            OverridingChildHandler.receivedCommands.clear();
            Configuration configuration = configuration();
            CommandHandlingComponent component = component(
                    new OverridingChildHandler(configuration.getComponent(EventStore.class)), configuration
            );

            // when a command the subclass declares is handled
            handleSuccessfully(component, command(TopUpCredits.class, new TopUpCredits("one"), Map.of()));

            // then the subclass' builder for TopUpCredits is more specific than the parent's for CreditsCommand
            assertThat(OverridingChildHandler.receivedCommands).containsExactly(new TopUpCredits("one"));
            assertThat(BuilderParent.receivedCommands).isEmpty();
        }

        @Test
        void subclassBuilderForTheSameCommandTypeReplacesTheInheritedOne() {
            // given parent and subclass both declare a builder for CreditsCommand
            BuilderParent.receivedCommands.clear();
            SameTypeOverridingChildHandler.receivedCommands.clear();
            Configuration configuration = configuration();
            CommandHandlingComponent component = component(
                    new SameTypeOverridingChildHandler(configuration.getComponent(EventStore.class)), configuration
            );

            // when a command the subclass declares is handled
            handleSuccessfully(component, command(TopUpCredits.class, new TopUpCredits("one"), Map.of()));

            // then the closest declaration wins
            assertThat(SameTypeOverridingChildHandler.receivedCommands).containsExactly(new TopUpCredits("one"));
            assertThat(BuilderParent.receivedCommands).isEmpty();
        }

        @Test
        void builderOnAbstractBaseDeclaringNoHandlerAppliesToItsConcreteSubclass() {
            // given a base class that contributes only a builder, which is a valid way to share one boundary rule
            AbstractBuilderBase.receivedCommands.clear();
            Configuration configuration = configuration();
            CommandHandlingComponent component = component(
                    new ConcreteFromAbstractBase(configuration.getComponent(EventStore.class)), configuration
            );

            // when
            handleSuccessfully(component, command(UseCredits.class, new UseCredits("one"), Map.of()));

            // then
            assertThat(AbstractBuilderBase.receivedCommands).containsExactly(new UseCredits("one"));
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

    private sealed interface CreditsCommand permits UseCredits, TopUpCredits {

        String accountId();
    }

    private record UseCredits(String accountId) implements CreditsCommand {
    }

    private record TopUpCredits(String accountId) implements CreditsCommand {
    }

    private record SharedCommand(String accountId) {
    }

    private interface Audited {
    }

    private interface Billed {
    }

    private record AuditedAndBilled(String accountId) implements Audited, Billed {
    }

    private record CreditsChanged(String accountId) {
    }

    /**
     * Declares a {@code UseCredits} handler, and no builder, so subclasses can add their own.
     */
    private static class PlainParent {

        protected final EventStore eventStore;

        private PlainParent(EventStore eventStore) {
            this.eventStore = eventStore;
        }

        @CommandHandler
        void handle(UseCredits command, ProcessingContext context) {
            sourceAndAppend(eventStore, context, command.accountId());
        }
    }

    /**
     * Declares a builder narrowed to its own {@code TopUpCredits} handler, deliberately not accepting the
     * {@code UseCredits} handler inherited from {@link PlainParent}.
     */
    private static class NarrowChildHandler extends PlainParent {

        private static final List<TopUpCredits> receivedCommands = new ArrayList<>();

        private NarrowChildHandler(EventStore eventStore) {
            super(eventStore);
        }

        @CommandHandler
        void handle(TopUpCredits command, ProcessingContext context) {
            sourceAndAppend(eventStore, context, command.accountId());
        }

        @AppendCriteriaBuilder
        static EventCriteria criteria(TopUpCredits command, EventCriteria sourcingCriteria) {
            receivedCommands.add(command);
            return sourcingCriteria;
        }
    }

    /**
     * Declares a builder wide enough to cover commands added by subclasses, alongside its own handler.
     */
    private static class BuilderParent {

        private static final List<CreditsCommand> receivedCommands = new ArrayList<>();
        protected final EventStore eventStore;

        private BuilderParent(EventStore eventStore) {
            this.eventStore = eventStore;
        }

        @CommandHandler
        void handle(UseCredits command, ProcessingContext context) {
            sourceAndAppend(eventStore, context, command.accountId());
        }

        @AppendCriteriaBuilder
        static EventCriteria criteria(CreditsCommand command, EventCriteria sourcingCriteria) {
            receivedCommands.add(command);
            return sourcingCriteria;
        }
    }

    /**
     * Adds a handler without declaring a builder of its own, inheriting {@link BuilderParent}'s.
     */
    private static class PlainChildHandler extends BuilderParent {

        private PlainChildHandler(EventStore eventStore) {
            super(eventStore);
        }

        @CommandHandler
        void handle(TopUpCredits command, ProcessingContext context) {
            sourceAndAppend(eventStore, context, command.accountId());
        }
    }

    /**
     * Declares a builder for its own command, more specific than the one it inherits from {@link BuilderParent}.
     */
    private static class OverridingChildHandler extends BuilderParent {

        private static final List<CreditsCommand> receivedCommands = new ArrayList<>();

        private OverridingChildHandler(EventStore eventStore) {
            super(eventStore);
        }

        @CommandHandler
        void handle(TopUpCredits command, ProcessingContext context) {
            sourceAndAppend(eventStore, context, command.accountId());
        }

        @AppendCriteriaBuilder
        static EventCriteria ownCriteria(TopUpCredits command, EventCriteria sourcingCriteria) {
            receivedCommands.add(command);
            return sourcingCriteria;
        }
    }

    /**
     * Declares a builder for the same command type as {@link BuilderParent}, replacing the inherited one.
     */
    private static class SameTypeOverridingChildHandler extends BuilderParent {

        private static final List<CreditsCommand> receivedCommands = new ArrayList<>();

        private SameTypeOverridingChildHandler(EventStore eventStore) {
            super(eventStore);
        }

        @CommandHandler
        void handle(TopUpCredits command, ProcessingContext context) {
            sourceAndAppend(eventStore, context, command.accountId());
        }

        @AppendCriteriaBuilder
        static EventCriteria ownCriteria(CreditsCommand command, EventCriteria sourcingCriteria) {
            receivedCommands.add(command);
            return sourcingCriteria;
        }
    }

    /**
     * Contributes a builder without declaring any command handler, sharing one boundary rule across subclasses.
     */
    private abstract static class AbstractBuilderBase {

        private static final List<CreditsCommand> receivedCommands = new ArrayList<>();
        protected final EventStore eventStore;

        private AbstractBuilderBase(EventStore eventStore) {
            this.eventStore = eventStore;
        }

        @AppendCriteriaBuilder
        static EventCriteria criteria(CreditsCommand command, EventCriteria sourcingCriteria) {
            receivedCommands.add(command);
            return sourcingCriteria;
        }
    }

    private static class ConcreteFromAbstractBase extends AbstractBuilderBase {

        private ConcreteFromAbstractBase(EventStore eventStore) {
            super(eventStore);
        }

        @CommandHandler
        void handle(UseCredits command, ProcessingContext context) {
            sourceAndAppend(eventStore, context, command.accountId());
        }
    }

    /**
     * Declares a builder accepting only its own command, leaving no room for subclasses to add handlers.
     */
    private static class NarrowBuilderParent {

        protected final EventStore eventStore;

        private NarrowBuilderParent(EventStore eventStore) {
            this.eventStore = eventStore;
        }

        @CommandHandler
        void handle(UseCredits command, ProcessingContext context) {
            sourceAndAppend(eventStore, context, command.accountId());
        }

        @AppendCriteriaBuilder
        static EventCriteria criteria(UseCredits command, EventCriteria sourcingCriteria) {
            return sourcingCriteria;
        }
    }

    /**
     * Adds a handler the builder inherited from {@link NarrowBuilderParent} cannot accept.
     */
    private static class IncompatibleChildHandler extends NarrowBuilderParent {

        private IncompatibleChildHandler(EventStore eventStore) {
            super(eventStore);
        }

        @CommandHandler
        void handle(TopUpCredits command, ProcessingContext context) {
            sourceAndAppend(eventStore, context, command.accountId());
        }
    }

    private static class CommonBuilderHandler {

        private static final List<CreditsCommand> receivedCommands = new ArrayList<>();
        private static final List<EventCriteria> receivedSourcingCriteria = new ArrayList<>();
        private static final List<EventCriteria> resolvedCriteria = new ArrayList<>();
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
            EventCriteria criteria = switch (command) {
                case UseCredits ignored -> sourcingCriteria.replaceEventTypes(CreditsChanged.class);
                case TopUpCredits ignored -> sourcingCriteria;
            };
            resolvedCriteria.add(criteria);
            return criteria;
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

    private static class PerCommandBuilderHandler {

        private static final List<String> receivedCommands = new ArrayList<>();
        private final EventStore eventStore;

        private PerCommandBuilderHandler(EventStore eventStore) {
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
        static EventCriteria use(UseCredits command, EventCriteria sourcingCriteria) {
            receivedCommands.add("use:" + command);
            return sourcingCriteria.replaceEventTypes(CreditsChanged.class);
        }

        @AppendCriteriaBuilder
        static EventCriteria topUp(TopUpCredits command, EventCriteria sourcingCriteria) {
            receivedCommands.add("topUp:" + command);
            return sourcingCriteria;
        }
    }

    private static class SpecificAndGeneralBuilderHandler {

        private static final List<String> receivedCommands = new ArrayList<>();
        private final EventStore eventStore;

        private SpecificAndGeneralBuilderHandler(EventStore eventStore) {
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
        static EventCriteria general(CreditsCommand command, EventCriteria sourcingCriteria) {
            receivedCommands.add("general:" + command);
            return sourcingCriteria;
        }

        @AppendCriteriaBuilder
        static EventCriteria specific(UseCredits command, EventCriteria sourcingCriteria) {
            receivedCommands.add("specific:" + command);
            return sourcingCriteria;
        }
    }

    private static class TypedAndCatchAllBuilderHandler {

        private static final List<String> receivedCommands = new ArrayList<>();
        private final EventStore eventStore;

        private TypedAndCatchAllBuilderHandler(EventStore eventStore) {
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
        static EventCriteria typed(UseCredits command, EventCriteria sourcingCriteria) {
            receivedCommands.add("typed:" + command);
            return sourcingCriteria;
        }

        @AppendCriteriaBuilder
        static EventCriteria catchAll(CommandMessage command, EventCriteria sourcingCriteria) {
            receivedCommands.add("catchAll:" + command.payload());
            return sourcingCriteria;
        }
    }

    private static class ObservingBuilderHandler {

        private final EventStore eventStore;
        private final AtomicReference<AppendCondition> finalCondition;

        private ObservingBuilderHandler(EventStore eventStore, AtomicReference<AppendCondition> finalCondition) {
            this.eventStore = eventStore;
            this.finalCondition = finalCondition;
        }

        @CommandHandler
        void handle(UseCredits command, ProcessingContext context) {
            // registered after the builder's override, so it observes the condition that override produced
            eventStore.transaction(context).overrideAppendCondition(condition -> {
                finalCondition.set(condition);
                return condition;
            });
            sourceAndAppend(eventStore, context, command.accountId());
        }

        @AppendCriteriaBuilder
        static EventCriteria criteria(UseCredits command, EventCriteria sourcingCriteria) {
            return sourcingCriteria.replaceEventTypes(CreditsChanged.class);
        }
    }

    private static class BroaderAnnotatedPayloadHandler {

        private static final List<String> receivedCommands = new ArrayList<>();
        private final EventStore eventStore;

        private BroaderAnnotatedPayloadHandler(EventStore eventStore) {
            this.eventStore = eventStore;
        }

        @CommandHandler(payloadType = CreditsCommand.class)
        void handle(UseCredits command, ProcessingContext context) {
            sourceAndAppend(eventStore, context, command.accountId());
        }

        @AppendCriteriaBuilder
        static EventCriteria general(CreditsCommand command, EventCriteria sourcingCriteria) {
            receivedCommands.add("general");
            return sourcingCriteria;
        }

        @AppendCriteriaBuilder
        static EventCriteria specific(UseCredits command, EventCriteria sourcingCriteria) {
            receivedCommands.add("specific");
            return sourcingCriteria;
        }
    }

    private static class TypedCommandMessageHandler {

        private static final List<UseCredits> receivedCommands = new ArrayList<>();
        private final EventStore eventStore;

        private TypedCommandMessageHandler(EventStore eventStore) {
            this.eventStore = eventStore;
        }

        @CommandHandler(payloadType = UseCredits.class)
        void handle(CommandMessage command, ProcessingContext context) {
            sourceAndAppend(eventStore, context, "one");
        }

        @AppendCriteriaBuilder
        static EventCriteria criteria(UseCredits command, EventCriteria sourcingCriteria) {
            receivedCommands.add(command);
            return sourcingCriteria;
        }
    }

    private static class UntypedCommandMessageHandler {

        @CommandHandler(commandName = "credits.Any")
        void handle(CommandMessage command) {
        }

        @AppendCriteriaBuilder
        static EventCriteria criteria(UseCredits command) {
            return ACCOUNT_CRITERIA;
        }
    }

    private abstract static class GenericCreditsHandler<C extends CreditsCommand> {

        protected final EventStore eventStore;

        private GenericCreditsHandler(EventStore eventStore) {
            this.eventStore = eventStore;
        }

        abstract void handle(C command, ProcessingContext context);
    }

    private static class BridgedHandler extends GenericCreditsHandler<UseCredits> {

        private static final List<UseCredits> receivedCommands = new ArrayList<>();

        private BridgedHandler(EventStore eventStore) {
            super(eventStore);
        }

        @Override
        @CommandHandler
        void handle(UseCredits command, ProcessingContext context) {
            sourceAndAppend(eventStore, context, command.accountId());
        }

        @AppendCriteriaBuilder
        static EventCriteria criteria(UseCredits command, EventCriteria sourcingCriteria) {
            receivedCommands.add(command);
            return sourcingCriteria;
        }
    }

    private static class DuplicateTypeBuilderHandler {

        @CommandHandler
        void handle(UseCredits command) {
        }

        @AppendCriteriaBuilder
        static EventCriteria first(UseCredits command) {
            return ACCOUNT_CRITERIA;
        }

        @AppendCriteriaBuilder
        static EventCriteria second(UseCredits command, EventCriteria sourcingCriteria) {
            return sourcingCriteria;
        }
    }

    private static class AmbiguousBuilderHandler {

        @CommandHandler
        void handle(AuditedAndBilled command) {
        }

        @AppendCriteriaBuilder
        static EventCriteria audited(Audited command) {
            return ACCOUNT_CRITERIA;
        }

        @AppendCriteriaBuilder
        static EventCriteria billed(Billed command) {
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

    private static class AmbiguousParameterBuilderHandler {

        @CommandHandler
        void handle(UseCredits command) {
        }

        @AppendCriteriaBuilder
        static EventCriteria criteria(UseCredits command, Object component) {
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
