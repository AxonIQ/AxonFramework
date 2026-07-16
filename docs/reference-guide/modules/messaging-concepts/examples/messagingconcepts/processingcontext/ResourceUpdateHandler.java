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
package messagingconcepts.processingcontext;

import java.util.ArrayList;
import org.axonframework.messaging.core.unitofwork.ProcessingContext;
import org.axonframework.messaging.eventhandling.annotation.EventHandler;

class ResourceUpdateHandler implements ResourceKeyDefinitions {

    // tag::updating-resources[]
    @EventHandler
    public void on(OrderPlacedEvent event, ProcessingContext context) {
        // Process event...
        // and update resource using a function
        context.updateResource(TAGS, tags -> {
            if (tags == null) {
                tags = new ArrayList<>();
            }
            tags.add("processed");
            return tags;
        });
    }
    // end::updating-resources[]
}
