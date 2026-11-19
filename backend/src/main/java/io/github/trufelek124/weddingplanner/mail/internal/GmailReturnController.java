package io.github.trufelek124.weddingplanner.mail.internal;

import io.github.trufelek124.weddingplanner.mail.internal.MailSetup.Outcome;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where Google sends the browser back to: the one path of this feature that answers without a sign-in, because a
 * browser coming back from Google carries no access token. It trusts nothing in the request except a ticket the
 * server issued itself, and it always answers with a redirect to the same page of the web app: the address is made
 * from the server's own settings, and nothing in the request can change it.
 */
@RestController
class GmailReturnController {

    private final MailService mail;
    private final MailSetup setup;

    GmailReturnController(MailService mail, MailSetup setup) {
        this.mail = mail;
        this.setup = setup;
    }

    @Operation(
            summary = "Where Google sends the browser back to",
            description = "Answers without a sign-in and always with a redirect to the web app's e-mail page: "
                    + "`connected=yes` when the mailbox was connected, `refused` when the person said no at Google, "
                    + "`failed` for everything else. It accepts nothing but a ticket the server issued, once, in time, "
                    + "from the browser it was issued to. Not for clients of the API: only browsers coming from Google.")
    @ApiResponse(responseCode = "303", description = "To the e-mail page, whatever happened.")
    @GetMapping(MailSetup.RETURN_PATH)
    ResponseEntity<Void> back(HttpServletRequest request) {
        Optional<MailSetup.Ready> ready = setup.ready();
        boolean secure = ready.isPresent() && ready.get().google().publicUrl().getScheme().equals("https");
        // A HEAD is somebody looking, not Google: it takes nothing.
        Outcome outcome = HttpMethod.HEAD.matches(request.getMethod())
                ? Outcome.FAILED
                : mail.complete(
                        only(request, "state"), ConnectCookie.valueIn(request), only(request, "code"), only(request, "error"));
        URI page = ready.isPresent() ? ready.get().google().page(outcome) : URI.create(MailSetup.pageAddress(outcome));
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .header(HttpHeaders.LOCATION, page.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Referrer-Policy", "no-referrer")
                .header(HttpHeaders.SET_COOKIE, ConnectCookie.clear(request, secure))
                .build();
    }

    /** The one value of a parameter; nothing when it is missing or given twice. */
    private static String only(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        return values != null && values.length == 1 ? values[0] : null;
    }
}
