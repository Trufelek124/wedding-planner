package io.github.trufelek124.weddingplanner.identity.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class UserRepository {

    record User(UUID id, String email, String googleSubject, String displayName) {

        /** Only the id: a user must be loggable without the address or the name. */
        @Override
        public String toString() {
            return "User[id=" + id + "]";
        }
    }

    private static final String COLUMNS = "id, email, google_subject, display_name";

    private final JdbcClient jdbc;

    UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<User> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_user WHERE id = ?").param(id).query(User.class).optional();
    }

    Optional<User> findByGoogleSubject(String subject) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_user WHERE google_subject = ?")
                .param(subject)
                .query(User.class)
                .optional();
    }

    Optional<User> findByEmail(String email) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_user WHERE email = ?")
                .param(AllowList.normalise(email))
                .query(User.class)
                .optional();
    }

    User create(String email, String googleSubject, String displayName, Instant now) {
        User user = new User(UUID.randomUUID(), AllowList.normalise(email), googleSubject, displayName);
        jdbc.sql("INSERT INTO app_user (id, email, google_subject, display_name, created_at) VALUES (?, ?, ?, ?, ?)")
                .params(user.id(), user.email(), user.googleSubject(), user.displayName(), at(now))
                .update();
        return user;
    }

    /** Ties a user to a Google account and takes over what Google says about it today. */
    User linkGoogleAccount(User user, String googleSubject, String email, String displayName) {
        User linked = new User(user.id(), AllowList.normalise(email), googleSubject, displayName);
        jdbc.sql("UPDATE app_user SET google_subject = ?, email = ?, display_name = ? WHERE id = ?")
                .params(linked.googleSubject(), linked.email(), linked.displayName(), linked.id())
                .update();
        return linked;
    }

    static OffsetDateTime at(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
