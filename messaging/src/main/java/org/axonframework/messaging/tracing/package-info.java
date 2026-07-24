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
 * The tracing API of Axon Framework: {@code SpanFactory}, {@code Span}, {@code SpanScope}, and
 * {@code SpanAttributesProvider}, plus the logging and no-op factory implementations.
 * <p>
 * The delegating tracing decorators live with the components they decorate (for example
 * {@code org.axonframework.messaging.commandhandling.tracing}); the {@code configuration} sub-package holds the
 * enhancers and settings wiring them, the {@code attributes} sub-package the built-in attribute providers and their
 * registry, and the {@code annotation} sub-package the per-method handler-span enhancer.
 */
@NullMarked
package org.axonframework.messaging.tracing;

import org.jspecify.annotations.NullMarked;
