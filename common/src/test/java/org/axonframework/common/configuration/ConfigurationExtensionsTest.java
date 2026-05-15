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

package org.axonframework.common.configuration;

import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.infra.ComponentDescriptor;
import org.axonframework.common.infra.MockComponentDescriptor;
import org.junit.jupiter.api.*;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationExtensionsTest {

    private StubExtendedConfiguration owner;

    @BeforeEach
    void setUp() {
        owner = new StubExtendedConfiguration();
    }

    @Nested
    class WhenExtending {

        @Test
        void createsExtensionFromRegisteredFactory() {
            // given
            owner.extend(StubExtension.class, StubExtension::new);

            // when
            StubExtension result = owner.extension(StubExtension.class);

            // then
            assertThat(result).isNotNull();
        }

        @Test
        void returnsSameInstanceOnSubsequentAccess() {
            // given
            owner.extend(StubExtension.class, StubExtension::new);

            // when
            StubExtension first = owner.extension(StubExtension.class);
            StubExtension second = owner.extension(StubExtension.class);

            // then
            assertThat(first).isSameAs(second);
        }

        @Test
        void returnsNullForUnregisteredExtension() {
            // when
            StubExtension result = owner.extension(StubExtension.class);

            // then
            assertThat(result).isNull();
        }

        @Test
        void lastFactoryWinsWhenRegisteredMultipleTimes() {
            // given
            owner.extend(StubExtension.class, StubExtension::new);
            var replacement = new StubExtension();
            owner.extend(StubExtension.class, () -> replacement);

            // when
            StubExtension result = owner.extension(StubExtension.class);

            // then
            assertThat(result).isSameAs(replacement);
        }

        @Test
        void supportsDifferentExtensionTypes() {
            // given
            owner.extend(StubExtension.class, StubExtension::new);
            owner.extend(AnotherStubExtension.class, AnotherStubExtension::new);

            // when
            StubExtension stubExtension = owner.extension(StubExtension.class);
            AnotherStubExtension anotherExtension = owner.extension(AnotherStubExtension.class);

            // then
            assertThat(stubExtension).isNotNull();
            assertThat(anotherExtension).isNotNull();
            assertThat(stubExtension).isNotSameAs(anotherExtension);
        }
    }

    @Nested
    class WhenValidating {

        @Test
        void validatesAllExtensions() {
            // given
            owner.extend(StubExtension.class, StubExtension::new);
            owner.extend(AnotherStubExtension.class, AnotherStubExtension::new);

            // when
            owner.extensions.validate();

            // then
            StubExtension stubExtension = owner.extension(StubExtension.class);
            AnotherStubExtension anotherExtension = owner.extension(AnotherStubExtension.class);
            assertThat(stubExtension.validated).isTrue();
            assertThat(anotherExtension.validated).isTrue();
        }
    }

    @Nested
    class WhenDescribing {

        @Test
        void describesExtensionsAsNamedMap() {
            // given
            owner.extend(StubExtension.class, StubExtension::new);
            owner.extend(AnotherStubExtension.class, AnotherStubExtension::new);
            var stubExtension = owner.extension(StubExtension.class);
            var anotherExtension = owner.extension(AnotherStubExtension.class);
            var descriptor = new MockComponentDescriptor();

            // when
            owner.extensions.describeTo(descriptor);

            // then
            assertThat(descriptor.getDescribedProperties()).containsKey("stubExtension");
            assertThat(descriptor.getDescribedProperties()).containsKey("anotherStubExtension");
            assertThat(descriptor.getDescribedProperties().get("stubExtension")).isSameAs(stubExtension);
            assertThat(descriptor.getDescribedProperties().get("anotherStubExtension")).isSameAs(anotherExtension);
        }

        @Test
        void extensionDescribesItsOwnProperties() {
            // given
            owner.extend(StubExtension.class, StubExtension::new);
            var descriptor = new MockComponentDescriptor();

            // when
            owner.extension(StubExtension.class).describeTo(descriptor);

            // then
            assertThat(descriptor.getDescribedProperties()).containsEntry("stub", "value");
        }
    }


    @Nested
    class WhenCopyingMissing {

        @Test
        void copiesExtensionsNotPresentOnTarget() {
            // given
            owner.extend(StubExtension.class, StubExtension::new);
            owner.extend(AnotherStubExtension.class, AnotherStubExtension::new);
            var target = new StubExtendedConfiguration();

            // when
            target.extensions.extendFrom(owner.extensions);

            // then
            assertThat(target.extension(StubExtension.class))
                    .isSameAs(owner.extension(StubExtension.class));
            assertThat(target.extension(AnotherStubExtension.class))
                    .isSameAs(owner.extension(AnotherStubExtension.class));
        }

        @Test
        void doesNotOverrideExistingExtensionsOnTarget() {
            // given
            owner.extend(StubExtension.class, StubExtension::new);
            var target = new StubExtendedConfiguration();
            target.extend(StubExtension.class, StubExtension::new);
            var targetOriginal = target.extension(StubExtension.class);

            // when
            target.extensions.extendFrom(owner.extensions);

            // then
            assertThat(target.extension(StubExtension.class)).isSameAs(targetOriginal);
            assertThat(target.extension(StubExtension.class))
                    .isNotSameAs(owner.extension(StubExtension.class));
        }

        @Test
        void copiesOnlyMissingWhenTargetHasSomeExtensions() {
            // given
            owner.extend(StubExtension.class, StubExtension::new);
            owner.extend(AnotherStubExtension.class, AnotherStubExtension::new);
            var target = new StubExtendedConfiguration();
            target.extend(StubExtension.class, StubExtension::new);
            var targetStub = target.extension(StubExtension.class);

            // when
            target.extensions.extendFrom(owner.extensions);

            // then — StubExtension preserved, AnotherStubExtension copied from source
            assertThat(target.extension(StubExtension.class)).isSameAs(targetStub);
            assertThat(target.extension(AnotherStubExtension.class))
                    .isSameAs(owner.extension(AnotherStubExtension.class));
        }
    }

    // -- test fixtures --

    static class StubExtendedConfiguration implements ExtendedConfiguration, ExtensibleConfigurer {

        final ConfigurationExtensions extensions = new ConfigurationExtensions(this);

        @Override
        public <T extends ConfigurationExtension<?>> T extension(Class<T> extensionType) {
            return extensions.extension(extensionType);
        }

        @Override
        public <T extends ConfigurationExtension<?>> ExtensibleConfigurer extend(
                Class<T> extensionType,
                Supplier<T> factory
        ) {
            return extensions.extend(extensionType, factory);
        }
    }

    static class StubExtension implements ConfigurationExtension<StubExtendedConfiguration> {

        boolean validated = false;

        StubExtension() {
        }

        @Override
        public String name() {
            return "stubExtension";
        }

        @Override
        public void validate() throws AxonConfigurationException {
            validated = true;
        }

        @Override
        public void describeTo(ComponentDescriptor descriptor) {
            descriptor.describeProperty("stub", "value");
        }
    }

    static class AnotherStubExtension implements ConfigurationExtension<StubExtendedConfiguration> {

        boolean validated = false;

        AnotherStubExtension() {
        }

        @Override
        public String name() {
            return "anotherStubExtension";
        }

        @Override
        public void validate() throws AxonConfigurationException {
            validated = true;
        }

        @Override
        public void describeTo(ComponentDescriptor descriptor) {
            descriptor.describeProperty("another", "data");
        }
    }
}
