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

import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.QualifiedName;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Meta-annotation used to have a common grounds for the {@link #name()} and {@link #version()} for more specific
 * {@link org.axonframework.messaging.core.Message} annotation.
 * <p>
 * Furthermore, allows for annotation scanning on a more generic level than per message type.
 * <p>
 * This annotation is meta-annotated with {@link org.axonframework.messaging.core.annotation.QualifiedName}, which
 * defines the {@link #namespace()} and {@link #name()} pairing that handler annotations can mirror when referring to a
 * message.
 *
 * @author Steven van Beelen
 * @since 5.0.0
 */
@Internal
@org.axonframework.messaging.core.annotation.QualifiedName
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface Message {

    /**
     * The namespace or (bounded) context of the annotated message.
     * <p>
     * Will typically be mapped to the {@link QualifiedName#namespace()}. Whenever this attribute is defined, the
     * {@link #name()} will become the {@link QualifiedName#localName()}. Together they would form the
     * {@link QualifiedName#name()}.
     *
     * @return The namespace or (bounded) context of the annotated message.
     */
    String namespace() default "";

    /**
     * The business or domain name of the annotated message.
     * <p>
     * Will typically be mapped to a {@link QualifiedName#QualifiedName(String)} and inserted into a
     * {@link MessageType}. By using the String-based constructor of the {@link QualifiedName}, this field will
     * represent the combination of the {@link QualifiedName#localName()} and {@link QualifiedName#namespace()}.
     * <p>
     * If the {@link #namespace()} has also been specified, this attribute will reflect the
     * {@link QualifiedName#localName()} <b>only</b>.
     *
     * @return The business or domain name of the annotated message.
     */
    String name() default "";

    /**
     * The version of the annotated message.
     * <p>
     * Will typically be mapped to the {@link MessageType#version()}. Defaults to {@link MessageType#DEFAULT_VERSION}.
     *
     * @return The version of the annotated message.
     */
    String version() default MessageType.DEFAULT_VERSION;

    /**
     * The type of {@link org.axonframework.messaging.core.Message} this annotation applies to.
     *
     * @return The type of {@link org.axonframework.messaging.core.Message} this annotation applies to.
     */
    Class<? extends org.axonframework.messaging.core.Message> messageType();
}
