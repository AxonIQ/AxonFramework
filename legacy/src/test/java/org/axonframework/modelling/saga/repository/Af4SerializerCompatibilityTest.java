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

package org.axonframework.modelling.saga.repository;

import org.axonframework.conversion.jackson.JacksonConverter;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies compatibility between the serialized representation produced by Axon Framework 4 and the converter used by
 * the legacy saga stores.
 *
 * @author Mateusz Nowak
 */
class Af4SerializerCompatibilityTest {

    private static final Path AF4_MESSAGING_JAR = Path.of(
            "target", "af4-serializer", "axon-messaging-af4.jar"
    );

    @Nested
    class JacksonSerialization {

        @Test
        void sagaSerializedByAf4SerializerIsConvertedByAf5Converter() throws Exception {
            // given
            StubSaga saga = new StubSaga();
            saga.handled("OrderPlaced");
            saga.handled("OrderPaid");

            // when
            Af4SerializedObject serialized = serializeWithAf4JacksonSerializer(saga);
            StubSaga converted = new JacksonConverter().convert(serialized.data(), StubSaga.class);

            // then
            assertThat(serialized.typeName()).isEqualTo(StubSaga.class.getName());
            assertThat(converted).isEqualTo(saga);
        }
    }

    private static Af4SerializedObject serializeWithAf4JacksonSerializer(Object value) throws Exception {
        assertThat(AF4_MESSAGING_JAR).isRegularFile();
        URL serializerJar = AF4_MESSAGING_JAR.toUri().toURL();
        try (URLClassLoader classLoader = new AxonFramework4ClassLoader(serializerJar)) {
            Class<?> serializerContract = classLoader.loadClass("org.axonframework.serialization.Serializer");
            Class<?> serializerType = classLoader.loadClass(
                    "org.axonframework.serialization.json.JacksonSerializer"
            );
            Object serializer = serializerType.getMethod("defaultSerializer").invoke(null);
            assertThat(serializer).isInstanceOf(serializerContract);

            Object serialized = serializerType.getMethod("serialize", Object.class, Class.class)
                                              .invoke(serializer, value, byte[].class);
            Class<?> serializedObjectType = classLoader.loadClass(
                    "org.axonframework.serialization.SerializedObject"
            );
            byte[] data = (byte[]) serializedObjectType.getMethod("getData").invoke(serialized);
            Object type = serializedObjectType.getMethod("getType").invoke(serialized);
            Method getName = type.getClass().getMethod("getName");
            return new Af4SerializedObject(data, (String) getName.invoke(type));
        }
    }

    private record Af4SerializedObject(byte[] data, String typeName) {
    }

    private static final class AxonFramework4ClassLoader extends URLClassLoader {

        private AxonFramework4ClassLoader(URL serializerJar) {
            super(new URL[]{serializerJar}, Af4SerializerCompatibilityTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loadedClass = findLoadedClass(name);
                if (loadedClass == null && name.startsWith("org.axonframework.")) {
                    try {
                        loadedClass = findClass(name);
                    } catch (ClassNotFoundException ignored) {
                        // The saga test fixture is an AF5 class and therefore comes from the parent class loader.
                    }
                }
                if (loadedClass == null) {
                    loadedClass = super.loadClass(name, false);
                }
                if (resolve) {
                    resolveClass(loadedClass);
                }
                return loadedClass;
            }
        }
    }
}
