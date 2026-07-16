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
package events.eventstoreinternals.criteria;

// The import is indented to the depth of the nested method below, so that the
// indent=0 normalization of the include renders both regions flush left.
// tag::event-criteria-import[]
    import org.axonframework.messaging.eventstreaming.EventCriteria;
    import org.axonframework.messaging.eventstreaming.Tag;

// end::event-criteria-import[]

class EventCriteriaExamples {

    // tag::event-criteria[]
    public EventCriteria createCriteriaFor(OrderPlacedEvent event) {
        return EventCriteria.havingTags(Tag.of("orderId", event.orderId().toString()))
                            .andBeingOneOfTypes("OrderPlaced");
    }
    // end::event-criteria[]
}

record OrderPlacedEvent(String orderId) {

}
