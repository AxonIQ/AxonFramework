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
package commands.infrastructure.registration.springboot;

import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;

// tag::event-sourced-creation-handler[]
@EventSourced
public class Order {

    @CommandHandler
    public static Order handle(CreateOrderCommand command) {
        // Creation handler
        return new Order(command.orderId(), command.productId());
    }
    // end::event-sourced-creation-handler[]

    Order(String orderId, String productId) {
    }
    // tag::event-sourced-creation-handler[]
}
// end::event-sourced-creation-handler[]
