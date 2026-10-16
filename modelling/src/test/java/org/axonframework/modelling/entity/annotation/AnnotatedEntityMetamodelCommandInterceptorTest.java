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

import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.commandhandling.GenericCommandResultMessage;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.commandhandling.interception.annotation.CommandHandlerInterceptor;
import org.axonframework.messaging.core.MessageHandlerInterceptorChain;
import org.axonframework.messaging.core.MessageStream;
import org.axonframework.messaging.core.MessageType;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Tests that {@link CommandHandlerInterceptor} (and the more generic
 * {@link org.axonframework.messaging.core.interception.annotation.MessageHandlerInterceptor}) methods declared directly
 * on an entity are invoked when commands are routed to that entity, both for the entity itself and for commands routed
 * to a child entity added through {@link EntityMember}, and across a polymorphic hierarchy.
 * <p>
 * Each nested test uses a small, self-contained entity fixture rather than the shared domain fixtures used elsewhere in
 * this package, since these tests are only concerned with interceptor invocation, not the rest of the entity model's
 * behavior.
 *
 * @author Steven van Beelen
 */
class AnnotatedEntityMetamodelCommandInterceptorTest {

    @Nested
    class BeforeStyleInterceptor extends AbstractAnnotatedEntityMetamodelTest<InterceptedEntity> {

        @Override
        protected AnnotatedEntityMetamodel<InterceptedEntity> getMetamodel() {
            return AnnotatedEntityMetamodel.forConcreteType(InterceptedEntity.class,
                                                            parameterResolverFactory,
                                                            handlerDefinition,
                                                            messageTypeResolver,
                                                            messageConverter,
                                                            eventConverter);
        }

        @Test
        void interceptorRunsBeforeHandlerAndHandlerStillExecutes() {
            entityState = new InterceptedEntity();

            dispatchInstanceCommand(new Ping("hello"));

            assertThat(entityState.invocations).containsExactly("intercepted", "handled");
        }
    }

    @Nested
    class SurroundStyleInterceptor extends AbstractAnnotatedEntityMetamodelTest<ShortCircuitingEntity> {

        @Override
        protected AnnotatedEntityMetamodel<ShortCircuitingEntity> getMetamodel() {
            return AnnotatedEntityMetamodel.forConcreteType(ShortCircuitingEntity.class,
                                                            parameterResolverFactory,
                                                            handlerDefinition,
                                                            messageTypeResolver,
                                                            messageConverter,
                                                            eventConverter);
        }

        @Test
        void interceptorShortCircuitsAndHandlerIsNeverInvoked() {
            entityState = new ShortCircuitingEntity();

            Object result = dispatchInstanceCommand(new Ping("hello"));

            assertThat(result).isEqualTo("short-circuited");
            assertThat(entityState.invocations).isEmpty();
        }
    }

    @Nested
    class MixedAnnotationInterceptors extends AbstractAnnotatedEntityMetamodelTest<MixedEntity> {

        @Override
        protected AnnotatedEntityMetamodel<MixedEntity> getMetamodel() {
            return AnnotatedEntityMetamodel.forConcreteType(MixedEntity.class,
                                                            parameterResolverFactory,
                                                            handlerDefinition,
                                                            messageTypeResolver,
                                                            messageConverter,
                                                            eventConverter);
        }

        @Test
        void bothCommandHandlerInterceptorAndDirectMessageHandlerInterceptorAreInvokedBeforeTheHandler() {
            entityState = new MixedEntity();

            dispatchInstanceCommand(new Ping("hello"));

            assertThat(entityState.invocations).contains("via-command-handler-interceptor",
                                                         "via-message-handler-interceptor");
            assertThat(entityState.invocations.getLast()).isEqualTo("handled");
        }
    }

    @Nested
    class ParentAndChildInterceptorOrdering extends AbstractAnnotatedEntityMetamodelTest<ParentEntity> {

        private final List<String> invocations = new ArrayList<>();

        @Override
        protected AnnotatedEntityMetamodel<ParentEntity> getMetamodel() {
            return AnnotatedEntityMetamodel.forConcreteType(ParentEntity.class,
                                                            parameterResolverFactory,
                                                            handlerDefinition,
                                                            messageTypeResolver,
                                                            messageConverter,
                                                            eventConverter);
        }

        @Test
        void parentInterceptorRunsBeforeChildInterceptorForCommandRoutedToChild() {
            entityState = new ParentEntity(invocations);
            entityState.child = new ChildEntity(invocations);

            dispatchInstanceCommand(new PingChild("hello"));

            assertThat(invocations).containsExactly("parent", "child", "child-handled");
        }
    }

    @Nested
    class PolymorphicSupertypeInterceptor extends AbstractAnnotatedEntityMetamodelTest<Shape> {

        @Override
        protected AnnotatedEntityMetamodel<Shape> getMetamodel() {
            return AnnotatedEntityMetamodel.forPolymorphicType(
                    Shape.class,
                    Set.of(Circle.class, Square.class),
                    parameterResolverFactory,
                    handlerDefinition,
                    messageTypeResolver,
                    messageConverter,
                    eventConverter
            );
        }

        @Test
        void supertypeInterceptorFiresForSupertypeDeclaredCommand() {
            entityState = new Circle();

            dispatchInstanceCommand(new Rename("new-name"));

            assertThat(entityState.invocations).containsExactly("intercepted");
            assertThat(entityState.name).isEqualTo("new-name");
        }

        @Test
        void supertypeInterceptorAlsoFiresForConcreteTypeOnlyCommand() {
            entityState = new Circle();

            dispatchInstanceCommand(new SetRadius(5));

            assertThat(entityState.invocations).containsExactly("intercepted");
            assertThat(((Circle) entityState).radius).isEqualTo(5);
        }
    }

    /**
     * A concrete type's own interceptor must still run when the command it intercepts is declared (and handled) on
     * the polymorphic super type, not on the concrete type itself. Before this was fixed, dispatch never visited the
     * concrete type's own metamodel for such a command, so its interceptor was silently skipped.
     */
    @Nested
    class ConcreteTypeInterceptorWithSupertypeHandler extends AbstractAnnotatedEntityMetamodelTest<GuardedShape> {

        @Override
        protected AnnotatedEntityMetamodel<GuardedShape> getMetamodel() {
            return AnnotatedEntityMetamodel.forPolymorphicType(
                    GuardedShape.class,
                    Set.of(GuardedCircle.class),
                    parameterResolverFactory,
                    handlerDefinition,
                    messageTypeResolver,
                    messageConverter,
                    eventConverter
            );
        }

        @Test
        void concreteTypeInterceptorFiresForCommandHandledBySupertype() {
            entityState = new GuardedCircle();

            dispatchInstanceCommand(new RenameShape("new-name"));

            assertThat(entityState.invocations).containsExactly("concrete-interceptor", "super-handled");
        }
    }

    /**
     * When both the polymorphic super type and a concrete type declare their own interceptor, both must fire for any
     * command reaching that concrete type's instance, regardless of whether the command is declared on the concrete
     * type or on the super type. Ordering here follows the concrete type first, then the super type, matching the
     * order already observed for a command declared directly on the concrete type: there is no parent-before-child
     * guarantee across this supertype/concrete-type axis (unlike the {@code @EntityMember} parent/child axis, which
     * is unaffected and still runs parent-before-child).
     */
    @Nested
    class InterceptorsAtBothHierarchyLevels extends AbstractAnnotatedEntityMetamodelTest<AuditedShape> {

        @Override
        protected AnnotatedEntityMetamodel<AuditedShape> getMetamodel() {
            return AnnotatedEntityMetamodel.forPolymorphicType(
                    AuditedShape.class,
                    Set.of(AuditedCircle.class),
                    parameterResolverFactory,
                    handlerDefinition,
                    messageTypeResolver,
                    messageConverter,
                    eventConverter
            );
        }

        @Test
        void bothLevelsFireForConcreteTypeHandledCommand() {
            entityState = new AuditedCircle();

            dispatchInstanceCommand(new ResizeShape(5));

            assertThat(entityState.invocations)
                    .containsExactly("concrete-interceptor", "super-interceptor", "concrete-handled");
        }

        @Test
        void bothLevelsFireForSupertypeHandledCommand() {
            entityState = new AuditedCircle();

            dispatchInstanceCommand(new RenameShape("new-name"));

            assertThat(entityState.invocations)
                    .containsExactly("concrete-interceptor", "super-interceptor", "super-handled");
        }
    }

    /**
     * Reproduces the scenario from the original bug report: an entity rejects a command based on its own current state
     * through a {@link CommandHandlerInterceptor}. Before the fix, this interceptor was silently never invoked.
     */
    @Nested
    class RegressionRejectingBasedOnEntityState extends AbstractAnnotatedEntityMetamodelTest<GiftCard> {

        @Override
        protected AnnotatedEntityMetamodel<GiftCard> getMetamodel() {
            return AnnotatedEntityMetamodel.forConcreteType(GiftCard.class,
                                                            parameterResolverFactory,
                                                            handlerDefinition,
                                                            messageTypeResolver,
                                                            messageConverter,
                                                            eventConverter);
        }

        @Test
        void interceptorRejectsRedeemCommandWhenAlreadyRedeemed() {
            entityState = new GiftCard();

            dispatchInstanceCommand(new RedeemGiftCard("card-1"));
            assertThat(entityState.redeemed).isTrue();

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> dispatchInstanceCommand(new RedeemGiftCard("card-1")))
                    .withMessage("Gift card already redeemed");
        }
    }

    @Nested
    class CreationalCommandWithInstanceInterceptor
            extends AbstractAnnotatedEntityMetamodelTest<CreatableGiftCard> {

        @Override
        protected AnnotatedEntityMetamodel<CreatableGiftCard> getMetamodel() {
            return AnnotatedEntityMetamodel.forConcreteType(CreatableGiftCard.class,
                                                            parameterResolverFactory,
                                                            handlerDefinition,
                                                            messageTypeResolver,
                                                            messageConverter,
                                                            eventConverter);
        }

        @Test
        void instanceInterceptorIsSkippedForCreationalCommand() {
            // given no entity instance exists yet
            entityState = null;
            CreatableGiftCard.invocations.clear();

            // when
            Object result = dispatchCreateCommand(new IssueGiftCard("card-1"));

            // then the instance interceptor cannot be invoked without an instance, but the handler still runs
            assertThat(result).isEqualTo("card-1");
            assertThat(CreatableGiftCard.invocations).containsExactly("created");
        }

        @Test
        void instanceInterceptorStillRunsOnceEntityExists() {
            // given an entity created by an earlier creational command
            dispatchCreateCommand(new IssueGiftCard("card-1"));
            entityState = new CreatableGiftCard();
            CreatableGiftCard.invocations.clear();

            // when
            dispatchInstanceCommand(new RedeemGiftCard("card-1"));

            // then
            assertThat(CreatableGiftCard.invocations).containsExactly("intercepted", "redeemed");
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> dispatchInstanceCommand(new RedeemGiftCard("card-1")))
                    .withMessage("Gift card already redeemed");
        }
    }

    @Nested
    class CreationalCommandWithStaticInterceptor
            extends AbstractAnnotatedEntityMetamodelTest<StaticallyInterceptedGiftCard> {

        @Override
        protected AnnotatedEntityMetamodel<StaticallyInterceptedGiftCard> getMetamodel() {
            return AnnotatedEntityMetamodel.forConcreteType(StaticallyInterceptedGiftCard.class,
                                                            parameterResolverFactory,
                                                            handlerDefinition,
                                                            messageTypeResolver,
                                                            messageConverter,
                                                            eventConverter);
        }

        @Test
        void staticInterceptorRunsBeforeCreationalHandler() {
            // given no entity instance exists yet
            entityState = null;
            StaticallyInterceptedGiftCard.invocations.clear();

            // when
            Object result = dispatchCreateCommand(new IssueGiftCard("card-1"));

            // then a static interceptor needs no instance, so it takes part in creational dispatch
            assertThat(result).isEqualTo("card-1");
            assertThat(StaticallyInterceptedGiftCard.invocations).containsExactly("intercepted", "created");
        }

        @Test
        void staticInterceptorCanRejectCreationalCommand() {
            // given
            entityState = null;
            StaticallyInterceptedGiftCard.invocations.clear();

            // when / then
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> dispatchCreateCommand(new IssueGiftCard("")))
                    .withMessage("Card identifier must not be empty");
            assertThat(StaticallyInterceptedGiftCard.invocations).containsExactly("intercepted");
        }

        @Test
        void staticInterceptorAlsoRunsForInstanceCommand() {
            // given an entity instance exists
            entityState = new StaticallyInterceptedGiftCard();
            StaticallyInterceptedGiftCard.invocations.clear();

            // when
            dispatchInstanceCommand(new RedeemGiftCard("card-1"));

            // then a static interceptor is not limited to creational dispatch; it guards instance commands as well
            assertThat(StaticallyInterceptedGiftCard.invocations).containsExactly("intercepted", "redeemed");
        }
    }

    @Nested
    class PolymorphicCreationalCommandWithInstanceInterceptor
            extends AbstractAnnotatedEntityMetamodelTest<CreatableShape> {

        @Override
        protected AnnotatedEntityMetamodel<CreatableShape> getMetamodel() {
            return AnnotatedEntityMetamodel.forPolymorphicType(
                    CreatableShape.class,
                    Set.of(CreatableCircle.class),
                    parameterResolverFactory,
                    handlerDefinition,
                    messageTypeResolver,
                    messageConverter,
                    eventConverter
            );
        }

        @Test
        void supertypeInstanceInterceptorIsSkippedForCreationalCommand() {
            // given no entity instance exists yet
            entityState = null;
            CreatableShape.invocations.clear();

            // when
            Object result = dispatchCreateCommand(new CreateShape("shape-1"));

            // then
            assertThat(result).isEqualTo("shape-1");
            assertThat(CreatableShape.invocations).containsExactly("created");
        }

        @Test
        void supertypeInstanceInterceptorStillRunsForConcreteTypeInstance() {
            // given a concrete instance
            entityState = new CreatableCircle();
            CreatableShape.invocations.clear();

            // when
            dispatchInstanceCommand(new DescribeShape("shape-1"));

            // then
            assertThat(CreatableShape.invocations).containsExactly("intercepted", "described");
        }
    }

    @SuppressWarnings("unused")
    static class InterceptedEntity {

        List<String> invocations = new ArrayList<>();

        @CommandHandlerInterceptor
        public void audit(CommandMessage command) {
            invocations.add("intercepted");
        }

        @CommandHandler
        public void handle(Ping command) {
            invocations.add("handled");
        }
    }

    @SuppressWarnings("unused")
    static class ShortCircuitingEntity {

        List<String> invocations = new ArrayList<>();

        @CommandHandlerInterceptor
        public MessageStream<?> guard(CommandMessage command, MessageHandlerInterceptorChain<CommandMessage> chain) {
            return MessageStream.just(
                    new GenericCommandResultMessage(new MessageType(String.class), "short-circuited"));
        }

        @CommandHandler
        public void handle(Ping command) {
            invocations.add("handled");
        }
    }

    @SuppressWarnings("unused")
    static class MixedEntity {

        List<String> invocations = new ArrayList<>();

        @CommandHandlerInterceptor
        public void auditOne(CommandMessage command) {
            invocations.add("via-command-handler-interceptor");
        }

        @org.axonframework.messaging.core.interception.annotation.MessageHandlerInterceptor(messageType = CommandMessage.class)
        public void auditTwo(CommandMessage command) {
            invocations.add("via-message-handler-interceptor");
        }

        @CommandHandler
        public void handle(Ping command) {
            invocations.add("handled");
        }
    }

    @SuppressWarnings("unused")
    static class ParentEntity {

        final List<String> invocations;

        @EntityMember
        ChildEntity child;

        ParentEntity(List<String> invocations) {
            this.invocations = invocations;
        }

        @CommandHandlerInterceptor
        public void audit(CommandMessage command) {
            invocations.add("parent");
        }
    }

    @SuppressWarnings("unused")
    static class ChildEntity {

        final List<String> invocations;

        ChildEntity(List<String> invocations) {
            this.invocations = invocations;
        }

        @CommandHandlerInterceptor
        public void audit(CommandMessage command) {
            invocations.add("child");
        }

        @CommandHandler
        public void handle(PingChild command) {
            invocations.add("child-handled");
        }
    }

    @SuppressWarnings("unused")
    abstract static class Shape {

        List<String> invocations = new ArrayList<>();
        String name;

        @CommandHandlerInterceptor
        public void audit(CommandMessage command) {
            invocations.add("intercepted");
        }

        @CommandHandler
        public void handle(Rename command) {
            this.name = command.name();
        }
    }

    @SuppressWarnings("unused")
    static class Circle extends Shape {

        int radius;

        @CommandHandler
        public void handle(SetRadius command) {
            this.radius = command.radius();
        }
    }

    @SuppressWarnings("unused")
    static class Square extends Shape {

        int side;
    }

    @SuppressWarnings("unused")
    abstract static class GuardedShape {

        final List<String> invocations = new ArrayList<>();

        @CommandHandler
        public void handle(RenameShape command) {
            invocations.add("super-handled");
        }
    }

    @SuppressWarnings("unused")
    static class GuardedCircle extends GuardedShape {

        @CommandHandlerInterceptor
        public void guard(CommandMessage command) {
            invocations.add("concrete-interceptor");
        }
    }

    @SuppressWarnings("unused")
    abstract static class AuditedShape {

        final List<String> invocations = new ArrayList<>();

        @CommandHandlerInterceptor
        public void auditOnSupertype(CommandMessage command) {
            invocations.add("super-interceptor");
        }

        @CommandHandler
        public void handle(RenameShape command) {
            invocations.add("super-handled");
        }
    }

    @SuppressWarnings("unused")
    static class AuditedCircle extends AuditedShape {

        @CommandHandlerInterceptor
        public void auditOnConcreteType(CommandMessage command) {
            invocations.add("concrete-interceptor");
        }

        @CommandHandler
        public void handle(ResizeShape command) {
            invocations.add("concrete-handled");
        }
    }

    @SuppressWarnings("unused")
    static class GiftCard {

        boolean redeemed = false;

        @CommandHandlerInterceptor
        public void rejectIfAlreadyRedeemed(CommandMessage command) {
            if (redeemed) {
                throw new IllegalStateException("Gift card already redeemed");
            }
        }

        @CommandHandler
        public void handle(RedeemGiftCard command) {
            this.redeemed = true;
        }
    }

    record Ping(String value) {

    }

    record PingChild(String value) {

    }

    record Rename(String name) {

    }

    record SetRadius(int radius) {

    }

    record RedeemGiftCard(String id) {

    }

    record RenameShape(String name) {

    }

    record ResizeShape(int radius) {

    }

    @SuppressWarnings("unused")
    static class CreatableGiftCard {

        static final List<String> invocations = new ArrayList<>();

        private boolean redeemed;

        @CommandHandler
        public static String handle(IssueGiftCard command) {
            invocations.add("created");
            return command.id();
        }

        @CommandHandlerInterceptor
        public void rejectIfAlreadyRedeemed(CommandMessage command) {
            invocations.add("intercepted");
            if (redeemed) {
                throw new IllegalStateException("Gift card already redeemed");
            }
        }

        @CommandHandler
        public void handle(RedeemGiftCard command) {
            invocations.add("redeemed");
            this.redeemed = true;
        }
    }

    @SuppressWarnings("unused")
    static class StaticallyInterceptedGiftCard {

        static final List<String> invocations = new ArrayList<>();

        @CommandHandler
        public static String handle(IssueGiftCard command) {
            invocations.add("created");
            return command.id();
        }

        @CommandHandler
        public void handle(RedeemGiftCard command) {
            invocations.add("redeemed");
        }

        @CommandHandlerInterceptor
        public static void rejectBlankIdentifier(CommandMessage command) {
            invocations.add("intercepted");
            if (command.payload() instanceof IssueGiftCard issue && issue.id().isEmpty()) {
                throw new IllegalArgumentException("Card identifier must not be empty");
            }
        }
    }

    record IssueGiftCard(String id) {

    }

    @SuppressWarnings("unused")
    abstract static class CreatableShape {

        static final List<String> invocations = new ArrayList<>();

        @CommandHandler
        public static String handle(CreateShape command) {
            invocations.add("created");
            return command.id();
        }

        @CommandHandlerInterceptor
        public void audit(CommandMessage command) {
            invocations.add("intercepted");
        }

        @CommandHandler
        public void handle(DescribeShape command) {
            invocations.add("described");
        }
    }

    @SuppressWarnings("unused")
    static class CreatableCircle extends CreatableShape {

    }

    record CreateShape(String id) {

    }

    record DescribeShape(String id) {

    }
}
