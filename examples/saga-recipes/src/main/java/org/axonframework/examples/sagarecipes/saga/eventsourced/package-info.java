/**
 * Event-sources the process from its own audit events. Commands are the only writers of those events, preserving a
 * command-to-event write slice at the cost of a second command per process step.
 */
package org.axonframework.examples.sagarecipes.saga.eventsourced;
