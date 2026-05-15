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
 * A general-purpose {@link Converter} that places no restrictions on the types it can convert.
 * <p>
 * Serves as a dedicated contract for components that perform unrestricted object conversion, in contrast to
 * type-specific converters like {@code MessageConverter} or {@code EventConverter} which restrict conversion to
 * particular message types. Use this interface when conversion is not tied to a specific message abstraction.
 * <p>
 * Implementations of this interface typically delegate to a {@link Converter}, such as
 * {@link DelegatingGeneralConverter}.
 *
 * @author Jakob Hatzl
 * @since 5.1.0
 */
public interface GeneralConverter extends Converter {

}
