package messagingconcepts.exceptionhandling;

public class OrderResult {

    public static OrderResult failed(String reason) {
        return new OrderResult();
    }

    public static OrderResult success(String orderId) {
        return new OrderResult();
    }
}
