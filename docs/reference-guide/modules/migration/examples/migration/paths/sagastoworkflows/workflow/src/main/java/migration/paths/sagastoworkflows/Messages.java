// tag::messages[]
package migration.paths.sagastoworkflows;


public final class Messages {
    private Messages() {}
    public record ChecksRequested(String orderId) {}
    public record CreditChecked(String orderId, boolean accepted) {}
    public record StockChecked(String orderId, boolean accepted) {}
    public record ChecksCompleted(String orderId, boolean accepted) {}
    public record ApprovalRequested(String orderId) {}
    public record ApprovalReceived(String orderId) {}
    public record ApprovalCompleted(String orderId, boolean approved) {}
    public record DeliveryRequested(String orderId) {}
    // Request services enqueue work and return without waiting for completion.
    // Result events carry the same orderId; a rejected check reports accepted = false.
    // Implement requests idempotently using orderId as the operation key.
    public interface ChecksService {
        // Emit CreditChecked when the check finishes.
        void requestCreditCheck(String orderId);
        // Emit StockChecked when the check finishes.
        void requestStockCheck(String orderId);
    }
    public interface ApprovalService {
        // Emit ApprovalReceived when approval is granted.
        void requestApproval(String orderId);
    }
    public interface DeliveryClient {
        // Implement idempotently: orderId is the stable operation key across retries.
        void send(String orderId);
    }
    public static class TemporaryFailure extends RuntimeException {}
}
// end::messages[]
