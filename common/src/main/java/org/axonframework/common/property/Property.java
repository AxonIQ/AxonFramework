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

package org.axonframework.common.property;

import org.jspecify.annotations.Nullable;

/**
 * Interface describing a mechanism that can read a predefined property from a given instance.
 *
 * @param <T> The type of object defining this property
 * @author Maxim Fedorov
 * @author Allard Buijze
 * @since 2.0
 */
@FunctionalInterface
public interface Property<T> {

    /**
     * Returns the value of the property on given {@code target}.
     *
     * @param target The instance to get the property value from
     * @param <V>    The type of value expected
     * @return the property value on {@code target}
     */
    <V> @Nullable V getValue(T target);
}
