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


/**
 * Part of the Axon Messaging module. Defines the per-segment progress-persistence seam used by the pooled streaming
 * event processor: a {@code WorkPackage} drives a
 * {@link org.axonframework.messaging.eventhandling.processing.streaming.pooled.progress.SegmentProgressStrategy} around
 * each batch (and on claim/release) to decide which {@code TrackingToken} to persist. The default
 * {@link org.axonframework.messaging.eventhandling.processing.streaming.pooled.progress.TokenStoringProgressStrategy}
 * stores the batch-end token; advanced strategies (such as self-checkpointing) plug in through the same seam.
 */
@NullMarked
package org.axonframework.messaging.eventhandling.processing.streaming.pooled.progress;

import org.jspecify.annotations.NullMarked;
