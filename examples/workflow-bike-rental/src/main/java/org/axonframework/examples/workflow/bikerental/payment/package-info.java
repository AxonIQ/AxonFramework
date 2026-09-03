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
/**
 * Payment side of the bike rental example: command and event handlers for preparing, confirming and rejecting payments,
 * together with a REST API and a read-model projection of payment status.
 * @since 5.4.0
 */
@NullMarked
package org.axonframework.examples.workflow.bikerental.payment;

import org.jspecify.annotations.NullMarked;
