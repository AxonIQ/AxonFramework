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

package org.axonframework.deadline.jobrunr;

import org.axonframework.deadline.TestScopeDescriptor;
import org.axonframework.messaging.core.ScopeDescriptor;
import org.axonframework.conversion.Serializer;
import org.axonframework.conversion.json.JacksonSerializer;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

class LabelUtilsTest {

    @Test
    void forShortStringLabelShouldBeSameAsInput() {
        String expected = "short";
        assertEquals(expected, LabelUtils.getLabel(expected));
    }

    @Test
    void forLongStringLabelShouldBeConsistentButShorter() {
        String longString = "shortsadfsdfsdfsdfsdfsdfsafdsfdgdgdgdgfgfdgdfgdfsfdsfsdfds"
                + "fdsfsdfdssdjklfisdfikusdufidsufsdfdsufsifsfsfsdfgdfdfgfgdfgdfgdfs";
        String expected = LabelUtils.getLabel(longString);
        assertEquals(expected, LabelUtils.getLabel(longString));
        assertTrue(longString.length() > expected.length());
    }

    @Test
    void combinedScopeShouldBeConsistentAndShorterThan45Characters() {
        String deadLineName = "deadlineName";
        Serializer serializer = JacksonSerializer.defaultSerializer();
        ScopeDescriptor descriptor = new TestScopeDescriptor("aggregateType", "identifier");
        String expected = LabelUtils.getCombinedLabel(serializer, deadLineName, descriptor);
        assertEquals(expected, LabelUtils.getCombinedLabel(serializer, deadLineName, descriptor));
        assertTrue(expected.length() < 45);
    }
}
