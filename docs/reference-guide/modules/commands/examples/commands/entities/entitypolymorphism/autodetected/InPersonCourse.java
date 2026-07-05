package commands.entities.entitypolymorphism.autodetected;

class InPersonCourse extends CourseEntity {

    private final String courseId;
    private final String location;

    InPersonCourse(CourseCreated event) {
        this.courseId = event.courseId();
        this.location = event.location();
    }
}
