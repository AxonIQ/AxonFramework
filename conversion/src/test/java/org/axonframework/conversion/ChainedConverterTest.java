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

package org.axonframework.conversion;

import org.apache.commons.io.IOUtils;
import org.axonframework.conversion.ChainedConverter;
import org.axonframework.conversion.ContentTypeConverter;
import org.axonframework.conversion.ConversionException;
import org.junit.jupiter.api.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.lang.annotation.Documented;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test class validating the {@link ChainedConverter}.
 *
 * @author Allard Buijze
 */
class ChainedConverterTest {

    private List<ContentTypeConverter<?, ?>> candidates;
    private ContentTypeConverter<?, ?> stringToByteConverter;
    private ContentTypeConverter<?, ?> stringToReaderConverter;
    private ContentTypeConverter<?, ?> bytesToInputStreamConverter;
    private ContentTypeConverter<?, ?> numberToStringConverter;

    private Object source;
    private Class<?> target;

    @SuppressWarnings("rawtypes")
    private ContentTypeConverter testSubject;

    @BeforeEach
    void setUp() {
        this.candidates = new ArrayList<>();
        this.stringToByteConverter = mockConverter(String.class, byte[].class, "hello".getBytes());
        this.stringToReaderConverter = mockConverter(String.class, Reader.class, new StringReader("hello"));
        this.bytesToInputStreamConverter = mockConverter(
                byte[].class, InputStream.class, new ByteArrayInputStream("hello".getBytes())
        );
        this.numberToStringConverter = mockConverter(Number.class, String.class, "hello");

        this.candidates.add(stringToByteConverter);
        this.candidates.add(stringToReaderConverter);
        this.candidates.add(numberToStringConverter);
        this.candidates.add(bytesToInputStreamConverter);
        this.candidates.add(mockConverter(InputStream.class, byte[].class, "hello".getBytes()));
    }

    private ContentTypeConverter<?, ?> mockConverter(Class<?> expectedType,
                                                     Class<?> targetType,
                                                     Object representation) {
        //noinspection rawtypes
        ContentTypeConverter mock = mock(ContentTypeConverter.class);
        when(mock.expectedSourceType()).thenReturn(expectedType);
        when(mock.targetType()).thenReturn(targetType);
        //noinspection unchecked
        when(mock.convert(any())).thenReturn(representation);
        return mock;
    }

    @Test
    void validateSourceAndTargetType() {
        Class<Number> testSourceType = Number.class;
        Class<InputStream> testTargetType = InputStream.class;

        testSubject = ChainedConverter.calculateChain(testSourceType, testTargetType, candidates);

        assertEquals(testSourceType, testSubject.expectedSourceType());
        assertEquals(testTargetType, testSubject.targetType());
    }

    @Test
    void complexRoute() throws Exception {
        target = InputStream.class;
        source = 1L;
        testSubject = ChainedConverter.calculateChain(Number.class, target, candidates);
        assertNotNull(testSubject);
        verify(numberToStringConverter, never()).convert(any());
        verify(stringToReaderConverter, never()).convert(any());
        verify(bytesToInputStreamConverter, never()).convert(any());
        verify(stringToByteConverter, never()).convert(any());

        InputStream actual = convertSource();
        assertNotNull(actual);
        assertArrayEquals("hello".getBytes(), IOUtils.toByteArray(actual));

        verify(numberToStringConverter).convert(any());
        verify(stringToByteConverter).convert(any());
        verify(bytesToInputStreamConverter).convert(any());
        verify(stringToReaderConverter, never()).convert(any());
    }

    @Test
    void simpleRoute() {
        target = String.class;
        source = 1L;
        testSubject = ChainedConverter.calculateChain(Number.class, target, candidates);
        assertNotNull(testSubject);
        verify(numberToStringConverter, never()).convert(any());
        verify(stringToReaderConverter, never()).convert(any());
        verify(bytesToInputStreamConverter, never()).convert(any());
        verify(stringToByteConverter, never()).convert(any());

        String actual = convertSource();
        assertEquals("hello", actual);

        verify(numberToStringConverter).convert(any());
        verify(stringToReaderConverter, never()).convert(any());
        verify(bytesToInputStreamConverter, never()).convert(any());
        verify(stringToByteConverter, never()).convert(any());
    }

    private <T> T convertSource() {
        //noinspection unchecked
        return (T) testSubject.convert(source);
    }

    @Test
    void routeForAssignableSourceSubclass() {
        source = new ByteArrayInputStream("hello".getBytes());
        target = byte[].class;
        testSubject = ChainedConverter.calculateChain(source.getClass(), target, candidates);
        assertNotNull(testSubject);
    }

    static class TestGenericSuperType {
    }

    static class TestSpecificSubtype extends TestGenericSuperType {
    }

    @Test
    void routeForAssignableTargetSuperclass() {
        source = "test";
        target = TestGenericSuperType.class;
        candidates.add(mockConverter(String.class, TestSpecificSubtype.class, new TestSpecificSubtype()));
        testSubject = ChainedConverter.calculateChain(source.getClass(), target, candidates);
        assertNotNull(testSubject);
    }

    @Test
    void inexistentRoute() {
        target = InputStream.class;
        source = new StringReader("hello");
        assertThrows(ConversionException.class,
                     () -> ChainedConverter.calculateChain(Reader.class, target, candidates));
    }

    // Detects an issue where the ChainedConverter hangs as it evaluates a recursive route
    @Test
    void anotherInexistentRoute() {
        target = Number.class;
        source = "hello";
        assertFalse(ChainedConverter.canConvert(String.class, target, candidates));
        assertThrows(ConversionException.class,
                     () -> ChainedConverter.calculateChain(String.class, target, candidates));
    }

    @Test
    void aThirdInexistentRoute() {
        target = Documented.class;
        source = "hello".getBytes();
        assertThrows(ConversionException.class,
                     () -> ChainedConverter.calculateChain(byte[].class, target, candidates));
    }

    @Test
    void discontinuousChainIsRejected() {
        //noinspection rawtypes,unchecked
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> new ChainedConverter(Arrays.<ContentTypeConverter>asList(
                        numberToStringConverter, bytesToInputStreamConverter
                ))
        );
        assertTrue(e.getMessage().contains("continuous chain"), "Wrong message: " + e.getMessage());
    }
}
