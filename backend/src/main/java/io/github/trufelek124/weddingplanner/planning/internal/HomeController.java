package io.github.trufelek124.weddingplanner.planning.internal;

import io.github.trufelek124.weddingplanner.identity.UserId;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningDtos.HomeResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the first page after sign-in shows. The server works it out so that the web app and the phone
 * agree on what "today" is: the date where the wedding takes place.
 */
@RestController
@RequestMapping(path = "/api/home", produces = MediaType.APPLICATION_JSON_VALUE)
class HomeController {

    private final EntryService entries;

    HomeController(EntryService entries) {
        this.entries = entries;
    }

    /** Answers the wedding-not-set-up problem until someone has set the wedding up. */
    @GetMapping
    HomeResponse home(UserId user) {
        return HomeResponse.of(entries.attention(user));
    }
}
