/*
 * Copyright (c) 2010-2023. Axon Framework
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

package org.axonframework.modelling.command.inspection;

import jakarta.persistence.Id;
import org.axonframework.commandhandling.CommandHandler;
import org.axonframework.commandhandling.CommandMessage;
import org.axonframework.commandhandling.CommandMessageHandlingMember;
import org.axonframework.commandhandling.GenericCommandMessage;
import org.axonframework.common.AxonConfigurationException;
import org.axonframework.eventhandling.EventHandler;
import org.axonframework.eventhandling.GenericEventMessage;
import org.axonframework.messaging.annotation.MessageHandlingMember;
import org.axonframework.modelling.command.AggregateIdentifier;
import org.axonframework.modelling.command.AggregateMember;
import org.axonframework.modelling.command.AggregateRoot;
import org.axonframework.modelling.command.AggregateVersion;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.lang.annotation.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.axonframework.commandhandling.GenericCommandMessage.asCommandMessage;
import static org.axonframework.eventhandling.GenericEventMessage.asEventMessage;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test case to validate all operations performed by the {@link AnnotatedAggregateMetaModelFactory}.
 *
 * @author Allard Buijze
 */
class AnnotatedAggregateMetaModelFactoryTest {

    @Test
    void detectAllAnnotatedHandlers() throws Exception {
        AggregateModel<SomeAnnotatedHandlers> inspector =
                AnnotatedAggregateMetaModelFactory.inspectAggregate(SomeAnnotatedHandlers.class);

        CommandMessage<?> message = asCommandMessage("ok");
        assertEquals(true, getHandler(inspector, message).handle(message, new SomeAnnotatedHandlers()));
        assertEquals(false, getHandler(inspector, message).handle(asCommandMessage("ko"), new SomeAnnotatedHandlers()));
    }

    @Test
    void detectAllAnnotatedHandlersInHierarchy() throws Exception {
        AggregateModel<SomeSubclass> inspector =
                AnnotatedAggregateMetaModelFactory.inspectAggregate(SomeSubclass.class);

        SomeSubclass target = new SomeSubclass();
        CommandMessage<?> message = asCommandMessage("sub");
        assertEquals(true, getHandler(inspector, message).handle(message, target));
        assertEquals(false, getHandler(inspector, message).handle(asCommandMessage("ok"), target));
    }

    @Test
    void detectFactoryMethodHandler() {
        AggregateModel<SomeAnnotatedFactoryMethodClass> inspector =
                AnnotatedAggregateMetaModelFactory.inspectAggregate(SomeAnnotatedFactoryMethodClass.class);

        CommandMessage<?> message = asCommandMessage("string");
        final MessageHandlingMember<? super SomeAnnotatedFactoryMethodClass> messageHandlingMember =
                getHandler(inspector, message);
        final Optional<CommandMessageHandlingMember> unwrap =
                messageHandlingMember.unwrap(CommandMessageHandlingMember.class);
        assertNotNull(unwrap);
        assertTrue(unwrap.isPresent());
        final CommandMessageHandlingMember commandMessageHandlingMember = unwrap.get();
        assertTrue(commandMessageHandlingMember.isFactoryHandler());
    }


    @Test
    void eventIsPublishedThroughoutRecursiveHierarchy() {
        // Note that if the inspector does not support recursive entities this will throw an StackOverflowError.
        AggregateModel<SomeRecursiveEntity> inspector =
                AnnotatedAggregateMetaModelFactory.inspectAggregate(SomeRecursiveEntity.class);

        // Create a hierarchy that we will use in this test.
        // The resulting hierarchy will look as follows:
        // root
        //      child1
        //              child2
        //              child3
        //                      child4
        String rootId = "root";
        String childId1 = "child1";
        String childId2 = "child2";
        String childId3 = "child3";
        String childId4 = "child4";

        SomeRecursiveEntity root = new SomeRecursiveEntity(LinkedList::new, null, rootId);

        // Assert root: it should not have any children (yet)
        assertEquals(0, root.children.size());

        // Publish an event that is picked up by root. It should have 1 child afterwards.
        inspector.publish(asEventMessage(new CreateChild(rootId, childId1)), root);
        assertEquals(1, root.children.size());

        // Assert child1: it should not have any children (yet)
        SomeRecursiveEntity child1 = root.getChild(childId1);
        assertNotNull(child1);
        assertEquals(0, child1.children.size());

        // Publish 2 events that are picked up by child1. It should have 2 children afterwards.
        inspector.publish(asEventMessage(new CreateChild(childId1, childId2)), root);
        inspector.publish(asEventMessage(new CreateChild(childId1, childId3)), root);
        assertEquals(2, child1.children.size());

        // Assert child2: it should not have any children
        SomeRecursiveEntity child2 = child1.getChild(childId2);
        assertNotNull(child2);
        assertEquals(0, child2.children.size());

        // Assert child3: it should not have any children
        SomeRecursiveEntity child3 = child1.getChild(childId3);
        assertNotNull(child3);
        assertEquals(0, child3.children.size());

        // Publish an event that is picked up by child3. It should have 1 child afterwards.
        inspector.publish(asEventMessage(new CreateChild(childId3, childId4)), root);
        assertEquals(1, child3.children.size());

        // Assert child4: it should not have any children
        SomeRecursiveEntity child4 = child3.getChild(childId4);
        assertNotNull(child4);
        assertEquals(0, child4.children.size());
    }

    @Test
    void linkedListIsModifiedDuringIterationInRecursiveHierarchy() {
        testCollectionIsModifiedDuringIterationInRecursiveHierarchy(LinkedList::new);
    }

    @Test
    void hashSetIsModifiedDuringIterationInRecursiveHierarchy() {
        testCollectionIsModifiedDuringIterationInRecursiveHierarchy(HashSet::new);
    }

    @Test
    void copyOnWriteArrayListIsModifiedDuringIterationInRecursiveHierarchy() {
        testCollectionIsModifiedDuringIterationInRecursiveHierarchy(CopyOnWriteArrayList::new);
    }

    @Test
    void concurrentLinkedQueueIsModifiedDuringIterationInRecursiveHierarchy() {
        testCollectionIsModifiedDuringIterationInRecursiveHierarchy(ConcurrentLinkedQueue::new);
    }

    private void testCollectionIsModifiedDuringIterationInRecursiveHierarchy(
            Supplier<Collection<SomeRecursiveEntity>> supplier) {
        // Note that if the inspector does not support recursive entities this will throw an StackOverflowError.
        AggregateModel<SomeRecursiveEntity> inspector =
                AnnotatedAggregateMetaModelFactory.inspectAggregate(SomeRecursiveEntity.class);

        // Create a hierarchy that we will use in this test.
        // The resulting hierarchy will look as follows:
        // root
        //      child1
        //              child2
        //                      child3
        String rootId = "root";
        String childId1 = "child1";
        String childId2 = "child2";
        String childId3 = "child3";

        SomeRecursiveEntity root = new SomeRecursiveEntity(supplier, null, rootId);
        inspector.publish(asEventMessage(new CreateChild(rootId, childId1)), root);
        inspector.publish(asEventMessage(new CreateChild(childId1, childId2)), root);
        inspector.publish(asEventMessage(new CreateChild(childId2, childId3)), root);
        SomeRecursiveEntity child1 = root.getChild(childId1);

        // Assert child1: it should have 1 child
        assertEquals(1, child1.children.size());

        // Now move child3 up one level so it is a child of child1.
        // The resulting hierarchy will look as follows:
        // root
        //      child1
        //              child2
        //              child3

        // Note that if the inspector does not use copy-iterators this will throw an ConcurrentModificationException.
        inspector.publish(asEventMessage(new MoveChildUp(childId2, childId3)), root);
        assertEquals(2, child1.children.size());
    }

    @Test
    void eventIsPublishedThroughoutHierarchy() {
        AggregateModel<SomeSubclass> inspector =
                AnnotatedAggregateMetaModelFactory.inspectAggregate(SomeSubclass.class);

        AtomicLong payload = new AtomicLong();

        inspector.publish(new GenericEventMessage<>(payload), new SomeSubclass());

        assertEquals(2L, payload.get());
    }

    @Test
    void expectCommandToBeForwardedToEntity() throws Exception {
        AggregateModel<SomeSubclass> inspector =
                AnnotatedAggregateMetaModelFactory.inspectAggregate(SomeSubclass.class);

        GenericCommandMessage<?> message = new GenericCommandMessage<>(BigDecimal.ONE);
        SomeSubclass target = new SomeSubclass();
        MessageHandlingMember<? super SomeSubclass> handler = getHandler(inspector, message);
        assertEquals("1", handler.handle(message, target));
    }

    @Test
    void methodIdentifierWithMethodParameters() {
        assertThrows(AggregateModellingException.class,
                     () -> AnnotatedAggregateMetaModelFactory
                             .inspectAggregate(SomeIllegalAnnotatedIdMethodClass.class));
        assertThrows(AggregateModellingException.class,
                     () -> AnnotatedAggregateMetaModelFactory
                             .inspectAggregate(SomeIllegalAnnotatedPersistenceIdMethodClass.class));
    }

    @Test
    void aggregateIdentifierPriority() {
        AggregateModel<SomeDifferentDoubleIdAnnotatedHandler> inspector =
                AnnotatedAggregateMetaModelFactory.inspectAggregate(SomeDifferentDoubleIdAnnotatedHandler.class);

        assertEquals("SomeDifferentDoubleIdAnnotatedHandler", inspector.type());
        assertEquals("id", inspector.getIdentifier(new SomeDifferentDoubleIdAnnotatedHandler()));
        assertEquals("id", inspector.routingKey());
    }

    @Test
    void findIdentifier() {
        AggregateModel<SomeAnnotatedHandlers> inspector =
                AnnotatedAggregateMetaModelFactory.inspectAggregate(SomeAnnotatedHandlers.class);

        assertEquals("SomeAnnotatedHandlers", inspector.type());
        assertEquals("id", inspector.getIdentifier(new SomeAnnotatedHandlers()));
        assertEquals("id", inspector.routingKey());
    }

    @Test
    void findGetterIdentifier() {
        AggregateModel<SomeGetterIdAnnotatedHandlers> inspector =
                AnnotatedAggregateMetaModelFactory.inspectAggregate(SomeGetterIdAnnotatedHandlers.class);

        assertEquals("SomeGetterIdAnnotatedHandlers", inspector.type());
        assertEquals("id", inspector.getIdentifier(new SomeGetterIdAnnotatedHandlers()));
        assertEquals("id", inspector.routingKey());
    }

    @Test
    void findMethodIdentifier() {
        AggregateModel<SomeMethodIdAnnotatedHandlers> inspector =
                AnnotatedAggregateMetaModelFactory.inspectAggregate(SomeMethodIdAnnotatedHandlers.class);

        assertEquals("SomeMethodIdAnnotatedHandlers", inspector.type());
        assertEquals("id", inspector.getIdentifier(new SomeMethodIdAnnotatedHandlers()));
        assertEquals("calculatedId", inspector.routingKey());
    }

    @Test
    void findJavaxPersistenceIdentifier() {
        AggregateModel<JakartaPersistenceAnnotatedHandlers> inspector =
                AnnotatedAggregateMetaModelFactory.inspectAggregate(JakartaPersistenceAnnotatedHandlers.class);

        assertEquals("id", inspector.getIdentifier(new JakartaPersistenceAnnotatedHandlers()));
        assertEquals("id", inspector.routingKey());
    }

    @Test
    void findJakartaPersistenceGetterIdentifier() {
        AggregateModel<JakartaPersistenceGetterAnnotatedHandlers> inspector =
                AnnotatedAggregateMetaModelFactory.inspectAggregate(JakartaPersistenceGetterAnnotatedHandlers.class);

        assertEquals("id", inspector.getIdentifier(new JakartaPersistenceGetterAnnotatedHandlers()));
        assertEquals("id", inspector.routingKey());
    }

    @Test
    void findJakartaPersistenceMethodIdentifier() {
        AggregateModel<JakartaPersistenceMethodIdAnnotatedHandlers> inspector =
                AnnotatedAggregateMetaModelFactory.inspectAggregate(JakartaPersistenceMethodIdAnnotatedHandlers.class);

        assertEquals("id", inspector.getIdentifier(new JakartaPersistenceMethodIdAnnotatedHandlers()));
        assertEquals("calculatedId", inspector.routingKey());
    }

    @Test
    void findIdentifierInSuperClass() {
        AggregateModel<SomeSubclass> inspector =
                AnnotatedAggregateMetaModelFactory.inspectAggregate(SomeSubclass.class);

        assertEquals("SomeOtherName", inspector.type());
        assertEquals("id", inspector.getIdentifier(new SomeSubclass()));
    }

    @Test
    void findGetterIdentifierInSuperClass() {
        AggregateModel<SomeGetterIdSubclass> inspector =
                AnnotatedAggregateMetaModelFactory.inspectAggregate(SomeGetterIdSubclass.class);

        assertEquals("SomeOtherGetterName", inspector.type());
        assertEquals("id", inspector.getIdentifier(new SomeGetterIdSubclass()));
    }

    @Test
    void entityInitializationIsThreadSafe() {
        for (int i = 0; i < 100; i++) {
            AggregateModel<PolyMorphAggregate> inspector =
                    AnnotatedAggregateMetaModelFactory.inspectAggregate(PolyMorphAggregate.class);

            PolyMorphAggregate instance1 = new PolyMorphAggregate();
            PolyMorphAggregate instance2 = new PolyMorphAggregate();

            AtomicLong counter = new AtomicLong();

            ForkJoinTask<?> task1 = ForkJoinPool.commonPool()
                                                .submit(() -> inspector.publish(asEventMessage(counter), instance1));
            ForkJoinTask<?> task2 = ForkJoinPool.commonPool()
                                                .submit(() -> inspector.publish(asEventMessage(counter), instance2));

            task1.join();
            task2.join();

            assertEquals(2, counter.get(), "Concurrency issue detected after " + i + " attempts.");
        }
    }

    @Test
    void illegalFactoryMethodThrowsExceptionClass() {
        assertThrows(
                AxonConfigurationException.class,
                () -> AnnotatedAggregateMetaModelFactory
                        .inspectAggregate(SomeIllegalAnnotatedFactoryMethodClass.class));
    }

    @Test
    void typedAggregateIdentifier() {
        assertThrows(
                AxonConfigurationException.class,
                () -> AnnotatedAggregateMetaModelFactory.inspectAggregate(TypedIdentifierAggregate.class)
        );
    }

    @Test
    void getterTypedAggregateIdentifier() {
        assertThrows(
                AxonConfigurationException.class,
                () -> AnnotatedAggregateMetaModelFactory.inspectAggregate(GetterTypedIdentifierAggregate.class)
        );
    }

    @Test
    void illegalDoubleIdentifiers() {
        assertThrows(AxonConfigurationException.class, () ->
                AnnotatedAggregateMetaModelFactory.inspectAggregate(IllegalDoubleIdFieldsAnnotatedAggregate.class)
        );
        assertThrows(AxonConfigurationException.class, () ->
                AnnotatedAggregateMetaModelFactory.inspectAggregate(IllegalDoubleIdMixedAnnotatedAggregate.class)
        );
        assertThrows(AxonConfigurationException.class, () ->
                AnnotatedAggregateMetaModelFactory.inspectAggregate(IllegalDoubleIdMethodsAnnotatedAggregate.class)
        );
    }

    @Test
    void voidMethodIdentifier() {
        assertThrows(AxonConfigurationException.class, () ->
                AnnotatedAggregateMetaModelFactory.inspectAggregate(IllegalVoidIdMethodAnnotatedAggregate.class)
        );
    }

    @Test
    void aggregateVersionAnnotatedField() {
        AggregateModel<AggregateWithAggregateVersionField> testSubject =
                AnnotatedAggregateMetaModelFactory.inspectAggregate(AggregateWithAggregateVersionField.class);

        assertEquals(42, testSubject.getVersion(new AggregateWithAggregateVersionField()));
    }

    @Test
    void aggregateVersionAnnotatedMethod() {
        AggregateModel<AggregateWithAggregateVersionMethod> testSubject =
                AnnotatedAggregateMetaModelFactory.inspectAggregate(AggregateWithAggregateVersionMethod.class);

        assertEquals(9001, testSubject.getVersion(new AggregateWithAggregateVersionMethod()));
    }

    @Test
    void illegalDoubleAggregateVersions() {
        assertThrows(AggregateModellingException.class, () ->
                AnnotatedAggregateMetaModelFactory.inspectAggregate(IllegalAggregateWithSeveralAggregateVersions.class)
        );
    }

    @SuppressWarnings("unchecked")
    private <T> MessageHandlingMember<T> getHandler(AggregateModel<?> members, CommandMessage<?> message) {
        return (MessageHandlingMember<T>) members.commandHandlers()
                                                 .stream()
                                                 .filter(ch -> ch.canHandle(message))
                                                 .findFirst()
                                                 .orElseThrow(() -> new AssertionError(
                                                         "Expected handler for this message"
                                                 ));
    }

    @Documented
    @EventHandler
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface MyCustomEventHandler {

    }

    @Documented
    @CommandHandler
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface MyCustomCommandHandler {

    }

    @SuppressWarnings("unused")
    private static class SomeDifferentDoubleIdAnnotatedHandler {

        @AggregateIdentifier
        private String id = "id";
        @Id
        private String javaxPersistenceId = "javaxPersistenceId";

        @CommandHandler
        public boolean testInt(Integer test) {
            return test > 0;
        }
    }


    @SuppressWarnings("unused")
    private static class JakartaPersistenceAnnotatedHandlers {

        @Id
        private String id = "id";

        @CommandHandler(commandName = "java.lang.String")
        public boolean handle(CharSequence test) {
            return test.equals("ok");
        }

        @CommandHandler
        public boolean testInt(Integer test) {
            return test > 0;
        }
    }

    @SuppressWarnings("unused")
    private static class JakartaPersistenceGetterAnnotatedHandlers {

        private String id = "id";

        @Id
        public String getId() {
            return id;
        }

        @CommandHandler(commandName = "java.lang.String")
        public boolean handle(CharSequence test) {
            return test.equals("ok");
        }

        @CommandHandler
        public boolean testInt(Integer test) {
            return test > 0;
        }
    }

    @SuppressWarnings("unused")
    private static class JakartaPersistenceMethodIdAnnotatedHandlers {

        private String id = "id";

        @Id
        public String calculatedId() {
            return id;
        }

        @CommandHandler(commandName = "java.lang.String")
        public boolean handle(CharSequence test) {
            return test.equals("ok");
        }

        @CommandHandler
        public boolean testInt(Integer test) {
            return test > 0;
        }
    }

    @SuppressWarnings("unused")
    private static class SomeAnnotatedHandlers {

        @AggregateIdentifier
        private String id = "id";

        @CommandHandler(commandName = "java.lang.String")
        public boolean handle(CharSequence test) {
            return test.equals("ok");
        }

        @CommandHandler
        public boolean testInt(Integer test) {
            return test > 0;
        }
    }

    @SuppressWarnings("unused")
    private static class SomeGetterIdAnnotatedHandlers {

        private String id = "id";

        @AggregateIdentifier
        public String getId() {
            return id;
        }

        @CommandHandler(commandName = "java.lang.String")
        public boolean handle(CharSequence test) {
            return test.equals("ok");
        }

        @CommandHandler
        public boolean testInt(Integer test) {
            return test > 0;
        }
    }

    @SuppressWarnings("unused")
    private static class SomeMethodIdAnnotatedHandlers {

        private String id = "id";

        @AggregateIdentifier
        public String calculatedId() {
            return id;
        }

        @CommandHandler(commandName = "java.lang.String")
        public boolean handle(CharSequence test) {
            return test.equals("ok");
        }

        @CommandHandler
        public boolean testInt(Integer test) {
            return test > 0;
        }
    }

    @SuppressWarnings("unused")
    private static class IllegalDoubleIdFieldsAnnotatedAggregate {

        @AggregateIdentifier
        private String id = "id";

        @AggregateIdentifier
        private String idTwo = "idTwo";

        @CommandHandler
        public boolean testInt(Integer test) {
            return test > 0;
        }
    }

    @SuppressWarnings("unused")
    private static class IllegalDoubleIdMixedAnnotatedAggregate {

        @AggregateIdentifier
        private String id = "id";

        @AggregateIdentifier
        public String getIdTwo() {
            return "idTwo";
        }

        @CommandHandler
        public boolean testInt(Integer test) {
            return test > 0;
        }
    }

    @SuppressWarnings("unused")
    private static class IllegalDoubleIdMethodsAnnotatedAggregate {

        @AggregateIdentifier
        public String getId() {
            return "id";
        }


        @AggregateIdentifier
        public String getIdTwo() {
            return "idTwo";
        }

        @CommandHandler
        public boolean testInt(Integer test) {
            return test > 0;
        }
    }

    @SuppressWarnings("unused")
    private static class IllegalVoidIdMethodAnnotatedAggregate {

        @AggregateIdentifier
        public void getId() {
        }

        @CommandHandler
        public boolean testInt(Integer test) {
            return test > 0;
        }
    }

    @SuppressWarnings("unused")
    @AggregateRoot(type = "SomeOtherName")
    private static class SomeSubclass extends SomeAnnotatedHandlers {

        @AggregateMember
        private SomeOtherEntity entity = new SomeOtherEntity();

        @MyCustomCommandHandler
        public boolean handleInSubclass(String test) {
            return test.contains("sub");
        }

        @MyCustomEventHandler
        public void handle(AtomicLong value) {
            value.incrementAndGet();
        }
    }

    @SuppressWarnings("unused")
    @AggregateRoot(type = "SomeOtherGetterName")
    private static class SomeGetterIdSubclass extends SomeGetterIdAnnotatedHandlers {

        @AggregateMember
        private SomeOtherEntity entity = new SomeOtherEntity();

        @MyCustomCommandHandler
        public boolean handleInSubclass(String test) {
            return test.contains("sub");
        }

        @MyCustomEventHandler
        public void handle(AtomicLong value) {
            value.incrementAndGet();
        }
    }

    @SuppressWarnings("unused")
    @AggregateRoot
    private static class PolyMorphAggregate {

        @AggregateMember
        private Object entity = new SomeOtherEntity();
    }

    private static class CustomIdentifier {

    }

    @SuppressWarnings("unused")
    @AggregateRoot
    private static class TypedIdentifierAggregate {

        @AggregateIdentifier
        private CustomIdentifier aggregateIdentifier = new CustomIdentifier();

        @CommandHandler
        public boolean handleInSubclass(String test) {
            return test.contains("sub");
        }

        @EventHandler
        public void handle(AtomicLong value) {
            value.incrementAndGet();
        }
    }

    @AggregateRoot
    private static class GetterTypedIdentifierAggregate {

        private CustomIdentifier aggregateIdentifier = new CustomIdentifier();

        @AggregateIdentifier
        public CustomIdentifier getAggregateIdentifier() {
            return aggregateIdentifier;
        }

        @CommandHandler
        public boolean handleInSubclass(String test) {
            return test.contains("sub");
        }

        @EventHandler
        public void handle(AtomicLong value) {
            value.incrementAndGet();
        }
    }

    private static class SomeOtherEntity {

        @CommandHandler
        public String handle(BigDecimal cmd) {
            return cmd.toPlainString();
        }

        @EventHandler
        public void handle(AtomicLong value) {
            value.incrementAndGet();
        }
    }

    private static class CreateChild {

        private final String parentId;
        private final String childId;

        public CreateChild(String parentId, String childId) {
            this.parentId = parentId;
            this.childId = childId;
        }
    }

    private static class MoveChildUp {

        private final String parentId;
        private final String childId;

        private MoveChildUp(String parentId, String childId) {
            this.parentId = parentId;
            this.childId = childId;
        }
    }

    private static class SomeRecursiveEntity {

        private final Supplier<Collection<SomeRecursiveEntity>> supplier;
        private final SomeRecursiveEntity parent;
        private final String entityId;

        @AggregateMember
        private final SomeIterable<SomeRecursiveEntity> children;

        public SomeRecursiveEntity(Supplier<Collection<SomeRecursiveEntity>> supplier,
                                   SomeRecursiveEntity parent,
                                   String entityId) {
            this.supplier = supplier;
            this.parent = parent;
            this.entityId = entityId;
            this.children = new SomeIterable<>(supplier.get());
        }

        public SomeRecursiveEntity getChild(String childId) {
            for (SomeRecursiveEntity c : children) {
                if (Objects.equals(c.entityId, childId)) {
                    return c;
                }
            }
            return null;
        }

        @EventHandler
        public void handle(CreateChild event) {
            if (Objects.equals(this.entityId, event.parentId)) {
                children.add(new SomeRecursiveEntity(supplier, this, event.childId));
            }
        }

        @EventHandler
        public void handle(MoveChildUp event) {
            if (Objects.equals(this.entityId, event.parentId)) {
                SomeRecursiveEntity child = getChild(event.childId);
                assertNotNull(child);
                assertTrue(this.children.remove(child));
                assertTrue(parent.children.add(child));
            }
        }
    }

    @SuppressWarnings({"UnusedAssignment", "unused"})
    public static class SomeAnnotatedFactoryMethodClass {

        @AggregateIdentifier
        private String id = "id";

        @CommandHandler
        public static SomeAnnotatedFactoryMethodClass factoryMethod(String id) {
            return new SomeAnnotatedFactoryMethodClass(id);
        }

        SomeAnnotatedFactoryMethodClass(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }

    @SuppressWarnings({"UnusedAssignment", "unused"})
    public static class SomeIllegalAnnotatedFactoryMethodClass {

        @AggregateIdentifier
        private String id = "id";

        @CommandHandler
        public static Object illegalFactoryMethod(String id) {
            return new SomeAnnotatedFactoryMethodClass(id);
        }

        SomeIllegalAnnotatedFactoryMethodClass(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }

    public static class SomeIllegalAnnotatedIdMethodClass {

        private String id = "id";

        @CommandHandler
        SomeIllegalAnnotatedIdMethodClass(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }

        @AggregateIdentifier
        public String calculatedIdMethod(String seed) {
            return seed + id;
        }
    }

    public static class SomeIllegalAnnotatedPersistenceIdMethodClass {

        private String id = "id";

        @CommandHandler
        SomeIllegalAnnotatedPersistenceIdMethodClass(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }

        @Id
        public String fancyCalculatedIdMethod(String seed) {
            return seed + id;
        }
    }

    /**
     * Wrapper implementation to ensure that the @AggregateMember field is solely triggered by the fact it implements
     * Iterable, and doesn't depend on any other interface being declared. See issue #461.
     *
     * @param <T> The type contained in this iterable
     */
    private static class SomeIterable<T> implements Iterable<T> {

        private final Collection<T> contents;

        public SomeIterable(Collection<T> contents) {
            this.contents = contents;
        }

        @Nonnull
        @Override
        public Iterator<T> iterator() {
            return contents.iterator();
        }

        @Override
        public void forEach(Consumer<? super T> action) {
            contents.forEach(action);
        }

        @Override
        public Spliterator<T> spliterator() {
            return contents.spliterator();
        }

        public boolean add(T item) {
            return contents.add(item);
        }

        public boolean remove(T item) {
            return contents.remove(item);
        }

        public int size() {
            return contents.size();
        }
    }


    private static class AggregateWithAggregateVersionField {

        @AggregateIdentifier
        private final String aggregateIdentifier = "aggregateIdentifier";
        @AggregateVersion
        private final long aggregateVersion = 42L;
    }

    private static class AggregateWithAggregateVersionMethod {

        @AggregateIdentifier
        private final String aggregateIdentifier = "aggregateIdentifier";

        @AggregateVersion
        public long getAggregateVersion() {
            return 9001L;
        }
    }

    private static class IllegalAggregateWithSeveralAggregateVersions {

        @AggregateIdentifier
        private String aggregateIdentifier;
        @AggregateVersion
        private long fieldAggregateVersion;

        @AggregateVersion
        public long getMethodAggregateVersion() {
            return 1337;
        }
    }
}
