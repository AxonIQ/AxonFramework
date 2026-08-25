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

package org.axonframework.conversion;

/**
 * Interface describing the structure of a serialized object.
 *
 * @param <T> The data type representing the serialized object
 * @author Allard Buijze
 * @since 2.0
 * TODO #3602 remove
 * @deprecated By shifting from the {@link Serializer} to the {@link Converter}, this exception becomes obsolete.
 */
@Deprecated(forRemoval = true, since = "5.0.0")
public interface SerializedObject<T> {

    /**
     * Returns the type of this representation's data.
     *
     * @return the type of this representation's data
     */
    Class<T> getContentType();

    /**
     * Returns the description of the type of object contained in the data.
     *
     * @return the description of the type of object contained in the data
     */
    SerializedType getType();

    /**
     * The actual data of the serialized object.
     *
     * @return the actual data of the serialized object
     */
    T getData();

}
