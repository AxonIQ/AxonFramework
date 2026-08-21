/**
 * Decomposes the process into independent event-to-command automation slices. Stateless slices use only their tracking
 * token; payment-triggered slices derive the minimal rental lookup from the event store. All event-driven slices use
 * the {@code rental-payment-automations} namespace so that they share one processor and tracking token.
 */
package org.axonframework.examples.sagarecipes.saga.automations;
