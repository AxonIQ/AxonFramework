/**
 * Derives process state on demand from rental and payment events. It owns no storage and ends when the derived state
 * reports that the rental request is settled. This recipe requires a shared event store containing both contexts.
 */
package org.axonframework.examples.sagarecipes.saga.injectentity;
