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

package org.axonframework.messaging.core;

/**
 * A {@link VersionSpecifier} that matches any version. This is the default specifier used when a
 * {@link MessageHandler} does not restrict the versions it supports.
 *
 * @author Ishaan Bhela
 * @since 5.4.0
 */
final class AnyVersionSpecifier implements VersionSpecifier {

    static final AnyVersionSpecifier INSTANCE = new AnyVersionSpecifier();

    private AnyVersionSpecifier() {
    }

    @Override
    public boolean matches(String version) {
        return true;
    }

    @Override
    public boolean overlaps(VersionSpecifier other) {
        return true;
    }

    @Override
    public String toString() {
        return "VersionSpecifier.any()";
    }
}
