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

package org.axonframework.common;

import org.axonframework.common.otherpackage.PackagePrivateMethodParent;
import org.axonframework.common.otherpackage.PublicOverride;
import org.junit.jupiter.api.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.axonframework.common.ReflectionUtils.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the {@link ReflectionUtils}.
 *
 * @author Allard Buijze
 */
class ReflectionUtilsTest {

    @Test
    void findFieldsInClass() {
        Iterable<Field> actualFields = ReflectionUtils.fieldsOf(SomeSubType.class);
        int t = 0;
        for (Field actual : actualFields) {
            if (actual.isSynthetic()) {
                // this test is probably running with ByteCode manipulation. We ignore synthetic fields.
                continue;
            }
            switch (t++) {
                case 0:
                    assertEquals("field3", actual.getName());
                    break;
                case 1:
                case 2:
                    assertTrue("field1".equals(actual.getName()) || "field2".equals(actual.getName()),
                               "Expected either field1 or field2, but got " + actual.getName()
                                       + " declared in " + actual.getDeclaringClass().getName());
                    break;
            }
        }
        assertTrue(t >= 2);
    }

    @Test
    void nonRecursivelyFindFieldsInClass() {
        Iterable<Field> actualFields = ReflectionUtils.fieldsOf(SomeSubType.class, NOT_RECURSIVE);
        int t = 0;
        for (Field actual : actualFields) {
            if (actual.isSynthetic()) {
                // this test is probably running with ByteCode manipulation. We ignore synthetic fields.
                continue;
            }
            assertEquals("field3", actual.getName());
            t++;
        }
        assertTrue(t >= 1);
    }

    @Test
    void findMethodsInClass() {
        Iterable<Method> actualMethods = ReflectionUtils.methodsOf(SomeSubType.class);
        int t = 0;
        for (Method actual : actualMethods) {
            if (actual.isSynthetic()) {
                //  this test is probably running with bytecode manipulation. We ignore synthetic methods.
                continue;
            }
            switch (t++) {
                case 0:
                    assertEquals("getField3", actual.getName());
                    break;
                case 1:
                    assertEquals("getField3", actual.getName());
                    assertEquals("SomeSubInterface", actual.getDeclaringClass().getSimpleName());
                    break;
                case 2:
                case 3:
                    assertTrue("getField1".equals(actual.getName()) || "getField2".equals(actual.getName()),
                               "Expected either getField1 or getField2, but got " + actual.getName()
                                       + " declared in " + actual.getDeclaringClass().getName());
                    break;
                case 4:
                    assertEquals("SomeInterface", actual.getDeclaringClass().getSimpleName());
                    break;
            }
        }
        assertTrue(t >= 4);
    }

    @Test
    void nonRecursivelyFindMethodsInClass() {
        Iterable<Method> actualMethods = ReflectionUtils.methodsOf(SomeSubType.class, NOT_RECURSIVE);
        int t = 0;
        for (Method actual : actualMethods) {
            if (actual.isSynthetic()) {
                //  this test is probably running with bytecode manipulation. We ignore synthetic methods.
                continue;
            }
            switch (t++) {
                case 0:
                    assertEquals("getField3", actual.getName());
                    break;
                case 1:
                    assertEquals("getField3", actual.getName());
                    assertEquals("SomeSubInterface", actual.getDeclaringClass().getSimpleName());
                    break;
            }
        }
        assertTrue(t >= 2);
    }

    @Test
    void getFieldValue() throws NoSuchFieldException {
        Object value = ReflectionUtils.getFieldValue(SomeType.class.getDeclaredField("field1"), new SomeSubType());
        assertEquals("field1", value);
    }

    @Test
    void setFieldValue() throws Exception {
        int expectedFieldValue = 4;
        SomeSubType testObject = new SomeSubType();
        ReflectionUtils.setFieldValue(SomeSubType.class.getDeclaredField("field3"), testObject, expectedFieldValue);
        assertEquals(expectedFieldValue, testObject.getField3());
    }

    @SuppressWarnings("deprecation")
    @Test
    void isAccessible() throws NoSuchFieldException {
        Field field1 = SomeType.class.getDeclaredField("field1");
        Field field2 = SomeType.class.getDeclaredField("field2");
        Field field3 = SomeSubType.class.getDeclaredField("field3");
        assertFalse(ReflectionUtils.isAccessible(field1));
        assertFalse(ReflectionUtils.isAccessible(field2));
        assertTrue(ReflectionUtils.isAccessible(field3));
    }

    @Test
    void explicitlyUnequal_NullValues() {
        assertFalse(explicitlyUnequal(null, null));
        assertTrue(explicitlyUnequal(null, ""));
        assertTrue(explicitlyUnequal("", null));
    }

    @Test
    void hasEqualsMethodTest() {
        assertTrue(hasEqualsMethod(String.class));
        assertTrue(hasEqualsMethod(ArrayList.class));
        assertFalse(hasEqualsMethod(SomeType.class));
    }

    @SuppressWarnings("StringOperationCanBeSimplified")
    @Test
    void explicitlyUnequal_ComparableValues() {
        assertFalse(explicitlyUnequal("value", new String("value")));
        assertTrue(explicitlyUnequal("value1", "value2"));
    }

    @Test
    void explicitlyUnequal_OverridesEqualsMethod() {
        assertFalse(explicitlyUnequal(Collections.singletonList("value"), Collections.singletonList("value")));
        assertTrue(explicitlyUnequal(Collections.singletonList("value1"), Collections.singletonList("value")));
    }

    @Test
    void explicitlyUnequal_NoEqualsOrComparable() {
        assertFalse(explicitlyUnequal(new SomeType(), new SomeType()));
    }

    @Test
    void resolvePrimitiveWrapperTypeForLong() {
        assertEquals(Long.class, resolvePrimitiveWrapperType(long.class));
    }

    @Test
    void getMemberValueFromField() throws NoSuchFieldException {
        assertEquals("field1",
                     ReflectionUtils.getMemberValue(SomeType.class.getDeclaredField("field1"), new SomeSubType()));
    }

    @Test
    void getMemberValueFromMethod() throws NoSuchMethodException {
        assertEquals("field1",
                     ReflectionUtils.getMemberValue(SomeType.class.getDeclaredMethod("getField1"), new SomeSubType()));
        assertEquals("someMethodResult",
                     ReflectionUtils.getMemberValue(SomeTypeWithMethods.class.getDeclaredMethod("someMethod"), new SomeTypeWithMethods()));
    }

    @Test
    void getMemberValueFromVoidMethod() throws NoSuchMethodException {
        SomeTypeWithMethods testObject = new SomeTypeWithMethods();
        Object voidReturnValue = getMemberValue(SomeTypeWithMethods.class.getDeclaredMethod("someVoidMethod"),
                                                testObject);
        assertNull(voidReturnValue);
        assertEquals(1, testObject.voidMethodInvocations.get());
    }

    @Test
    void getMemberValueFromConstructor() throws NoSuchMethodException {
        Constructor<SomeType> testConstructor = SomeType.class.getDeclaredConstructor();
        SomeSubType testTarget = new SomeSubType();
        assertThrows(IllegalStateException.class, () -> ReflectionUtils.getMemberValue(testConstructor, testTarget));
    }

    @Test
    void getMemberValueTypeFromField() throws NoSuchFieldException {
        Class<?> fieldType = getMemberValueType(SomeType.class.getDeclaredField("field1"));
        assertEquals(String.class, fieldType);
    }

    @Test
    void getMemberValueTypeFromMethod() throws NoSuchMethodException {
        Class<?> methodValueType = getMemberValueType(SomeSubType.class.getDeclaredMethod("getField3"));
        assertEquals(int.class, methodValueType);
        Class<?> voidResultType = getMemberValueType(SomeTypeWithMethods.class.getDeclaredMethod("someVoidMethod"));
        assertEquals(void.class, voidResultType);
    }

    @Test
    void getMemberValueTypeFromConstructor() throws NoSuchMethodException {
        Constructor<SomeType> testConstructor = SomeType.class.getDeclaredConstructor();
        assertThrows(IllegalStateException.class, () -> getMemberValueType(testConstructor));
    }

    @Test
    void invokeAndGetMethodValueTest() throws NoSuchMethodException {
        assertEquals("field1",
                     invokeAndGetMethodValue(SomeTypeWithMethods.class.getDeclaredMethod("getField1"), new SomeTypeWithMethods()));
        assertEquals("someMethodResult",
                     invokeAndGetMethodValue(SomeTypeWithMethods.class.getDeclaredMethod("someMethod"), new SomeTypeWithMethods()));

        SomeTypeWithMethods testObject = new SomeTypeWithMethods();
        Object voidMethodResult = invokeAndGetMethodValue(SomeTypeWithMethods.class.getDeclaredMethod("someVoidMethod"),
                                                          testObject);
        assertNull(voidMethodResult);
        assertEquals(1, testObject.voidMethodInvocations.get());
    }

    @Test
    void memberGenericTypeFromField() throws NoSuchFieldException {
        Field field = SomeType.class.getDeclaredField("field1");
        Type memberGenericType = getMemberGenericType(field);
        assertEquals(field.getGenericType(), memberGenericType);
    }

    @Test
    void memberGenericTypeFromMethod() throws NoSuchMethodException {
        Method method = SomeTypeWithMethods.class.getDeclaredMethod("someMethod");
        Type memberGenericType = getMemberGenericType(method);
        assertEquals(method.getGenericReturnType(), memberGenericType);
    }

    @Test
    void memberGenericTypeFromConstructor() throws NoSuchMethodException {
        Constructor<SomeTypeWithMethods> constructor = SomeTypeWithMethods.class.getDeclaredConstructor();
        assertThrows(IllegalStateException.class, () -> getMemberGenericType(constructor));
    }

    @Test
    void memberGenericStringFromField() throws NoSuchFieldException {
        Field field = SomeType.class.getDeclaredField("field1");
        String memberGenericString = getMemberGenericString(field);
        assertEquals(field.toGenericString(), memberGenericString);
    }

    @Test
    void memberGenericStringFromMethod() throws NoSuchMethodException {
        Method method = SomeTypeWithMethods.class.getDeclaredMethod("someMethod");
        String memberGenericString = getMemberGenericString(method);
        assertEquals(method.toGenericString(), memberGenericString);
    }

    @Test
    void memberGenericStringFromConstructor() throws NoSuchMethodException {
        Constructor<SomeTypeWithMethods> constructor = SomeTypeWithMethods.class.getDeclaredConstructor();
        String memberGenericString = getMemberGenericString(constructor);
        assertEquals(constructor.toGenericString(), memberGenericString);
    }


    @Test
    void toDiscernableSignatureOfConstructor() throws NoSuchMethodException {
        Constructor<SomeTypeWithMethods> constructor = SomeTypeWithMethods.class.getDeclaredConstructor();
        String memberGenericString = toDiscernibleSignature(constructor);
        assertEquals("org.axonframework.common.ReflectionUtilsTest$SomeTypeWithMethods()", memberGenericString);
    }

    @Test
    void toDiscernableSignatureOfMethod() throws NoSuchMethodException {
        Method method = SomeTypeWithMethods.class.getMethod("someMethodWithParameters", String.class, Integer.class, Object.class);
        String memberGenericString = toDiscernibleSignature(method);
        assertEquals("someMethodWithParameters(java.lang.String,java.lang.Integer,java.lang.Object)", memberGenericString);
    }

    @Test
    void fieldNameFromMemberReturnsFieldName() throws NoSuchFieldException {
        Field field = SomeType.class.getDeclaredField("field1");
        assertEquals("field1", ReflectionUtils.fieldNameFromMember(field));
    }

    @Test
    void fieldNameFromMemberStripsGetSetIsAndDecapitalizes() throws NoSuchMethodException {
        Method getMethod = SomeType.class.getDeclaredMethod("getField1");
        assertEquals("field1", ReflectionUtils.fieldNameFromMember(getMethod));
        Method setMethod = SomeTypeWithSetters.class.getDeclaredMethod("setField1", String.class);
        assertEquals("field1", ReflectionUtils.fieldNameFromMember(setMethod));
        Method isMethod = SomeTypeWithSetters.class.getDeclaredMethod("isActive");
        assertEquals("active", ReflectionUtils.fieldNameFromMember(isMethod));
        Method plainMethod = SomeTypeWithSetters.class.getDeclaredMethod("plainField");
        assertEquals("plainField", ReflectionUtils.fieldNameFromMember(plainMethod));
    }

    @Test
    void fieldNameFromMemberHandlesGetURLSpecialCase() throws NoSuchMethodException {
        Method getURLMethod = SomeTypeWithSetters.class.getDeclaredMethod("getURL");
        assertEquals("URL", ReflectionUtils.fieldNameFromMember(getURLMethod));
    }

    @Nested
    class CollectSealedHierarchyIfSealedTest {

        sealed interface Foo {
            record Bar() implements Foo {}
        }

        sealed interface Level0 {
            sealed class Level1a implements Level0 {
                final class Level2a extends Level1a {}
            }
            sealed class Level1b implements Level0 {
                final class Level2b extends Level1b {}
            }
        }

        @Test
        void emptyWhenTypeIsNotSealed() {
            assertThat(collectSealedHierarchyIfSealed(Object.class)).isEmpty();
        }

        @Test
        void returnsSubclassesWhenTypeIsSealed() {
            assertThat(collectSealedHierarchyIfSealed(Foo.class)).containsExactly(Foo.Bar.class);
        }

        @Test
        void returnsRecursiveSubclassesWhenTypeIsSealed() {
            assertThat(collectSealedHierarchyIfSealed(Level0.class)).containsExactlyInAnyOrder(
                    Level0.Level1a.Level2a.class,
                    Level0.Level1b.Level2b.class
            );
        }
    }

    @Nested
    class EnclosingClassesOfTest {

        @Test
        void returnsEmptyForTopLevelClass() {
            Iterable<Class<?>> result = enclosingClassesOf(String.class);

            assertThat(result).isEmpty();
        }

        @Test
        void returnsEnclosingClassForNestedClass() {
            Iterable<Class<?>> result = enclosingClassesOf(EnclosingClassFixture.InnerClass.class);

            assertThat(result).containsExactly(
                    EnclosingClassFixture.class,
                    ReflectionUtilsTest.class
            );
        }

        @Test
        void returnsAllEnclosingClassesFromInnermostToOutermost() {
            Iterable<Class<?>> result = enclosingClassesOf(EnclosingClassFixture.InnerClass.DeeplyNestedClass.class);

            assertThat(result).containsExactly(
                    EnclosingClassFixture.InnerClass.class,
                    EnclosingClassFixture.class,
                    ReflectionUtilsTest.class
            );
        }
    }

    private static class EnclosingClassFixture {

        private static class InnerClass {

            private static class DeeplyNestedClass {

            }
        }
    }

    @Nested
    class CollectAnnotatedMethodsAndFieldsTest {

        static class Foo {

            private String xfield;

            private String xmethod() {
                return null;
            }
        }

        record Bar(String xfield) {

            private String xmethod() {
                return null;
            }
        }

        static class Baz {

            private String xfield;

            private String xfield() {
                return xfield;
            }
        }

        @Test
        void findAllMethodsAndFieldsOnSimpleClass() {
            var members = collectMatchingMethodsAndFields(Foo.class, it -> it.getName().startsWith("x"));
            assertThat(members).hasSize(2);
            assertThat(members).extracting(Member::getName)
                    .containsExactlyInAnyOrder("xfield", "xmethod");
        }

        @Test
        void findAllMethodsAndFieldsOnRecord() {
            var members = collectMatchingMethodsAndFields(Bar.class, it -> it.getName().startsWith("x"));
            assertThat(members).hasSize(2);
            assertThat(members).extracting(Member::getName)
                    .containsExactlyInAnyOrder("xfield", "xmethod");
        }

        @Test
        void nonRecordFieldIsKeptForCollidingSameNamedAccessor() {
            var members = collectMatchingMethodsAndFields(Baz.class, it -> it.getName().startsWith("x"));
            assertThat(members).hasSize(2);
            assertThat(members).extracting(Member::getName)
                    .containsExactlyInAnyOrder("xfield", "xfield");
        }
    }

    private static class SomeTypeWithSetters {
        private String field1;
        private boolean active;
        private String URL;
        public String getField1() { return field1; }
        public void setField1(String value) { this.field1 = value; }
        public boolean isActive() { return active; }
        public String getURL() { return URL; }
        public String plainField() { return "plain"; }
    }

    @SuppressWarnings("FieldCanBeLocal")
    private static class SomeType implements SomeInterface {

        private final String field1 = "field1";
        private final String field2 = "field2";

        @Override
        public String getField1() {
            return field1;
        }

        public String getField2() {
            return field2;
        }
    }

    public interface SomeInterface {

        @SuppressWarnings("unused")
        String getField1();
    }

    public interface SomeSubInterface {

        int getField3();
    }

    public static class SomeSubType extends SomeType implements SomeSubInterface {

        public int field3 = 3;

        @Override
        public int getField3() {
            return field3;
        }
    }

    private static class SomeTypeWithMethods implements SomeInterface {

        public AtomicInteger voidMethodInvocations = new AtomicInteger();

        @Override
        public String getField1() {
            return "field1";
        }

        public String someMethod() {
            return "someMethodResult";
        }

        public void someVoidMethod() {
            voidMethodInvocations.incrementAndGet();
        }

        public String someMethodWithParameters(String parameterOne, Integer parameterTwo, Object parameter3) {
            return "someMethodWithParametersResult";
        }
    }

    @Nested
    class DispatchTarget {

        static class Parent {

            void on(String event) {
            }
        }

        static class Child extends Parent {

            @Override
            void on(String event) {
            }
        }

        static class ChildWithoutOverride extends Parent {

        }

        static class PrivateParent {

            @SuppressWarnings("unused")
            private void on(String event) {
            }
        }

        static class PrivateChild extends PrivateParent {

            @SuppressWarnings("unused")
            private void on(String event) {
            }
        }

        static class StaticParent {

            static void on(String event) {
            }
        }

        static class StaticChild extends StaticParent {

            static void on(String event) {
            }
        }

        static class ChildInAnotherPackage extends PackagePrivateMethodParent {

            @SuppressWarnings("unused")
            void on(String event) {
            }
        }

        static class ChildOfPublicOverride extends PublicOverride {

            @Override
            public void on(String event) {
            }
        }

        static class GenericParent<T> {

            void on(T event) {
            }
        }

        static class StringChild extends GenericParent<String> {

            @Override
            void on(String event) {
            }
        }

        static class ListChild extends GenericParent<List<String>> {

            @Override
            void on(List<String> event) {
            }
        }

        static class BoundedMid<U extends CharSequence> extends GenericParent<U> {

        }

        static class BoundedChild extends BoundedMid<String> {

            @Override
            void on(String event) {
            }
        }

        static class ArrayParent<T> {

            void on(T[] events) {
            }
        }

        static class ArrayChild extends ArrayParent<String> {

            @Override
            void on(String[] events) {
            }
        }

        static class OverloadingChild extends GenericParent<String> {

            void on(Integer event) {
            }

            @Override
            void on(String event) {
            }
        }

        interface Greeter {

            default void on(String event) {
            }
        }

        interface LoudGreeter extends Greeter {

            @Override
            default void on(String event) {
            }
        }

        static class LoudGreeterImpl implements LoudGreeter {

        }

        @Test
        void returnsOverrideDeclaredBySubclass() throws NoSuchMethodException {
            // when
            Method target = dispatchTarget(Child.class, Parent.class.getDeclaredMethod("on", String.class));

            // then
            assertThat(target).isEqualTo(Child.class.getDeclaredMethod("on", String.class));
        }

        @Test
        void returnsMethodItselfWhenNotOverridden() throws NoSuchMethodException {
            // given
            Method parentMethod = Parent.class.getDeclaredMethod("on", String.class);

            // when
            Method target = dispatchTarget(ChildWithoutOverride.class, parentMethod);

            // then
            assertThat(target).isEqualTo(parentMethod);
        }

        @Test
        void neverResolvesPrivateMethods() throws NoSuchMethodException {
            // given
            Method parentMethod = PrivateParent.class.getDeclaredMethod("on", String.class);

            // when
            Method target = dispatchTarget(PrivateChild.class, parentMethod);

            // then
            assertThat(target).isEqualTo(parentMethod);
        }

        @Test
        void neverResolvesStaticMethods() throws NoSuchMethodException {
            // given
            Method parentMethod = StaticParent.class.getDeclaredMethod("on", String.class);

            // when
            Method target = dispatchTarget(StaticChild.class, parentMethod);

            // then
            assertThat(target).isEqualTo(parentMethod);
        }

        @Test
        void doesNotResolvePackagePrivateMethodFromAnotherPackage() throws NoSuchMethodException {
            // given
            Method parentMethod = PackagePrivateMethodParent.class.getDeclaredMethod("on", String.class);

            // when
            Method target = dispatchTarget(ChildInAnotherPackage.class, parentMethod);

            // then
            assertThat(target).isEqualTo(parentMethod);
        }

        @Test
        void resolvesOverrideChainThroughPublicOverrideInAnotherPackage() throws NoSuchMethodException {
            // given - PublicOverride makes the package-private method public, so this package can override it again
            Method parentMethod = PackagePrivateMethodParent.class.getDeclaredMethod("on", String.class);

            // when
            Method target = dispatchTarget(ChildOfPublicOverride.class, parentMethod);

            // then
            assertThat(target).isEqualTo(ChildOfPublicOverride.class.getDeclaredMethod("on", String.class));
        }

        @Test
        void resolvesBridgeOfGenericParentToBridgedMethod() throws NoSuchMethodException {
            // given - javac adds a bridge on(Object) to StringChild that calls on(String)
            Method parentMethod = GenericParent.class.getDeclaredMethod("on", Object.class);

            // when
            Method target = dispatchTarget(StringChild.class, parentMethod);

            // then
            assertThat(target).isEqualTo(StringChild.class.getDeclaredMethod("on", String.class));
        }

        @Test
        void resolvesBridgeOfGenericParentWithParameterizedTypeArgument() throws NoSuchMethodException {
            // when
            Method target = dispatchTarget(ListChild.class, GenericParent.class.getDeclaredMethod("on", Object.class));

            // then
            assertThat(target).isEqualTo(ListChild.class.getDeclaredMethod("on", List.class));
        }

        @Test
        void resolvesBridgeOfGenericParentThroughTypeVariableOfIntermediateClass() throws NoSuchMethodException {
            // when
            Method target = dispatchTarget(BoundedChild.class,
                                           GenericParent.class.getDeclaredMethod("on", Object.class));

            // then
            assertThat(target).isEqualTo(BoundedChild.class.getDeclaredMethod("on", String.class));
        }

        @Test
        void resolvesBridgeOfGenericParentWithGenericArrayParameter() throws NoSuchMethodException {
            // when
            Method target = dispatchTarget(ArrayChild.class, ArrayParent.class.getDeclaredMethod("on", Object[].class));

            // then
            assertThat(target).isEqualTo(ArrayChild.class.getDeclaredMethod("on", String[].class));
        }

        @Test
        void resolvesBridgeToTheMatchingOverload() throws NoSuchMethodException {
            // when
            Method target = dispatchTarget(OverloadingChild.class,
                                           GenericParent.class.getDeclaredMethod("on", Object.class));

            // then
            assertThat(target).isEqualTo(OverloadingChild.class.getDeclaredMethod("on", String.class));
        }

        @Test
        void returnsMostSpecificInterfaceDefault() throws NoSuchMethodException {
            // when
            Method target = dispatchTarget(LoudGreeterImpl.class, Greeter.class.getDeclaredMethod("on", String.class));

            // then
            assertThat(target).isEqualTo(LoudGreeter.class.getDeclaredMethod("on", String.class));
        }
    }

    @Nested
    class AllSupertypes {

        interface Root {

        }

        interface Leaf extends Root {

        }

        interface Other {

        }

        static class Base implements Leaf {

        }

        static class Sub extends Base implements Other {

        }

        @Test
        void includesSuperclassesAndInterfacesTransitivelyWithoutTheTypeItself() {
            // when
            Set<Class<?>> supertypes = allSupertypes(Sub.class);

            // then
            assertThat(supertypes).containsExactlyInAnyOrder(Base.class, Object.class, Leaf.class, Root.class,
                                                             Other.class);
        }
    }
}
