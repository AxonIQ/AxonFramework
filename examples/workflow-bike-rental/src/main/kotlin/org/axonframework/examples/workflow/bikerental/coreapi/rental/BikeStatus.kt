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

import jakarta.persistence.Entity
import jakarta.persistence.Id

/**
 * @since 5.4.0
 */
@Entity
class BikeStatus {
  @Id
  var bikeId: String? = null
    private set
  var bikeType: String? = null
    private set
  var location: String? = null
    private set
  var renter: String? = null
    private set
  private var status: RentalStatus? = null

  constructor()

  constructor(bikeId: String?, bikeType: String?, location: String?) {
    this.bikeId = bikeId
    this.bikeType = bikeType
    this.location = location
    this.status = RentalStatus.AVAILABLE
  }

  fun returnedAt(location: String?) {
    this.location = location
    this.status = RentalStatus.AVAILABLE
    this.renter = null
  }

  fun requestedBy(renter: String?) {
    this.renter = renter
    this.status = RentalStatus.REQUESTED
  }

  fun rentedBy(renter: String?) {
    this.renter = renter
    this.status = RentalStatus.RENTED
  }

  fun getStatus(): RentalStatus {
    return status!!
  }

  fun description(): String {
    when (status) {
      RentalStatus.RENTED -> return String.format("Bike %s was rented by %s in %s", bikeId, renter, location)
      RentalStatus.AVAILABLE -> return String.format("Bike %s is available for rental in %s.", bikeId, location)
      RentalStatus.REQUESTED -> return String.format("Bike %s is requested by %s in %s", bikeId, renter, location)
      else -> return "Status unknown"
    }
  }
}
