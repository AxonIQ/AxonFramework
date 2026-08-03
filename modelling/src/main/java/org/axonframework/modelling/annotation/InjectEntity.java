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

package org.axonframework.modelling.annotation;

import org.axonframework.messaging.core.annotation.MessageHandler;
import org.axonframework.modelling.EntityIdResolver;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/**
 * Annotation to be placed on a parameter of a {@link MessageHandler} annotated method that should receive an entity
 * loaded from the {@link org.axonframework.modelling.StateManager}.
 * <p>
 * The parameter should be of the type of the entity to inject, or of a
 * {@link org.axonframework.modelling.repository.ManagedEntity} with the generic of the entity to inject. Either can
 * also be wrapped in an {@link java.util.Optional}, for example {@code Optional<MyEntity>} or
 * {@code Optional<ManagedEntity<ID, MyEntity>>}.
 * <p>
 * The {@code idProperty} attribute can be used to specify the property of the message payload that contains the
 * identifier of the entity to inject. If not specified, the {@code idResolver} is used to resolve the identifier of the
 * entity to inject.
 * <p>
 * Unless a specific {@code idResolver} is specified, the {@link AnnotationBasedEntityIdResolver} is used to resolve the
 * entity id from the message. This is based on finding a {@link TargetEntityId} annotation on a field or accessor
 * method of the message payload.
 * <p>
 * So, identifiers will be resolved in the following order:
 * <ol>
 *     <li>From the property specified in {@code idProperty}.</li>
 *     <li>From the {@code idResolver}.</li>
 *     <li>From the {@link TargetEntityId} annotation on the message payload.</li>
 * </ol>
 * <p>
 * When no entity can be found for the resolved identifier, the parameter's type and nullability determine the
 * outcome. By default, an {@link org.axonframework.modelling.repository.EntityNotFoundException} is propagated,
 * failing the message being handled. A parameter annotated with an annotation resembling {@code "nullable"} will
 * instead resolve to {@code null}, allowing the handler to deal with a missing entity itself, for example to support
 * create-or-update semantics. Declaring the parameter as {@code Optional<MyEntity>} (or
 * {@code Optional<ManagedEntity<ID, MyEntity>>}) achieves the same outcome without needing a {@code "nullable"}
 * annotation: the parameter resolves to {@link java.util.Optional#empty()} instead of {@code null}.
 * <p>
 * Nullability is resolved through the {@link org.axonframework.common.nullability.NullabilityDetector} chain, so
 * languages expressing it in their type system rather than through a runtime-visible annotation are honored too.
 * With the Kotlin extension on the classpath, a Kotlin {@code MyEntity?} parameter behaves as a nullable one without
 * any annotation, while a non-null {@code MyEntity} keeps the default failing behavior.
 *
 * @author Mitchell Herrijgers
 * @since 5.0.0
 */
@Target({ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface InjectEntity {

    /**
     * The property of the message payload that contains the identifier of the entity to inject. If not specified, the
     * {@code idResolver} is used to resolve the identifier of the entity to inject.
     *
     * @return The property of the message payload that contains the identifier of the entity to inject.
     */
    String idProperty() default "";

    /**
     * The {@link EntityIdResolver} to resolve the identifier of the entity to inject. Should have a no-arg
     * constructor.
     *
     * @return The {@link EntityIdResolver} to resolve the identifier of the entity to inject.
     */
    Class<? extends EntityIdResolver> idResolver() default AnnotationBasedEntityIdResolver.class;
}
