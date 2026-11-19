package io.github.trufelek124.weddingplanner.mail.internal;

import io.github.trufelek124.weddingplanner.identity.UserId;
import io.github.trufelek124.weddingplanner.mail.internal.MailDtos.ConnectionStart;
import io.github.trufelek124.weddingplanner.mail.internal.MailDtos.MailStateResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in owner's side of the e-mail import. The return from Google is {@link GmailReturnController}. */
@RestController
@RequestMapping(path = "/api/mail", produces = MediaType.APPLICATION_JSON_VALUE)
class MailController {

    private final MailService mail;
    private final MailSetup setup;

    MailController(MailService mail, MailSetup setup) {
        this.mail = mail;
        this.setup = setup;
    }

    @Operation(
            summary = "Where the e-mail import stands",
            description = "Whether the import is set up on this server, the caller's own connection with the mailbox's "
                    + "address, and the partner's connection without it. Nothing is asked of Google.")
    @GetMapping
    MailStateResponse state(UserId user) {
        return mail.state(user);
    }

    @Operation(
            summary = "Starts connecting a Gmail mailbox",
            description = "Makes a ticket that is good for ten minutes, once, in this browser (it is bound to it by a cookie "
                    + "that is sent with this answer), and answers with Google's address to send the browser to. Google asks "
                    + "for read-only access to Gmail and nothing else, and sends the browser back to the server, which ends "
                    + "on the web app's e-mail page. Answers mail-not-set-up while the import is off.")
    @PostMapping("/gmail/connection")
    ConnectionStart connect(UserId user, HttpServletRequest request, HttpServletResponse response) {
        MailService.Started started = mail.start(user);
        response.addHeader(
                HttpHeaders.SET_COOKIE,
                ConnectCookie.set(request, setup.require().google().publicUrl().getScheme().equals("https"), started.cookieValue()));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return new ConnectionStart(started.authorizationUrl());
    }

    @Operation(
            summary = "Disconnects the caller's mailbox",
            description = "Asks Google to withdraw the permission (if Google cannot be reached, the rest still happens) and "
                    + "removes the caller's own connection with its token. Disconnecting again changes nothing. The partner's "
                    + "connection is never touched.")
    @DeleteMapping("/gmail/connection")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void disconnect(UserId user) {
        mail.disconnect(user);
    }
}
