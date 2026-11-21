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

package org.axonframework.messaging.eventhandling.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Container annotation for repeated {@link EventHandler @EventHandler} annotations on a single method.
 * <p>
 * The Java compiler places this annotation on a method that declares {@code @EventHandler} more than once. It is not
 * meant to be used directly: repeat the {@code @EventHandler} annotation instead. Each repeated annotation registers
 * the method as a separate event handler.
 *
 * @author Allard Buijze
 * @see EventHandler
 * @since 5.5.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
public @interface EventHandlers {

    /**
     * The repeated {@link EventHandler @EventHandler} annotations.
     *
     * @return the repeated {@link EventHandler @EventHandler} annotations
     */
    EventHandler[] value();
}
