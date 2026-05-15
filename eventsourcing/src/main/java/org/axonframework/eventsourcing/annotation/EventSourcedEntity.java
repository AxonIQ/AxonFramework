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

package org.axonframework.eventsourcing.annotation;

import org.axonframework.eventsourcing.CriteriaResolver;
import org.axonframework.eventsourcing.EventSourcedEntityFactory;
import org.axonframework.eventsourcing.EventSourcingRepository;
import org.axonframework.eventsourcing.annotation.reflection.AnnotationBasedEventSourcedEntityFactoryDefinition;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.messaging.commandhandling.CommandHandler;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.messaging.core.MessageTypeResolver;
import org.axonframework.messaging.core.QualifiedName;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.modelling.annotation.AnnotationBasedEntityIdResolver;
import org.axonframework.modelling.annotation.EntityIdResolverDefinition;
import org.axonframework.modelling.annotation.TargetEntityId;
import org.axonframework.modelling.entity.EntityCommandHandler;
import org.axonframework.modelling.entity.annotation.AnnotatedEntityIdResolverDefinition;
import org.axonframework.modelling.entity.annotation.AnnotatedEntityMetamodel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to configure several aspects of an event-sourced entity. This annotation is required to construct an
 * annotation-based entity through the {@link EventSourcedEntityModule#autodetected(Class, Class)}.
 *
 * <h2>Event Criteria</h2>
 * While loading the entity from the {@link EventSourcingRepository}, the provided {@code id} needs to be translated to
 * an {@link EventCriteria} instance to load the correct events. Unless overridden, this translation is done by the
 * {@link AnnotationBasedEventCriteriaResolver}. The {@link EventCriteria} will be resolved in the following order:
 * <ol>
 *     <li>
 *         Using the custom {@link #criteriaResolverDefinition()}. The definition should return a {@link CriteriaResolver},
 *         implement the {@link CriteriaResolverDefinition} interface, and have a no-arg constructor.
 *     </li>
 *     <li>
 *         By defining a static method in the entity class annotated with {@link EventCriteriaBuilder} which returns an
 *         {@link EventCriteria} and accepts the {@code id} as a parameter. This method should be static and return an
 *         {@link EventCriteria}. Multiple methods can be defined with different id types, and the first matching method
 *         will be used. Optionally, you can define {@link MessageTypeResolver} as a second
 *         parameter to resolve the type of the message. Other arguments are not supported.
 *     </li>
 *     <li>
 *         If no matching {@link EventCriteriaBuilder} is found, the {@link EventSourcedEntity#tagKey()} will be used as
 *         the tag key, and the {@link Object#toString()} of the id will be used as value.
 *     </li>
 *     <li>
 *         If the {@link EventSourcedEntity#tagKey()} is empty, the {@link Class#getSimpleName()} of the entity will be
 *         used as the key, and the {@link Object#toString()} of the id will be used as value.
 *     </li>
 * </ol>
 *
 * <h2>Entity Factory</h2>
 * Event-sourced entities need to be instantiated before they can be evolved based on past events. The
 * {@link EventSourcedEntityFactory} is responsible for creating a new instance of the entity.
 * By default, this is done based on constructors or static methods annotated with {@link EntityCreator}.
 * These creators can take the payload or message of the first event, or the entity id as a parameter.
 * For more information, see the examples in the Javadoc of the annotation.
 *
 * <h2>Command handling</h2>
 * Entities can declare {@link org.axonframework.messaging.commandhandling.annotation.CommandHandler}-annotated methods
 * to execute a command on the entity. When a command targets an entity, the following steps are taken:
 * <ol>
 *     <li>The {@link org.axonframework.modelling.entity.EntityCommandHandlingComponent} will use the
 *     {@link EntityIdResolverDefinition} to resolve the entity id from the command message.</li>
 *     <li>The entity id is used to resolve the {@link EventCriteria} for the entity, as described above.</li>
 *     <li>The {@link EventSourcedEntityFactory} is used to create a new instance of the entity by the {@link EventSourcingRepository}.</li>
 *     <li>Existing events for the entity are used to {@link org.axonframework.modelling.EntityEvolver evolve} the entity.</li>
 *     <li>The command is called on the entity in case of a
 *     {@link org.axonframework.modelling.entity.EntityMetamodelBuilder#instanceCommandHandler(QualifiedName, EntityCommandHandler) instance command handler},
 *     or on the {@link org.axonframework.modelling.entity.EntityMetamodelBuilder#creationalCommandHandler(QualifiedName, CommandHandler) creational command handler}
 *     if it did not exist.
 *     </li>
 * </ol>
 * <p>
 * By default, the id is resolved using the {@link AnnotationBasedEntityIdResolver}, which resolves the
 * id based on the {@link TargetEntityId} annotation on a field or method
 * of the command payload. You can customize this behavior by providing a custom {@link #entityIdResolverDefinition()}.
 *
 * <h2>Polymorphic entities</h2>
 * Polymorphic entities are entities that can have multiple concrete types, and the type of the entity is determined
 * by the payload of the first event. In this case, the {@link EventSourcedEntity#concreteTypes()} should be
 * specified with the concrete types of the entity.
 *
 * @author Mitchell Herrijgers
 * @see EntityCreator
 * @see EventCriteriaBuilder
 * @see AnnotationBasedEventCriteriaResolver
 * @see EventCriteriaBuilder
 * @see EventSourcedEntityFactory
 * @since 5.0.0
 */
@SuppressWarnings("unused")
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface EventSourcedEntity {

    /**
     * The tag name to use when resolving the {@link EventCriteria} for the entity. If empty, the simple name of the
     * entity class will be used.
     * <p>
     * This value does not take effect if a matching {@link EventCriteriaBuilder} is found, or a custom
     * {@link #criteriaResolverDefinition()} is provided.
     *
     * @return The tag name to use when resolving the {@link EventCriteria} for the entity.
     */
    String tagKey() default "";

    /**
     * If the entity is a polymorphic entity, any subclasses that should be considered concrete types of the entity
     * should be specified here. Classes that are not specified here will not be scanned.
     *
     * @return The concrete types of the entity that should be considered when building the
     * {@link AnnotatedEntityMetamodel}.
     */
    Class<?>[] concreteTypes() default {};

    /**
     * The definition of the {@link CriteriaResolver} to use to resolve the {@link EventCriteria} for the entity. A
     * custom definition can be provided to override the default behavior of the
     * {@link AnnotationBasedEventCriteriaResolver}.
     *
     * @return The definition to construct a {@link CriteriaResolverDefinition}.
     */
    Class<? extends CriteriaResolverDefinition> criteriaResolverDefinition() default AnnotationBasedEventCriteriaResolverDefinition.class;

    /**
     * The definition of the {@link EventSourcedEntityFactory} to use to create a new instance of the entity. A custom
     * definition can be provided to override the default behavior of the
     * {@link AnnotationBasedEventSourcedEntityFactoryDefinition}.
     *
     * @return The definition to construct an {@link EventSourcedEntityFactory}.
     */
    Class<? extends EventSourcedEntityFactoryDefinition<?, ?>> entityFactoryDefinition() default AnnotationBasedEventSourcedEntityFactoryDefinition.class;

    /**
     * The definition of the {@link EntityIdResolverDefinition} to use to resolve the entity id from a
     * {@link CommandMessage command message}. Defaults to the
     * {@link AnnotatedEntityIdResolverDefinition}, which resolves the entity id based on the
     * {@link TargetEntityId} annotation on a payload field or method, after
     * converting the payload to the representation wanted by the entity.
     *
     * @return The definition to construct an {@link EntityIdResolverDefinition}.
     */
    Class<? extends EntityIdResolverDefinition> entityIdResolverDefinition() default AnnotatedEntityIdResolverDefinition.class;
}
