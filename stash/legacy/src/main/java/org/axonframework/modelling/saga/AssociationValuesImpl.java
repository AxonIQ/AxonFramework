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

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Default implementation of the AssociationValues interface. Note that this implementation is meant to be used by
 * a single thread at a time.
 *
 * @author Allard Buijze
 * @since 0.7
 */
public class AssociationValuesImpl implements AssociationValues {

    private final Set<AssociationValue> values = new HashSet<>();
    private final Set<AssociationValue> addedValues = new HashSet<>();
    private final Set<AssociationValue> removedValues = new HashSet<>();

    /**
     * Initializes a new AssociationValues object without initial associations.
     */
    public AssociationValuesImpl() {
    }

    /**
     * Initializes a new AssociationValues object with given initial associations.
     *
     * @param initialValues initial set of association values
     */
    public AssociationValuesImpl(Set<AssociationValue> initialValues) {
        this.values.addAll(initialValues);
    }

    @Override
    public int size() {
        return values.size();
    }

    @Override
    public boolean contains(AssociationValue associationValue) {
        return values.contains(associationValue);
    }

    @Override
    public Iterator<AssociationValue> iterator() {
        return Collections.unmodifiableSet(values).iterator();
    }

    @Override
    public boolean add(AssociationValue associationValue) {
        final boolean added = values.add(associationValue);
        if (added) {
                if (!removedValues.remove(associationValue)) {
                    addedValues.add(associationValue);
                }
        }
        return added;
    }

    @Override
    public boolean remove(AssociationValue associationValue) {
        final boolean removed = values.remove(associationValue);
        if (removed) {
                if (!addedValues.remove(associationValue)) {
                    removedValues.add(associationValue);
            }
        }
        return removed;
    }

    @Override
    public Set<AssociationValue> asSet() {
        return Collections.unmodifiableSet(values);
    }

    @Override
    public Set<AssociationValue> removedAssociations() {
        if (removedValues.isEmpty()) {
            return Collections.emptySet();
        }
        return removedValues;
    }

    @Override
    public Set<AssociationValue> addedAssociations() {
        if (addedValues.isEmpty()) {
            return Collections.emptySet();
        }
        return addedValues;
    }

    @Override
    public void commit() {
        addedValues.clear();
        removedValues.clear();
    }
}
