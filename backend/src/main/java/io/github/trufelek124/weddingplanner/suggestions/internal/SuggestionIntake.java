package io.github.trufelek124.weddingplanner.suggestions.internal;

import io.github.trufelek124.weddingplanner.api.ApiException;
import io.github.trufelek124.weddingplanner.api.FieldProblem;
import io.github.trufelek124.weddingplanner.api.ProblemCode;
import io.github.trufelek124.weddingplanner.planning.EntryToAdd;
import io.github.trufelek124.weddingplanner.planning.PlanEntries;
import io.github.trufelek124.weddingplanner.suggestions.Proposal;
import io.github.trufelek124.weddingplanner.suggestions.Suggestions;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionRepository.SuggestionRow;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionSettingsRepository.Settings;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionTypes.Kind;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionTypes.PaymentKind;
import io.github.trufelek124.weddingplanner.suggestions.internal.SuggestionTypes.State;
import io.github.trufelek124.weddingplanner.wedding.Weddings;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Where what the import proposes comes in: checked, kept as waiting, or added by itself when it is sure enough. */
@Component
class SuggestionIntake implements Suggestions {

    static final int KEY_LENGTH = 200;
    static final int TITLE_LENGTH = 200;
    static final int NOTES_LENGTH = 10_000;
    static final int SENDER_NAME_LENGTH = 200;
    static final int ADDRESS_LENGTH = 254;
    static final int SUBJECT_LENGTH = 300;
    static final int PASSAGE_LENGTH = 1_000;
    static final int PHONE_LENGTH = 40;
    static final int WEBSITE_LENGTH = 2_000;
    static final int LINK_LENGTH = 2_000;
    /** The only host a message is opened at. */
    static final String MAIL_HOST = "mail.google.com";

    private static final Pattern SPACE_OR_CONTROL = Pattern.compile("[\\s\\p{Cntrl}\\\\]");

    private static final Logger log = LoggerFactory.getLogger(SuggestionIntake.class);

    private final SuggestionRepository suggestions;
    private final SuggestionSettingsRepository settings;
    private final PlanEntries entries;
    private final Weddings weddings;
    private final Clock clock;
    private final TransactionTemplate asideFromTheRest;

    SuggestionIntake(
            SuggestionRepository suggestions,
            SuggestionSettingsRepository settings,
            PlanEntries entries,
            Weddings weddings,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.suggestions = suggestions;
        this.settings = settings;
        this.entries = entries;
        this.weddings = weddings;
        this.clock = clock;
        // A savepoint: an entry that the planner refuses is taken back without taking the hand-in with it.
        this.asideFromTheRest = new TransactionTemplate(transactions);
        this.asideFromTheRest.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    @Override
    @Transactional
    public UUID handIn(Proposal proposal) {
        SuggestionRow row = checked(proposal);
        // A concurrent hand-in of the same key waits on the unique key and then finds this one kept.
        if (!suggestions.insert(proposal.weddingId(), row)) {
            return suggestions.idOfSource(proposal.weddingId(), row.sourceKey()).orElseThrow();
        }
        addByItselfIfSure(proposal.weddingId(), row);
        return row.id();
    }

    /**
     * S05 and D-008: only a task or a step of the plan, never anything that names money, when the import is at least as
     * sure as the wedding asks, and only as the planner takes it. The suggestion and the entry come into being together
     * or the suggestion waits and nothing was made.
     */
    private void addByItselfIfSure(UUID weddingId, SuggestionRow row) {
        boolean tasksAndStepsOnly = (row.kind() == Kind.TASK || row.kind() == Kind.PLAN_ITEM) && row.amount() == null;
        Settings wanted = settings.of(weddingId);
        if (!tasksAndStepsOnly || !wanted.addsByItself() || row.confidence() < wanted.threshold()) {
            return;
        }
        EntryToAdd entry = SuggestionEntries.entry(row.kind(), SuggestionEntries.asProposed(row), row.id());
        UUID entryId;
        try {
            entryId = asideFromTheRest.execute(status -> entries.addEntryFor(weddingId, row.inboxMemberId(), entry));
        } catch (ApiException refused) {
            // The planner would not take it as it is: it waits for a person, who can change it.
            return;
        }
        suggestions.markAdded(weddingId, row.id(), entryId);
        log.info("Suggestion {} ({}) was added by itself for the inbox of member {}", row.id(), row.kind(), row.inboxMemberId());
    }

    @Override
    @Transactional
    public List<Proposed> proposedUnder(UUID weddingId, String keyBeginning, int most) {
        if (keyBeginning == null || keyBeginning.isBlank()) {
            throw new IllegalArgumentException("keyBeginning");
        }
        if (most <= 0) {
            throw new IllegalArgumentException("most");
        }
        return suggestions.proposedUnder(weddingId, keyBeginning, most);
    }

    @Override
    @Transactional
    public int madeSince(UUID weddingId, UUID inboxMemberId, Instant since) {
        return suggestions.madeSince(weddingId, inboxMemberId, since);
    }

    private SuggestionRow checked(Proposal p) {
        List<FieldProblem> problems = new ArrayList<>();
        if (p.weddingId() == null) {
            problems.add(new FieldProblem("weddingId", "Say which wedding."));
        }
        String key = strip(p.sourceKey());
        if (key.isEmpty() || key.length() > KEY_LENGTH) {
            problems.add(new FieldProblem("sourceKey", "Give the proposal a key of at most 200 characters."));
        }
        if (p.kind() == null) {
            problems.add(new FieldProblem("kind", "Say what is proposed."));
        }
        String title = strip(p.title());
        if (title.isEmpty()) {
            problems.add(new FieldProblem("title", "Enter a title."));
        }
        tooLong(problems, "title", title, TITLE_LENGTH);
        String notes = strip(p.notes());
        tooLong(problems, "notes", notes, NOTES_LENGTH);
        if (p.amount() != null && p.amount() < 0) {
            problems.add(new FieldProblem("amount", "Enter an amount, or zero."));
        }
        String vendorName = blankToNull(p.vendorName());
        String vendorEmail = blankToNull(p.vendorEmail());
        String vendorPhone = blankToNull(p.vendorPhone());
        String vendorWebsite = blankToNull(p.vendorWebsite());
        tooLong(problems, "vendorName", vendorName, TITLE_LENGTH);
        tooLong(problems, "vendorEmail", vendorEmail, ADDRESS_LENGTH);
        tooLong(problems, "vendorPhone", vendorPhone, PHONE_LENGTH);
        tooLong(problems, "vendorWebsite", vendorWebsite, WEBSITE_LENGTH);
        String senderName = strip(p.senderName());
        String senderAddress = strip(p.senderAddress());
        String subject = strip(p.subject());
        String passage = strip(p.passage());
        tooLong(problems, "senderName", senderName, SENDER_NAME_LENGTH);
        tooLong(problems, "senderAddress", senderAddress, ADDRESS_LENGTH);
        tooLong(problems, "subject", subject, SUBJECT_LENGTH);
        tooLong(problems, "passage", passage, PASSAGE_LENGTH);
        if (p.receivedAt() == null) {
            problems.add(new FieldProblem("receivedAt", "Say when the message arrived."));
        }
        if (p.confidence() < 0 || p.confidence() > 100) {
            problems.add(new FieldProblem("confidence", "Use a whole percentage from 0 to 100."));
        }
        if (p.weddingId() != null && (p.inboxMemberId() == null || !weddings.hasMember(p.weddingId(), p.inboxMemberId()))) {
            problems.add(new FieldProblem("inboxMemberId", "The inbox has to be one of the wedding's members."));
        }
        String link = p.mailLink() == null ? "" : p.mailLink();
        if (!isGmailLink(link)) {
            problems.add(new FieldProblem("mailLink", "Only an https address at mail.google.com can open a message."));
        }
        if (!problems.isEmpty()) {
            throw new ApiException(ProblemCode.BAD_REQUEST, "The suggestion is not valid.", problems);
        }
        return new SuggestionRow(
                UUID.randomUUID(), key, Kind.valueOf(p.kind().name()), State.WAITING, title, notes, p.startDate(), p.dueDate(),
                p.amount(), p.paymentKind() == null ? null : PaymentKind.valueOf(p.paymentKind().name()), p.categoryId(),
                p.vendorId(), vendorName, vendorEmail, vendorPhone, vendorWebsite, senderName, senderAddress, subject,
                p.receivedAt(), p.inboxMemberId(), link, passage, p.confidence(), null, null, null, null, clock.instant());
    }

    /** An https address whose host is exactly mail.google.com: no user, no port, nothing that could lead elsewhere. */
    static boolean isGmailLink(String link) {
        if (link.length() > LINK_LENGTH || SPACE_OR_CONTROL.matcher(link).find() || !link.startsWith("https://" + MAIL_HOST)) {
            return false;
        }
        try {
            URI uri = new URI(link);
            return "https".equals(uri.getScheme())
                    && MAIL_HOST.equals(uri.getHost())
                    && uri.getUserInfo() == null
                    && uri.getPort() == -1
                    && (uri.getRawAuthority() != null && uri.getRawAuthority().equals(MAIL_HOST));
        } catch (URISyntaxException notAnAddress) {
            return false;
        }
    }

    private static void tooLong(List<FieldProblem> problems, String field, String value, int most) {
        if (value != null && value.length() > most) {
            problems.add(new FieldProblem(field, "Use at most " + most + " characters."));
        }
    }

    private static String strip(String value) {
        return value == null ? "" : value.strip();
    }

    private static String blankToNull(String value) {
        String stripped = strip(value);
        return stripped.isEmpty() ? null : stripped;
    }
}
