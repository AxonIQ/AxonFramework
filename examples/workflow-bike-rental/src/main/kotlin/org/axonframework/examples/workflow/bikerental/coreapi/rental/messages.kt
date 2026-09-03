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
package org.axonframework.examples.workflow.bikerental.coreapi.rental

import org.axonframework.eventsourcing.annotation.EventTag
import org.axonframework.messaging.commandhandling.annotation.Command
import org.axonframework.messaging.eventhandling.annotation.Event
import org.axonframework.messaging.queryhandling.annotation.Query
import org.axonframework.modelling.annotation.TargetEntityId

/**
 * @since 5.4.0
 */
@Command(routingKey = "bikeId")
@JvmRecord
data class RegisterBikeCommand(
    @TargetEntityId
    val bikeId: String,
    val bikeType: String,
    val location: String
)

@Command(routingKey = "bikeId")
@JvmRecord
data class RequestBikeCommand(
    @TargetEntityId
    val bikeId: String,
    val renter: String
)

@Command(routingKey = "bikeId")
@JvmRecord
data class ReturnBikeCommand(
    @TargetEntityId
    val bikeId: String,
    val location: String
)

@Command(routingKey = "bikeId")
@JvmRecord
data class ApproveRequestCommand(
    @TargetEntityId
    val bikeId: String,
    val renter: String
)

@Command(routingKey = "bikeId")
@JvmRecord
data class RejectRequestCommand(
    @TargetEntityId
    val bikeId: String,
    val renter: String
)


@Event
@JvmRecord
data class RequestRejectedEvent(
    @EventTag(key = "Bike")
    val bikeId: String
)


@Event
@JvmRecord
data class BikeInUseEvent(
    @EventTag(key = "Bike")
    val bikeId: String,
    val renter: String
)

@Event
@JvmRecord
data class BikeRegisteredEvent(
    @EventTag(key = "Bike")
    val bikeId: String,
    val bikeType: String,
    val location: String
)

@Event
@JvmRecord
data class BikeRequestedEvent(
    @EventTag(key = "Bike")
    val bikeId: String,
    val renter: String,
    val rentalReference: String
)

@Event
@JvmRecord
data class BikeReturnedEvent(
    @EventTag(key = "Bike")
    val bikeId: String,
    val location: String
)

@Query(namespace = "org.axonframework.examples.workflow.bikerental.coreapi.rental", name = "findAll")
object FindAllBikeRentalsQuery

@Query(namespace = "org.axonframework.examples.workflow.bikerental.coreapi.rental", name = "findAvailable")
@JvmRecord
data class FindAvailableQuery(
    val bikeType: String
)

@Query(namespace = "org.axonframework.examples.workflow.bikerental.coreapi.rental", name = "findOne")
@JvmRecord
data class FindRentalByBikeIdQuery(
    val bikeId: String
)
