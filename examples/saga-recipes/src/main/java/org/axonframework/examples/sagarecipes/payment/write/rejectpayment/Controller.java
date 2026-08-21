package org.axonframework.examples.sagarecipes.payment.write.rejectpayment;

import org.axonframework.examples.sagarecipes.payment.PaymentId;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

@RestController("rejectPaymentController")
@RequestMapping("/payments/{paymentId}/reject")
class Controller {
    private final CommandGateway commandGateway;

    Controller(CommandGateway commandGateway) {
        this.commandGateway = commandGateway;
    }

    @PostMapping
    CompletableFuture<ResponseEntity<Void>> reject(@PathVariable("paymentId") String paymentId,
                                                    @RequestBody Request request) {
        var command = new RejectPayment(PaymentId.of(paymentId), request.reason());
        return commandGateway.send(command).getResultMessage()
                             .thenApply(ignored -> ResponseEntity.accepted().build());
    }

    record Request(String reason) {
    }
}
