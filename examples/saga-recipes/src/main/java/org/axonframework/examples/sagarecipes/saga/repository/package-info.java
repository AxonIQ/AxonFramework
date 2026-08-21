/**
 * Stores process state in a private JPA table. The event processor's Spring transaction contains the repository
 * mutation and tracking-token update, and commits only after the returned command future succeeds. Lifecycle
 * completion deletes the row. Target command idempotency makes redelivery after deletion harmless.
 */
package org.axonframework.examples.sagarecipes.saga.repository;
