package messagingconcepts.exceptionhandling;

import java.util.List;

public record PlaceOrderCommand(List<String> productIds, String userId, String order) {
}
