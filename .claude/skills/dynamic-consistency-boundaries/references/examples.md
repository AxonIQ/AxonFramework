# DCB worked examples

Concrete patterns, each modeled as source -> decide -> append. The first is real code from this
repository; the others are canonical DCB.events patterns expressed with Axon Framework criteria.

## 1. Cross-entity invariant: course subscription (real, in-repo)

Files: `examples/university-java/src/main/java/org/axonframework/examples/university/`

The rule for `SubscribeStudentToCourse` spans two entities at once: a course holds at most its
capacity, and a student joins at most `MAX_COURSES_PER_STUDENT`. Classic aggregates cannot enforce
both transactionally; DCB selects exactly the events both rules need.

The decision model and its criteria (`write/subscribestudent/SubscribeStudentToCourseCommandHandler.java`):

```java
@EventSourcedEntity
static class State {
    private CourseId courseId;
    private int courseCapacity = 0;
    private int noOfStudentsSubscribedToCourse = 0;
    private String studentId;
    private int noOfCoursesStudentSubscribed = 0;
    private boolean alreadySubscribed = false;

    @EntityCreator public State() {}

    @EventSourcingHandler void evolve(CourseCreated e)          { courseId = e.courseId(); courseCapacity = e.capacity(); }
    @EventSourcingHandler void evolve(CourseCapacityChanged e)  { courseCapacity = e.capacity(); }
    @EventSourcingHandler void evolve(StudentEnrolledInFaculty e){ studentId = e.studentId(); }
    @EventSourcingHandler void evolve(StudentSubscribedToCourse e) {
        if (e.courseId().equals(courseId))   noOfStudentsSubscribedToCourse++;
        if (e.studentId().equals(studentId)) noOfCoursesStudentSubscribed++;
        if (e.studentId().equals(studentId) && e.courseId().equals(courseId)) alreadySubscribed = true;
    }
    // StudentUnsubscribedFromCourse decrements symmetrically

    @EventCriteriaBuilder
    private static EventCriteria resolveCriteria(SubscriptionId id) {
        var courseId  = id.courseId().toString();
        var studentId = id.studentId();
        return EventCriteria.either(
            EventCriteria.havingTags(Tag.of(FacultyTags.COURSE_ID, courseId))
                         .andBeingOneOfTypes(CourseCreated.class.getName(),
                                             CourseCapacityChanged.class.getName(),
                                             StudentSubscribedToCourse.class.getName(),
                                             StudentUnsubscribedFromCourse.class.getName()),
            EventCriteria.havingTags(Tag.of(FacultyTags.STUDENT_ID, studentId))
                         .andBeingOneOfTypes(StudentEnrolledInFaculty.class.getName(),
                                             StudentSubscribedToCourse.class.getName(),
                                             StudentUnsubscribedFromCourse.class.getName())
        );
    }
}
```

The handler reads the injected state and decides:

```java
@CommandHandler
void handle(SubscribeStudentToCourse cmd, @InjectEntity State state, EventAppender appender) {
    assertStudentEnrolledFaculty(state);
    assertStudentNotSubscribedToTooManyCourses(state);
    assertCourseExists(state);
    assertEnoughVacantSpotsInCourse(state);
    assertStudentNotAlreadySubscribed(state);
    appender.append(new StudentSubscribedToCourse(cmd.studentId(), cmd.courseId()));
}
```

`StudentSubscribedToCourse` is tagged on both axes, so the one event participates in both the
course boundary and the student boundary:

```java
public record StudentSubscribedToCourse(
        @EventTag(key = FacultyTags.STUDENT_ID) String studentId,
        @EventTag(key = FacultyTags.COURSE_ID)  CourseId courseId) {}
```

Why DCB here: the same event must count toward two independent limits. With one event in the
global log carrying two tags, both criterions select it -- no event duplication, no saga,
strong consistency for the subscribe decision. The append condition replays the marker over those
two criterions, so a concurrent subscription that would breach either limit causes a conflict and
retry.

## 2. Uniqueness: claim a unique username

Invariant: a username is taken at most once. There is no entity that owns "all usernames"; the
boundary is just "events that touched this username."

- Tag: `Tag.of("username", "alice")` on a `UsernameClaimed` event.
- Criteria: `EventCriteria.havingTags(Tag.of("username", name)).andBeingOneOfTypes(UsernameClaimed.class.getName(), UsernameReleased.class.getName())`.
- Decide: fold to a boolean `claimed`. If already claimed, reject; else append `UsernameClaimed`.
- Conflict: two concurrent claims of "alice" both source an empty/identical set, both decide
  "free", but only the first append wins -- the second sees a matching event after its marker and
  is rejected, then retried (and now rejected by the rule). Uniqueness holds without a unique
  index or a registry aggregate.

## 3. Gapless sequence: next invoice number

Invariant: invoice numbers are sequential with no gaps, per company.

- Tag: `Tag.of("companyId", id)` on `InvoiceCreated`.
- Criteria: that tag restricted to `InvoiceCreated` (and any event that changes numbering).
- Decide: fold to `highestNumber`; new number = `highestNumber + 1`.
- Conflict: two concurrent invoice creations for the same company both compute `n+1`; the marker
  check lets only one commit, the other retries and computes `n+2`. The criteria is scoped to one
  company, so different companies never contend.

## How to reason about a new boundary

Given an invariant, walk the loop:

1. **What events does the rule read?** List event types whose facts the decision depends on.
2. **What selects them?** Choose tags so the criteria returns exactly those events and no more.
   Use one criterion per independent axis (per course, per student, per username) and OR them.
3. **Restrict by type** with `andBeingOneOfTypes(...)` so unrelated events sharing a tag are
   excluded.
4. **Fold to the minimal state** the rule needs (a count, a flag, a max) in `@EventSourcingHandler`
   methods -- the store will not compute these for you.
5. **Check the conflict surface**: the criteria is also the lock. Confirm it is narrow enough to
   avoid false conflicts but wide enough that no concurrent event could violate the rule unseen.
