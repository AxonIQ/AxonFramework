package commands.entities.entityhierarchies;

// tag::parent-entity[]
import java.util.List;

// Parent entity
public record CourseEntity(String courseId, int capacity, List<EnrollmentEntity> enrollments) {

    public CourseEntity(String courseId) { // <1>
        this(courseId, 0, List.of());
    }

    public List<EnrollmentEntity> getEnrollments() { // <2>
        return enrollments;
    }

    public CourseEntity withEnrollments(List<EnrollmentEntity> updated) { // <3>
        return new CourseEntity(courseId, capacity, List.copyOf(updated));
    }
}

// end::parent-entity[]
