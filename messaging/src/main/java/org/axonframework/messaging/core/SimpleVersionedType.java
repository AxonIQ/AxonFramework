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

import static java.util.Objects.requireNonNull;

/**
 * A simple implementation of {@link VersionedType} representing a component that has a
 * {@link QualifiedName} and a {@code version}.
 * <p>
 * This record can be used by components that require versions to be implemented.
 *
 * @param qualifiedName The {@code QualifiedName} of this versioned type.
 * @param version       The version of this versioned type. Must not be null or blank.
 * @author Ishaan Bhela
 * @since 5.4.0
 */
public record SimpleVersionedType(QualifiedName qualifiedName, String version) implements VersionedType {

    /**
     * The default version when none is given. Set to {@code 0.0.1}.
     */
    public static final String DEFAULT_VERSION = "0.0.1";

    /**
     * Constructor validating that the given {@code qualifiedName} is non-null, and that {@code version} is
     * non-null and not blank.
     */
    public SimpleVersionedType {
        requireNonNull(qualifiedName, "The qualifiedName cannot be null.");
        requireNonNull(version, "The version cannot be null.");

        if (version.isBlank()) {
            throw new IllegalArgumentException("The version cannot be blank.");
        }
    }

    /**
     * A {@code SimpleVersionedType} constructor setting the {@code version} to {@code 0.0.1}.
     *
     * @param qualifiedName The {@code QualifiedName} of this {@code SimpleVersionedType}.
     */
    public SimpleVersionedType(QualifiedName qualifiedName) {
        this(qualifiedName, DEFAULT_VERSION);
    }

    /**
     * A {@code SimpleVersionedType} constructor using the given {@code qualifiedNameString} invoking the
     * {@link QualifiedName#QualifiedName(String)} constructor.
     *
     * @param qualifiedNameString The string to create a {@link QualifiedName} from.
     * @param version             The version for this type.
     */
    public SimpleVersionedType(String qualifiedNameString, String version) {
        this(new QualifiedName(qualifiedNameString), version);
    }

    /**
     * A {@code SimpleVersionedType} constructor using the given {@code qualifiedNameString} invoking the
     * {@link QualifiedName#QualifiedName(String)} constructor, and setting the {@code version} to {@code 0.0.1}.
     *
     * @param qualifiedNameString The string to create a {@link QualifiedName} from.
     */
    public SimpleVersionedType(String qualifiedNameString) {
        this(new QualifiedName(qualifiedNameString), DEFAULT_VERSION);
    }

    /**
     * Returns the string representation of the {@link #qualifiedName()}.
     *
     * @return the name as a string
     */
    @Override
    public String name() {
        return qualifiedName.toString();
    }

    @Override
    public String toString() {
        return qualifiedName + "#" + version;
    }
}
