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

import org.axonframework.messaging.commandhandling.CommandHandler;
import org.axonframework.common.configuration.Configuration;
import org.axonframework.eventsourcing.EventSourcingRepository;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.annotation.ParameterResolverFactory;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.modelling.entity.EntityMetamodelBuilder;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation that indicates that a method or constructor is a factory method for an event-sourced entity. This
 * annotation will only work if the {@link AnnotationBasedEventSourcedEntityFactory} is used by the
 * {@link EventSourcingRepository}. Constructors or methods not annotated with this annotation will not be considered as
 * a way to create the entity.
 *
 * <h3>Entity creation methods</h3>
 * There are two main ways to create an entity.
 * <p>
 * First, a constructor or factory method with a payload parameter (or
 * {@link EventMessage} parameter) can be defined, which will be called when the entity
 * is sourced and at least one event is found in the stream. This allows the entity to be created with non-nullable
 * properties, based on the origin event. If no event is found, the entity will be {@code null} until the first event is
 * published and the entity is created using this method. This means that, for commands that target an entity that might
 * not have an origin event, an
 * {@link EntityMetamodelBuilder#creationalCommandHandler(QualifiedName, CommandHandler) creational command
 * handler} should be defined.
 * <p>
 * Secondly, a constructor or factory method can define no payload. It can still define the identifier as an argument.
 * This will always initialize the entity, even if no events are found in the stream. This is useful for entities that
 * are created without an origin event, such as those with a dynamic boundary.
 *
 * <h3>Parameter types</h3>
 * The constructor or method can declare any number of parameters, as long as they can be resolved by a
 * {@link ParameterResolverFactory}. If the payload is declared, this should be
 * the first parameter. {@link Configuration} components can be injected, as well as any
 * message-related parameters. In addition to all regular parameters, the method can also declare the identifier of the
 * entity as a parameter.
 * <p>
 * The declared payload parameter is the wanted represented type, and will be injected if the
 * {@link #payloadQualifiedNames()} matches with the {@link EventMessage}. If a payload
 * parameter is declared, and the {@link #payloadQualifiedNames()} is empty, it will be determined based on the
 * {@link MessageTypeResolver}.
 * <p>
 * You can inject the entity identifier by declaring a parameter with the {@link InjectEntityId} annotation. This
 * annotation is necessary to disambiguate the entity identifier from the payload, as the first parameter without an
 * annotation is assumed to be the payload.
 *
 * <h3>Factory methods</h3>
 * It's not always possible to only use constructors. For example, when using polymorphic entities, the factory method
 * needs to call the right constructor based on the arguments of the method. This method needs to be static, and return
 * the entity type of one of the declared subtypes.
 *
 * <h3>Examples: Mutable entity</h3>
 * In the following example, the entity is created with a constructor that takes the identifier. The instance command
 * handler then be used to handle the create command.
 * <pre>{@code
 *     class MutableEntity {
 *         private String identifier;
 *         private Boolean created;
 *
 *         @EntityCreator
 *         public MutableEntity(@InjectEntityId String identifier) {
 *             this.identifier = identifier;
 *             this.created = false;
 *         }
 *
 *         @CommandHandler
 *         public void handle(CreateCommand command, EventAppender appender) {
 *             if(created) {
 *                 throw new IllegalStateException("Entity already created");
 *             }
 *             appender.append(new MutableEntityCreatedEvent(identifier));
 *         }
 *
 *         @EventSourcingHandler
 *         public void on(MutableEntityCreatedEvent event) {
 *             this.created = true;
 *         }
 *     }
 * }
 * </pre>
 *
 * <h3>Examples: Immutable entity</h3>
 * In the following example, the entity is created with a constructor that takes the event. If there is no event, it
 * will not be created. As such, we will need a creational (static) command handler method.
 * <pre>{@code
 *     class ImmutableEntity {
 *         private final String identifier;
 *
 *         @EntityCreator
 *         public MutableEntity(ImmutableEntityCreatedEvent createdEvent, @InjectEntityId String identifier) {
 *             this.identifier = createdEvent.identifier();
 *             // Or: this.identifier = identifier
 *         }
 *
 *         @CommandHandler
 *         public static void handle(CreateCommand command, EventAppender appender) {
 *             appender.append(new MutableEntityCreatedEvent(identifier));
 *         }
 *     }
 * }
 * </pre>
 *
 * <h3>Examples: Polymorphic mutable entities</h3>
 * Polymorphic mutable entities need a factory method that can, based on the identifier, create the right entity. The
 * factory method needs to be static, and be defined on the superclass.
 * <pre>{@code
 *     abstract class MutablePolymorphicEntity {
 *         @EntityCreator
 *         public static MutablePolymorphicEntity create(@InjectEntityId MyPolymorphicIdentifier identifier) {
 *             if (identifier.type() == MyPolymorphicIdentifier.Type.TYPE1) {
 *                 return new MutablePolymorphicEntityType1(identifier);
 *             } else if (identifier.type() == MyPolymorphicIdentifier.Type.TYPE2) {
 *                 return new MutablePolymorphicEntityType2(identifier);
 *             } else {
 *                 throw new IllegalArgumentException("Unknown type: " + identifier.type());
 *             }
 *         }
 *     }
 * }
 * </pre>
 *
 * <h3>Examples: Polymorphic immutable entities</h3>
 * When using immutable polymorphic entities, you can either declare an {@link EntityCreator} on superclass with an
 * event parameter, and call the right constructor. Alternatively, you can declare a constructor on each of the
 * subclasses, and use the {@link EntityCreator} to call the right constructor. For the latter, the event types need to
 * be unique.
 *
 * <pre>{@code
 *     class MutablePolymorphicEntityType1 extends MutablePolymorphicEntity {
 *     @EntityCreator
 *     public MutablePolymorphicEntityType1(MutablePolymorphicEntityType1CreatedEvent event) {
 *         // Initialize the entity with the event
 *     }
 * }</pre>
 *
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface EntityCreator {

    /**
     * The qualified names of the payload types that this factory method can handle. If a payload parameter is declared,
     * and this value is left at default, the payload's qualified name will be determined based on the
     * {@link MessageTypeResolver}.
     *
     * @return The qualified names of the payload types that this factory method can handle.
     */
    String[] payloadQualifiedNames() default {};
}
