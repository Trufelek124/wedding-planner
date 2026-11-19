package io.github.trufelek124.weddingplanner.suggestions.internal;

import io.github.trufelek124.weddingplanner.api.ApiException;
import io.github.trufelek124.weddingplanner.api.FieldProblem;
import io.github.trufelek124.weddingplanner.api.ProblemCode;
import io.github.trufelek124.weddingplanner.identity.UserId;
import io.github.trufelek124.weddingplanner.planning.EntryToAdd;
import io.github.trufelek124.weddingplanner.planning.PlanEntries;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionDtos.SuggestionAcceptance;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionRepository.SuggestionRow;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionTypes.Kind;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionTypes.PaymentKind;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionTypes.State;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionTypes.Which;
import io.github.trufelek124.weddingplanner.vendors.VendorRegister;
import io.github.trufelek124.weddingplanner.vendors.VendorToAdd;
import io.github.trufelek124.weddingplanner.wedding.Membership;
import io.github.trufelek124.weddingplanner.wedding.Weddings;
import java.time.Clock;
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

    private final SuggestionRepository suggestions;
    private final PlanEntries entries;
    private final VendorRegister vendors;
    private final Weddings weddings;
    private final Clock clock;

    SuggestionService(
            SuggestionRepository suggestions, PlanEntries entries, VendorRegister vendors, Weddings weddings, Clock clock) {
        this.suggestions = suggestions;
        this.entries = entries;
        this.vendors = vendors;
        this.weddings = weddings;
        this.clock = clock;
    }

    @Transactional
    List<SuggestionRow> list(UserId user, Which which) {
        Membership membership = weddings.membershipOf(user);
        return suggestions.list(membership.weddingId(), which).stream()
                .map(row -> asSeenBy(membership, row))
                .toList();
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
        SuggestionAcceptance wanted = values != null ? values : asProposed(suggestion);
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
        suggestions.decide(membership.weddingId(), id, State.REJECTED, membership.memberId(), clock.instant(), null, null);
        return asSeenBy(membership, held(membership, id));
    }

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
                .orElseThrow(() -> new ApiException(ProblemCode.NOT_FOUND, "There is no such suggestion."));
    }

    private UUID makeEntry(UserId user, SuggestionRow suggestion, SuggestionAcceptance wanted) {
        if (suggestion.kind() == Kind.PAYMENT && wanted.amount() == null) {
            throw ApiException.invalidField("amount", "Enter the amount of this payment.");
        }
        EntryToAdd.Kind kind = suggestion.kind() == Kind.PLAN_ITEM ? EntryToAdd.Kind.PLAN_ITEM : EntryToAdd.Kind.TASK;
        return entries.addEntry(user, new EntryToAdd(
                kind, wanted.title(), wanted.notes(), wanted.categoryId(), wanted.startDate(), wanted.dueDate(),
                wanted.vendorId(), wanted.amount(),
                wanted.paymentKind() == null ? null : EntryToAdd.PaymentKind.valueOf(wanted.paymentKind().name())));
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

    private static SuggestionAcceptance asProposed(SuggestionRow s) {
        return new SuggestionAcceptance(
                s.title(), s.notes(), s.startDate(), s.dueDate(), s.amount(),
                s.paymentKind() == null ? null : PaymentKind.valueOf(s.paymentKind().name()), s.categoryId(), s.vendorId(),
                s.vendorName(), s.vendorEmail(), s.vendorPhone(), s.vendorWebsite());
    }
}
