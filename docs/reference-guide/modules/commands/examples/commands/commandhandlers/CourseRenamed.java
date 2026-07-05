package commands.commandhandlers;

public record CourseRenamed(FacultyId facultyId, CourseId courseId, String name) {
}
