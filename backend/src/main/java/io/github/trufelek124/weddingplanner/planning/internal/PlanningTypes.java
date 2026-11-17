package io.github.trufelek124.weddingplanner.planning.internal;

/** The small fixed vocabularies of the planning module, as the API spells them. */
final class PlanningTypes {

    private PlanningTypes() {}

    /** A plan item is a step that takes time; a task is a single action. */
    enum Kind {
        PLAN_ITEM,
        TASK
    }

    enum Status {
        NOT_STARTED,
        IN_PROGRESS,
        DONE,
        DROPPED
    }

    /** From most to least urgent; the order of the constants is the order lists sort by. */
    enum Priority {
        CRITICAL,
        HIGH,
        NORMAL,
        LOW
    }

    enum PriorityMode {
        /** The planner works the priority out. */
        AUTO,
        /** A person chose the priority and it stays. */
        MANUAL
    }

    /** What happens to the tasks of a plan item that is deleted. */
    enum TasksOnDelete {
        /** They stay, without a plan item. */
        KEEP,
        DELETE
    }

    enum Sort {
        DUE_DATE,
        PRIORITY,
        TITLE,
        CREATED
    }
}
