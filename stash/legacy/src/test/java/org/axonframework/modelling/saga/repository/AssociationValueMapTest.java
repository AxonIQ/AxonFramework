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

package org.axonframework.modelling.saga.repository;

import org.axonframework.modelling.saga.AssociationValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 *
 */
class AssociationValueMapTest {

    private AssociationValueMap testSubject;

    @BeforeEach
    void setUp() {
        testSubject = new AssociationValueMap();
    }

    @Test
    void storeVarietyOfItems() {
        assertTrue(testSubject.isEmpty());

        Object anObject = new Object();
        testSubject.add(av("1"), "T", "1");
        testSubject.add(av("1"), "T", "1");
        assertEquals(1, testSubject.size(), "Wrong count after adding an object twice");
        testSubject.add(av("2"), "T", "1");
        assertEquals(2, testSubject.size(), "Wrong count after adding two objects");
        testSubject.add(av("a"), "T", "1");
        testSubject.add(av("a"), "T", "1");
        assertEquals(3, testSubject.size(), "Wrong count after adding two identical Strings");
        testSubject.add(av("b"), "T", "1");
        assertEquals(4, testSubject.size(), "Wrong count after adding two identical Strings");

        testSubject.add(av("a"), "T", "2");
        testSubject.add(av("a"), "Y", "2");
        assertEquals(6, testSubject.size(), "Wrong count after adding two identical Strings for different saga");
        assertEquals(2, testSubject.findSagas("T", av("a")).size());
    }

    @Test
    void removeItems() {
        storeVarietyOfItems();
        assertEquals(6, testSubject.size(), "Wrong initial item count");
        testSubject.remove(av("a"), "T", "1");
        assertEquals(5, testSubject.size(), "Wrong item count");
        testSubject.remove(av("a"), "T", "2");
        assertEquals(4, testSubject.size(), "Wrong item count");

        testSubject.clear();
        assertTrue(testSubject.isEmpty());
        assertEquals(0, testSubject.size(), "Wrong item count");
    }

    private AssociationValue av(String value) {
        return new AssociationValue("key", value);
    }

    @Test
    void findAssociations() {
        List<AssociationValue> usedAssociations = new ArrayList<>(1000);
        for (int t = 0; t < 1000; t++) {
            String key = UUID.randomUUID().toString();
            for (int i = 0; i < 10; i++) {
                AssociationValue associationValue = new AssociationValue(key, UUID.randomUUID().toString());
                if (usedAssociations.size() < 1000) {
                    usedAssociations.add(associationValue);
                }
                testSubject.add(associationValue, "type", key);
            }
        }

        assertEquals(10000, testSubject.size());
        for (AssociationValue item : usedAssociations) {
            Set<String> actualResult = testSubject.findSagas("type", item);
            assertEquals(1, actualResult.size(),"Failure on item: " + usedAssociations.indexOf(item));
            assertEquals(item.getKey(), actualResult.iterator().next());
        }
    }
}
