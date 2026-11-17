package io.github.trufelek124.weddingplanner.identity.internal;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** The addresses that may sign in. Anyone else is refused, whatever Google says about them. */
@Component
class AllowList {

    private final Set<String> emails;

    @Autowired
    AllowList(IdentityProperties properties) {
        this(properties.allowedEmails());
    }

    AllowList(String commaSeparated) {
        this.emails = Arrays.stream(commaSeparated.split(","))
                .map(AllowList::normalise)
                .filter(email -> !email.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    boolean allows(String email) {
        return email != null && emails.contains(normalise(email));
    }

    boolean isEmpty() {
        return emails.isEmpty();
    }

    static String normalise(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
