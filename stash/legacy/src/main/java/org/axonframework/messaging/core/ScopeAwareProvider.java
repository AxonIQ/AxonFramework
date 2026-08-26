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

package org.axonframework.messaging.core;

import java.util.stream.Stream;

/**
 * Contract towards a mechanism to provide a {@link Stream} of components which are {@link ScopeAware}.
 *
 * @author Steven van Beelen
 * @since 3.3
 */
public interface ScopeAwareProvider {

    /**
     * Retrieve a {@link Stream} of {@link ScopeAware} components, by performing a check whether that component is able
     * to handle a {@link Scope} described by a {@link ScopeDescriptor}.
     *
     * @param scopeDescriptor a {@link ScopeDescriptor} describing the {@link Scope} a component {@link ScopeAware}
     *                        should be able to handle
     * @return a {@link Stream} of {@link ScopeAware} components
     */
    Stream<ScopeAware> provideScopeAwareStream(ScopeDescriptor scopeDescriptor);
}
