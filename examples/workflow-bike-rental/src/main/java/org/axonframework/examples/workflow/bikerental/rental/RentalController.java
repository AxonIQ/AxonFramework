/*
 * Copyright (c) 2010-2026. AxonIQ B.V.
 *
 * Licensed under the AXONIQ TERMS OF SERVICE,
 * Version 29 April 2026 (the "License");
 *
 * The software is available for evaluation use without registration.
 * Continued use beyond the evaluation period requires registration
 * and a commercial license. See the License for the specific language
 * governing permissions and limitations under the License.
 * You may not use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at:
 *  https://www.axoniq.io/legal/terms-of-service
 *
 * For licensing information and to register, visit:
 *  https://www.axoniq.io/pricing
 */
package org.axonframework.examples.workflow.bikerental.rental;

import org.axonframework.examples.workflow.bikerental.coreapi.rental.BikeStatus;
import org.axonframework.examples.workflow.bikerental.coreapi.rental.FindAllBikeRentalsQuery;
import org.axonframework.examples.workflow.bikerental.coreapi.rental.FindRentalByBikeIdQuery;
import org.axonframework.examples.workflow.bikerental.coreapi.rental.RegisterBikeCommand;
import org.axonframework.examples.workflow.bikerental.coreapi.rental.RequestBikeCommand;
import org.axonframework.examples.workflow.bikerental.coreapi.rental.ReturnBikeCommand;
import org.axonframework.messaging.commandhandling.gateway.CommandGateway;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * @since 5.4.0
 */
@RestController
@RequestMapping("/")
public class RentalController {

    private static final List<String> LOCATIONS = Arrays.asList("Amsterdam",
                                                                "Paris",
                                                                "Vilnius",
                                                                "Barcelona",
                                                                "London",
                                                                "New York",
                                                                "Toronto",
                                                                "Berlin",
                                                                "Milan",
                                                                "Rome",
                                                                "Belgrade");
    private final CommandGateway commandGateway;
    private final QueryGateway queryGateway;

    public RentalController(
            CommandGateway commandGateway,
            QueryGateway queryGateway
    ) {
        this.commandGateway = commandGateway;
        this.queryGateway = queryGateway;
    }

    @PostMapping
    public CompletableFuture<Void> generateBikes(@RequestParam("bikes") int bikeCount,
                                                 @RequestParam(value = "bikeType") String bikeType) {
        CompletableFuture<Void> all = CompletableFuture.completedFuture(null);
        for (int i = 0; i < bikeCount; i++) {
            all = CompletableFuture.allOf(all,
                                          commandGateway.send(new RegisterBikeCommand(randomBikeId(),
                                                                                      bikeType,
                                                                                      randomLocation()))
                                                        .resultAs(Void.class)
            );
        }
        return all;
    }

    @PostMapping("/requestBike")
    public CompletableFuture<String> requestBike(@RequestParam("bikeId") String bikeId,
                                                 @RequestParam("renter") String renter) {
        return commandGateway.send(new RequestBikeCommand(bikeId, renter)).resultAs(String.class);
    }

    @PostMapping("/returnBike")
    public CompletableFuture<String> returnBike(@RequestParam("bikeId") String bikeId,
                                                @RequestParam("location") String location) {
        return commandGateway.send(new ReturnBikeCommand(bikeId, location != null ? location : randomLocation()))
                             .resultAs(String.class);
    }

    @GetMapping("/bikes")
    public CompletableFuture<List<BikeStatus>> findAll() {
        return queryGateway.queryMany(FindAllBikeRentalsQuery.INSTANCE, BikeStatus.class);
    }

    @GetMapping("/bikes/{bikeId}")
    public CompletableFuture<BikeStatus> findStatus(@PathVariable("bikeId") String bikeId) {
        return queryGateway.query(new FindRentalByBikeIdQuery(bikeId), BikeStatus.class);
    }

    private String randomLocation() {
        return LOCATIONS.get(ThreadLocalRandom.current().nextInt(LOCATIONS.size()));
    }

    private String randomBikeId() {
        return UUID.randomUUID().toString();
    }
}
