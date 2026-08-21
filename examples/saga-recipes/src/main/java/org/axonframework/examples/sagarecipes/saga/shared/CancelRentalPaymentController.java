package org.axonframework.examples.sagarecipes.saga.shared;

import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/rentals/{rentalId}/cancel-payment")
class CancelRentalPaymentController {
    private final CommandGateway commandGateway;

    CancelRentalPaymentController(CommandGateway commandGateway) {
        this.commandGateway = commandGateway;
    }

    @PostMapping
    ResponseEntity<Void> cancel(@PathVariable("rentalId") String rentalId) {
        commandGateway.sendAndWait(new CancelRentalPayment(RentalId.of(rentalId)));
        return ResponseEntity.accepted().build();
    }
}
