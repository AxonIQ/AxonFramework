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
package migration.paths.aggregates.multientitymigration;

import java.util.ArrayList;
import java.util.List;

import org.axonframework.eventsourcing.annotation.EventSourcedEntity;
import org.axonframework.modelling.entity.annotation.EntityMember;
import org.axonframework.modelling.entity.annotation.RoutingKeyEventTargetMatcherDefinition;

@EventSourcedEntity(tagKey = "cardId")
public class GiftCardWithExplicitEventTargetMatcher {

    // tag::explicit-event-target-matcher[]
    @EntityMember(
        routingKey = "transactionId",
        eventTargetMatcher = RoutingKeyEventTargetMatcherDefinition.class // <1>
    )
    private List<Transaction> transactions = new ArrayList<>();
    // end::explicit-event-target-matcher[]
}
