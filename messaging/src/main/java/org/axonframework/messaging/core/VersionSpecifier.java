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
 * Specifies which message versions a {@link MessageHandler} supports.
 * <p>
 * A {@code VersionSpecifier} is used during message dispatch to determine whether a handler is eligible to handle a
 * message based on the message's {@link VersionedType#version() version}. It is also used during registration to detect
 * overlapping handlers for the same {@link QualifiedName}.
 *
 * @author Ishaan
 * @since 5.4.0
 * @see MessageHandler#supportedVersions()
 */
public interface VersionSpecifier {

    /**
     * Returns whether this specifier matches the given {@code version}.
     *
     * @param version The version string to test against this specifier.
     * @return {@code true} if the given version falls within the range of this specifier, {@code false} otherwise.
     */
    boolean matches(String version);

    /**
     * Returns whether this specifier overlaps with the given {@code other} specifier. Two specifiers overlap if there
     * exists at least one version string that both would {@link #matches(String) match}.
     * <p>
     * This is used during handler registration to detect conflicting subscriptions for message types that allow only a
     * single handler per {@link QualifiedName} (e.g., commands).
     *
     * @param other The other specifier to check for overlap.
     * @return {@code true} if the two specifiers overlap, {@code false} otherwise.
     */
    boolean overlaps(VersionSpecifier other);

    /**
     * Returns a {@code VersionSpecifier} that matches all versions.
     * <p>
     * This is the default specifier used when a handler does not restrict the versions it supports.
     *
     * @return A {@code VersionSpecifier} matching any version.
     */
    static VersionSpecifier any() {
        return AnyVersionSpecifier.INSTANCE;
    }

    /**
     * Parses a string representation of a version specifier into a {@link VersionSpecifier} instance.
     * <p>
     * The following formats are supported:
     * <ul>
     * <li>Empty string or {@code null} returns {@link #any()}</li>
     * <li>A single version (e.g., {@code "1.0"}) returns an exact match specifier</li>
     * <li>A range separated by a hyphen (e.g., {@code "1.0-2.0"}) returns a range specifier</li>
     * </ul>
     *
     * @param spec The string representation of the version specifier.
     * @return A {@code VersionSpecifier} corresponding to the parsed string.
     */
    static VersionSpecifier parse(String spec) {
        if (spec == null || spec.trim().isEmpty()) {
            return any();
        }
        String[] parts = spec.split("-", 2);
        if (parts.length == 2) {
            return range(parts[0].trim(), parts[1].trim());
        }
        return exact(spec.trim());
    }

    /**
     * Returns a {@code VersionSpecifier} that matches versions within the inclusive range {@code [from, to]}.
     * <p>
     * Versions are compared segment by segment (split on {@code '.'}), with each segment parsed as an integer where
     * possible. When {@code from} and {@code to} are equal, this effectively acts as an exact-version specifier.
     *
     * @param from The lower bound of the version range (inclusive).
     * @param to   The upper bound of the version range (inclusive).
     * @return A {@code VersionSpecifier} matching versions in the given range.
     * @throws IllegalArgumentException if {@code from} is greater than {@code to}.
     */
    static VersionSpecifier range(String from, String to) {
        return new VersionRange(from, to);
    }

    /**
     * Returns a {@code VersionSpecifier} that matches exactly the given {@code version}.
     * <p>
     * This is equivalent to calling {@code range(version, version)}.
     *
     * @param version The exact version to match.
     * @return A {@code VersionSpecifier} matching only the given version.
     */
    static VersionSpecifier exact(String version) {
        return new VersionRange(version, version);
    }
}
