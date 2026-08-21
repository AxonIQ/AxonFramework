package org.axonframework.examples.sagarecipes.rental.write.requestbike;

import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.examples.sagarecipes.rental.RentalId;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

@RestController("requestBikeController")
@RequestMapping("/bikes/{bikeId}/request")
class Controller {
    private final CommandGateway commandGateway;

    Controller(CommandGateway commandGateway) {
        this.commandGateway = commandGateway;
    }

    @PostMapping
    CompletableFuture<ResponseEntity<Void>> request(@PathVariable("bikeId") String bikeId,
                                                     @RequestBody Request request) {
        var command = new RequestBike(BikeId.of(bikeId), request.renter(), RentalId.of(request.rentalId()));
        return commandGateway.send(command).getResultMessage()
                             .thenApply(ignored -> ResponseEntity.accepted().build());
    }

    record Request(String rentalId, String renter) {
    }
}
