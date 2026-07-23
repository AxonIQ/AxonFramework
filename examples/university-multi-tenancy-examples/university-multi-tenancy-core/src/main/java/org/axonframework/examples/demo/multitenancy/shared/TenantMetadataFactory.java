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

package org.axonframework.examples.demo.multitenancy.shared;

import io.axoniq.framework.messaging.multitenancy.api.MetadataBasedTenantResolver;
import io.axoniq.framework.messaging.multitenancy.api.TenantDescriptor;
import org.axonframework.messaging.core.Metadata;

/**
 * Builds the message metadata that carries a tenant, so both the command and query drivers route their
 * messages to the same tenant the framework resolves from
 * {@link MetadataBasedTenantResolver#DEFAULT_TENANT_METADATA_KEY}.
 */
final class TenantMetadataFactory {

    private TenantMetadataFactory() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * Returns metadata carrying the given {@code tenant} under the default tenant metadata key.
     *
     * @param tenant the tenant the message belongs to
     * @return metadata carrying the tenant
     */
    static Metadata forTenant(TenantDescriptor tenant) {
        return Metadata.with(MetadataBasedTenantResolver.DEFAULT_TENANT_METADATA_KEY, tenant.tenantId());
    }
}
