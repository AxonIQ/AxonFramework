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

package org.axonframework.common;

import org.junit.jupiter.api.*;

import static org.axonframework.common.StringUtils.lowerCaseFirstCharacterOf;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the {@link StringUtils}.
 *
 * @author Steven van Beelen
 */
class StringUtilsTest {

    @Test
    void nonEmptyOrNullReturnsFalseForEmptyString() {
        assertFalse(StringUtils.nonEmptyOrNull(""));
    }

    @Test
    void nonEmptyOrNullReturnsFalseForNullString() {
        assertFalse(StringUtils.nonEmptyOrNull(null));
    }

    @Test
    void nonEmptyOrNullReturnsTrueForNonEmptyString() {
        assertTrue(StringUtils.nonEmptyOrNull("some-string"));
    }

    @Test
    void emptyOrNullReturnsTrueForEmptyStrings() {
        assertTrue(StringUtils.emptyOrNull(""));
    }

    @Test
    void emptyOrNullReturnsTrueForNullString() {
        assertTrue(StringUtils.emptyOrNull(null));
    }

    @Test
    void emptyOrNullReturnsFalseForNonEmptyString() {
        assertFalse(StringUtils.emptyOrNull("some-string"));
    }

    @Test
    void lowerCaseFirstCharacterOfAdjustsFirstCharacterToLowerCase() {
        String fullUppercase = "FOO";
        String lowerCasedOutputOfFullUppercase = "fOO";
        assertEquals(lowerCasedOutputOfFullUppercase, lowerCaseFirstCharacterOf(fullUppercase));
        assertEquals(lowerCasedOutputOfFullUppercase, lowerCaseFirstCharacterOf(lowerCasedOutputOfFullUppercase));

        String partialUppercase = "FOo";
        String partialLowercase = "fOo";
        assertEquals(partialLowercase, lowerCaseFirstCharacterOf(partialUppercase));
        assertEquals(partialLowercase, lowerCaseFirstCharacterOf(partialLowercase));
    }

    @Test
    void lowerCaseFirstCharacterOfReturnsNullForNull() {
        assertNull(lowerCaseFirstCharacterOf(null));
    }

    @Test
    void lowerCaseFirstCharacterOfReturnsEmptyForEmpty() {
        assertEquals("", lowerCaseFirstCharacterOf(""));
    }

    @Test
    void capitalizeReturnsStringWithFirstCharacterUppercase() {
        String allLowercase = "foo";
        String capitalizedOutputOfAllLowercase = "Foo";
        assertEquals(capitalizedOutputOfAllLowercase, StringUtils.capitalize(allLowercase));
        assertEquals(capitalizedOutputOfAllLowercase, StringUtils.capitalize(capitalizedOutputOfAllLowercase));

        String partialUppercase = "fOo";
        String partialUppercaseOutput = "FOo";
        assertEquals(partialUppercaseOutput, StringUtils.capitalize(partialUppercase));
        assertEquals(partialUppercaseOutput, StringUtils.capitalize(partialUppercaseOutput));
    }
}
