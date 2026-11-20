package io.github.trufelek124.weddingplanner.planning.internal;

import io.github.trufelek124.weddingplanner.api.ApiException;
import io.github.trufelek124.weddingplanner.api.ProblemCode;
import io.github.trufelek124.weddingplanner.identity.UserId;
import io.github.trufelek124.weddingplanner.planning.internal.TaskFileDtos.Report;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tasks from a file (S06). The body is the file itself, read here as text, so that a file that is not JSON ends in
 * words for a person and not in the framework's own refusal, and so that a body above the limit is never read whole.
 */
@RestController
@RequestMapping(path = "/api/tasks/file", produces = MediaType.APPLICATION_JSON_VALUE)
class TaskFileController {

    private static final String FILE = "The file of tasks, as the text it is (JSON, UTF-8, at most 1 MB): the format is "
            + "published at /formats/wedding-planner-tasks-1.schema.json.";

    private final TaskFileService files;

    TaskFileController(TaskFileService files) {
        this.files = files;
    }

    @PostMapping(path = "/check", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "checkTaskFile",
            summary = "What a file of tasks would do",
            description = "Checks the file against the planner and changes nothing. Always answers with a report, also for a file "
                    + "that cannot be taken: the report says why. A body above 1 MB is the upload-too-large problem.")
    @RequestBody(required = true, description = FILE, content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "string")))
    Report check(UserId user, HttpServletRequest request) throws IOException {
        return files.check(user, text(request));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            operationId = "applyTaskFile",
            summary = "Takes a file of tasks",
            description = "Adds the tasks that are new and changes the ones the planner has from an earlier file, in one piece. "
                    + "Nothing is ever removed. With anything wrong in the file nothing is changed, and the answer is the "
                    + "task-file-refused problem: ask the check what is wrong. A body above 1 MB is the upload-too-large problem.")
    @RequestBody(required = true, description = FILE, content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "string")))
    Report apply(UserId user, HttpServletRequest request) throws IOException {
        return files.apply(user, text(request));
    }

    /** The body as text; null when it is not UTF-8. Never more than the limit is read. */
    private static String text(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > TaskFileReader.MOST_BYTES) {
            throw tooLarge();
        }
        byte[] body = request.getInputStream().readNBytes(TaskFileReader.MOST_BYTES + 1);
        if (body.length > TaskFileReader.MOST_BYTES) {
            throw tooLarge();
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
            // A byte order mark at the beginning is not part of the file.
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        } catch (CharacterCodingException notUtf8) {
            return null;
        }
    }

    private static ApiException tooLarge() {
        return new ApiException(ProblemCode.UPLOAD_TOO_LARGE, "The file is larger than 1 MB. Split it into several files.");
    }
}
