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
package migration.paths.tracing;

import org.axonframework.messaging.core.Context.ResourceKey;
import org.axonframework.messaging.core.Message;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.tracing.SpanAttributesProvider;
import org.jspecify.annotations.Nullable;

import java.util.Map;

// tag::provider-signature[]
public class TenantSpanAttributesProvider implements SpanAttributesProvider {

    public static final ResourceKey<String> TENANT_ID = ResourceKey.withLabel("tenantIdentifier");

    @Override
    public Map<String, String> provideForMessage(Message message, @Nullable ProcessingContext context) {
        if (context == null) {
            return Map.of();
        }
        String tenantIdentifier = context.getResource(TENANT_ID);
        return tenantIdentifier == null
                ? Map.of()
                : Map.of("tenant.id", tenantIdentifier);
    }
}
// end::provider-signature[]
