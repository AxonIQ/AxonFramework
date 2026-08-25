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

import org.axonframework.common.Assert;

import java.util.Objects;

import static java.lang.String.format;

/**
 * SerializedType implementation that takes its properties as constructor parameters.
 *
 * @author Allard Buijze
 * @since 2.0
 * TODO #3602 remove
 * @deprecated By shifting from the {@link Serializer} to the {@link Converter}, this exception becomes obsolete.
 */
@Deprecated(forRemoval = true, since = "5.0.0")
public class SimpleSerializedType implements SerializedType {

    private static final SerializedType EMPTY_TYPE = new SimpleSerializedType("empty", null);
    private final String type;
    private final String revisionId;

    /**
     * Returns the type that represents an empty message, of undefined type. The type of such message is "empty" and
     * always has a {@code null} revision.
     * @return the type representing an empty message
     */
    public static SerializedType emptyType() {
        return EMPTY_TYPE;
    }

    /**
     * Initialize with given {@code objectType} and {@code revisionNumber}
     *
     * @param objectType     The description of the serialized object's type
     * @param revisionNumber The revision of the serialized object's type
     */
    public SimpleSerializedType(String objectType, String revisionNumber) {
        Assert.notNull(objectType, () -> "objectType cannot be null");
        this.type = objectType;
        this.revisionId = revisionNumber;
    }

    @Override
    public String getName() {
        return type;
    }

    @Override
    public String getRevision() {
        return revisionId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SimpleSerializedType that = (SimpleSerializedType) o;
        return Objects.equals(type, that.type) && Objects.equals(revisionId, that.revisionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, revisionId);
    }

    @Override
    public String toString() {
        return format("SimpleSerializedType[%s] (revision %s)", type, revisionId);
    }
}
