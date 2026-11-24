package io.github.trufelek124.weddingplanner.mailreading.internal;

import io.github.trufelek124.weddingplanner.mailreading.MailExtraction.Kind;
import io.github.trufelek124.weddingplanner.mailreading.MailExtraction.PaymentKind;
import io.github.trufelek124.weddingplanner.mailreading.MailExtraction.Place;
import io.github.trufelek124.weddingplanner.mailreading.MailExtraction.Proposed;
import io.github.trufelek124.weddingplanner.planning.PlanCategories;
import io.github.trufelek124.weddingplanner.suggestions.Proposal;
import io.github.trufelek124.weddingplanner.vendors.Vendors;
import io.github.trufelek124.weddingplanner.wedding.WeddingFacts;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * From one checked proposal to what is handed in as a suggestion (S04 section 5). Everything here is the server's, not the
 * model's: whose inbox, the link, the lengths, the money, which vendor and which category are the planner's, and which
 * contact details are kept. A proposal says what it says; this decides nothing but what is proposed, and acts on nothing.
 */
@Component
class ProposalMaker {

    /** What the suggestions module keeps at most of each text, in characters. */
    static final int TITLE = 200;
    static final int SENDER_NAME = 200;
    static final int SENDER_ADDRESS = 254;
    static final int SUBJECT = 300;
    static final int NOTES = 10_000;
    static final int PASSAGE = 1_000;

    /**
     * Where a proposal comes from. It holds a part of somebody's mail and a link with a mailbox's address: nothing of it is
     * printed.
     */
    record Origin(UUID weddingId, UUID memberId, String senderName, String senderAddress, String subject, Instant arrived, String link) {

        @Override
        public String toString() {
            return "Origin[member=" + memberId + "]";
        }
    }

    private final Vendors vendors;
    private final PlanCategories categories;

    ProposalMaker(Vendors vendors, PlanCategories categories) {
        this.vendors = vendors;
        this.categories = categories;
    }

    /**
     * @return what to hand in; empty when the proposal is a vendor the planner has already
     */
    Optional<Proposal> make(Origin origin, Proposed proposed, String sourceKey, WeddingFacts facts) {
        UUID wedding = origin.weddingId();
        String senderAddress = origin.senderAddress().length() > SENDER_ADDRESS ? "" : origin.senderAddress();
        String email = ContactDetails.email(proposed.vendorEmail());
        String phone = ContactDetails.phone(proposed.vendorPhone());
        String website = ContactDetails.website(proposed.vendorWebsite());

        UUID vendorId = null;
        String vendorName = null;
        String vendorEmail = null;
        String vendorPhone = null;
        String vendorWebsite = null;
        String title = cut(proposed.title(), TITLE);
        if (proposed.kind() == Kind.VENDOR) {
            // The planner has this business already: nothing to propose. A known vendor may introduce somebody else, though:
            // the sender counts only when the proposal has no address of its own, or the sender's own.
            boolean senderIntroducesItself = email == null || email.equalsIgnoreCase(senderAddress.strip());
            if (vendors.named(wedding, proposed.title()).isPresent()
                    || known(wedding, email).isPresent()
                    || (senderIntroducesItself && known(wedding, senderAddress).isPresent())) {
                return Optional.empty();
            }
            vendorEmail = email;
            vendorPhone = phone;
            vendorWebsite = website;
        } else {
            Optional<UUID> found = known(wedding, senderAddress);
            if (found.isEmpty() && proposed.vendorName() != null) {
                found = vendors.named(wedding, proposed.vendorName());
            }
            if (found.isPresent()) {
                vendorId = found.get();
            } else if (proposed.vendorName() != null) {
                vendorName = cut(proposed.vendorName(), TITLE);
                vendorEmail = email;
                vendorPhone = phone;
                vendorWebsite = website;
            }
        }

        UUID categoryId = null;
        if (proposed.kind() != Kind.VENDOR && proposed.category() != null) {
            categoryId = categories.named(wedding, proposed.category()).orElse(null);
        }

        // Days: a first day only on a step of the plan, and never after the day it is due.
        var start = proposed.kind() == Kind.PLAN_ITEM ? proposed.startDate() : null;
        var due = proposed.dueDate();
        if (start != null && due != null && start.isAfter(due)) {
            start = null;
        }

        Long amount = null;
        String notes = proposed.notes() == null ? "" : proposed.notes();
        if (proposed.kind() == Kind.PAYMENT && proposed.amount() != null) {
            OptionalLong units = Money.smallestUnits(proposed.amount(), facts.currency());
            if (units.isPresent()) {
                amount = units.getAsLong();
            } else {
                notes = withLastLine(notes, notLeftOver(proposed, facts));
            }
        }
        PaymentKind paymentKind = proposed.kind() == Kind.PAYMENT ? proposed.paymentKind() : null;

        return Optional.of(new Proposal(
                wedding,
                sourceKey,
                Proposal.Kind.valueOf(proposed.kind().name()),
                title,
                cut(notes, NOTES),
                start,
                due,
                amount,
                paymentKind == null ? null : Proposal.PaymentKind.valueOf(paymentKind.name()),
                categoryId,
                vendorId,
                vendorName,
                vendorEmail,
                vendorPhone,
                vendorWebsite,
                cut(origin.senderName(), SENDER_NAME),
                senderAddress,
                cut(origin.subject(), SUBJECT),
                origin.arrived(),
                origin.memberId(),
                origin.link(),
                cut(proposed.passage(), PASSAGE),
                proposed.sure(),
                proposed.place() == Place.DOCUMENT));
    }

    private Optional<UUID> known(UUID wedding, String address) {
        return address == null || address.isBlank() ? Optional.empty() : vendors.withAddress(wedding, address);
    }

    /** What the person who decides is told when the amount could not be carried over. */
    private static String notLeftOver(Proposed proposed, WeddingFacts facts) {
        String named = proposed.amount().value() + (proposed.amount().currency().isEmpty() ? "" : " " + proposed.amount().currency());
        if (proposed.amount().currency().isEmpty()) {
            return "The e-mail names " + named + " with no currency; the planner counts in " + facts.currency() + ".";
        }
        if (!proposed.amount().currency().equalsIgnoreCase(facts.currency())) {
            return "The e-mail names " + named + "; the planner counts in " + facts.currency() + ".";
        }
        return "The e-mail names " + named + "; the planner cannot use it as an amount.";
    }

    /** The note's own text is shortened, not the line that tells what is missing. */
    private static String withLastLine(String notes, String line) {
        String base = cut(notes, NOTES - line.length() - 1);
        return base.isEmpty() ? line : base + "\n" + line;
    }

    /** At most this many characters, never through the middle of a character that takes two. */
    static String cut(String text, int most) {
        if (text == null) {
            return "";
        }
        if (text.length() <= most) {
            return text;
        }
        int end = most;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }
}
