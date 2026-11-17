package io.github.trufelek124.weddingplanner.planning.internal;

import io.github.trufelek124.weddingplanner.identity.UserId;
import io.github.trufelek124.weddingplanner.planning.internal.EntryRepository.Filter;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningDtos.Conversion;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningDtos.EntryChange;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningDtos.EntryResponse;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningDtos.NewEntry;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningDtos.StatusChange;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Kind;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Sort;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Status;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.TasksOnDelete;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/entries", produces = MediaType.APPLICATION_JSON_VALUE)
class EntryController {

    private final EntryService entries;

    EntryController(EntryService entries) {
        this.entries = entries;
    }

    @GetMapping
    List<EntryResponse> list(
            UserId user,
            @RequestParam(required = false) Kind kind,
            @Parameter(description = "Only entries in one of these states.") @RequestParam(required = false) List<Status> status,
            @RequestParam(required = false) UUID categoryId,
            @Parameter(description = "Only entries this member does (alone or with the other).") @RequestParam(required = false)
                    UUID assigneeId,
            @Parameter(description = "Only the tasks of this plan item.") @RequestParam(required = false) UUID parentId,
            @Parameter(description = "true: only entries with a due date; false: only those without.")
                    @RequestParam(required = false)
                    Boolean scheduled,
            @RequestParam(defaultValue = "DUE_DATE") Sort sort) {
        Filter filter = new Filter(kind, status, categoryId, assigneeId, parentId, scheduled);
        return entries.list(user, filter, sort).stream().map(EntryResponse::of).toList();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    EntryResponse create(UserId user, @Valid @RequestBody NewEntry request) {
        return EntryResponse.of(entries.create(user, request));
    }

    @GetMapping("/{id}")
    EntryResponse get(UserId user, @PathVariable UUID id) {
        return EntryResponse.of(entries.get(user, id));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    EntryResponse change(UserId user, @PathVariable UUID id, @Valid @RequestBody EntryChange request) {
        return EntryResponse.of(entries.change(user, id, request));
    }

    @PutMapping(path = "/{id}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    EntryResponse setStatus(UserId user, @PathVariable UUID id, @Valid @RequestBody StatusChange request) {
        return EntryResponse.of(entries.setStatus(user, id, request.status()));
    }

    @PostMapping(path = "/{id}/convert", consumes = MediaType.APPLICATION_JSON_VALUE)
    EntryResponse convert(UserId user, @PathVariable UUID id, @Valid @RequestBody Conversion request) {
        return EntryResponse.of(entries.convert(user, id, request.kind()));
    }

    /** Makes the plan item wait for another one. Saying it twice changes nothing. */
    @PutMapping("/{id}/dependencies/{dependsOnId}")
    EntryResponse addDependency(UserId user, @PathVariable UUID id, @PathVariable UUID dependsOnId) {
        return EntryResponse.of(entries.addDependency(user, id, dependsOnId));
    }

    @DeleteMapping("/{id}/dependencies/{dependsOnId}")
    EntryResponse removeDependency(UserId user, @PathVariable UUID id, @PathVariable UUID dependsOnId) {
        return EntryResponse.of(entries.removeDependency(user, id, dependsOnId));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(
            UserId user,
            @PathVariable UUID id,
            @Parameter(description = "For a plan item with tasks: what happens to them. Without it such a plan item "
                            + "is not deleted and the answer is the entry-has-tasks problem.")
                    @RequestParam(required = false)
                    TasksOnDelete tasks) {
        entries.delete(user, id, tasks);
    }
}
