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

package org.axonframework.extension.micronaut.stereotype;

import io.micronaut.context.annotation.AliasFor;
import io.micronaut.context.annotation.Executable;
import org.axonframework.eventsourcing.CriteriaResolver;
import org.axonframework.eventsourcing.EventSourcedEntityFactory;
import org.axonframework.eventsourcing.annotation.AnnotationBasedEventCriteriaResolver;
import org.axonframework.eventsourcing.annotation.AnnotationBasedEventCriteriaResolverDefinition;
import org.axonframework.eventsourcing.annotation.CriteriaResolverDefinition;
import org.axonframework.eventsourcing.annotation.EventCriteriaBuilder;
import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.eventsourcing.annotation.EventSourcedEntityFactoryDefinition;
import org.axonframework.eventsourcing.annotation.reflection.AnnotationBasedEventSourcedEntityFactoryDefinition;
import org.axonframework.messaging.eventstreaming.EventCriteria;
import org.axonframework.messaging.commandhandling.CommandMessage;
import org.axonframework.modelling.annotation.EntityIdResolverDefinition;
import org.axonframework.modelling.annotation.TargetEntityId;
import org.axonframework.modelling.entity.annotation.AnnotatedEntityIdResolverDefinition;
import org.axonframework.modelling.entity.annotation.AnnotatedEntityMetamodel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation that informs Axon's auto configurer for Spring that a given {@link Component} is an event-sourced entity instance.
 * <p>This annotation is a meta-annotation of {@link EventSourcedEntity} allowing to put the configuration
 * directly.</p>
 *
 * @author Allard Buijze
 * @author Simon Zambrovski
 * @since 3.0
 */
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@EventSourcedEntity
public @interface EventSourced {

    /**
     * Get the String representation of the entity's type. Optional. This defaults to the simple name of the annotated
     * class.
     *
     * @return The type of the entity.
     */
    String type() default "";

    /**
     * Get the Class of entity's id type, important for construction of the
     *
     * @return The class of the entity id.
     */
    Class<?> idType() default String.class;

    /**
     * The tag name to use when resolving the {@link EventCriteria} for the entity. If empty, the simple name of the
     * entity class will be used.
     * <p>
     * This value does not take effect if a matching {@link EventCriteriaBuilder} is found, or a custom
     * {@link #criteriaResolverDefinition()} is provided.
     *
     * @return The tag name to use when resolving the {@link EventCriteria} for the entity.
     */
    @AliasFor(annotation = EventSourcedEntity.class, member = "tagKey")
    String tagKey() default "";

    /**
     * If the entity is a polymorphic entity, any subclasses that should be considered concrete types of the entity
     * should be specified here. Classes that are not specified here will not be scanned.
     *
     * @return The concrete types of the entity that should be considered when building the
     * {@link AnnotatedEntityMetamodel}.
     */
    @AliasFor(annotation = EventSourcedEntity.class, member = "concreteTypes")
    Class<?>[] concreteTypes() default {};

    /**
     * The definition of the {@link CriteriaResolver} to use to resolve the {@link EventCriteria} for the entity. A
     * custom definition can be provided to override the default behavior of the
     * {@link AnnotationBasedEventCriteriaResolver}.
     *
     * @return The definition to construct a {@link CriteriaResolverDefinition}.
     */
    @AliasFor(annotation = EventSourcedEntity.class, member = "criteriaResolverDefinition")
    Class<? extends CriteriaResolverDefinition> criteriaResolverDefinition() default AnnotationBasedEventCriteriaResolverDefinition.class;

    /**
     * The definition of the {@link EventSourcedEntityFactory} to use to create a new instance of the entity. A custom
     * definition can be provided to override the default behavior of the
     * {@link AnnotationBasedEventSourcedEntityFactoryDefinition}.
     *
     * @return The definition to construct an {@link EventSourcedEntityFactory}.
     */
    @AliasFor(annotation = EventSourcedEntity.class, member = "entityFactoryDefinition")
    Class<? extends EventSourcedEntityFactoryDefinition> entityFactoryDefinition() default AnnotationBasedEventSourcedEntityFactoryDefinition.class;

    /**
     * The definition of the {@link EntityIdResolverDefinition} to use to resolve the entity id from a
     * {@link CommandMessage command message}. Defaults to the
     * {@link AnnotatedEntityIdResolverDefinition}, which resolves the entity id based on the
     * {@link TargetEntityId} annotation on a payload field or method, after
     * converting the payload to the representation wanted by the entity.
     *
     * @return The definition to construct an {@link EntityIdResolverDefinition}.
     */
    @AliasFor(annotation = EventSourcedEntity.class, member = "entityIdResolverDefinition")
    Class<? extends EntityIdResolverDefinition> entityIdResolverDefinition() default AnnotatedEntityIdResolverDefinition.class;
}
