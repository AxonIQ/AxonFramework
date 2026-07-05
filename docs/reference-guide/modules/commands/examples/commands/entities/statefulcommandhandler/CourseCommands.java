package commands.entities.statefulcommandhandler;

// The import is indented to the depth of the nested records below, so that the
// indent=0 normalization of the include renders both regions flush left.
// tag::course-commands-import[]
    import org.axonframework.modelling.annotation.TargetEntityId;
// end::course-commands-import[]

class CourseCommands {

    // tag::course-commands[]

    public record CreateCourse(String courseId, String name, int capacity) {}

    public record RenameCourse(@TargetEntityId String courseId, String name) {}
    // end::course-commands[]
}
