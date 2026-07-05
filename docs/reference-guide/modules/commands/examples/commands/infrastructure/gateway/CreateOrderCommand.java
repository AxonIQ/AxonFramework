package commands.infrastructure.gateway;

record CreateOrderCommand(String orderId, String productId, int quantity) {
}
