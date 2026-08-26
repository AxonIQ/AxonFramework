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

package org.axonframework.deadline.annotation;

import org.axonframework.common.annotation.Internal;
import org.axonframework.messaging.core.annotation.MessageHandlingMember;

/**
 * Interface describing a message handler capable of handling a specific deadline.
 *
 * @param <T> The type of entity to which the message handler will delegate the actual handling of the deadline
 * @author Milan Savic
 * @since 3.3.0
 */
@Internal
public interface DeadlineHandlingMember<T> extends MessageHandlingMember<T> {

}
