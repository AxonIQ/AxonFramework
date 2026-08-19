/*
 * Copyright (c) 2010-2026. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.axonframework.messaging.core.timeout;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Represents a task with a timeout. The task will be interrupted when the {@code timeout} is reached. If the
 * {@code warningThreshold} is lower than the timeout, warnings will be logged at the configured {@code warningInterval}
 * until the timeout is reached. All times are in milliseconds.
 * <p>
 * Warning logging will include the task's name, the current time taken by the task and its remaining time to execute.
 * The stack trace of the thread handling the message will also be included in the log, up to the point where the task
 * was started.
 * <p>
 * Once the {@code timeout} is reached, a message will be logged with the current stack trace of the thread handling the
 * message, and the thread will be interrupted. If the task is completed before the timeout, the task should be marked
 * as completed.
 *
 * @author Mitchell Herrijgers
 * @since 4.11.0
 */
class AxonTimeLimitedTask {

    private final Thread thread;
    private final int timeout;
    private final int warningThreshold;
    private final int warningInterval;
    private final String taskName;
    private final ScheduledExecutorService scheduledExecutorService;
    private final Logger logger;
    @Nullable
    private final String callerClassName; // stored as name to avoid getName() on every stack frame check
    private boolean completed = false;
    private boolean interrupted = false;
    private boolean interruptedExternally = false;
    private long startTimeMs = -1;
    private Future<?> currentScheduledFuture = null;


    /**
     * Creates a new {@link AxonTimeLimitedTask} for the given {@code task} with the given {@code timeout},
     * {@code warningThreshold} and {@code warningInterval}. Runs the provided task on the current thread after
     * scheduling a timeout and warnings on another thread.
     * <p>
     * If you wish to provide a logger of your own, or your own {@code scheduledExecutorService}, use
     * {@link #AxonTimeLimitedTask(String, int, int, int, ScheduledExecutorService, Logger)}.
     *
     * @param taskName         The task's name to be included in the logging
     * @param timeout          The timeout in milliseconds
     * @param warningThreshold The threshold in milliseconds after which a warning is logged. Setting this to a value
     *                         equal or higher than {@code timeout} will disable warnings.
     * @param warningInterval  The interval in milliseconds between warnings.
     */
    public AxonTimeLimitedTask(String taskName,
                               int timeout,
                               int warningThreshold,
                               int warningInterval) {
        this(taskName, timeout, warningThreshold, warningInterval, AxonTaskJanitor.INSTANCE, AxonTaskJanitor.LOGGER, null);
    }

    /**
     * Creates a new {@link AxonTimeLimitedTask} for the given {@code task} with the given {@code timeout},
     * {@code warningThreshold} and {@code warningInterval}. Runs the provided task on the current thread after
     * scheduling a timeout and warnings on another thread.
     * <p>
     * The {@code callerClass} is used to trim the stack trace in timeout/warning logs, cutting off framework internals
     * below the caller. If you do not need trimming, use
     * {@link #AxonTimeLimitedTask(String, int, int, int)}.
     *
     * @param taskName         The task's name to be included in the logging
     * @param timeout          The timeout in milliseconds
     * @param warningThreshold The threshold in milliseconds after which a warning is logged. Setting this to a value
     *                         equal or higher than {@code timeout} will disable warnings.
     * @param warningInterval  The interval in milliseconds between warnings.
     * @param callerClass      the class of the direct caller, used to trim the stack trace in timeout/warning logs
     */
    public AxonTimeLimitedTask(String taskName,
                               int timeout,
                               int warningThreshold,
                               int warningInterval,
                               Class<?> callerClass) {
        this(taskName,
             timeout,
             warningThreshold,
             warningInterval,
             AxonTaskJanitor.INSTANCE,
             AxonTaskJanitor.LOGGER,
             callerClass
        );
    }

    /**
     * Creates a new {@link AxonTimeLimitedTask} for the given {@code task} with the given {@code timeout},
     * {@code warningThreshold} and {@code warningInterval}. For scheduling, the provided
     * {@code scheduledExecutorService} will be used. To log warnings and errors, the provided {@code logger} will be
     * used. Runs the provided task on the current thread after scheduling a timeout and warnings on the provided
     * {@code scheduledExecutorService}.
     * <p>
     * If you do not wish to provide a logger of your own {@code scheduledExecutorService}, use
     * {@link #AxonTimeLimitedTask(String, int, int, int)}.
     * <p>
     *
     * @param taskName                 The task's name to be included in the logging
     * @param timeout                  The timeout in milliseconds
     * @param warningThreshold         The threshold in milliseconds after which a warning is logged. Setting this to a
     *                                 value equal or higher than {@code timeout} will disable warnings.
     * @param warningInterval          The interval in milliseconds between warnings.
     * @param scheduledExecutorService the executor service to schedule the timeout and warnings
     * @param logger                   the logger to log the warnings and errors
     */
    public AxonTimeLimitedTask(String taskName,
                               int timeout,
                               int warningThreshold,
                               int warningInterval,
                               ScheduledExecutorService scheduledExecutorService,
                               Logger logger) {
        this(taskName, timeout, warningThreshold, warningInterval, scheduledExecutorService, logger, null);
    }

    /**
     * Creates a new {@link AxonTimeLimitedTask} for the given {@code task} with the given {@code timeout},
     * {@code warningThreshold} and {@code warningInterval}. For scheduling, the provided
     * {@code scheduledExecutorService} will be used. To log warnings and errors, the provided {@code logger} will be
     * used.
     * <p>
     * The {@code callerClass} is used to trim the stack trace in timeout/warning logs, cutting off framework internals
     * below the caller. If you do not need trimming, use
     * {@link #AxonTimeLimitedTask(String, int, int, int, ScheduledExecutorService, Logger)}.
     *
     * @param taskName                 The task's name to be included in the logging
     * @param timeout                  The timeout in milliseconds
     * @param warningThreshold         The threshold in milliseconds after which a warning is logged. Setting this to a
     *                                 value equal or higher than {@code timeout} will disable warnings.
     * @param warningInterval          The interval in milliseconds between warnings.
     * @param scheduledExecutorService the executor service to schedule the timeout and warnings
     * @param logger                   the logger to log the warnings and errors
     * @param callerClass              the class of the direct caller, used to trim the stack trace in timeout/warning
     *                                 logs
     */
    public AxonTimeLimitedTask(String taskName,
                               int timeout,
                               int warningThreshold,
                               int warningInterval,
                               ScheduledExecutorService scheduledExecutorService,
                               Logger logger,
                               Class<?> callerClass) {
        if (taskName == null || taskName.isEmpty()) {
            throw new IllegalArgumentException("Task name cannot be null or empty");
        }
        this.taskName = taskName;
        this.timeout = timeout;
        this.warningThreshold = warningThreshold;
        this.warningInterval = warningInterval;
        this.scheduledExecutorService = scheduledExecutorService;
        this.logger = logger;
        this.callerClassName = callerClass != null ? callerClass.getName() : null;
        this.thread = Thread.currentThread();
    }

    /**
     * Starts the task, scheduling the first warning or immediate interrupt. Once the task is completed, the
     * {@link #complete()} method should be called. At completion, the caller should also call
     * {@link #ensureNoInterruptionWasSwallowed()} to ensure that any swallowed interruptions are properly handled. In
     * addition, any exceptions thrown during the handling of the message should be passed to
     * {@link #detectInterruptionInsteadOfException(Exception)} to ensure that the proper error status is restored.
     */
    public void start() {
        if (startTimeMs != -1) {
            throw new IllegalStateException("Task can only be run once");
        }
        startTimeMs = System.currentTimeMillis();

        if (warningThreshold < 0 || warningThreshold >= timeout) {
            scheduleImmediateInterrupt();
        } else {
            scheduleFirstWarning();
        }
    }

    /**
     * Marks the task as completed. Cancels the current future warning or interrupt if any exists.
     */
    public void complete() {
        completed = true;
        if (currentScheduledFuture != null) {
            currentScheduledFuture.cancel(false);
            currentScheduledFuture = null;
        }
        if (logger.isTraceEnabled()) {
            logger.trace("{} completed", taskName);
        }
    }

    /**
     * Even though the task was processed successfully, it might have been interrupted while processing, and the
     * exception might have been caught and swallowed by a lower component. This happens, for example, by the
     * {@code LoggingErrorHandler} , which is the default in event processors.
     * <p>
     * This function checks if the task was interrupted, and if so, it throws an {@link AxonTimeoutException} to
     * indicate that the processing was aborted due to a timeout. If the task was not interrupted, it checks if the
     * thread was interrupted. If it was, it throws an {@link InterruptedException} to indicate that the processing was
     * aborted due to an interrupt. This effectively restores the proper error status, so upper components can handle
     * it.
     *
     * @throws InterruptedException when the thread was interrupted
     */
    public void ensureNoInterruptionWasSwallowed() throws InterruptedException {
        if (isInterrupted()) {
            AxonTaskJanitor.LOGGER.info(
                    "Task [{}] was completed successfully, but was interrupted by the janitor because it was processing for too long. The exception was swallowed by a lower component. Throwing TimeoutException.",
                    getTaskName()
            );
            //noinspection ResultOfMethodCallIgnored
            Thread.interrupted(); // Clear the interrupt status
            throw new AxonTimeoutException(String.format("%s has timed out", getTaskName()));
        } else if (Thread.interrupted()) {
            // Something was interrupted while processing the task, so we don't need to interrupt it ourselves anymore.
            this.interrupted = true;
            this.interruptedExternally = true;
            Thread.currentThread().interrupt();
            throw new InterruptedException(String.format("%s was interrupted", getTaskName()));
        }
    }

    /**
     * If an exception is thrown during the handling of the message and it bubbles up, we check if the thread was
     * interrupted. If it was, we check if the task was interrupted as well. If both hold true, that must mean the
     * exception was caused by the interruption of the task, and a {@link AxonTimeoutException} is returned to indicate
     * that the processing was aborted due to a timeout. If the thread was not interrupted, the
     * original exception is returned.
     * <p>
     * This might happen if someone catches the {@link InterruptedException} and wraps it using a different exception,
     * or throws a different exception altogether.
     * <p>
     * Creators of this task should use this method in their catch block to ensure that the exception is properly
     * handled and the interrupt status is restored.
     *
     * @param e the exception that was thrown during the handling of the message
     * @return the exception that should be thrown instead
     */
    public Exception detectInterruptionInsteadOfException(Exception e) {
        if (interruptedExternally) {
            // Keep the interruption status of the thread intact, as it was interrupted by the ensureNoInterruptionWasSwallowed function
            Thread.currentThread().interrupt();
            return e;
        }
        if (!Thread.interrupted() && !(e instanceof InterruptedException) && !isInterrupted()) {
            return e;
        }
        if (isInterrupted()) {
            return new AxonTimeoutException(String.format("%s has timed out.", getTaskName()));
        } else {
            // If the task was interrupted, we restore the interrupt status and rethrow the exception
            Thread.currentThread().interrupt();
            return new InterruptedException(String.format("%s was interrupted.", getTaskName()));
        }
    }

    /**
     * When a warning has not been configured, this method schedules an interrupt immediately for the timeout.
     */
    private void scheduleImmediateInterrupt() {
        if (logger.isTraceEnabled()) {
            logger.trace("{} will be interrupted after [{}ms]",
                         taskName,
                         timeout);
        }
        scheduleInterrupt(timeout);
    }

    /**
     * Schedules the first warning for the task. This warning will be issued after the configured
     * {@code warningThreshold}
     */
    private void scheduleFirstWarning() {
        if (logger.isTraceEnabled()) {
            logger.trace(
                    "{} will be interrupted in [{}ms]. First warning will be issued in [{}ms].",
                    taskName,
                    timeout,
                    warningThreshold);
        }
        scheduleWarning(warningThreshold);
    }

    /**
     * Schedule a subsequent warning for the task after the configured {@code timeout}. Once the warning time is
     * reached, it will log a warning (if the task is not completed yet) and schedule the next warning or the timeout
     * interrupt.
     *
     * @param timeout The time in milliseconds before the warning should be scheduled
     */
    private void scheduleWarning(long timeout) {
        currentScheduledFuture = scheduledExecutorService.schedule(() -> {
            if (!completed) {
                scheduleWarningOrInterrupt();
            }
        }, timeout, TimeUnit.MILLISECONDS);
    }

    /**
     * Schedules either a warning or interrupt, after the first warning has been issued. Which of the two is scheduled
     * depends on the time taken so far. If the remaining time is less than the warning interval, an interrupt is
     * scheduled. Otherwise, a warning is scheduled.
     */
    private void scheduleWarningOrInterrupt() {
        long takenTime = System.currentTimeMillis() - startTimeMs;
        logger.warn(
                "{} on thread [{}] is taking a long time to process. Current time: [{}ms]. Will be interrupted in [{}ms].\nStacktrace of current thread:\n{}",
                taskName,
                thread.getName(),
                takenTime,
                timeout - takenTime,
                getCurrentStackTrace());
        if (takenTime + warningInterval < timeout) {
            scheduleWarning(warningInterval);
        } else {
            scheduleInterrupt(timeout - takenTime);
        }
    }

    /**
     * Schedules an interrupt to the thread handling the message.
     *
     * @param remainingTimeout The time in milliseconds before the interrupt should be scheduled
     */
    private void scheduleInterrupt(long remainingTimeout) {
        currentScheduledFuture = scheduledExecutorService.schedule(() -> {
            if (!completed && !interrupted) {
                logger.error(
                        "{} has exceeded its timeout of [{}ms]. Interrupting thread.\nStacktrace of current thread:\n{}",
                        taskName,
                        timeout,
                        getCurrentStackTrace());
                interrupted = true;
                thread.interrupt();
            }
        }, remainingTimeout, TimeUnit.MILLISECONDS);
    }

    /**
     * Returns the current stack trace of the thread handling the message. Cuts off the stack trace at the point where
     * the original {@link #start()} method was called.
     *
     * @return The current stack trace of the thread handling the message
     */
    private String getCurrentStackTrace() {
        StackTraceElement[] stackTrace = thread.getStackTrace();
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement element : stackTrace) {
            sb.append(element).append("\n");
            if (callerClassName != null && element.getClassName().equals(callerClassName)) {
                break;
            }
        }
        return sb.toString();
    }

    /**
     * Returns whether the task has been completed. If the task was still running, or was interrupted, this will return
     * {@code false}.
     *
     * @return {@code true} if the task has been completed successfully, {@code false} otherwise
     */
    public boolean isCompleted() {
        return completed;
    }

    /**
     * Returns whether the task has been interrupted. If the task was still running, or was completed, this will return
     * {@code false}.
     *
     * @return {@code true} if the task has been interrupted, {@code false} otherwise
     */
    public boolean isInterrupted() {
        return interrupted;
    }

    /**
     * Returns the name of the task. This is used in logging to identify the task.
     *
     * @return The name of the task.
     */
    public String getTaskName() {
        return taskName;
    }
}
