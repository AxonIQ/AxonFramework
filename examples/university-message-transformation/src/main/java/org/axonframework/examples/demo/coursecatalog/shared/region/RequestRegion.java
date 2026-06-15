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

package org.axonframework.examples.demo.coursecatalog.shared.region;

import org.axonframework.messaging.core.Context;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.jspecify.annotations.Nullable;

/**
 * The region an operation runs in, carried as a request-scoped resource on the active
 * {@link ProcessingContext}.
 * <p>
 * Most transformations in this catalog are pure structural rewrites and ignore the
 * {@code ProcessingContext} entirely. This one models the exception: the catalog gained
 * multi-region support only after events had been written, so historic events have no
 * region. {@link org.axonframework.examples.demo.coursecatalog.catalog.transformations.StudentRegisteredV2ToV3}
 * backfills it from the region the read runs in.
 * <p>
 * In a real application the region would be attached once, at the edge of a request — for
 * example, a command interceptor reading it from the caller's identity and calling
 * {@code context.putResource(}{@link #RESOURCE_KEY}{@code , region)}. Because the same
 * {@code ProcessingContext} threads through to the transformation chain when an entity is
 * sourced, the backfill is stable for the whole operation. When no region is attached
 * (such as a background tracking processor that reads with no caller), the backfill falls
 * back to {@link #UNKNOWN_REGION}.
 */
public final class RequestRegion {

    /** Resource key under which the request-scoped region is stored on the {@link ProcessingContext}. */
    public static final Context.ResourceKey<String> RESOURCE_KEY = Context.ResourceKey.withLabel("requestRegion");

    /**
     * Metadata key a caller uses to carry the region into a message, so an edge interceptor
     * (see {@link org.axonframework.examples.demo.coursecatalog.catalog.write.RequestRegionCommandInterceptor})
 * can lift it onto the {@link ProcessingContext}.
     */
    public static final String METADATA_KEY = "region";

    /** Region used when no request-scoped region is attached to the context. */
    public static final String UNKNOWN_REGION = "GLOBAL";

    private RequestRegion() {
    }

    /**
     * @param context the active processing context, or {@code null} when a read runs outside one
     * @return the region attached to {@code context}, or {@link #UNKNOWN_REGION} when none is present
     */
    public static String resolve(@Nullable ProcessingContext context) {
        if (context == null) {
            return UNKNOWN_REGION;
        }
        String region = context.getResource(RESOURCE_KEY);
        return region == null ? UNKNOWN_REGION : region;
    }
}
