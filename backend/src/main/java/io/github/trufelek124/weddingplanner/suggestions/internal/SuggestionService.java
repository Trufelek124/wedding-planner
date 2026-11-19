package io.github.trufelek124.weddingplanner.suggestions.internal;

import io.github.trufelek124.weddingplanner.api.ApiException;
import io.github.trufelek124.weddingplanner.api.FieldProblem;
import io.github.trufelek124.weddingplanner.api.ProblemCode;
import io.github.trufelek124.weddingplanner.identity.UserId;
import io.github.trufelek124.weddingplanner.planning.EntryToAdd;
import io.github.trufelek124.weddingplanner.planning.PlanEntries;
import io.github.trufelek124.weddingplanner.planning.PlanEntries.Removal;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionDtos.SuggestionAcceptance;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionRepository.SuggestionRow;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionSettingsRepository.Settings;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionTypes.Kind;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionTypes.State;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionTypes.Which;
import io.github.trufelek124.weddingplanner.vendors.VendorRegister;
import io.github.trufelek124.weddingplanner.vendors.VendorToAdd;
import io.github.trufelek124.weddingplanner.wedding.Membership;
import io.github.trufelek124.weddingplanner.wedding.Weddings;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What an owner can do with a suggestion: read the ones that wait, accept one or reject one. A decision stands, and
 * what accepting makes is made by the planning and vendor modules' own "add", so that it is checked as adding by hand.
 */
@Service
class SuggestionService {

    /** The names a vendor's problems have in the suggestion's own form. */
    private static final Map<String, String> VENDOR_FIELDS =
            Map.of("name", "title", "email", "vendorEmail", "phone", "vendorPhone", "website", "vendorWebsite");

    /** After this long, an entry that was added by itself and that nobody undid counts as kept (S05 section 5). */
    static final Duration KEPT_AFTER = Duration.ofDays(30);

    private final SuggestionRepository suggestions;
    private final SuggestionSettingsRepository settings;
    private final PlanEntries entries;
    private final VendorRegister vendors;
    private final Weddings weddings;
    private final Clock clock;

    SuggestionService(
            SuggestionRepository suggestions,
            SuggestionSettingsRepository settings,
            PlanEntries entries,
            VendorRegister vendors,
            Weddings weddings,
            Clock clock) {
        this.suggestions = suggestions;
        this.settings = settings;
        this.entries = entries;
        this.vendors = vendors;
        this.weddings = weddings;
        this.clock = clock;
    }

    @Transactional
    List<SuggestionRow> list(UserId user, Which which) {
        Membership membership = weddings.membershipOf(user);
        return suggestions.list(membership.weddingId(), which, clock.instant().minus(KEPT_AFTER)).stream()
                .map(row -> asSeenBy(membership, settled(row)))
                .toList();
    }

    @Transactional
    SuggestionRow get(UserId user, UUID id) {
        Membership membership = weddings.membershipOf(user);
        SuggestionRow row = suggestions.find(membership.weddingId(), id)
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "There is no such suggestion."));
        return asSeenBy(membership, settled(row));
    }

    @Transactional
    Settings settings(UserId user) {
        return settings.of(weddings.membershipOf(user).weddingId());
    }

    /** A change applies to what is handed in from then on; nothing that waits or was added is touched. */
    @Transactional
    Settings change(UserId user, Settings wanted) {
        settings.save(weddings.membershipOf(user).weddingId(), wanted, clock.instant());
        return wanted;
    }

    /**
     * An entry that was added by itself and that nobody has kept or undone for thirty days counts as kept: that is
     * worked out here, from when it was added, whenever a suggestion is read or decided. Nothing has to run, and
     * nothing is written: the same row answers as waiting-to-be-looked-at at twenty-nine days and as accepted, by
     * nobody, at thirty.
     */
    private SuggestionRow settled(SuggestionRow row) {
        Instant keptFrom = row.createdAt().plus(KEPT_AFTER);
        if (row.state() == State.ADDED && !keptFrom.isAfter(clock.instant())) {
            return row.decided(State.ACCEPTED, null, keptFrom);
        }
        return row;
    }

    /**
     * Makes what is proposed. Asked again after it was accepted, it answers the same and makes nothing more; after it
     * was rejected, it is refused. Two requests at once are taken one after the other: the row is held meanwhile.
     *
     * @param values null: as proposed; otherwise the values the person wants
     */
    @Transactional
    SuggestionRow accept(UserId user, UUID id, SuggestionAcceptance values) {
        Membership membership = weddings.membershipOf(user);
        SuggestionRow suggestion = held(membership, id);
        if (suggestion.state() == State.ACCEPTED) {
            return asSeenBy(membership, suggestion);
        }
        if (suggestion.state() == State.REJECTED) {
            throw new ApiException(ProblemCode.ALREADY_DECIDED, "This suggestion was rejected already.");
        }
        if (suggestion.state() == State.ADDED) {
            throw new ApiException(ProblemCode.ALREADY_DECIDED, ADDED_BY_ITSELF);
        }
        SuggestionAcceptance wanted = values != null ? values : SuggestionEntries.asProposed(suggestion);
        UUID madeEntryId = null;
        UUID madeVendorId = null;
        // Whatever the planning or vendor module refuses leaves here as an exception, and this transaction, the
        // hold on the row and everything made so far go with it: the suggestion still waits.
        if (suggestion.kind() == Kind.VENDOR) {
            madeVendorId = makeVendor(user, suggestion, wanted);
        } else {
            madeEntryId = makeEntry(user, suggestion, wanted);
        }
        suggestions.decide(
                membership.weddingId(), id, State.ACCEPTED, membership.memberId(), clock.instant(), madeEntryId, madeVendorId);
        return asSeenBy(membership, held(membership, id));
    }

    /** Makes nothing. Asked again after it was rejected, it answers the same; after it was accepted, it is refused. */
    @Transactional
    SuggestionRow reject(UserId user, UUID id) {
        Membership membership = weddings.membershipOf(user);
        SuggestionRow suggestion = held(membership, id);
        if (suggestion.state() == State.REJECTED) {
            return asSeenBy(membership, suggestion);
        }
        if (suggestion.state() == State.ACCEPTED) {
            throw new ApiException(ProblemCode.ALREADY_DECIDED, "This suggestion was accepted already.");
        }
        if (suggestion.state() == State.ADDED) {
            throw new ApiException(ProblemCode.ALREADY_DECIDED, ADDED_BY_ITSELF);
        }
        suggestions.decide(membership.weddingId(), id, State.REJECTED, membership.memberId(), clock.instant(), null, null);
        return asSeenBy(membership, held(membership, id));
    }

    /**
     * Says "yes, this belongs in our plan" to an entry that was added by itself: the suggestion is accepted, by this
     * person. Nothing else changes. Asked again, or of one that is accepted, it answers as it is.
     */
    @Transactional
    SuggestionRow keep(UserId user, UUID id) {
        Membership membership = weddings.membershipOf(user);
        SuggestionRow suggestion = held(membership, id);
        switch (suggestion.state()) {
            case WAITING -> throw new ApiException(ProblemCode.BAD_REQUEST, NOTHING_ADDED);
            case REJECTED -> throw new ApiException(ProblemCode.ALREADY_DECIDED, "This suggestion was undone already.");
            case ACCEPTED -> {
                return asSeenBy(membership, suggestion);
            }
            case ADDED -> {
                suggestions.settleAdded(
                        membership.weddingId(), id, State.ACCEPTED, membership.memberId(), clock.instant(), suggestion.madeEntryId());
                return asSeenBy(membership, held(membership, id));
            }
        }
        throw new IllegalStateException();
    }

    /** What undoing came to: the suggestion as it stands now, and what became of the entry. */
    record Undone(SuggestionRow suggestion, Removal entry) {}

    /**
     * Removes the entry that was added and rejects the suggestion, in one action. When the entry is no longer what was
     * added, it stays, and the suggestion counts as kept: that has to be written, so this does not throw; the caller
     * answers the refusal once this transaction is over.
     */
    @Transactional
    Undone undo(UserId user, UUID id) {
        Membership membership = weddings.membershipOf(user);
        SuggestionRow suggestion = held(membership, id);
        switch (suggestion.state()) {
            case WAITING -> throw new ApiException(ProblemCode.BAD_REQUEST, NOTHING_ADDED);
            case ACCEPTED -> throw new ApiException(
                    ProblemCode.ALREADY_DECIDED,
                    suggestion.decidedBy() == null ? "This suggestion was kept already." : "This suggestion was accepted already.");
            case REJECTED -> {
                return new Undone(asSeenBy(membership, suggestion), Removal.REMOVED);
            }
            case ADDED -> {
                Removal removal = entries.removeIfUntouched(membership.weddingId(), suggestion.madeEntryId(), id);
                boolean removed = removal == Removal.REMOVED;
                suggestions.settleAdded(
                        membership.weddingId(), id, removed ? State.REJECTED : State.ACCEPTED, membership.memberId(),
                        clock.instant(), removed ? null : suggestion.madeEntryId());
                return new Undone(asSeenBy(membership, held(membership, id)), removal);
            }
        }
        throw new IllegalStateException();
    }

    private static final String ADDED_BY_ITSELF =
            "This suggestion was added by itself and nobody has decided it: keep it or undo it.";
    private static final String NOTHING_ADDED = "This suggestion has not been added; it is still waiting. Accept it or reject it.";

    /**
     * The one place that decides what link a person is given. The link of a message in the other owner's mailbox names
     * that mailbox in its query, and the partner's address is never shown: it is answered without its query.
     */
    private static SuggestionRow asSeenBy(Membership membership, SuggestionRow row) {
        if (membership.memberId().equals(row.inboxMemberId())) {
            return row;
        }
        return row.withMailLink(withoutQuery(row.mailLink()));
    }

    static String withoutQuery(String link) {
        int question = link.indexOf('?');
        int hash = link.indexOf('#');
        if (question < 0 || (hash >= 0 && hash < question)) {
            return link;
        }
        String shortened = link.substring(0, question) + (hash < 0 ? "" : link.substring(hash));
        return SuggestionIntake.isGmailLink(shortened) ? shortened : "https://" + SuggestionIntake.MAIL_HOST + "/mail/";
    }

    private SuggestionRow held(Membership membership, UUID id) {
        return suggestions.findForDecision(membership.weddingId(), id)
                .map(this::settled)
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "There is no such suggestion."));
    }

    private UUID makeEntry(UserId user, SuggestionRow suggestion, SuggestionAcceptance wanted) {
        if (suggestion.kind() == Kind.PAYMENT && wanted.amount() == null) {
            throw ApiException.invalidField("amount", "Enter the amount of this payment.");
        }
        return entries.addEntry(user, SuggestionEntries.entry(suggestion.kind(), wanted, suggestion.id()));
    }

    private UUID makeVendor(UserId user, SuggestionRow suggestion, SuggestionAcceptance wanted) {
        try {
            return vendors.addVendor(user, new VendorToAdd(
                    wanted.categoryId(), wanted.title(), wanted.vendorPhone(), wanted.vendorEmail(), wanted.vendorWebsite(),
                    wanted.notes()));
        } catch (ApiException refused) {
            if (refused.fields().isEmpty()) {
                throw refused;
            }
            List<FieldProblem> theirs = refused.fields().stream()
                    .map(problem -> new FieldProblem(VENDOR_FIELDS.getOrDefault(problem.field(), problem.field()), problem.message()))
                    .toList();
            throw new ApiException(refused.code(), refused.getMessage(), theirs);
        }
    }
}
