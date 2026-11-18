package io.github.trufelek124.weddingplanner.inspirations.internal;

import io.github.trufelek124.weddingplanner.identity.UserId;
import io.github.trufelek124.weddingplanner.inspirations.internal.InspirationDtos.InspirationResponse;
import io.github.trufelek124.weddingplanner.inspirations.internal.PictureService.Served;
import io.github.trufelek124.weddingplanner.inspirations.internal.PictureService.Size;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** The pictures of an inspiration. Like everything under /api they need an access token. */
@RestController
@RequestMapping("/api/inspirations/{inspirationId}/pictures")
class PictureController {

    private final PictureService pictures;
    private final InspirationService inspirations;

    PictureController(PictureService pictures, InspirationService inspirations) {
        this.pictures = pictures;
        this.inspirations = inspirations;
    }

    /** Adds one or more pictures, in the order sent. If one is refused none is added. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    InspirationResponse add(
            UserId user,
            @PathVariable UUID inspirationId,
            @Parameter(description = "JPEG, PNG, GIF or WebP pictures, at most 15 MB each.") @RequestPart("files")
                    List<MultipartFile> files) {
        pictures.add(user, inspirationId, files);
        return InspirationResponse.of(inspirations.get(user, inspirationId));
    }

    @DeleteMapping(path = "/{pictureId}", produces = MediaType.APPLICATION_JSON_VALUE)
    InspirationResponse remove(UserId user, @PathVariable UUID inspirationId, @PathVariable UUID pictureId) {
        pictures.remove(user, inspirationId, pictureId);
        return InspirationResponse.of(inspirations.get(user, inspirationId));
    }

    /** The picture itself. A picture never changes once it is kept, so a client may keep its copy. */
    @GetMapping("/{pictureId}")
    @ApiResponse(
            responseCode = "200",
            description = "The picture, or its smaller version as a JPEG.",
            content = @Content(mediaType = "image/*", schema = @Schema(type = "string", format = "binary")))
    ResponseEntity<Resource> picture(
            UserId user,
            @PathVariable UUID inspirationId,
            @PathVariable UUID pictureId,
            @Parameter(description = "THUMBNAIL: the smaller version for a board; the picture itself where there is none.")
                    @RequestParam(defaultValue = "FULL")
                    Size size) {
        Served served = pictures.open(user, inspirationId, pictureId, size);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(served.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
                .body(served.bytes());
    }
}
