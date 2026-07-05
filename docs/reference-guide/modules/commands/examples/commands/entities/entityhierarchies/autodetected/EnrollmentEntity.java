package commands.entities.entityhierarchies.autodetected;

class EnrollmentEntity {

    private final String studentId;

    EnrollmentEntity(String studentId) {
        this.studentId = studentId;
    }

    public String getStudentId() {
        return studentId;
    }
}
