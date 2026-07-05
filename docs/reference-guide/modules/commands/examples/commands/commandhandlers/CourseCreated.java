package commands.commandhandlers;

public record CourseCreated(FacultyId facultyId, CourseId courseId, String name, int capacity) {
}
