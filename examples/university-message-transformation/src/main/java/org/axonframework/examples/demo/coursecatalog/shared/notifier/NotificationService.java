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

package org.axonframework.examples.demo.coursecatalog.shared.notifier;

/**
 * Outbound side effect port for automations. The default
 * {@link LoggingNotificationService} just logs; tests substitute a recording
 * implementation to assert that the automation fired.
 */
public interface NotificationService {

    /**
     * @param notification the notification to send
     */
    void send(Notification notification);

    /**
     * @param topic short topic label (e.g. {@code "overbooking-risk"})
     * @param body  human-readable message body
     */
    record Notification(String topic, String body) {
    }
}
