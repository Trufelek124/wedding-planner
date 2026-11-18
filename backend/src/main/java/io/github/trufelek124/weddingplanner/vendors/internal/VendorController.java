package io.github.trufelek124.weddingplanner.vendors.internal;

import io.github.trufelek124.weddingplanner.identity.UserId;
import io.github.trufelek124.weddingplanner.vendors.internal.VendorDtos.NewVendor;
import io.github.trufelek124.weddingplanner.vendors.internal.VendorDtos.VendorChange;
import io.github.trufelek124.weddingplanner.vendors.internal.VendorDtos.VendorResponse;
import io.github.trufelek124.weddingplanner.vendors.internal.VendorRepository.Filter;
import io.github.trufelek124.weddingplanner.vendors.internal.VendorService.Shown;
import io.github.trufelek124.weddingplanner.vendors.internal.VendorTypes.LinksOnDelete;
import io.github.trufelek124.weddingplanner.vendors.internal.VendorTypes.Status;
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
@RequestMapping(path = "/api/vendors", produces = MediaType.APPLICATION_JSON_VALUE)
class VendorController {

    private final VendorService vendors;

    VendorController(VendorService vendors) {
        this.vendors = vendors;
    }

    /** By name. */
    @GetMapping
    List<VendorResponse> list(
            UserId user,
            @Parameter(description = "Only vendors in one of these states.") @RequestParam(required = false) List<Status> status,
            @RequestParam(required = false) UUID categoryId) {
        return vendors.list(user, new Filter(status, categoryId)).stream().map(VendorController::response).toList();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    VendorResponse create(UserId user, @Valid @RequestBody NewVendor request) {
        return response(vendors.create(user, request));
    }

    @GetMapping("/{id}")
    VendorResponse get(UserId user, @PathVariable UUID id) {
        return response(vendors.get(user, id));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    VendorResponse change(UserId user, @PathVariable UUID id, @Valid @RequestBody VendorChange request) {
        return response(vendors.change(user, id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(
            UserId user,
            @PathVariable UUID id,
            @Parameter(description = "For a vendor that plan items, tasks or inspirations point to: REMOVE lets them stay "
                            + "without a vendor. Without it such a vendor is not deleted and the answer is the "
                            + "vendor-in-use problem.")
                    @RequestParam(required = false)
                    LinksOnDelete links) {
        vendors.delete(user, id, links);
    }

    private static VendorResponse response(Shown shown) {
        return VendorResponse.of(shown.vendor(), shown.use());
    }
}
