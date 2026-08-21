/**
 * Stores process state in a private JPA table. Command dispatch completes before the unit of work records progress,
 * and lifecycle completion deletes the row. Target command idempotency makes redelivery after deletion harmless.
 */
package org.axonframework.examples.sagarecipes.saga.repository;
