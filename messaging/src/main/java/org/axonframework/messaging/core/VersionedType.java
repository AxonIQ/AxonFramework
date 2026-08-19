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
 *
 * @author Ishaan Bhela
 * @since 5.4.0
 * @see QualifiedName
 */
public interface VersionedType {

    /**
     * The separator between a {@link #qualifiedName()} and {@link #version()} in a String representation.
     * This character cannot be used inside a version string or a qualified name string.
     */
    String VERSION_DELIMITER = "#";

    /**
     * Creates a {@code VersionedType} from the given {@code qualifiedName} and {@code version}.
     *
     * @param qualifiedName The {@code QualifiedName} of this versioned type.
     * @param version       The version of this versioned type. Must not be null or blank, and never contains the {@value #VERSION_DELIMITER}.
     * @return a new {@code VersionedType} instance
     */
    static VersionedType of(QualifiedName qualifiedName, String version) {
        return new SimpleVersionedType(qualifiedName, version);
    }

    /**
     * Creates a {@code VersionedType} from the given {@code name} and {@code version}.
     *
     * @param name    The string to create a {@link QualifiedName} from.
     * @param version The version of this versioned type. Must not be null, blank, or contain the {@value #VERSION_DELIMITER}.
     * @return a new {@code VersionedType} instance
     */
    static VersionedType of(String name, String version) {
        return new SimpleVersionedType(name, version);
    }

    /**
     * Creates a {@code VersionedType} from the given {@code name} with the default version.
     *
     * @param name The string to create a {@link QualifiedName} from.
     * @return a new {@code VersionedType} instance
     */
    static VersionedType of(String name) {
        return new SimpleVersionedType(name);
    }

    /**
     * Creates a {@code VersionedType} from the given {@code qualifiedName} with the default version.
     *
     * @param qualifiedName The {@code QualifiedName} of this versioned type.
     * @return a new {@code VersionedType} instance
     */
    static VersionedType of(QualifiedName qualifiedName) {
        return new SimpleVersionedType(qualifiedName);
    }

    /**
     * Validates that the given {@code version} is not blank and does not contain the {@link #VERSION_DELIMITER}.
     *
     * @param version The version string to validate.
     * @throws IllegalArgumentException if the version is blank or contains the delimiter.
     */
    static void validateVersion(String version) {
        if (version.isBlank()) {
            throw new IllegalArgumentException("The version cannot be blank.");
        }
        if (version.contains(VERSION_DELIMITER)) {
            throw new IllegalArgumentException(
                    "The version [" + version + "] is unsupported because it contains \""
                            + VERSION_DELIMITER + "\", which is reserved as the separator in VersionedType's "
                            + "String representation."
            );
        }
    }
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
