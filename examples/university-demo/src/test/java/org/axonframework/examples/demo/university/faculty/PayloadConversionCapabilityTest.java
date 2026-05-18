package org.axonframework.examples.demo.university.faculty;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.axonframework.conversion.jackson2.Jackson2Converter;
import org.axonframework.examples.demo.university.shared.ids.CourseId;
import org.axonframework.examples.demo.university.shared.ids.FacultyId;
import org.axonframework.examples.demo.university.shared.ids.StudentId;
import org.axonframework.messaging.core.MessageType;
import org.axonframework.messaging.core.annotation.AnnotationMessageTypeResolver;
import org.axonframework.messaging.eventhandling.GenericEventMessage;
import org.axonframework.messaging.eventhandling.annotation.Event;
import org.axonframework.messaging.eventhandling.conversion.DelegatingEventConverter;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies what AF5 handles automatically at payload conversion time -- no upcaster needed.
 *
 * Each nested class maps to one row in the capability map. Tests go through the real AF5
 * conversion infrastructure: {@link DelegatingEventConverter} backed by {@link Jackson2Converter},
 * exactly as the framework does at handling time.
 *
 * Stored events are represented as {@code byte[]} (JSON-serialized payload), which matches what
 * AF5 reads back from the event store and feeds to the conversion pipeline.
 */
class PayloadConversionCapabilityTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // The real AF5 converter chain used at handling time: byte[] <-> JsonNode <-> POJO
    private final DelegatingEventConverter eventConverter =
            new DelegatingEventConverter(new Jackson2Converter(OBJECT_MAPPER));

    // -------------------------------------------------------------------------
    // Scenario 1 -- Added a new field
    //
    // CourseCreated gains an optional `description` field.
    // Old events stored without it receive null -- AF5 fills in the default
    // when converting the stored byte[] payload to the handler's declared type.
    // -------------------------------------------------------------------------

    @Nested
    class AddedNewField {

        record CourseCreatedWithDescription(
                FacultyId facultyId,
                CourseId courseId,
                String name,
                int capacity,
                String description
        ) {}

        @Test
        void newFieldDefaultsToNull_whenMissingFromStoredPayload() throws Exception {
            // given -- event stored before `description` was added; byte[] is the AF5 stored form
            byte[] storedPayload = """
                    {
                      "facultyId": {"raw": "Faculty:ONLY_FACULTY_ID"},
                      "courseId":  {"raw": "Course:course-1"},
                      "name":      "Mathematics",
                      "capacity":  30
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            var storedEvent = new GenericEventMessage(new MessageType("CourseCreated"), storedPayload);

            // when -- AF5 EventConverter converts stored byte[] to the handler's declared type
            var result = eventConverter.convertPayload(storedEvent, CourseCreatedWithDescription.class);

            // then -- existing fields preserved; new `description` defaults to null
            assertThat(result).isNotNull();
            assertThat(result.name()).isEqualTo("Mathematics");
            assertThat(result.capacity()).isEqualTo(30);
            assertThat(result.description()).isNull();
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 2 -- Removed a field
    //
    // StudentEnrolledInFaculty drops `lastName`.
    // Old events still carry it in their stored byte[] -- the new class simply
    // ignores it via @JsonIgnoreProperties.
    //
    // To prove that @JsonIgnoreProperties is the mechanism (not just a Jackson
    // default), both tests run through a converter backed by a strict ObjectMapper
    // that rejects unknown properties unless the class opts out explicitly.
    // -------------------------------------------------------------------------

    @Nested
    class RemovedField {

        // Strict ObjectMapper: fails on unknown properties unless @JsonIgnoreProperties opts out.
        // This makes the annotation the mechanism under test, not a Jackson default.
        private final DelegatingEventConverter strictEventConverter =
                new DelegatingEventConverter(new Jackson2Converter(
                        new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                ));

        @JsonIgnoreProperties(ignoreUnknown = true)
        record StudentEnrolledWithoutLastName(
                FacultyId facultyId,
                StudentId studentId,
                String firstName
        ) {}

        @Test
        void removedField_isIgnoredWhenPresentInStoredPayload() throws Exception {
            // given -- event stored when `lastName` still existed in StudentEnrolledInFaculty
            byte[] storedPayload = """
                    {
                      "facultyId":  {"raw": "Faculty:ONLY_FACULTY_ID"},
                      "studentId":  {"raw": "Student:student-1"},
                      "firstName":  "John",
                      "lastName":   "Doe"
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            var storedEvent = new GenericEventMessage(new MessageType("StudentEnrolledInFaculty"), storedPayload);

            // when -- AF5 EventConverter converts the stored payload; @JsonIgnoreProperties allows `lastName` to be dropped
            var result = strictEventConverter.convertPayload(storedEvent, StudentEnrolledWithoutLastName.class);

            // then -- known fields preserved; removed `lastName` dropped without error
            assertThat(result).isNotNull();
            assertThat(result.firstName()).isEqualTo("John");
        }

        @Test
        void removedField_failsWithoutAnnotation_whenConverterIsStrict() throws Exception {
            // given -- same strict converter; class has no @JsonIgnoreProperties opt-out
            record StudentEnrolledWithoutAnnotation(FacultyId facultyId, StudentId studentId, String firstName) {}

            byte[] storedPayload = """
                    {
                      "facultyId":  {"raw": "Faculty:ONLY_FACULTY_ID"},
                      "studentId":  {"raw": "Student:student-1"},
                      "firstName":  "John",
                      "lastName":   "Doe"
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            var storedEvent = new GenericEventMessage(new MessageType("StudentEnrolledInFaculty"), storedPayload);

            // when / then -- strict converter rejects `lastName`; annotation is what prevents failure
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> strictEventConverter.convertPayload(storedEvent, StudentEnrolledWithoutAnnotation.class)
            ).isInstanceOf(Exception.class);
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 3 -- Renamed a field
    //
    // `capacity` renamed to `maxCapacity` in CourseCreated.
    // Annotating the new field with @JsonProperty("capacity") maps the old
    // stored key name. AF5's EventConverter handles the rest.
    // -------------------------------------------------------------------------

    @Nested
    class RenamedField {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record CourseCreatedWithRenamedCapacity(
                FacultyId facultyId,
                CourseId courseId,
                String name,
                @JsonProperty("capacity") int maxCapacity
        ) {}

        @Test
        void renamedField_isMappedViaJsonProperty() throws Exception {
            // given -- event stored with the old field name `capacity`
            byte[] storedPayload = """
                    {
                      "facultyId": {"raw": "Faculty:ONLY_FACULTY_ID"},
                      "courseId":  {"raw": "Course:course-1"},
                      "name":      "Mathematics",
                      "capacity":  50
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            var storedEvent = new GenericEventMessage(new MessageType("CourseCreated"), storedPayload);

            // when -- AF5 EventConverter converts payload; @JsonProperty maps the old key to the new field name
            var result = eventConverter.convertPayload(storedEvent, CourseCreatedWithRenamedCapacity.class);

            // then -- value correctly mapped to the new field name
            assertThat(result).isNotNull();
            assertThat(result.maxCapacity()).isEqualTo(50);
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 4 -- Changed a field to a compatible type
    //
    // `capacity` widened from `int` to `long` in CourseCreated.
    // Jackson (AF5's default converter) coerces numeric types automatically.
    // -------------------------------------------------------------------------

    @Nested
    class CompatibleTypeChange {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record CourseCreatedWithLongCapacity(
                FacultyId facultyId,
                CourseId courseId,
                String name,
                long capacity
        ) {}

        @Test
        void intWidenedToLong_isCoercedByEventConverter() throws Exception {
            // given -- event stored with int capacity value
            byte[] storedPayload = """
                    {
                      "facultyId": {"raw": "Faculty:ONLY_FACULTY_ID"},
                      "courseId":  {"raw": "Course:course-1"},
                      "name":      "Mathematics",
                      "capacity":  30
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            var storedEvent = new GenericEventMessage(new MessageType("CourseCreated"), storedPayload);

            // when -- AF5 EventConverter converts the stored payload; Jackson2Converter coerces int to long
            var result = eventConverter.convertPayload(storedEvent, CourseCreatedWithLongCapacity.class);

            // then -- numeric type widening handled transparently
            assertThat(result).isNotNull();
            assertThat(result.capacity()).isEqualTo(30L);
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 5 -- Renamed the Java class only
    //
    // CourseCreatedEvent renamed to CourseCreated.
    // The payload JSON does not contain the Java class name -- it only contains
    // field values. AF5 routes events by MessageType name (from @Event(name=...)),
    // not by Java class name.
    //
    // Migration step: add @Event(name="CourseCreatedEvent") to the renamed class
    // so the framework still matches stored events (with the old MessageType name)
    // to the new class. The payload itself is byte-for-byte identical.
    // -------------------------------------------------------------------------

    @Nested
    class RenamedJavaClass {

        // Before: class was CourseCreatedEvent
        // After:  class is CourseCreated -- @Event(name="CourseCreatedEvent") preserves routing
        @Event(name = "CourseCreatedEvent")
        @JsonIgnoreProperties(ignoreUnknown = true)
        record CourseCreated(
                FacultyId facultyId,
                CourseId courseId,
                String name,
                int capacity
        ) {}

        @Test
        void messageTypeResolver_usesAnnotationName_notJavaClassName() {
            // given -- the renamed class annotated with the old event name
            var resolver = new AnnotationMessageTypeResolver();

            // when -- framework resolves MessageType for handler registration and event routing
            var resolvedType = resolver.resolve(CourseCreated.class).orElseThrow();

            // then -- routing uses "CourseCreatedEvent" (the stored name), not "CourseCreated" (the class name)
            assertThat(resolvedType.qualifiedName().localName()).isEqualTo("CourseCreatedEvent");
        }

        @Test
        void payloadIsUnchanged_soEventConverterDeserializesCorrectly() throws Exception {
            // given -- event stored under the old name "CourseCreatedEvent"; payload fields are identical
            byte[] storedPayload = """
                    {
                      "facultyId": {"raw": "Faculty:ONLY_FACULTY_ID"},
                      "courseId":  {"raw": "Course:course-1"},
                      "name":      "Mathematics",
                      "capacity":  30
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            var storedEvent = new GenericEventMessage(new MessageType("CourseCreatedEvent"), storedPayload);

            // when -- handler declares the new class type; AF5 EventConverter converts the payload
            var result = eventConverter.convertPayload(storedEvent, CourseCreated.class);

            // then -- payload identical; field values preserved under the new class name
            assertThat(result).isNotNull();
            assertThat(result.name()).isEqualTo("Mathematics");
            assertThat(result.capacity()).isEqualTo(30);
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 6 -- Handler receives a different representation
    //
    // Different handlers for the same stored event can declare different parameter
    // types. One handler declares the concrete event class; another declares JsonNode.
    // AF5's EventConverter converts the stored byte[] to whatever each handler
    // requests -- both conversions work from the same stored event, without any
    // changes to the event class or the event store.
    // -------------------------------------------------------------------------

    @Nested
    class HandlerReceivesDifferentRepresentation {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record CourseCreated(FacultyId facultyId, CourseId courseId, String name, int capacity) {}

        @Test
        void differentHandlers_receiveTheSameStoredEvent_inDifferentRepresentations() throws Exception {
            // given -- one stored event as byte[], shared by all handlers for this event type
            byte[] storedPayload = """
                    {
                      "facultyId": {"raw": "Faculty:ONLY_FACULTY_ID"},
                      "courseId":  {"raw": "Course:course-1"},
                      "name":      "Mathematics",
                      "capacity":  30
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            var storedEvent = new GenericEventMessage(new MessageType("CourseCreated"), storedPayload);

            // when -- handler A declares the typed class; handler B declares JsonNode
            // both use the same stored event and the same EventConverter
            CourseCreated typedResult = eventConverter.convertPayload(storedEvent, CourseCreated.class);
            JsonNode jsonResult = eventConverter.convertPayload(storedEvent, JsonNode.class);

            // then -- handler A receives the typed object with correct field values
            assertThat(typedResult).isNotNull();
            assertThat(typedResult.name()).isEqualTo("Mathematics");
            assertThat(typedResult.capacity()).isEqualTo(30);

            // and -- handler B receives the same data as a JsonNode; no event class needed
            assertThat(jsonResult).isNotNull();
            assertThat(jsonResult.get("name").asText()).isEqualTo("Mathematics");
            assertThat(jsonResult.get("capacity").asInt()).isEqualTo(30);
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 7 -- Switched serialization format
    //
    // The EventConverter handles format-level conversion transparently. When the
    // serialization format changes (e.g., from XStream to Jackson), the developer
    // reconfigures the EventConverter -- event classes require no changes.
    //
    // A complete XStream-to-Jackson migration test would require XStream on the
    // classpath. Instead, this is demonstrated by reading the same logical event
    // from two different stored payload types (byte[] and JsonNode) through the
    // same EventConverter and the same unchanged event class -- proving the
    // EventConverter's format-agnosticism, which is the mechanism that makes
    // serialization format migration transparent.
    // -------------------------------------------------------------------------

    @Nested
    class SwitchedSerializationFormat {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record CourseCreated(
                FacultyId facultyId,
                CourseId courseId,
                String name,
                int capacity
        ) {}

        @Test
        void eventClass_requiresNoChange_whenPayloadStoredAsByteArray() throws Exception {
            // given -- payload in byte[] form (e.g., Jackson-serialized JSON, the AF5 default)
            byte[] storedPayload = """
                    {
                      "facultyId": {"raw": "Faculty:ONLY_FACULTY_ID"},
                      "courseId":  {"raw": "Course:course-1"},
                      "name":      "Mathematics",
                      "capacity":  30
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            var storedEvent = new GenericEventMessage(new MessageType("CourseCreated"), storedPayload);

            // when -- EventConverter converts byte[] to the event class
            var result = eventConverter.convertPayload(storedEvent, CourseCreated.class);

            // then -- handler receives correct data; event class unchanged
            assertThat(result).isNotNull();
            assertThat(result.name()).isEqualTo("Mathematics");
            assertThat(result.capacity()).isEqualTo(30);
        }

        @Test
        void eventClass_requiresNoChange_whenPayloadStoredAsJsonNode() throws Exception {
            // given -- payload in JsonNode form (e.g., as produced by a different serialization path)
            JsonNode storedPayload = OBJECT_MAPPER.readTree("""
                    {
                      "facultyId": {"raw": "Faculty:ONLY_FACULTY_ID"},
                      "courseId":  {"raw": "Course:course-1"},
                      "name":      "Mathematics",
                      "capacity":  30
                    }
                    """);
            var storedEvent = new GenericEventMessage(new MessageType("CourseCreated"), storedPayload);

            // when -- same EventConverter, same event class; only the stored representation differs
            var result = eventConverter.convertPayload(storedEvent, CourseCreated.class);

            // then -- handler receives identical data regardless of stored format; class unchanged
            assertThat(result).isNotNull();
            assertThat(result.name()).isEqualTo("Mathematics");
            assertThat(result.capacity()).isEqualTo(30);
        }
    }
}
