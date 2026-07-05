package messagingconcepts.supportedparameters;

public record PlaceOrderCommand(String orderId, long amount) {
}
