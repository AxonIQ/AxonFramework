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
 * A {@link VersionSpecifier} that matches versions within an inclusive range {@code [from, to]}.
 * <p>
 * This class supports semantic versioning (e.g. {@code 1.2.0}). When the {@code from} and {@code to}
 * versions are equal, this effectively acts as an exact version match.
 *
 * @param from The lower bound of the version range (inclusive).
 * @param to   The upper bound of the version range (inclusive).
 * @author Ishaan Bhela
 * @since 5.4.0
 */
record VersionRange(String from, String to) implements VersionSpecifier {

    VersionRange {
        requireNonNull(from, "The 'from' version may not be null.");
        requireNonNull(to, "The 'to' version may not be null.");
        if (compareVersions(from, to) > 0) {
            throw new IllegalArgumentException(
                    "The 'from' version [%s] must not be greater than the 'to' version [%s].".formatted(from, to)
            );
        }
    }

    @Override
    public boolean matches(String version) {
        if (version == null) {
            return false;
        }
        return compareVersions(from, version) <= 0
                && compareVersions(version, to) <= 0;
    }

    @Override
    public boolean overlaps(VersionSpecifier other) {
        if (other instanceof AnyVersionSpecifier) {
            return true;
        }
        if (other instanceof VersionRange otherRange) {
            return compareVersions(from, otherRange.to) <= 0
                    && compareVersions(otherRange.from, to) <= 0;
        }
        // Conservative default: assume overlap for unknown implementations.
        return true;
    }

    @Override
    public String toString() {
        return from.equals(to)
                ? "VersionSpecifier.exact(\"%s\")".formatted(from)
                : "VersionSpecifier.range(\"%s\", \"%s\")".formatted(from, to);
    }

    /**
     * Compares two version strings segment by segment.
     * <p>
     * Each version is split on {@code '.'} and segments are compared as integers when possible, falling back to
     * lexicographic comparison for non-numeric segments. Missing trailing segments are treated as {@code 0}.
     *
     * @param v1 The first version string.
     * @param v2 The second version string.
     * @return A negative integer, zero, or a positive integer if {@code v1} is less than, equal to, or greater than
     * {@code v2}.
     */
    static int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int length = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < length; i++) {
            String segment1 = i < parts1.length ? parts1[i] : "0";
            String segment2 = i < parts2.length ? parts2[i] : "0";
            int comparison = compareSegments(segment1, segment2);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private static int compareSegments(String s1, String s2) {
        try {
            return Integer.compare(Integer.parseInt(s1), Integer.parseInt(s2));
        } catch (NumberFormatException e) {
            return s1.compareTo(s2);
        }
    }
}
