package org.axonframework.examples.sagarecipes.rental.write.returnbike;

import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("returnBikeController")
@RequestMapping("/bikes/{bikeId}/return")
class Controller {
    private final CommandGateway commandGateway;

    Controller(CommandGateway commandGateway) {
        this.commandGateway = commandGateway;
    }

    @PostMapping
    ResponseEntity<Void> returnBike(@PathVariable("bikeId") String bikeId, @RequestBody Request request) {
        commandGateway.sendAndWait(new ReturnBike(BikeId.of(bikeId), request.location()));
        return ResponseEntity.accepted().build();
    }

    record Request(String location) {
    }
}
