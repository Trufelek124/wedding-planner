package io.github.trufelek124.weddingplanner.mail.internal;

import io.github.trufelek124.weddingplanner.mail.internal.GmailAccess.Access;
import io.github.trufelek124.weddingplanner.mail.internal.GmailAccess.Granted;
import io.github.trufelek124.weddingplanner.mail.internal.GmailAccess.NeedsReconnecting;
import io.github.trufelek124.weddingplanner.mail.internal.GmailAccess.NotConnected;
import io.github.trufelek124.weddingplanner.mail.internal.GmailAccess.Unavailable;
import io.github.trufelek124.weddingplanner.mail.internal.GoogleClient.Failure;
import io.github.trufelek124.weddingplanner.mail.internal.GoogleClient.GoogleFailure;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Gmail as a {@link MailSource}: {@code users.getProfile} for the marker, {@code users.messages.list} for a look by
 * date and {@code users.history.list} for what was added since a marker. It goes out through {@link GoogleClient}, with
 * its limits, and with the access token {@link GmailAccess} gives. Of a message it reads its two identifiers (and, in
 * the history, its labels, only to leave out what is not received mail); nothing else is asked for or kept.
 */
@Component
class GmailSource implements MailSource {

    /** What the owner sent, drafts, spam, the bin and chats: not received mail. */
    static final Set<String> NOT_RECEIVED = Set.of("SENT", "DRAFT", "SPAM", "TRASH", "CHAT");

    /** The same, as a search for {@code users.messages.list}. */
    static String receivedQuery(Instant from) {
        return "after:" + from.getEpochSecond() + " -in:sent -in:drafts -in:spam -in:trash -in:chats";
    }

    private static final String PAGE_SIZE = "100";
    /** What Google's identifiers look like; anything else is not one and is not noted. */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private final GmailAccess access;
    private final GoogleClient google;

    GmailSource(GmailAccess access, GoogleClient google) {
        this.access = access;
        this.google = google;
    }

    @Override
    public String standingNow(UUID owner) throws SourceFailure {
        JsonNode profile = read(owner, "profile", Map.of());
        return identifier(scalar(profile, "historyId")).orElseThrow(() -> new SourceFailure(Kind.INTERRUPTED));
    }

    @Override
    public Page receivedSince(UUID owner, Instant from, String continuation) throws SourceFailure {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("q", receivedQuery(from));
        query.put("maxResults", PAGE_SIZE);
        if (continuation != null) {
            query.put("pageToken", continuation);
        }
        JsonNode answer = read(owner, "messages", query);
        List<Noted> found = new ArrayList<>();
        for (JsonNode message : array(answer.get("messages"))) {
            noted(message).ifPresent(found::add);
        }
        return new Page(found, next(answer), null);
    }

    @Override
    public Page addedSince(UUID owner, String marker, String continuation) throws SourceFailure {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("startHistoryId", marker);
        query.put("historyTypes", "messageAdded");
        query.put("maxResults", PAGE_SIZE);
        if (continuation != null) {
            query.put("pageToken", continuation);
        }
        JsonNode answer = read(owner, "history", query);
        List<Noted> found = new ArrayList<>();
        for (JsonNode entry : array(answer.get("history"))) {
            for (JsonNode added : array(entry.get("messagesAdded"))) {
                JsonNode message = added.get("message");
                if (message != null && received(message)) {
                    noted(message).ifPresent(found::add);
                }
            }
        }
        return new Page(found, next(answer), identifier(scalar(answer, "historyId")).orElse(null));
    }

    // ---------------------------------------------------------------- reading

    /**
     * One read. A 401 asks for the access token once more and tries once more; a connection that has to be made
     * again, or is gone, ends the look.
     */
    private JsonNode read(UUID owner, String path, Map<String, String> query) throws SourceFailure {
        try {
            return google.gmailGet(token(owner), path, query);
        } catch (GoogleFailure first) {
            if (first.failure() != Failure.UNAUTHORIZED) {
                throw translated(first);
            }
        }
        access.forget(owner);
        try {
            return google.gmailGet(token(owner), path, query);
        } catch (GoogleFailure second) {
            // Two refusals of a token that was just made: not a matter of the connection, but of Google's mood.
            throw new SourceFailure(Kind.REFUSED_FOR_NOW);
        }
    }

    private String token(UUID owner) throws SourceFailure {
        Access given = access.accessTokenFor(owner);
        return switch (given) {
            case Granted granted -> granted.accessToken();
            case NotConnected none -> throw new SourceFailure(Kind.NOT_CONNECTED);
            case NeedsReconnecting again -> throw new SourceFailure(Kind.NEEDS_RECONNECTING);
            case Unavailable unavailable -> throw new SourceFailure(Kind.INTERRUPTED);
        };
    }

    private static SourceFailure translated(GoogleFailure failure) {
        return new SourceFailure(switch (failure.failure()) {
            case NOT_FOUND -> Kind.MARKER_TOO_OLD;
            case SERVER_TROUBLE, REFUSED, UNAUTHORIZED -> Kind.REFUSED_FOR_NOW;
            default -> Kind.INTERRUPTED;
        });
    }

    private static boolean received(JsonNode message) {
        for (JsonNode label : array(message.get("labelIds"))) {
            if (label.isString() && NOT_RECEIVED.contains(label.asString())) {
                return false;
            }
        }
        return true;
    }

    private static Optional<Noted> noted(JsonNode message) {
        Optional<String> id = identifier(scalar(message, "id"));
        Optional<String> thread = identifier(scalar(message, "threadId"));
        return id.isPresent() && thread.isPresent() ? Optional.of(new Noted(id.get(), thread.get())) : Optional.empty();
    }

    /** The next page's token. One that is there but cannot be used is not "no next page": that would end a look early. */
    private static String next(JsonNode answer) throws SourceFailure {
        JsonNode token = answer.get("nextPageToken");
        if (token == null || token.isNull()) {
            return null;
        }
        String text = token.isString() ? token.asString() : null;
        if (text == null || text.isBlank() || text.length() > 2048) {
            throw new SourceFailure(Kind.INTERRUPTED);
        }
        return text;
    }

    private static String scalar(JsonNode object, String field) {
        JsonNode value = object == null ? null : object.get(field);
        return value != null && value.isValueNode() && !value.isNull() ? value.asString() : null;
    }

    private static Optional<String> identifier(String text) {
        return text != null && IDENTIFIER.matcher(text).matches() ? Optional.of(text) : Optional.empty();
    }

    private static Iterable<JsonNode> array(JsonNode node) {
        return node != null && node.isArray() ? node : List.of();
    }
}
