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

import org.junit.jupiter.api.*;

import java.lang.annotation.Annotation;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.axonframework.common.annotation.AnnotationUtils.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the {@link AnnotationUtils}
 *
 * @author Allard Buijze
 */
class AnnotationUtilsTest {

    @Test
    void findAttributesOnDirectAnnotation() throws NoSuchMethodException {
        Optional<Map<String, Object>> optionalResult =
                findAnnotationAttributes(getClass().getDeclaredMethod("directAnnotated"), TheTarget.class);
        assertTrue(optionalResult.isPresent());
        Map<String, Object> results = optionalResult.get();

        assertEquals("value", results.get("property"));
        assertFalse(results.containsKey("value"), "value property should use annotation Simple class name as key");
        assertEquals("value()", results.get("theTarget"));
    }

    @Test
    void findAttributesOnStaticMetaAnnotation() throws NoSuchMethodException {
        Optional<Map<String, Object>> optionalResult =
                findAnnotationAttributes(getClass().getDeclaredMethod("staticallyOverridden"), TheTarget.class);
        assertTrue(optionalResult.isPresent());
        Map<String, Object> results = optionalResult.get();

        assertEquals("overridden_statically", results.get("property"));
    }

    @Test
    void findAttributesOnDynamicMetaAnnotation() throws NoSuchMethodException {
        Optional<Map<String, Object>> optionalResult =
                findAnnotationAttributes(getClass().getDeclaredMethod("dynamicallyOverridden"), TheTarget.class);
        assertTrue(optionalResult.isPresent());
        Map<String, Object> results = optionalResult.get();

        assertEquals("dynamic-override", results.get("property"));
        assertEquals("extra", results.get("extraValue"));
    }

    @Test
    void findAttributesOnDynamicMetaAnnotationUsingAnnotationName() throws NoSuchMethodException {
        Optional<Map<String, Object>> optionalResult = findAnnotationAttributes(
                getClass().getDeclaredMethod("dynamicallyOverridden"), TheTarget.class.getName()
        );
        assertTrue(optionalResult.isPresent());
        Map<String, Object> results = optionalResult.get();

        assertEquals("dynamic-override", results.get("property"));
        assertEquals("extra", results.get("extraValue"));
        assertEquals("otherValue", results.get("theTarget"));
    }

    @Test
    void findAnnotationAttributesOnlyReturnsTargetAttributesAndOverridesForClassAnnotation()
            throws NoSuchMethodException {
        Method annotatedElement = getClass().getDeclaredMethod("dynamicallyOverridden");

        Map<String, Object> expected = new HashMap<>();
        expected.put("property", "dynamic-override");
        expected.put("theTarget", "otherValue");

        Optional<Map<String, Object>> result =
                findAnnotationAttributes(annotatedElement, TheTarget.class, OVERRIDE_ONLY);

        assertTrue(result.isPresent());
        assertEquals(expected, result.get());
    }

    @Test
    void findAnnotationAttributesOnlyReturnsTargetAttributesAndOverridesForStringAnnotation()
            throws NoSuchMethodException {
        Method annotatedElement = getClass().getDeclaredMethod("someAnnotatedMethod");

        Map<String, Object> expected = new HashMap<>();
        expected.put("property", "some-property");
        expected.put("theTarget", "otherValue");

        Optional<Map<String, Object>> result =
                findAnnotationAttributes(annotatedElement, TheTarget.class.getName(), OVERRIDE_ONLY);

        assertTrue(result.isPresent());
        assertEquals(expected, result.get());
    }

    @Test
    void isAnnotatedWithReturnsTrue() {
        Set<Class<? extends Annotation>> expectedAnnotatedWithSubject = new HashSet<>();
        expectedAnnotatedWithSubject.add(DynamicOverrideAnnotated.class);
        expectedAnnotatedWithSubject.add(AnotherMetaAnnotation.class);
        Set<Class<? extends Annotation>> expectedVisited = new HashSet<>();
        expectedVisited.add(DynamicOverrideAnnotated.class);
        expectedVisited.add(AnotherMetaAnnotation.class);
        expectedVisited.add(TheTarget.class);
        expectedVisited.add(Retention.class);
        expectedVisited.add(Target.class);
        expectedVisited.add(Documented.class);

        Set<Class<? extends Annotation>> resultAnnotatedWithSubject = new HashSet<>();
        Set<Class<? extends Annotation>> resultVisited = new HashSet<>();
        boolean result = isAnnotatedWith(
                AnotherMetaAnnotation.class, TheTarget.class, resultAnnotatedWithSubject, resultVisited
        );

        assertTrue(result);
        assertEquals(expectedAnnotatedWithSubject, resultAnnotatedWithSubject);
        assertEquals(expectedVisited, resultVisited);
    }

    @Test
    void isAnnotatedWithReturnsFalse() {
        Set<Class<? extends Annotation>> expectedAnnotatedWithSubject = Collections.emptySet();
        Set<Class<? extends Annotation>> expectedVisited = new HashSet<>();
        expectedVisited.add(NotMetaAnnotated.class);
        expectedVisited.add(Documented.class);
        // Retention and Target are added as those are meta-annotation on Documented
        expectedVisited.add(Retention.class);
        expectedVisited.add(Target.class);

        Set<Class<? extends Annotation>> resultAnnotatedWithSubject = new HashSet<>();
        Set<Class<? extends Annotation>> resultVisited = new HashSet<>();
        boolean result = isAnnotatedWith(
                NotMetaAnnotated.class, TheTarget.class, resultAnnotatedWithSubject, resultVisited
        );

        assertFalse(result);
        assertEquals(expectedAnnotatedWithSubject, resultAnnotatedWithSubject);
        assertEquals(expectedVisited, resultVisited);
    }

    @Test
    void isAnnotatedTrueWhenAnnotationExists() throws NoSuchMethodException {
        var method = AnnotationUtilsTest.class.getDeclaredMethod("directAnnotated");
        assertThat(isAnnotatedWith(TheTarget.class)).accepts(method);
    }

    @Test
    void isAnnotatedFalseWhenAnnotationNotExists() throws NoSuchMethodException {
        var method = AnnotationUtilsTest.class.getDeclaredMethod("directAnnotated");
        assertThat(isAnnotatedWith(AnotherMetaAnnotation.class)).rejects(method);
    }

    @Test
    void isTypeAnnotatedWith() {
        var predicate = AnnotationUtils.isTypeAnnotatedWith(TheTypeTarget.class);
        assertThat(predicate.test(new SomeType())).isTrue();
        assertThat(predicate.test(new SomeMetaAnnotatedType())).isTrue();
        assertThat(predicate.test("")).isFalse();
    }

    @Test
    void isTypeAnnotatedHavingAttributeValue() {
        var predicate = AnnotationUtils.isTypeAnnotatedWithHavingAttributeValue(TheTypeTarget.class, "property", "selected");
        assertThat(predicate.test(new SomeType())).isFalse();
        assertThat(predicate.test(new SomeType2())).isTrue();
        assertThat(predicate.test(new SomeMetaAnnotatedType())).isFalse();
        assertThat(predicate.test(new SomeMetaAnnotatedType2())).isTrue();
        assertThat(predicate.test("")).isFalse();
    }

    @TheTypeTarget
    static class SomeType {

    }

    @TheTypeTarget(property = "selected")
    static class SomeType2 {

    }


    @MetaAnnotatedTypeTarget
    static class SomeMetaAnnotatedType {

    }

    @MetaAnnotatedTypeTarget(property = "selected")
    static class SomeMetaAnnotatedType2 {

    }

    @TheTarget
    public void directAnnotated() {
    }

    @StaticOverrideAnnotated
    public void staticallyOverridden() {
    }

    @DynamicOverrideAnnotated(property = "dynamic-override")
    public void dynamicallyOverridden() {
    }

    @AnotherMetaAnnotation
    public void someAnnotatedMethod() {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.ANNOTATION_TYPE, ElementType.METHOD})
    public @interface TheTarget {

        String property() default "value";

        String value() default "value()";
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.ANNOTATION_TYPE, ElementType.TYPE})
    public @interface TheTypeTarget {
        String property() default "default";
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE})
    @TheTypeTarget
    public @interface MetaAnnotatedTypeTarget {
        String property() default "otherDefault";
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.ANNOTATION_TYPE, ElementType.METHOD})
    @TheTarget(property = "overridden_statically")
    public @interface StaticOverrideAnnotated {

    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.ANNOTATION_TYPE, ElementType.METHOD})
    @TheTarget
    public @interface DynamicOverrideAnnotated {

        String property();

        String extraValue() default "extra";

        String theTarget() default "otherValue";
    }

    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @DynamicOverrideAnnotated(property = "some-property")
    public @interface AnotherMetaAnnotation {

    }

    @Documented
    public @interface NotMetaAnnotated {

    }

    @Nested
    class FindAnnotationAttributesOnTypeTest {

        private static final String ATTRIBUTE_KEY = "layeredAnnotation";

        @Test
        void findsAnnotationOnTypeItself() {
            Optional<Map<String, Object>> result = findAnnotationAttributesOnType(
                    DirectlyAnnotatedClass.class,
                    LayeredAnnotation.class,
                    attrs -> hasNonEmptyAttribute(attrs, ATTRIBUTE_KEY)
            );

            assertThat(result).isPresent();
            assertThat(result.get().get(ATTRIBUTE_KEY)).isEqualTo("direct");
        }

        @Test
        void findsAnnotationOnEnclosingClass() {
            Optional<Map<String, Object>> result = findAnnotationAttributesOnType(
                    AnnotatedContainer.NestedClass.class,
                    LayeredAnnotation.class,
                    attrs -> hasNonEmptyAttribute(attrs, ATTRIBUTE_KEY)
            );

            assertThat(result).isPresent();
            assertThat(result.get().get(ATTRIBUTE_KEY)).isEqualTo("container");
        }

        @Test
        void findsAnnotationOnOutermostEnclosingClass() {
            Optional<Map<String, Object>> result = findAnnotationAttributesOnType(
                    AnnotatedContainer.NestedClass.DeeplyNestedClass.class,
                    LayeredAnnotation.class,
                    attrs -> hasNonEmptyAttribute(attrs, ATTRIBUTE_KEY)
            );

            assertThat(result).isPresent();
            assertThat(result.get().get(ATTRIBUTE_KEY)).isEqualTo("container");
        }

        @Test
        void prefersAnnotationOnInnerClassOverOuterClass() {
            Optional<Map<String, Object>> result = findAnnotationAttributesOnType(
                    AnnotatedContainer.AnnotatedNestedClass.class,
                    LayeredAnnotation.class,
                    attrs -> hasNonEmptyAttribute(attrs, ATTRIBUTE_KEY)
            );

            assertThat(result).isPresent();
            assertThat(result.get().get(ATTRIBUTE_KEY)).isEqualTo("nested");
        }

        @Test
        void returnsEmptyWhenAnnotationNotFound() {
            Optional<Map<String, Object>> result = findAnnotationAttributesOnType(
                    UnannotatedClass.class,
                    LayeredAnnotation.class,
                    attrs -> hasNonEmptyAttribute(attrs, ATTRIBUTE_KEY)
            );

            assertThat(result).isEmpty();
        }

        @Test
        void continuesSearchWhenPredicateReturnsFalse() {
            // AnnotatedContainer has LayeredAnnotation("container"), NestedWithEmptyAnnotation has LayeredAnnotation("")
            // The predicate requires non-empty value, so it should find the container's annotation
            Optional<Map<String, Object>> result = findAnnotationAttributesOnType(
                    AnnotatedContainer.NestedWithEmptyAnnotation.class,
                    LayeredAnnotation.class,
                    attrs -> hasNonEmptyAttribute(attrs, ATTRIBUTE_KEY)
            );

            assertThat(result).isPresent();
            assertThat(result.get().get(ATTRIBUTE_KEY)).isEqualTo("container");
        }

        private boolean hasNonEmptyAttribute(Map<String, Object> attrs, String key) {
            Object value = attrs.get(key);
            return value instanceof String s && !s.isEmpty();
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target({ElementType.TYPE, ElementType.PACKAGE, ElementType.MODULE}) @interface LayeredAnnotation {

            String value() default "";
        }

        @LayeredAnnotation("direct")
        private static class DirectlyAnnotatedClass {

        }

        @LayeredAnnotation("container")
        private static class AnnotatedContainer {

            private static class NestedClass {

                private static class DeeplyNestedClass {

                }
            }

            @LayeredAnnotation("nested")
            private static class AnnotatedNestedClass {

            }

            @LayeredAnnotation("")
            private static class NestedWithEmptyAnnotation {

            }
        }

        private static class UnannotatedClass {

        }
    }

    @Nested
    class FindAllAnnotationAttributesTest {

        @Test
        void returnsEmptyListWhenAnnotationIsNotPresent() throws NoSuchMethodException {
            List<Map<String, Object>> result =
                    findAllAnnotationAttributes(methodNamed("notAnnotated"), Tagged.class);

            assertThat(result).isEmpty();
        }

        @Test
        void findsDirectAnnotation() throws NoSuchMethodException {
            List<Map<String, Object>> result =
                    findAllAnnotationAttributes(methodNamed("directlyAnnotated"), Tagged.class);

            assertThat(result).containsExactly(Map.of("key", "direct"));
        }

        @Test
        void findsRepeatedAnnotationsAsSeparateOccurrences() throws NoSuchMethodException {
            List<Map<String, Object>> result =
                    findAllAnnotationAttributes(methodNamed("repeatedlyAnnotated"), Tagged.class);

            assertThat(result).containsExactlyInAnyOrder(Map.of("key", "first"), Map.of("key", "second"));
        }

        @Test
        void findsAnnotationsInExplicitContainerAnnotation() throws NoSuchMethodException {
            List<Map<String, Object>> result =
                    findAllAnnotationAttributes(methodNamed("containerAnnotated"), Tagged.class);

            assertThat(result).containsExactlyInAnyOrder(Map.of("key", "first"), Map.of("key", "second"));
        }

        @Test
        void findsDirectAndMetaAnnotatedOccurrences() throws NoSuchMethodException {
            List<Map<String, Object>> result =
                    findAllAnnotationAttributes(methodNamed("mixedAnnotated"), Tagged.class);

            assertThat(result).containsExactlyInAnyOrder(Map.of("key", "direct"), Map.of("key", "meta"));
        }

        @Test
        void findsRepeatedAnnotationsOnMetaAnnotation() throws NoSuchMethodException {
            List<Map<String, Object>> result =
                    findAllAnnotationAttributes(methodNamed("repeatedMetaAnnotated"), Tagged.class);

            assertThat(result).containsExactlyInAnyOrder(Map.of("key", "metaFirst"), Map.of("key", "metaSecond"));
        }

        @Test
        void findsMetaAnnotationsOnAnyLevel() throws NoSuchMethodException {
            List<Map<String, Object>> result =
                    findAllAnnotationAttributes(methodNamed("nestedMetaAnnotated"), Tagged.class);

            assertThat(result).containsExactly(Map.of("key", "meta"));
        }

        @Test
        void findsOccurrenceForEveryMetaAnnotationPath() throws NoSuchMethodException {
            List<Map<String, Object>> result =
                    findAllAnnotationAttributes(methodNamed("multiplePathsAnnotated"), Tagged.class);

            assertThat(result).containsExactly(Map.of("key", "meta"), Map.of("key", "meta"));
        }

        @Test
        void overridesAttributesWithIdenticallyNamedAttributesOfMetaAnnotatedAnnotations()
                throws NoSuchMethodException {
            List<Map<String, Object>> defaultResult =
                    findAllAnnotationAttributes(methodNamed("overridingDefault"), Tagged.class);
            List<Map<String, Object>> explicitResult =
                    findAllAnnotationAttributes(methodNamed("overridingExplicit"), Tagged.class);

            assertThat(defaultResult).containsExactly(Map.of("key", "overridden"));
            assertThat(explicitResult).containsExactly(Map.of("key", "explicit"));
        }

        @Test
        void excludesAttributesNotDeclaredByTheAnnotationType() throws NoSuchMethodException {
            List<Map<String, Object>> singleResult =
                    findAllAnnotationAttributes(methodNamed("overridingExplicit"), Tagged.class);
            List<Map<String, Object>> repeatedResult =
                    findAllAnnotationAttributes(methodNamed("repeatedKeyedAnnotated"), Tagged.class);

            assertThat(singleResult).hasSize(1)
                                    .allSatisfy(attributes -> assertThat(attributes).containsOnlyKeys("key"));
            assertThat(repeatedResult).hasSize(2)
                                      .allSatisfy(attributes -> assertThat(attributes).containsOnlyKeys("key"));
        }

        @Test
        void findsOccurrenceForEveryRepeatedMetaAnnotatedAnnotation() throws NoSuchMethodException {
            List<Map<String, Object>> singleResult =
                    findAllAnnotationAttributes(methodNamed("singleKeyedAnnotated"), Tagged.class);
            List<Map<String, Object>> repeatedResult =
                    findAllAnnotationAttributes(methodNamed("repeatedKeyedAnnotated"), Tagged.class);

            assertThat(singleResult).containsExactly(Map.of("key", "x"));
            assertThat(repeatedResult).containsExactly(Map.of("key", "x"), Map.of("key", "y"));
        }

        @Test
        void terminatesOnCyclicMetaAnnotations() throws NoSuchMethodException {
            List<Map<String, Object>> taggedFirstResult =
                    findAllAnnotationAttributes(methodNamed("cycleTaggedAnnotated"), Tagged.class);
            List<Map<String, Object>> untaggedFirstResult =
                    findAllAnnotationAttributes(methodNamed("cycleUntaggedAnnotated"), Tagged.class);

            assertThat(taggedFirstResult).containsExactly(Map.of("key", "cycle"));
            assertThat(untaggedFirstResult).containsExactly(Map.of("key", "cycle"));
        }

        @Test
        void doesNotTreatLookAlikeContainerAnnotationAsContainer() throws NoSuchMethodException {
            List<Map<String, Object>> lookAlikeResult =
                    findAllAnnotationAttributes(methodNamed("lookAlikeContainerAnnotated"), Tagged.class);
            List<Map<String, Object>> containerResult =
                    findAllAnnotationAttributes(methodNamed("keyedContainerAnnotated"), Tagged.class);

            assertThat(lookAlikeResult).isEmpty();
            assertThat(containerResult).containsExactly(Map.of("key", "a"), Map.of("key", "b"));
        }

        @Test
        void findsEachOccurrenceOnceWhenDirectAnnotationAndContainerAnnotationCoexist()
                throws NoSuchMethodException {
            List<Map<String, Object>> directResult =
                    findAllAnnotationAttributes(methodNamed("directAndContainerAnnotated"), Tagged.class);
            List<Map<String, Object>> metaResult =
                    findAllAnnotationAttributes(methodNamed("keyedDirectAndContainerAnnotated"), Tagged.class);

            assertThat(directResult).containsExactlyInAnyOrder(Map.of("key", "a"), Map.of("key", "b"));
            assertThat(metaResult).containsExactlyInAnyOrder(Map.of("key", "a"), Map.of("key", "b"));
        }

        @Test
        void findsOccurrencesInContainerAnnotationInheritedFromSuperclass() {
            List<Map<String, Object>> parentResult =
                    findAllAnnotationAttributes(InheritingParent.class, Tagged.class);
            List<Map<String, Object>> childResult =
                    findAllAnnotationAttributes(InheritingChild.class, Tagged.class);

            assertThat(parentResult).containsExactly(Map.of("key", "a"), Map.of("key", "b"));
            assertThat(childResult).containsExactly(Map.of("key", "a"), Map.of("key", "b"));
        }

        @Test
        void directlyPresentOccurrenceHidesOccurrencesInheritedFromSuperclass() {
            List<Map<String, Object>> result =
                    findAllAnnotationAttributes(OverridingChild.class, Tagged.class);

            assertThat(result).containsExactly(Map.of("key", "c"));
        }

        private Method methodNamed(String name) throws NoSuchMethodException {
            return FindAllAnnotationAttributesTest.class.getDeclaredMethod(name);
        }

        void notAnnotated() {
        }

        @Tagged(key = "direct")
        void directlyAnnotated() {
        }

        @Tagged(key = "first")
        @Tagged(key = "second")
        void repeatedlyAnnotated() {
        }

        @TaggedContainer({@Tagged(key = "first"), @Tagged(key = "second")})
        void containerAnnotated() {
        }

        @Tagged(key = "direct")
        @MetaTagged
        void mixedAnnotated() {
        }

        @RepeatedMetaTagged
        void repeatedMetaAnnotated() {
        }

        @NestedMetaTagged
        void nestedMetaAnnotated() {
        }

        @MetaTagged
        @NestedMetaTagged
        void multiplePathsAnnotated() {
        }

        @OverridingTagged
        void overridingDefault() {
        }

        @OverridingTagged(key = "explicit")
        void overridingExplicit() {
        }

        @KeyedTagged(key = "x")
        void singleKeyedAnnotated() {
        }

        @KeyedTagged(key = "x")
        @KeyedTagged(key = "y", extra = "customExtra")
        void repeatedKeyedAnnotated() {
        }

        @CycleTagged
        void cycleTaggedAnnotated() {
        }

        @CycleUntagged
        void cycleUntaggedAnnotated() {
        }

        @KeyedTaggedGroup({@KeyedTagged(key = "a"), @KeyedTagged(key = "b")})
        void lookAlikeContainerAnnotated() {
        }

        @KeyedTaggedContainer({@KeyedTagged(key = "a"), @KeyedTagged(key = "b")})
        void keyedContainerAnnotated() {
        }

        @Tagged(key = "a")
        @TaggedContainer({@Tagged(key = "b")})
        void directAndContainerAnnotated() {
        }

        @KeyedTagged(key = "a")
        @KeyedTaggedContainer({@KeyedTagged(key = "b")})
        void keyedDirectAndContainerAnnotated() {
        }

        @InheritedKeyedTaggedContainer({@InheritedKeyedTagged(key = "a"), @InheritedKeyedTagged(key = "b")})
        static class InheritingParent {

        }

        static class InheritingChild extends InheritingParent {

        }

        @InheritedKeyedTagged(key = "c")
        static class OverridingChild extends InheritingParent {

        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        @interface KeyedTaggedGroup {

            KeyedTagged[] value();
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        @Inherited
        @Repeatable(InheritedKeyedTaggedContainer.class)
        @Tagged(key = "default")
        @interface InheritedKeyedTagged {

            String key();
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        @Inherited
        @interface InheritedKeyedTaggedContainer {

            InheritedKeyedTagged[] value();
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        @Repeatable(KeyedTaggedContainer.class)
        @Tagged(key = "default")
        @interface KeyedTagged {

            String key();

            String extra() default "defaultExtra";
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        @interface KeyedTaggedContainer {

            KeyedTagged[] value();
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
        @Tagged(key = "cycle")
        @CycleUntagged
        @interface CycleTagged {

        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
        @CycleTagged
        @interface CycleUntagged {

        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
        @Repeatable(TaggedContainer.class)
        @interface Tagged {

            String key() default "";
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
        @interface TaggedContainer {

            Tagged[] value();
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
        @Tagged(key = "meta")
        @interface MetaTagged {

        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        @Tagged(key = "metaFirst")
        @Tagged(key = "metaSecond")
        @interface RepeatedMetaTagged {

        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        @MetaTagged
        @interface NestedMetaTagged {

        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        @Tagged(key = "default")
        @interface OverridingTagged {

            String key() default "overridden";

            String extra() default "extraValue";
        }
    }

    @Nested
    class HasAnnotationNamedTest {

        @Test
        void returnsTrueWhenAnnotationSimpleNameMatchesExactly() throws NoSuchMethodException {
            Method method = HasAnnotationNamedTest.class.getDeclaredMethod("annotatedMethod");
            assertThat(hasAnnotationNamed(method, "CustomMarker")).isTrue();
        }

        @Test
        void returnsTrueWhenAnnotationSimpleNameMatchesIgnoringCase() throws NoSuchMethodException {
            Method method = HasAnnotationNamedTest.class.getDeclaredMethod("annotatedMethod");
            assertThat(hasAnnotationNamed(method, "custommarker")).isTrue();
            assertThat(hasAnnotationNamed(method, "CUSTOMMARKER")).isTrue();
        }

        @Test
        void returnsFalseWhenAnnotationNotPresent() throws NoSuchMethodException {
            Method method = HasAnnotationNamedTest.class.getDeclaredMethod("unannotatedMethod");
            assertThat(hasAnnotationNamed(method, "CustomMarker")).isFalse();
        }

        @Test
        void returnsFalseForDifferentSimpleName() throws NoSuchMethodException {
            Method method = HasAnnotationNamedTest.class.getDeclaredMethod("annotatedMethod");
            assertThat(hasAnnotationNamed(method, "OtherMarker")).isFalse();
        }

        @Test
        void detectsTypeUseAnnotationOnParameterType() throws NoSuchMethodException {
            Method method =
                    HasAnnotationNamedTest.class.getDeclaredMethod("methodWithTypeUseAnnotatedParameter", String.class);
            Parameter parameter = method.getParameters()[0];

            assertThat(hasAnnotationNamed(parameter.getAnnotatedType(), "Nullable")).isTrue();
        }

        @Test
        void doesNotDetectTypeUseAnnotationOnPlainParameter() throws NoSuchMethodException {
            Method method = HasAnnotationNamedTest.class.getDeclaredMethod("methodWithPlainParameter", String.class);
            Parameter parameter = method.getParameters()[0];

            assertThat(hasAnnotationNamed(parameter.getAnnotatedType(), "Nullable")).isFalse();
        }

        @Test
        void detectsTypeUseAnnotationWhenPassingParameterItself() throws NoSuchMethodException {
            Method method = HasAnnotationNamedTest.class.getDeclaredMethod(
                    "methodWithTypeUseAnnotatedParameter", String.class
            );
            Parameter parameter = method.getParameters()[0];

            // Passing the Parameter itself (declaration position) must also find a TYPE_USE-only annotation,
            // since hasAnnotationNamed additionally inspects Parameter#getAnnotatedType() for Parameter elements.
            assertThat(hasAnnotationNamed(parameter, "Nullable")).isTrue();
        }

        @CustomMarker
        void annotatedMethod() {
        }

        void unannotatedMethod() {
        }

        void methodWithTypeUseAnnotatedParameter(@Nullable String value) {
        }

        void methodWithPlainParameter(String value) {
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.METHOD)
        @interface CustomMarker {

        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE_USE)
        @interface Nullable {

        }
    }

    @Nested
    class HasAnnotationNamedOnDeclarationOnlyParameterTest {

        @Test
        void detectsDeclarationOnlyAnnotationOnParameter() throws NoSuchMethodException {
            Method method = HasAnnotationNamedOnDeclarationOnlyParameterTest.class.getDeclaredMethod(
                    "methodWithDeclarationOnlyAnnotatedParameter", String.class
            );
            Parameter parameter = method.getParameters()[0];

            // JSR-305's javax.annotation.Nullable is declaration-only (no TYPE_USE target), so it is only visible
            // on the Parameter itself, never on Parameter#getAnnotatedType().
            assertThat(hasAnnotationNamed(parameter, "Nullable")).isTrue();
            assertThat(hasAnnotationNamed(parameter.getAnnotatedType(), "Nullable")).isFalse();
        }

        void methodWithDeclarationOnlyAnnotatedParameter(@Nullable String value) {
        }

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.PARAMETER)
        @interface Nullable {

        }
    }
}
