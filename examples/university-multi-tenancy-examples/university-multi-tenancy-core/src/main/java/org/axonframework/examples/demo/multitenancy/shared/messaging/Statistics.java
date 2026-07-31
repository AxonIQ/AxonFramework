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

package org.axonframework.examples.demo.multitenancy.shared.messaging;

import io.axoniq.framework.messaging.multitenancy.api.MetadataBasedTenantResolver;
import io.axoniq.framework.messaging.multitenancy.api.TenantDescriptor;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.GetTenantStatistics;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.ReadModelWrites;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.TenantStatistics;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.queryhandling.GenericQueryMessage;
import org.axonframework.messaging.queryhandling.QueryMessage;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.reactivestreams.Publisher;

import java.util.concurrent.TimeUnit;

/**
 * Reads a tenant's statistics through the query gateway, either once or as a subscription.
 * <p>
 * Every query carries its tenant in metadata under
 * {@link MetadataBasedTenantResolver#DEFAULT_TENANT_METADATA_KEY}, which is how the framework routes it to that
 * tenant's components. {@link GetTenantStatistics} itself carries no tenant field, so nothing here names one in
 * a payload.
 *
 * @author Laura Devriendt
 * @since 5.3.0
 */
public final class Statistics {

    private static final long TIMEOUT_SECONDS = 5;

    private Statistics() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * Reads the given {@code tenant}'s statistics and blocks for the response. The query handler is handed that
     * tenant's components, so the result holds only that tenant's data.
     *
     * @param queryGateway the gateway to send the query on
     * @param tenant       the tenant whose statistics to read
     * @return the tenant's isolated statistics
     */
    public static TenantStatistics read(QueryGateway queryGateway, TenantDescriptor tenant) {
        QueryMessage query = statisticsQuery().andMetadata(TenantMetadataFactory.forTenant(tenant));
        return queryGateway.query(query, TenantStatistics.class)
                           .orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                           .join();
    }

    /**
     * Sends a statistics query carrying no tenant metadata at all and blocks, expecting the framework to refuse
     * it.
     * <p>
     * There is no tenant to serve such a query for, so the framework rejects it at dispatch rather than letting
     * it reach a handler that would have nothing to resolve its components from. The tenant is the only thing
     * missing here, which is what separates this from a query naming a tenant the application does not know.
     *
     * @param queryGateway the gateway to send the query on
     * @throws RuntimeException always, carrying the framework's refusal to serve a query without a tenant
     */
    public static void readWithoutTenant(QueryGateway queryGateway) {
        queryGateway.query(statisticsQuery(), TenantStatistics.class)
                    .orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .join();
    }

    /**
     * Subscribes to the given {@code tenant}'s statistics. The returned publisher emits the tenant's current
     * statistics first, and every fresh update {@link ReadModelWrites} emits after. It completes once none of the
     * tenant's courses has a seat left, since there is no further enrollment to report.
     * <p>
     * The tenant travels only in the query's metadata, resolved once from this initial query. Emitting a later
     * update never names a tenant either, and still only this subscription's own tenant receives it: the
     * framework isolates emission by the tenant it resolves for the update, not by anything this subscription's
     * own query says.
     *
     * @param queryGateway the gateway to send the subscription query on
     * @param tenant       the tenant whose statistics to subscribe to
     * @return a publisher of the tenant's statistics, starting with its current value
     */
    public static Publisher<TenantStatistics> subscribeTo(QueryGateway queryGateway, TenantDescriptor tenant) {
        QueryMessage query = statisticsQuery().andMetadata(TenantMetadataFactory.forTenant(tenant));
        return queryGateway.subscriptionQuery(query, TenantStatistics.class);
    }

    // The statistics query itself, carrying no tenant. Callers add the tenant metadata that routes it, except the
    // one that deliberately leaves it off.
    private static QueryMessage statisticsQuery() {
        return new GenericQueryMessage(new MessageType(GetTenantStatistics.class), new GetTenantStatistics());
    }
}
