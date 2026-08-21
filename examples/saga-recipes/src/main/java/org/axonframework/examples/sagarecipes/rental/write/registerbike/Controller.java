package org.axonframework.examples.sagarecipes.rental.write.registerbike;

import org.axonframework.examples.sagarecipes.rental.BikeId;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

@RestController("registerBikeController")
@RequestMapping("/bikes")
class Controller {
    private final CommandGateway commandGateway;

    Controller(CommandGateway commandGateway) {
        this.commandGateway = commandGateway;
    }

    @PostMapping
    CompletableFuture<ResponseEntity<Void>> register(@RequestBody Request request) {
        var command = new RegisterBike(BikeId.of(request.bikeId()), request.bikeType(), request.location());
        return commandGateway.send(command).getResultMessage()
                             .thenApply(ignored -> ResponseEntity.accepted().build());
    }

    record Request(String bikeId, String bikeType, String location) {
    }
}
