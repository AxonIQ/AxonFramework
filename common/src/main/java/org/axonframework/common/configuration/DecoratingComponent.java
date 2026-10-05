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

package org.axonframework.common.configuration;

import org.jspecify.annotations.Nullable;

/**
 * Implemented by a {@link ComponentDecorator} output class to expose the single delegate it wraps, so code holding a
 * decorated value can see through it back to whatever it was built from.
 * <p>
 * {@link #decoratedDelegate()} must return the immediate delegate -- never {@code this}, and never something that
 * requires further unwrapping this class isn't aware of -- so that {@link #unwrapFully(Object)} always terminates.
 *
 * @author John Hendrikx
 * @since 5.4.0
 */
public interface DecoratingComponent {

    /**
     * Returns the single delegate this decorator wraps.
     *
     * @return the wrapped delegate; never {@code null}
     */
    Object decoratedDelegate();

    /**
     * Follows the {@link #decoratedDelegate()} chain from {@code this} delegate down to its root, returning the
     * first value encountered that is not itself a {@link DecoratingComponent}.
     *
     * @return the root delegate at the bottom of this instance's decoration chain; never {@code null}, provided
     * every {@link #decoratedDelegate()} in the chain honors its contract
     */
    default Object unwrapFully() {
        return unwrapFully(this);
    }

    /**
     * Follows the {@link #decoratedDelegate()} chain from the given {@code value} down to its root, returning the
     * first value encountered that is not itself a {@link DecoratingComponent}.
     * <p>
     * Returns {@code value} itself, unchanged, when it is not a {@code DecoratingComponent} to begin with.
     *
     * @param value the value to unwrap; may be {@code null}, in which case {@code null} is returned
     * @return the root delegate at the bottom of {@code value}'s decoration chain, or {@code value} itself if it is
     * not decorated; {@code null} if {@code value} is {@code null}
     */
    static @Nullable Object unwrapFully(@Nullable Object value) {
        Object current = value;
        while (current instanceof DecoratingComponent decorating) {
            current = decorating.decoratedDelegate();
        }
        return current;
    }
}
