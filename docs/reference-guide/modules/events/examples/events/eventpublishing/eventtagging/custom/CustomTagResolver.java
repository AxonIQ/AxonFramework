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
package events.eventpublishing.eventtagging.custom;

// tag::custom-tag-resolver[]
import org.axonframework.eventsourcing.eventstore.TagResolver;
import org.axonframework.messaging.eventhandling.EventMessage;
import org.axonframework.messaging.eventstreaming.Tag;

import java.util.Set;

public class CustomTagResolver implements TagResolver {

    @Override
    public Set<Tag> resolve(EventMessage event) {
        Object payload = event.payload();
        if (payload instanceof OrderPlacedEvent orderEvent) {
            return Set.of(
                    Tag.of("orderId", orderEvent.orderId()),
                    Tag.of("customer", orderEvent.customerId())
            );
        }
        return Set.of();
    }
}
// end::custom-tag-resolver[]

record OrderPlacedEvent(String orderId, String orderRegion, String customerId, int amount) {

}
