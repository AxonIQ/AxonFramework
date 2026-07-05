package commands.entities.entitypolymorphism.autodetected;

class OnlineCourse extends CourseEntity {

    private final String courseId;
    private final String platformUrl;

    OnlineCourse(CourseCreated event) {
        this.courseId = event.courseId();
        this.platformUrl = event.platformUrl();
    }
}
