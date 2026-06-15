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

package org.axonframework.examples.demo.coursecatalog.catalog.chain;

import io.axoniq.framework.messaging.transformation.ChainConfigurationException;
import io.axoniq.framework.messaging.transformation.events.EventTransformer;
import io.axoniq.framework.messaging.transformation.events.EventTransformerChain;
import org.axonframework.examples.demo.coursecatalog.catalog.transformations.CoursePublishedV1ToV2;
import org.axonframework.examples.demo.coursecatalog.catalog.transformations.CoursePublishedV2ToV3;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChainLockingTest {

    @Test
    void registrationAfterBuildThrowsChainConfigurationException() {
        EventTransformerChain.Builder builder = EventTransformerChain.builder()
                                                                     .register(CoursePublishedV1ToV2.build());
        builder.build();
        EventTransformer secondTransformer = CoursePublishedV2ToV3.build();

        assertThatThrownBy(() -> builder.register(secondTransformer))
                .isInstanceOf(ChainConfigurationException.class)
                .hasMessageContaining("build");
    }
}
