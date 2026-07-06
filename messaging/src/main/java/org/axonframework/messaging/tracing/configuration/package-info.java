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

/**
 * Configuration support for the tracing modules: the
 * {@link org.axonframework.messaging.tracing.configuration.SpanAttributesProviderRegistry} collecting the
 * {@link org.axonframework.messaging.tracing.SpanAttributesProvider SpanAttributesProviders} a
 * {@link org.axonframework.messaging.tracing.SpanFactory} is constructed with, and the shared enhancer/decorator ordering
 * constants.
 */
@NullMarked
package org.axonframework.messaging.tracing.configuration;

import org.jspecify.annotations.NullMarked;
