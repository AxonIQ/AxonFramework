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
package org.axonframework.examples.workflow.bikerental.coreapi.payment

import jakarta.persistence.Entity
import jakarta.persistence.Id

/**
 * @since 5.4.0
 */
@Entity
class PaymentStatus {
  @Id
  var id: String? = null
    private set

  @JvmField
  var status: Status? = null
  var amount: Int = 0
    private set
  private var reference: String? = null

  constructor()

  constructor(id: String?, amount: Int, reference: String?) {
    this.id = id
    this.amount = amount
    this.reference = reference
    this.status = Status.PENDING
  }

  enum class Status {
    PENDING, APPROVED, REJECTED
  }
}
