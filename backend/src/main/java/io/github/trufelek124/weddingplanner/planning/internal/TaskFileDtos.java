package io.github.trufelek124.weddingplanner.planning.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * What the two calls with a file of tasks (S06) answer. A report holds what a file says (keys, titles, values), so
 * none of these prints it: {@code toString()} hides it.
 */
final class TaskFileDtos {

    private TaskFileDtos() {}

    @Schema(name = "TaskFileOutcome", description = "What becomes of one row: a new task, a change of a task the planner has "
            + "from an earlier file, no change, or a row that cannot be taken.")
    enum Outcome {
        NEW,
        CHANGED,
        UNCHANGED,
        REFUSED
    }

    @Schema(name = "TaskFileProblem", description = "Something wrong with the file as a whole.")
    record FileProblem(@Schema(description = "In words for a person.") String message) {
        @Override
        public String toString() {
            return "FileProblem[hidden]";
        }
    }

    @Schema(name = "TaskFileRowProblem", description = "Something wrong with one row.")
    record RowProblem(
            @Schema(
                            description = "The file's own name of the field (key, title, notes, dueDate, status, priority, category, "
                                    + "vendor, amount, paymentKind, paidOn), or a name the format does not have; null for the row as such.",
                            nullable = true)
                    String field,
            @Schema(description = "In words for a person.") String message) {
        @Override
        public String toString() {
            return "RowProblem[hidden]";
        }
    }

    @Schema(name = "TaskFileChange", description = "One field of a task that the row changes.")
    record Change(
            @Schema(description = "The file's own name of the field.") String field,
            @Schema(
                            description = "What the task has now: a day as YYYY-MM-DD; a status, a priority or a kind of payment as its "
                                    + "name (null for none, or for a priority the planner sets); a category or a vendor by its name; an "
                                    + "amount as the whole number of the currency's smallest unit, in digits; text as it is.",
                            nullable = true)
                    String from,
            @Schema(description = "What it becomes, written the same way.", nullable = true) String to) {
        @Override
        public String toString() {
            return "Change[hidden]";
        }
    }

    @Schema(name = "TaskFileRow", description = "What becomes of one row of the file.")
    record RowReport(
            @Schema(description = "The row's place in the file, from 1.") int row,
            @Schema(description = "The row's key; null when the row has none that could be read.", nullable = true) String key,
            @Schema(description = "The row's title; null when the row has none that could be read.", nullable = true) String title,
            Outcome outcome,
            @Schema(description = "For a row that changes a task: each field that changes. Otherwise empty.") List<Change> changes,
            @Schema(description = "For a row that cannot be taken: what is wrong. Otherwise empty.") List<RowProblem> problems) {
        @Override
        public String toString() {
            return "RowReport[hidden]";
        }
    }

    @Schema(
            name = "TaskFileReport",
            description = "What a file of tasks does, or did: the answer of both calls with a file. A file that is not this format "
                    + "at all has no rows, and says why in problems.")
    record Report(
            @Schema(description = "true when this call took the file (also when nothing in it differed from the planner); "
                            + "false for a check, which changes nothing, and for a file that cannot be taken.")
                    boolean applied,
            @Schema(description = "Nothing is wrong anywhere in the file: it can be applied.") boolean canBeApplied,
            @Schema(description = "How many rows are new tasks.") int added,
            @Schema(description = "How many rows change a task the planner has.") int changed,
            @Schema(description = "How many rows leave their task as it is.") int unchanged,
            @Schema(description = "How many rows cannot be taken.") int refused,
            @Schema(description = "The categories the file would add (or added), as the file writes them, each once. Empty while "
                            + "the file cannot be applied.")
                    List<String> newCategories,
            @Schema(description = "What is wrong with the file as a whole.") List<FileProblem> problems,
            @Schema(description = "One per row of the file, in the file's order.") List<RowReport> rows) {
        @Override
        public String toString() {
            return "Report[hidden]";
        }
    }
}
