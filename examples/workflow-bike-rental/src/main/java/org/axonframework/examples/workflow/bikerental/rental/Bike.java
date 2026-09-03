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

import org.axonframework.examples.workflow.bikerental.coreapi.rental.ApproveRequestCommand;
import org.axonframework.examples.workflow.bikerental.coreapi.rental.BikeInUseEvent;
import org.axonframework.examples.workflow.bikerental.coreapi.rental.BikeRegisteredEvent;
import org.axonframework.examples.workflow.bikerental.coreapi.rental.BikeRequestedEvent;
import org.axonframework.examples.workflow.bikerental.coreapi.rental.BikeReturnedEvent;
import org.axonframework.examples.workflow.bikerental.coreapi.rental.RegisterBikeCommand;
import org.axonframework.examples.workflow.bikerental.coreapi.rental.RejectRequestCommand;
import org.axonframework.examples.workflow.bikerental.coreapi.rental.RequestBikeCommand;
import org.axonframework.examples.workflow.bikerental.coreapi.rental.RequestRejectedEvent;
import org.axonframework.examples.workflow.bikerental.coreapi.rental.ReturnBikeCommand;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventsourcing.annotation.reflection.EntityCreator;
import org.axonframework.extension.spring.stereotype.EventSourced;
import org.axonframework.messaging.commandhandling.annotation.CommandHandler;
import org.axonframework.messaging.eventhandling.gateway.EventAppender;

import java.util.Objects;
import java.util.UUID;

/**
 * @since 5.4.0
 */
@EventSourced(tagKey = "Bike")
public class Bike {

    private String bikeId;

    private boolean isAvailable;
    private String reservedBy;
    private boolean reservationConfirmed;

    @EntityCreator
    public Bike() {
    }

    @CommandHandler
    public static void handle(RegisterBikeCommand command, EventAppender eventAppender) {
        eventAppender.append(new BikeRegisteredEvent(command.bikeId(), command.bikeType(), command.location()));
    }

    @CommandHandler
    public String handle(RequestBikeCommand command, EventAppender eventAppender) {
        if (!this.isAvailable) {
            throw new IllegalStateException("Bike is already rented");
        }
        String rentalReference = UUID.randomUUID().toString();
        eventAppender.append(new BikeRequestedEvent(command.bikeId(), command.renter(), rentalReference));

        return rentalReference;
    }

    @CommandHandler
    public void handle(ApproveRequestCommand command, EventAppender eventAppender) {
        if (!Objects.equals(reservedBy, command.renter())
                || reservationConfirmed) {
            return;
        }
        eventAppender.append(new BikeInUseEvent(command.bikeId(), command.renter()));
    }

    @CommandHandler
    public void handle(RejectRequestCommand command, EventAppender eventAppender) {
        if (!Objects.equals(reservedBy, command.renter())
                || reservationConfirmed) {
            return;
        }
        eventAppender.append(new RequestRejectedEvent(command.bikeId()));
    }

    @CommandHandler
    public void handle(ReturnBikeCommand command, EventAppender eventAppender) {
        if (this.isAvailable) {
            throw new IllegalStateException("Bike was already returned");
        }
        eventAppender.append(new BikeReturnedEvent(command.bikeId(), command.location()));
    }

    @EventSourcingHandler
    public void handle(BikeRegisteredEvent event) {
        this.bikeId = event.bikeId();
        this.isAvailable = true;
    }

    @EventSourcingHandler
    protected void handle(BikeReturnedEvent event) {
        this.isAvailable = true;
        this.reservationConfirmed = false;
        this.reservedBy = null;
    }

    @EventSourcingHandler
    protected void handle(BikeRequestedEvent event) {
        this.reservedBy = event.renter();
        this.reservationConfirmed = false;
        this.isAvailable = false;
    }

    @EventSourcingHandler
    protected void handle(RequestRejectedEvent event) {
        this.reservedBy = null;
        this.reservationConfirmed = false;
        this.isAvailable = true;
    }

    @EventSourcingHandler
    protected void on(BikeInUseEvent event) {
        this.isAvailable = false;
        this.reservationConfirmed = true;
    }

    @SuppressWarnings("unused")
    public String getBikeId() {
        return bikeId;
    }

    @SuppressWarnings("unused")
    public boolean isAvailable() {
        return isAvailable;
    }

    @SuppressWarnings("unused")
    public String getReservedBy() {
        return reservedBy;
    }

    @SuppressWarnings("unused")
    public boolean isReservationConfirmed() {
        return reservationConfirmed;
    }
}
