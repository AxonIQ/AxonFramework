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

import org.axonframework.common.annotation.Internal;
import org.axonframework.common.infra.ComponentDescriptor;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.Objects;

/**
 * A {@link GeneralConverter} implementation delegating conversion operations to a {@link Converter}.
 * <p>
 * Useful to adapt any {@link Converter} to the {@link GeneralConverter} contract, ensuring callers work against the
 * more specific {@link GeneralConverter} interface without restricting the types that can be converted.
 *
 * @author Jakob Hatzl
 * @since 5.1.0
 */
public class DelegatingGeneralConverter implements GeneralConverter {

    private final Converter delegate;

    /**
     * Constructs a {@code DelegatingGeneralConverter}, delegating operations to the given {@code delegate}.
     *
     * @param delegate The converter to delegate all conversion operations to.
     */
    public DelegatingGeneralConverter(Converter delegate) {
        this.delegate = Objects.requireNonNull(delegate, "The Converter must not be null.");
    }

    @Nullable
    @Override
    public <T> T convert(@Nullable Object input, Type targetType) {
        return delegate.convert(input, targetType);
    }

    @Override
    public void describeTo(ComponentDescriptor descriptor) {
        descriptor.describeWrapperOf(delegate);
    }

    /**
     * Returns the delegate {@link Converter} this {@code DelegatingGeneralConverter} delegates to.
     * <p>
     * Useful to construct other instances with the exact same {@link Converter}.
     *
     * @return The {@link Converter} this {@code DelegatingGeneralConverter} delegates to.
     */
    @Internal
    public Converter delegate() {
        return delegate;
    }
}
