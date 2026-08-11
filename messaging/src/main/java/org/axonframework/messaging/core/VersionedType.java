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

package org.axonframework.messaging.core;

/**
 * Describes a component that has a {@link QualifiedName qualified name} and a {@code version}.
 * <p>
 * This interface provides a general-purpose abstraction for any versioned, named type in the framework.
 * It decouples the concept of "a named thing with a version" from {@link MessageType}, so that non-message
 * components (such as snapshot stores) can reference versioned type information without implying that
 * the component is a message.
 * <p>
 * {@link MessageType} is the primary implementation of this interface.
 *
 * @author Allard Buijze
 * @author Ishaan Bhela
 * @since 5.4.0
 * @see MessageType
 * @see QualifiedName
 */
public interface VersionedType {

    /**
     * Returns the {@link QualifiedName} of this versioned type.
     *
     * @return the qualified name, never {@code null}
     */
    QualifiedName qualifiedName();

    /**
     * Returns the version of this versioned type.
     *
     * @return the version string, never {@code null}
     */
    String version();

    /**
     * Returns the string representation of the {@link #qualifiedName()}.
     * <p>
     * This is a convenience method equivalent to {@code qualifiedName().toString()}.
     *
     * @return the name as a string, never {@code null}
     */
    String name();
}
