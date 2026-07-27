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

package org.axonframework.hunt.model;

import java.util.Objects;

/**
 * A key-value association carried by a modelled event, mirroring the framework's event tag.
 *
 * @param key   the association's name, for example {@code student}
 * @param value the associated identifier
 * @author Stefan Dragisic
 * @since 5.3.0
 */
public record ModelTag(String key, String value) {

    /**
     * Compact constructor rejecting missing parts.
     */
    public ModelTag {
        Objects.requireNonNull(key, "The key cannot be null.");
        Objects.requireNonNull(value, "The value cannot be null.");
    }

    /**
     * Creates a tag.
     *
     * @param key   the association's name
     * @param value the associated identifier
     * @return the tag
     */
    public static ModelTag of(String key, String value) {
        return new ModelTag(key, value);
    }
}
