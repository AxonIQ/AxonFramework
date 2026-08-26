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

package org.axonframework.modelling.saga;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Allard Buijze
 */
class AssociationValuesImplTest {

    private AssociationValuesImpl testSubject;
    private AssociationValue associationValue;

    @BeforeEach
    void setUp() {
        testSubject = new AssociationValuesImpl();
        associationValue = new AssociationValue("key", "value");
    }

    @Test
    void addAssociationValue() {
        testSubject.add(associationValue);

        assertEquals(1, testSubject.addedAssociations().size());
        assertTrue(testSubject.removedAssociations().isEmpty());
    }

    @Test
    void addAssociationValue_AddedTwice() {
        testSubject.add(associationValue);
        testSubject.commit();
        testSubject.add(associationValue);
        assertTrue(testSubject.addedAssociations().isEmpty());
        assertTrue(testSubject.removedAssociations().isEmpty());
    }

    @Test
    void removeAssociationValue() {
        assertTrue(testSubject.add(associationValue));
        testSubject.commit();
        assertTrue(testSubject.remove(associationValue));
        assertTrue(testSubject.addedAssociations().isEmpty());
        assertEquals(1, testSubject.removedAssociations().size());
    }

    @Test
    void removeAssociationValue_NotInContainer() {
        testSubject.remove(associationValue);
        assertTrue(testSubject.addedAssociations().isEmpty());
        assertTrue(testSubject.removedAssociations().isEmpty());
    }

    @Test
    void addAndRemoveEntry() {
        testSubject.add(associationValue);
        testSubject.remove(associationValue);

        assertTrue(testSubject.addedAssociations().isEmpty());
        assertTrue(testSubject.removedAssociations().isEmpty());
    }

    @Test
    void contains() {
        assertFalse(testSubject.contains(associationValue));
        testSubject.add(associationValue);
        assertTrue(testSubject.contains(associationValue));
        assertTrue(testSubject.contains(new AssociationValue("key", "value")));
        testSubject.remove(associationValue);
        assertFalse(testSubject.contains(associationValue));
    }

    @Test
    void asSet() {
        testSubject.add(associationValue);
        int t = 0;
        for (AssociationValue actual : testSubject.asSet()) {
            assertSame(associationValue, actual);
            t++;
        }
        assertEquals(1, t);
    }

    @Test
    void iterator() {
        testSubject.add(associationValue);
        Iterator<AssociationValue> iterator = testSubject.iterator();
        assertSame(associationValue, iterator.next());
        assertFalse(iterator.hasNext());
    }
}
