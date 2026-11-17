package io.github.trufelek124.weddingplanner.planning.internal;

import io.github.trufelek124.weddingplanner.identity.UserId;
import io.github.trufelek124.weddingplanner.planning.internal.CategoryRepository.CategoryRow;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningDtos.CategoryName;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningDtos.CategoryResponse;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/categories", produces = MediaType.APPLICATION_JSON_VALUE)
class CategoryController {

    private final CategoryService categories;

    CategoryController(CategoryService categories) {
        this.categories = categories;
    }

    /** The first call for a wedding fills the list with a starter set. */
    @GetMapping
    List<CategoryResponse> list(UserId user) {
        return categories.list(user).stream().map(CategoryController::response).toList();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    CategoryResponse add(UserId user, @Valid @RequestBody CategoryName request) {
        return response(categories.add(user, request.name()));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    CategoryResponse rename(UserId user, @PathVariable UUID id, @Valid @RequestBody CategoryName request) {
        return response(categories.rename(user, id, request.name()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(UserId user, @PathVariable UUID id) {
        categories.delete(user, id);
    }

    private static CategoryResponse response(CategoryRow row) {
        return new CategoryResponse(row.id(), row.name());
    }
}
