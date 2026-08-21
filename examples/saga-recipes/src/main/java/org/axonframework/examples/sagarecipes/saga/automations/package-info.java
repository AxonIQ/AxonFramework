/**
 * Decomposes the process into independent event-to-command automation slices. Stateless slices use only their tracking
 * token; payment-triggered slices derive the minimal rental lookup from the event store.
 */
package org.axonframework.examples.sagarecipes.saga.automations;
