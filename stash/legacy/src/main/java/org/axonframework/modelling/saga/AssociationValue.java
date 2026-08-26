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

import org.axonframework.common.Assert;

import java.util.Objects;

/**
 * A combination of key and value by which a Saga can be found. Sagas are triggered by events that have a property that
 * the saga is associated with. A single Association Value can lead to multiple Sagas, and a single Saga can be
 * associated with multiple Association Values.
 * <p/>
 * Two association values are considered equal when both their key and value are equal. For example, a Saga managing
 * Orders could have a AssociationValue with key &quot;orderId&quot; and the order identifier as value.
 *
 * @author Allard Buijze
 * @since 0.7
 */
public class AssociationValue {

    private final String propertyKey;
    private final String propertyValue;

    /**
     * Creates a Association Value instance with the given {@code key} and {@code value}.
     *
     * @param key   The key of the Association Value. Usually indicates where the value comes from.
     * @param value The value corresponding to the key of the association. It is highly recommended to only use
     *              serializable values.
     */
    public AssociationValue(String key, String value) {
        Assert.notNull(key, () -> "Cannot associate a Saga with a null key");
        this.propertyKey = key;
        this.propertyValue = value;
    }

    /**
     * Returns the key of this association value. The key usually indicates where the property's value comes from.
     *
     * @return the key of this association value
     */
    public String getKey() {
        return propertyKey;
    }

    /**
     * Returns the value of this association.
     *
     * @return the value of this association. Never {@code null}.
     */
    public String getValue() {
        return propertyValue;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AssociationValue that = (AssociationValue) o;
        return Objects.equals(propertyKey, that.propertyKey) && Objects.equals(propertyValue, that.propertyValue);
    }

    @Override
    public int hashCode() {
        return Objects.hash(propertyKey, propertyValue);
    }
}
