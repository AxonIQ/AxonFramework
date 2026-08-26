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

import java.util.Set;

/**
 * Interface describing a container of {@link AssociationValue Association Values} for a single {@link Saga} instance.
 * This container tracks changes made to its contents between commits (see {@link #commit()}).
 *
 * @author Allard Buijze
 * @since 0.7
 */
public interface AssociationValues extends Iterable<AssociationValue> {

    /**
     * Returns the Set of association values that have been removed since the last {@link #commit()}.
     * <p/>
     * If an association was added and then removed (or vice versa), without any calls to {@link #commit()} in
     * between, it is not returned.
     *
     * @return the Set of association values removed since the last {@link #commit()}.
     */
    Set<AssociationValue> removedAssociations();

    /**
     * Returns the Set of association values that have been added since the last  {@link #commit()}.
     * <p/>
     * If an association was added and then removed (or vice versa), without any calls to {@link #commit()} in
     * between, it is not returned.
     *
     * @return the Set of association values added since the last {@link #commit()}.
     */
    Set<AssociationValue> addedAssociations();

    /**
     * Resets the tracked changes.
     */
    void commit();

    /**
     * Returns the number of AssociationValue instances available in this container
     *
     * @return the number of AssociationValue instances available
     */
    int size();

    /**
     * Indicates whether this instance contains the given {@code associationValue}.
     *
     * @param associationValue the association value to verify
     * @return {@code true} if the association value is available in this instance, otherwise {@code false}
     */
    boolean contains(AssociationValue associationValue);

    /**
     * Adds the given {@code associationValue}, if it has not been previously added.
     * <p/>
     * When added (method returns {@code true}), the given {@code associationValue} will be returned on the
     * next call to {@link #addedAssociations()}, unless it has been removed after the last call to {@link
     * #removedAssociations()}.
     *
     * @param associationValue The association value to add
     * @return {@code true} if the value was added, {@code false} if it was already contained in this
     *         instance
     */
    boolean add(AssociationValue associationValue);

    /**
     * Removes the given {@code associationValue}, if it is contained by this instance.
     * <p/>
     * When removed (method returns {@code true}), the given {@code associationValue} will be returned on the
     * next call to {@link #removedAssociations()}, unless it has been added after the last call to {@link
     * #addedAssociations()}.
     *
     * @param associationValue The association value to remove
     * @return {@code true} if the value was removed, {@code false} if it was not contained in this instance
     */
    boolean remove(AssociationValue associationValue);

    /**
     * Returns this instance as a Set of Association Values. The returned set is a read-only view on this container.
     * Any changes made to the container after this invocation <em>may</em> be reflected in the returned Set.
     *
     * @return a read only view on the contents of this container
     */
    Set<AssociationValue> asSet();
}