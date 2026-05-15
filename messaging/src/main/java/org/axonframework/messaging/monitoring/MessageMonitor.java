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

package org.axonframework.messaging.monitoring;

import org.axonframework.messaging.commandhandling.CommandResultMessage;
import org.axonframework.messaging.core.Message;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Specifies a mechanism to monitor message processing. When a message is supplied to a message monitor it returns a
 * callback which should be used to notify the message monitor of the result of the processing of the event.
 * <p>
 * For example, a message monitor can track various things like message processing times, failure and success rates and
 * occurred exceptions. It also can gather information contained in messages headers like timestamps and tracers
 *
 * @param <T> the type of message to monitor
 * @author Marijn van Zelst
 * @author Nakul Mishra
 * @since 3.0
 */
public interface MessageMonitor<T extends Message> {

    /**
     * Takes a message and returns a callback that should be used to inform the message monitor about the result of
     * processing the message
     *
     * @param message the message to monitor
     * @return the callback
     */
    MonitorCallback onMessageIngested(T message);

    /**
     * Takes a collection of messages and returns a map containing events along with their callbacks
     *
     * @param messages to monitor
     * @return map where key = event and value = the callback
     */
    default Map<? super T, MonitorCallback> onMessagesIngested(Collection<? extends T> messages) {
        return messages.stream().collect(Collectors.toMap(msg -> msg, this::onMessageIngested));
    }

    /**
     * An interface to let the message processor inform the message monitor of the result of processing the message
     */
    interface MonitorCallback {

        /**
         * Notify the monitor that the message was handled successfully
         */
        void reportSuccess();

        /**
         * Notify the monitor that a failure occurred during processing of the message
         *
         * @param cause or {@code null} if unknown
         */
        void reportFailure(Throwable cause);

        /**
         * Notify the monitor that the message was ignored
         */
        void reportIgnored();

        default BiConsumer<? super CommandResultMessage, ? super @Nullable Throwable> complete() {
            return (r, e) -> {
                if (e == null) {
                    reportSuccess();
                } else {
                    reportFailure(e);
                }
            };
        }
    }
}
