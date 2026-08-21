/**
 * Replaces point deadlines with a replayable projection of payments awaiting confirmation. Scheduled sweeps only read
 * the projection and may safely duplicate work because cancellation is idempotent. Polling is approximate, and a
 * production cluster should coordinate sweepers when duplicate dispatch is undesirable.
 */
package org.axonframework.examples.sagarecipes.saga.deadline;
