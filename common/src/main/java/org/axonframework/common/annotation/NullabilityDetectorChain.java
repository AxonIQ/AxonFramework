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

package org.axonframework.common.annotation;

import java.lang.reflect.Parameter;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.StreamSupport;

/**
 * Holds the {@link NullabilityDetector} instances discovered through the {@link ServiceLoader}, ordered by descending
 * {@link NullabilityDetector#priority()}.
 * <p>
 * Kept package-private and separate from {@link NullabilityDetector} because an interface cannot hold the private
 * static state this loading requires. Callers reach this through
 * {@link NullabilityDetector#nullabilityOf(Parameter)}.
 *
 * @author Mateusz Nowak
 * @since 5.3.0
 */
final class NullabilityDetectorChain {

    private static final List<NullabilityDetector> DETECTORS = load();

    private NullabilityDetectorChain() {
        // Utility class, not meant to be instantiated.
    }

    private static List<NullabilityDetector> load() {
        return StreamSupport.stream(ServiceLoader.load(NullabilityDetector.class).spliterator(), false)
                            .sorted(Comparator.comparingInt(NullabilityDetector::priority).reversed())
                            .toList();
    }

    static Nullability detect(Parameter parameter) {
        for (NullabilityDetector detector : DETECTORS) {
            Nullability nullability = detector.detect(parameter);
            if (nullability != Nullability.UNKNOWN) {
                return nullability;
            }
        }
        return Nullability.UNKNOWN;
    }
}
