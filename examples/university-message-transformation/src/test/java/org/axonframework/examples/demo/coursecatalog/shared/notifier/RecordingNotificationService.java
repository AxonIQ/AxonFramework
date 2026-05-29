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

import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test double for {@link NotificationService} that records every notification it
 * receives so tests can assert against them.
 */
public final class RecordingNotificationService implements NotificationService {

    private final List<Notification> received = new CopyOnWriteArrayList<>();

    @Override
    public void send(@NonNull Notification notification) {
        received.add(notification);
    }

    /** @return every notification sent so far, in arrival order */
    public List<Notification> received() {
        return List.copyOf(received);
    }
}
