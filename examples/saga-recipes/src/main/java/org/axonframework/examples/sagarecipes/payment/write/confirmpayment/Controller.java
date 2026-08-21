package org.axonframework.examples.sagarecipes.payment.write.confirmpayment;

import org.axonframework.examples.sagarecipes.payment.PaymentId;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

@RestController("confirmPaymentController")
@RequestMapping("/payments/{paymentId}/confirm")
class Controller {
    private final CommandGateway commandGateway;

    Controller(CommandGateway commandGateway) {
        this.commandGateway = commandGateway;
    }

    @PostMapping
    CompletableFuture<ResponseEntity<Void>> confirm(@PathVariable("paymentId") String paymentId) {
        var command = new ConfirmPayment(PaymentId.of(paymentId));
        return commandGateway.send(command).getResultMessage()
                             .thenApply(ignored -> ResponseEntity.accepted().build());
    }
}
