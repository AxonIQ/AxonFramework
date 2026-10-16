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

package org.axonframework.messaging.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to be placed on a parameter of a {@link MessageHandler} annotated method that should receive a component
 * from the {@link org.axonframework.common.configuration.Configuration}.
 * <p>
 * Without a {@link #name()}, the parameter resolves to the component registered for the parameter's type. With a
 * {@link #name()}, the parameter resolves to the component registered for the parameter's type under that name. This
 * allows a handler to select one of several components of the same type, for example one of several named
 * {@link org.axonframework.messaging.eventhandling.EventSink EventSinks}:
 * <pre>{@code
 * public class AuditProjector {
 *
 *     @EventHandler
 *     public void on(OrderPlaced event, @InjectComponent(name = "auditSink") EventSink auditSink) {
 *         // publish to the "auditSink" instead of the default EventSink
 *     }
 * }
 * }</pre>
 * <p>
 * For a generic parameter type, the type arguments are part of the lookup. A parameter of type
 * {@code Repository<Order>} resolves to a component registered with that generic type, for example through a
 * {@link org.axonframework.common.TypeReference}, and not to a component registered with the raw type
 * {@code Repository}. A component registered with a raw type is injected into a parameter of that raw type.
 * <p>
 * A parameter annotated with {@code @InjectComponent} takes precedence over all other parameter resolution based on
 * the parameter's type, including resolution of beans in a Spring Application Context. When using Spring, the
 * {@link #name()} matches the bean name, as well as the value of a
 * {@code org.springframework.beans.factory.annotation.Qualifier} on the bean. When no bean has that name and several
 * beans carry it as their qualifier, resolution fails with an
 * {@link org.axonframework.common.configuration.AmbiguousComponentMatchException}.
 * <p>
 * This annotation can also be used as a meta-annotation, for example to define a dedicated {@code @AuditSink}
 * annotation that is itself annotated with {@code @InjectComponent(name = "auditSink")}.
 * <p>
 * The component is resolved when the handler is inspected, which typically happens while the application starts. If no
 * matching component exists, the handler's inspection fails with a
 * {@link org.axonframework.common.configuration.ComponentNotFoundException}, instead of failing on the first message
 * handled.
 *
 * @author Allard Buijze
 * @see InjectComponentParameterResolverFactory
 * @since 5.5.0
 */
@Documented
@Target({ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface InjectComponent {

    /**
     * The name of the component to inject. When empty, which is the default, the component is resolved by the
     * parameter's type only.
     *
     * @return the name of the component to inject, or an empty {@code String} to resolve by type only
     */
    String name() default "";
}
