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
 * Describes the type of a serialized object. This information is used to decide how to deserialize an object.
 *
 * @author Allard Buijze
 * @since 2.0
 * TODO #3602 remove
 * @deprecated By shifting from the {@link Serializer} to the {@link Converter}, this exception becomes obsolete.
 */
@Deprecated(forRemoval = true, since = "5.0.0")
public interface SerializedType {

    /**
     * Returns the type that represents an empty message, of undefined type. The type of such message is "empty" and
     * always has a {@code null} revision.
     *
     * @return the type representing an empty message
     */
    static SerializedType emptyType() {
        return SimpleSerializedType.emptyType();
    }

    /**
     * Check whether the {@code serializedType} equals {@link SerializedType#emptyType#getName()} and returns a
     * corresponding {@code true} or {@code false} whether this is the case or not. The given {@code serializedType}
     * should not be {@code null} as otherwise a {@link NullPointerException} will be thrown.
     *
     * @param serializedType the type to check whether it equals {@link SerializedType#emptyType()}
     * @return {@code true} if the {@code serializedType} does equals the {@link SerializedType#emptyType()#getName()}
     * and {@code false} if it does
     *
     * @throws NullPointerException if the given {@link SerializedType} is {@code null}
     */
    static boolean isEmptyType(SerializedType serializedType) {
        return emptyType().getName().equals(serializedType.getName());
    }

    /**
     * Returns the name of the serialized type. This may be the class name of the serialized object, or an alias for
     * that name.
     *
     * @return the name of the serialized type
     */
    String getName();

    /**
     * Returns the revision identifier of the serialized object. This revision identifier is used by upcasters to
     * decide how to transform serialized objects during deserialization.
     *
     * @return the revision identifier of the serialized object
     */
    String getRevision();
}
