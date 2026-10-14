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

package org.axonframework.examples.sagarecipes.saga.deadline;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * The to-do list of payments still waiting to be paid.
 *
 * @author Mateusz Nowak
 * @since 5.4.0
 */
@Repository
interface PendingPaymentRepository extends JpaRepository<PendingPayment, String> {

    /**
     * Finds every payment that was prepared before the given moment and is still outstanding.
     * <p>
     * The filtering is done by the database, on an indexed column. Loading everything and filtering in Java would
     * work in a demo and fall over in production, which is exactly the kind of difference an example should not
     * teach by omission.
     *
     * @param cutoff the moment before which a payment counts as overdue
     * @return the overdue payments, oldest first
     */
    List<PendingPayment> findByPreparedAtBeforeOrderByPreparedAtAsc(Instant cutoff);
}
