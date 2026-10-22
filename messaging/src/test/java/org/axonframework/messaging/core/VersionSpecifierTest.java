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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VersionSpecifierTest {

    @Nested
    class AnyVersionSpecifierTest {

        @Test
        void matchesAnything() {
            VersionSpecifier specifier = VersionSpecifier.any();
            assertTrue(specifier.matches("1.0"));
            assertTrue(specifier.matches("2.5.1"));
            assertTrue(specifier.matches("anything"));
            assertTrue(specifier.matches(""));
        }

        @Test
        void overlapsWithAnything() {
            VersionSpecifier any = VersionSpecifier.any();
            assertTrue(any.overlaps(VersionSpecifier.any()));
            assertTrue(any.overlaps(VersionSpecifier.exact("1.0")));
            assertTrue(any.overlaps(VersionSpecifier.range("1.0", "2.0")));
        }
    }

    @Nested
    class ExactVersionSpecifierTest {

        @Test
        void matchesExactVersionOnly() {
            VersionSpecifier specifier = VersionSpecifier.exact("1.0");
            assertTrue(specifier.matches("1.0"));
            assertTrue(specifier.matches("1.0.0")); // Semantic equivalent
            assertFalse(specifier.matches("1.1"));
            assertFalse(specifier.matches("2.0"));
            assertFalse(specifier.matches("0.9"));
        }

        @Test
        void overlapsWithSameOrEncompassingRange() {
            VersionSpecifier exact1 = VersionSpecifier.exact("1.0");
            VersionSpecifier exact1b = VersionSpecifier.exact("1.0.0");
            VersionSpecifier exact2 = VersionSpecifier.exact("2.0");
            VersionSpecifier rangeIncludes = VersionSpecifier.range("0.5", "1.5");
            VersionSpecifier rangeExcludes = VersionSpecifier.range("1.5", "2.5");

            assertTrue(exact1.overlaps(exact1b));
            assertFalse(exact1.overlaps(exact2));
            assertTrue(exact1.overlaps(rangeIncludes));
            assertFalse(exact1.overlaps(rangeExcludes));
        }
    }

    @Nested
    class RangeVersionSpecifierTest {

        @Test
        void matchesWithinRange() {
            VersionSpecifier specifier = VersionSpecifier.range("1.0", "2.0");
            assertTrue(specifier.matches("1.0"));
            assertTrue(specifier.matches("1.5"));
            assertTrue(specifier.matches("2.0"));
            assertTrue(specifier.matches("2.0.0"));
            
            assertFalse(specifier.matches("0.9"));
            assertFalse(specifier.matches("2.1"));
        }

        @Test
        void overlapsWithIntersectingRanges() {
            VersionSpecifier range1 = VersionSpecifier.range("1.0", "3.0");
            
            assertTrue(range1.overlaps(VersionSpecifier.range("2.0", "4.0"))); // Overlap end
            assertTrue(range1.overlaps(VersionSpecifier.range("0.5", "1.5"))); // Overlap start
            assertTrue(range1.overlaps(VersionSpecifier.range("1.5", "2.5"))); // Fully inside
            assertTrue(range1.overlaps(VersionSpecifier.range("0.5", "4.0"))); // Fully encompasses

            assertFalse(range1.overlaps(VersionSpecifier.range("3.1", "4.0"))); // Completely after
            assertFalse(range1.overlaps(VersionSpecifier.range("0.1", "0.9"))); // Completely before
        }
        
        @Test
        void rejectsInvalidRange() {
            assertThrows(IllegalArgumentException.class, () -> VersionSpecifier.range("2.0", "1.0"));
        }
    }
    
    @Nested
    class VersionComparisonTest {
        
        @Test
        void comparesSemanticVersionsCorrectly() {
            assertTrue(VersionRange.compareVersions("1.0", "2.0") < 0);
            assertTrue(VersionRange.compareVersions("2.0", "1.0") > 0);
            assertTrue(VersionRange.compareVersions("1.0", "1.0") == 0);
            
            assertTrue(VersionRange.compareVersions("1.0.0", "1.0") == 0);
            assertTrue(VersionRange.compareVersions("1.0", "1.0.0") == 0);
            
            assertTrue(VersionRange.compareVersions("1.2", "1.10") < 0);
            assertTrue(VersionRange.compareVersions("1.10", "1.2") > 0);
        }
        
        @Test
        void fallsBackToLexicographicalForNonNumeric() {
            assertTrue(VersionRange.compareVersions("1.0-alpha", "1.0-beta") < 0);
            assertTrue(VersionRange.compareVersions("v1", "v2") < 0);
        }
    }
}
