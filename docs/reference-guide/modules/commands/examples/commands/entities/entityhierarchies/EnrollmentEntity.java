package commands.entities.entityhierarchies;

// tag::child-entity[]
// Child entity
public record EnrollmentEntity(String studentId, boolean dropped) {

    public EnrollmentEntity(String studentId) {
        this(studentId, false);
    }

    public String getStudentId() { // <4>
        return studentId;
    }
}
// end::child-entity[]
