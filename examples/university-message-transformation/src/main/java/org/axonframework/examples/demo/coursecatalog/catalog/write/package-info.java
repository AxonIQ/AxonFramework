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
 * Command-handling write slices for the catalog (one subpackage per command), together with the
 * cross-slice {@link org.axonframework.examples.demo.coursecatalog.catalog.write.RequestRegionCommandInterceptor}
 * that lifts the request region off command metadata onto the active {@code ProcessingContext}.
 */
@NullMarked
package org.axonframework.examples.demo.coursecatalog.catalog.write;

import org.jspecify.annotations.NullMarked;
