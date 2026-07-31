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

import io.axoniq.framework.messaging.multitenancy.api.TenantDescriptor;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.axonframework.examples.demo.multitenancy.university.read.statistics.TenantStatistics;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One tenant's open statistics subscription, holding every update it has received, whether it was completed, and
 * the means to close it.
 * <p>
 * Each update is logged as it arrives, since hearing about a change when it happens is the point of a subscription
 * query.
 *
 * @author Laura Devriendt
 * @since 5.3.0
 */
public final class StatisticsSubscription implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(StatisticsSubscription.class);

    private final String tenantId;
    private final List<TenantStatistics> received = new CopyOnWriteArrayList<>();
    private final AtomicBoolean completed = new AtomicBoolean();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final Disposable subscription;

    private StatisticsSubscription(QueryGateway queryGateway, TenantDescriptor tenant) {
        this.tenantId = tenant.tenantId();
        this.subscription = Flux.from(Statistics.subscribeTo(queryGateway, tenant))
                                .doOnComplete(() -> {
                                    completed.set(true);
                                    logger.info("Tenant [{}]'s subscription completed: no seats left anywhere.",
                                                tenantId);
                                })
                                .subscribe(statistics -> {
                                               logger.info("Tenant [{}]'s subscription received: {} enrollment(s).",
                                                           tenantId, statistics.totalEnrollments());
                                               received.add(statistics);
                                           },
                                           subscriptionFailure -> {
                                               failure.set(subscriptionFailure);
                                               logger.warn("Tenant [{}]'s statistics subscription failed.",
                                                           tenantId, subscriptionFailure);
                                           });
    }

    /**
     * Subscribes to the given {@code tenant}'s statistics, returning once its initial result has arrived.
     * <p>
     * A subscription query registers for updates before it delivers its initial result, so waiting for that result
     * is how a caller knows an update it triggers next will reach the subscription.
     *
     * @param queryGateway the gateway the subscription query is sent on
     * @param tenant       the tenant to subscribe to
     * @param atMost       how long to wait for the initial result
     * @return the tenant's open subscription
     */
    public static StatisticsSubscription openFor(QueryGateway queryGateway,
                                                 TenantDescriptor tenant,
                                                 Duration atMost) {
        StatisticsSubscription subscription = new StatisticsSubscription(queryGateway, tenant);
        try {
            Awaitility.await("tenant [" + tenant.tenantId() + "] subscription active")
                      .atMost(atMost)
                      .until(() -> !subscription.received.isEmpty() || subscription.failure.get() != null);
        } catch (ConditionTimeoutException timeout) {
            logger.warn("""
                        Tenant [{}]'s statistics subscription produced no initial result within {}. \
                        Anything observed on it is unreliable.""",
                        tenant.tenantId(), atMost);
        }
        return subscription;
    }

    /**
     * Stops receiving updates on this subscription.
     */
    @Override
    public void close() {
        subscription.dispose();
    }

    /**
     * Returns whether the publisher completed this subscription.
     *
     * @return {@code true} if this subscription was completed
     */
    public boolean isCompleted() {
        return completed.get();
    }

    /**
     * The updates received so far, including the initial result.
     *
     * @return the updates received, in the order they arrived
     */
    public List<TenantStatistics> received() {
        return List.copyOf(received);
    }

    /**
     * The number of updates received so far, including the initial result.
     *
     * @return how many updates arrived
     */
    public int receivedCount() {
        return received.size();
    }

    /**
     * The running enrollment total this subscription saw, one entry per update received. Starting from the initial
     * result, each entry is the tenant's own total at that moment, so the whole list reads as that tenant's
     * enrollments arriving one at a time.
     *
     * @return the running enrollment totals received, in the order they arrived
     */
    public List<Integer> receivedTotals() {
        return received.stream().map(TenantStatistics::totalEnrollments).toList();
    }

    /**
     * The failure this subscription ended with, if it failed.
     *
     * @return the failure, or an empty optional while the subscription is healthy
     */
    public Optional<Throwable> failure() {
        return Optional.ofNullable(failure.get());
    }
}
