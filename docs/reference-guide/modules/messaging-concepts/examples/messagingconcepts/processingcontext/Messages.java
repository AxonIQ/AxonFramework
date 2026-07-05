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
