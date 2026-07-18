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

// Supporting commands, events, and results referenced by the snippets on this page.
// They are defined in the page's own package so the rendered snippets need no imports for them.

class PlaceOrderCommand {

    private final String orderId;

    PlaceOrderCommand(String orderId) {
        this.orderId = orderId;
    }

    String getOrderId() {
        return orderId;
    }
}

class CreateOrderCommand {

    private final String orderId;

    CreateOrderCommand(String orderId) {
        this.orderId = orderId;
    }

    String orderId() {
        return orderId;
    }
}

class MyCommand {
}

class ProcessOrderCommand {

    private final String orderId;

    ProcessOrderCommand(String orderId) {
        this.orderId = orderId;
    }

    String orderId() {
        return orderId;
    }
}

class OrderPlacedEvent {

    private final String orderId;

    OrderPlacedEvent(String orderId) {
        this.orderId = orderId;
    }

    String getOrderId() {
        return orderId;
    }
}

class OrderCreatedEvent {

    private final String orderId;

    OrderCreatedEvent(String orderId) {
        this.orderId = orderId;
    }

    String getOrderId() {
        return orderId;
    }
}

class OrderResult {
}
