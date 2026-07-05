package messagingconcepts.supportedparameters;

public record PaymentRequestedEvent(String orderId, long amount) {
}
