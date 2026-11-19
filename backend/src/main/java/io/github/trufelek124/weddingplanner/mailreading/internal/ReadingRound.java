package io.github.trufelek124.weddingplanner.mailreading.internal;

import io.github.trufelek124.weddingplanner.api.ApiException;
import io.github.trufelek124.weddingplanner.api.FieldProblem;
import io.github.trufelek124.weddingplanner.mail.MailReading;
import io.github.trufelek124.weddingplanner.mail.MailReading.AboutTheWedding;
import io.github.trufelek124.weddingplanner.mail.MailReading.Content;
import io.github.trufelek124.weddingplanner.mail.MailReading.CopyOfOneRead;
import io.github.trufelek124.weddingplanner.mail.MailReading.CouldNotBeRead;
import io.github.trufelek124.weddingplanner.mail.MailReading.Letter;
import io.github.trufelek124.weddingplanner.mail.MailReading.MessageHandle;
import io.github.trufelek124.weddingplanner.mail.MailReading.NotAboutTheWedding;
import io.github.trufelek124.weddingplanner.mail.MailReading.Reason;
import io.github.trufelek124.weddingplanner.mail.MailReading.Unavailable;
import io.github.trufelek124.weddingplanner.mail.MailReading.Verdict;
import io.github.trufelek124.weddingplanner.mail.ReadingReport;
import io.github.trufelek124.weddingplanner.mailreading.MailExtraction;
import io.github.trufelek124.weddingplanner.mailreading.MailTriage;
import io.github.trufelek124.weddingplanner.mailreading.Outcome;
import io.github.trufelek124.weddingplanner.mailreading.Sender;
import io.github.trufelek124.weddingplanner.mailreading.Used;
import io.github.trufelek124.weddingplanner.suggestions.Suggestions;
import io.github.trufelek124.weddingplanner.wedding.WeddingFacts;
import io.github.trufelek124.weddingplanner.wedding.Weddings;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * The reading of the mail (S04): every round takes the newest messages that wait, one after the other, the whole way of
 * section 2, and keeps the brakes of section 7 and 8. It is the one place that decides what happens to a message, and the
 * only things it does with what a message says are asking the two models about it and handing in what they propose
 * as suggestions that wait for a person.
 *
 * <p>Nothing of a message is kept here beyond one round. What is remembered between rounds is in memory only and says
 * nothing readable: the keys of messages that could not be had (a hash), when to ask again, how long the service
 * asked the reading to wait, and whether the key was refused.
 *
 * <p>The log says that a message was read, by which steps, what it came to, and how many a round read; it names a
 * mailbox by its member's id. Never which message, who sent it, a title, a passage, a link, an address or the key, and of
 * an exception only its kind.
 */
@Component
@EnableScheduling
class ReadingRound implements ReadingReport, SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ReadingRound.class);

    static final Duration FIRST_PUT_OFF = Duration.ofMinutes(5);
    static final Duration LONGEST_PUT_OFF = Duration.ofDays(1);
    static final Duration FIRST_WAIT = Duration.ofMinutes(1);
    static final Duration LONGEST_WAIT = Duration.ofHours(1);
    static final int MOST_PUT_OFF = 1_000;
    /** How many proposals already made from the thread the extraction is told of. */
    static final int TITLES_TOLD = 20;
    /** How many proposals lately made from other mail of the wedding it is told of, and how far back "lately" goes. */
    static final int LATELY_TOLD = 30;
    static final Duration LATELY = Duration.ofDays(30);

    private final ReadingSteps steps;
    private final Rules rules;
    private final MailReading mail;
    private final Suggestions suggestions;
    private final Weddings weddings;
    private final ProposalMaker maker;
    private final UsageRepository usage;
    private final Clock clock;

    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean stopping;
    private volatile boolean keyNotAccepted;
    private volatile Instant waitUntil = Instant.MIN;
    private volatile int waitedInARow;
    /** Messages that could not be had for now, by their key (a hash), in the order they were noted; at most {@link #MOST_PUT_OFF}. */
    private final Map<String, PutOff> putOff = new LinkedHashMap<>();

    private record PutOff(int times, Instant until) {}

    ReadingRound(
            ReadingSteps steps,
            Rules rules,
            MailReading mail,
            Suggestions suggestions,
            Weddings weddings,
            ProposalMaker maker,
            UsageRepository usage,
            Clock clock) {
        this.steps = steps;
        this.rules = rules;
        this.mail = mail;
        this.suggestions = suggestions;
        this.weddings = weddings;
        this.maker = maker;
        this.usage = usage;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- when it runs

    /** The server's own clock for the reading: every {@code app.reading.interval}, once the previous round has ended. */
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (rules.on() && rules.automatic()) {
            registrar.addFixedDelayTask(new IntervalTask(this::poll, rules.interval(), rules.interval()));
        }
    }

    void poll() {
        if (rules.on() && rules.automatic()) {
            round();
        }
    }

    @PreDestroy
    void shutDown() {
        stopping = true;
    }

    /** Forgets what is only in memory: the pause, the refused key, the messages put off. For tests; a restart does the same. */
    void forget() {
        keyNotAccepted = false;
        waitUntil = Instant.MIN;
        waitedInARow = 0;
        synchronized (putOff) {
            putOff.clear();
        }
    }

    // ---------------------------------------------------------------- the state

    @Override
    public Report report() {
        Instant now = clock.instant();
        BigDecimal month = estimate(monthTokens(now));
        BigDecimal all = estimate(usage.tokens(UsageRepository.Step.TRIAGE), usage.tokens(UsageRepository.Step.EXTRACTION));
        return new Report(stateAt(now, month), cents(month), cents(all), cents(rules.monthlyLimitUsd()));
    }

    private State stateAt(Instant now, BigDecimal monthEstimate) {
        if (!rules.on() || !mail.isSetUp()) {
            return State.OFF;
        }
        if (keyNotAccepted) {
            return State.KEY_NOT_ACCEPTED;
        }
        if (monthEstimate.compareTo(rules.monthlyLimitUsd()) >= 0) {
            return State.MONTHLY_LIMIT;
        }
        if (usage.triagedOn(today(now)) >= rules.dailyMessages()) {
            return State.DAILY_LIMIT;
        }
        if (now.isBefore(waitUntil)) {
            return State.WAITING_FOR_THE_SERVICE;
        }
        return State.READING;
    }

    private State stateNow() {
        Instant now = clock.instant();
        return rules.on() ? stateAt(now, estimate(monthTokens(now))) : State.OFF;
    }

    private UsageRepository.Tokens[] monthTokens(Instant now) {
        YearMonth month = YearMonth.from(now.atZone(ZoneOffset.UTC));
        LocalDate from = month.atDay(1);
        LocalDate until = month.plusMonths(1).atDay(1);
        return new UsageRepository.Tokens[] {
            usage.tokens(UsageRepository.Step.TRIAGE, from, until), usage.tokens(UsageRepository.Step.EXTRACTION, from, until)
        };
    }

    private BigDecimal estimate(UsageRepository.Tokens... triageThenExtraction) {
        return UsageRepository.dollars(triageThenExtraction[0], rules.triagePrice())
                .add(UsageRepository.dollars(triageThenExtraction[1], rules.extractionPrice()));
    }

    private static long cents(BigDecimal dollars) {
        return dollars.movePointRight(2).setScale(0, RoundingMode.CEILING).longValueExact();
    }

    private static LocalDate today(Instant now) {
        return now.atZone(ZoneOffset.UTC).toLocalDate();
    }

    // ---------------------------------------------------------------- a round

    /** What became of one message. */
    private enum Result {
        /** Marked: it is read. */
        MARKED,
        /** Left as it is; the round goes on. */
        LEFT,
        /** The service asked to wait, or the key is not accepted, or there is nothing to ask: the round ends. */
        ENDS_THE_ROUND
    }

    /** What one round knows of itself. */
    private static final class Round {
        final Set<UUID> leftOut = new HashSet<>();
        int read;
        int left;
    }

    /**
     * One round: up to {@code batch} of the messages that wait, the newest first, each the whole way.
     *
     * @return how many messages were read (marked); 0 also when the reading is off or paused, or another round is under way
     */
    int round() {
        if (!rules.on() || !running.compareAndSet(false, true)) {
            return 0;
        }
        try {
            if (!mail.isSetUp() || stateNow() != State.READING) {
                return 0;
            }
            Round round = new Round();
            for (MessageHandle handle : nextBatch(round)) {
                if (stopping || stateNow() != State.READING) {
                    break;
                }
                if (round.leftOut.contains(handle.memberId())) {
                    continue;
                }
                if (suggestionsToday(handle) >= rules.dailySuggestionsPerMailbox()) {
                    round.leftOut.add(handle.memberId());
                    log.info("Member {} has reached the day's limit of new suggestions; that mailbox waits until tomorrow", handle.memberId());
                    continue;
                }
                Result result = readOne(handle, round);
                if (result == Result.MARKED) {
                    round.read++;
                } else {
                    round.left++;
                }
                if (result == Result.ENDS_THE_ROUND) {
                    break;
                }
            }
            if (round.read > 0 || round.left > 0) {
                log.info("A round of reading is over: {} read, {} left as they were", round.read, round.left);
            }
            return round.read;
        } catch (RuntimeException unexpected) {
            log.warn("A round of reading stopped unexpectedly ({})", unexpected.getClass().getSimpleName());
            return 0;
        } finally {
            running.set(false);
        }
    }

    /**
     * The messages this round takes: the newest that wait, without those put off, and without the mailboxes that have
     * reached their limit (asked again, so that such a mailbox's messages do not take the others' places).
     */
    private List<MessageHandle> nextBatch(Round round) {
        Set<UUID> askedAbout = new HashSet<>();
        Instant now = clock.instant();
        while (true) {
            int putOffNow = activePutOffs(now);
            List<MessageHandle> got = mail.waiting(rules.batch() + putOffNow, round.leftOut);
            boolean added = false;
            for (MessageHandle handle : got) {
                if (askedAbout.add(handle.memberId()) && suggestionsToday(handle) >= rules.dailySuggestionsPerMailbox()) {
                    round.leftOut.add(handle.memberId());
                    added = true;
                    log.info("Member {} has reached the day's limit of new suggestions; that mailbox waits until tomorrow", handle.memberId());
                }
            }
            if (added) {
                continue;
            }
            List<MessageHandle> batch = new ArrayList<>();
            for (MessageHandle handle : got) {
                if (batch.size() < rules.batch() && !isPutOff(handle, now)) {
                    batch.add(handle);
                }
            }
            return batch;
        }
    }

    private int suggestionsToday(MessageHandle handle) {
        Instant startOfToday = today(clock.instant()).atStartOfDay(ZoneOffset.UTC).toInstant();
        return suggestions.madeSince(handle.weddingId(), handle.memberId(), startOfToday);
    }

    // ---------------------------------------------------------------- one message

    private Result readOne(MessageHandle handle, Round round) {
        try {
            Result result = theWay(handle, round);
            if (result == Result.MARKED) {
                forgetPutOff(handle);
            }
            return result;
        } catch (RuntimeException unexpected) {
            // What the exception carries may be somebody's words: only its kind is said. The message stays as it is.
            log.warn("A message of member {} was left as it is after an unexpected failure ({})", handle.memberId(), unexpected.getClass().getSimpleName());
            putOff(handle);
            return Result.LEFT;
        }
    }

    private Result theWay(MessageHandle handle, Round round) {
        Content content = mail.read(handle);
        if (content instanceof Unavailable unavailable) {
            return unavailable(handle, unavailable.why(), round);
        }
        Letter letter = (Letter) content;
        // A letter that went to both mailboxes is read once (S04 section 2): the copy reached second is sent nowhere.
        if (mail.copyWasRead(handle, letter.key())) {
            mark(handle, new CopyOfOneRead(), letter.key());
            log.info("Read a message of member {}: a copy of a letter that was read already (nothing was asked of the AI service)",
                    handle.memberId());
            return Result.MARKED;
        }
        Sender from = new Sender(orEmpty(letter.from().name()), orEmpty(letter.from().address()));
        String subject = orEmpty(letter.subject());

        // What step two needs is asked for before anything is paid for: with no wedding or no mailbox nothing can be proposed.
        Optional<WeddingFacts> facts = weddings.factsOf(handle.weddingId());
        Optional<String> link = mail.linkTo(handle);
        if (facts.isEmpty() || link.isEmpty()) {
            round.leftOut.add(handle.memberId());
            putOff(handle);
            return Result.LEFT;
        }

        // Step one.
        Outcome<MailTriage.Triaged> triaged = steps.triage(new MailTriage.Input(from, subject, letter.arrived(), letter.text().text()));
        count(UsageRepository.Step.TRIAGE, triaged.used());
        if (triaged instanceof Outcome.Unanswered<MailTriage.Triaged> failed) {
            return stopped(handle, failed.why(), "triage", letter.key(), round);
        }
        served();
        MailTriage.Triaged verdict = ((Outcome.Answered<MailTriage.Triaged>) triaged).answer();
        // A "no" that is not sure enough is read on as a "yes": missing a message about the wedding is worse.
        if (!verdict.concernsTheWedding() && verdict.sure() >= rules.triageSure()) {
            mark(handle, new NotAboutTheWedding(), letter.key());
            log.info("Read a message of member {}: not about the wedding (triage)", handle.memberId());
            return Result.MARKED;
        }

        // Step two.
        MailReading.Keys keys = mail.keysOf(handle);
        String ownKeys = keys.thread() + "/" + keys.message() + "/";
        List<String> alreadyProposed = alreadyProposed(handle, keys.thread() + "/", ownKeys, facts.get().currency());
        LocalDate today = LocalDate.now(clock.withZone(facts.get().timeZone()));
        List<MailExtraction.EarlierMessage> earlier = letter.earlier().stream()
                .map(message -> new MailExtraction.EarlierMessage(
                        new Sender(orEmpty(message.from().name()), orEmpty(message.from().address())), message.arrived(), message.text().text()))
                .toList();
        Outcome<MailExtraction.Extracted> extracted = steps.extract(new MailExtraction.Input(
                from, subject, letter.arrived(), letter.text().text(), earlier, today, facts.get().weddingDate(), alreadyProposed));
        count(UsageRepository.Step.EXTRACTION, extracted.used());
        if (extracted instanceof Outcome.Unanswered<MailExtraction.Extracted> failed) {
            return stopped(handle, failed.why(), "extraction", letter.key(), round);
        }
        served();

        ProposalMaker.Origin origin =
                new ProposalMaker.Origin(handle.weddingId(), handle.memberId(), from.name(), from.address(), subject, letter.arrived(), link.get());
        List<MailExtraction.Proposed> proposals = ((Outcome.Answered<MailExtraction.Extracted>) extracted).answer().proposals();
        int handedIn = 0;
        for (int n = 1; n <= proposals.size(); n++) {
            Optional<io.github.trufelek124.weddingplanner.suggestions.Proposal> made =
                    maker.make(origin, proposals.get(n - 1), ownKeys + n, facts.get());
            if (made.isEmpty()) {
                continue;
            }
            try {
                suggestions.handIn(made.get());
                handedIn++;
            } catch (ApiException refused) {
                // Which fields, never what was in them.
                log.warn("A proposal of a message of member {} was refused by the suggestions: fields {}", handle.memberId(),
                        refused.fields().stream().map(FieldProblem::field).toList());
            }
        }
        // Marking is the last thing: a message that stops before it is done again and proposes nothing twice.
        mark(handle, new AboutTheWedding(handedIn), letter.key());
        log.info("Read a message of member {}: about the wedding, {} proposals of {} handed in (triage, extraction)", handle.memberId(), handedIn,
                proposals.size());
        return Result.MARKED;
    }

    /**
     * What the extraction is told was proposed, one line each, without anything twice and without what this message itself
     * proposed (an earlier try of it): first what came from this thread, then what came from any mail of the wedding in the
     * last {@link #LATELY}, at most {@link #LATELY_TOLD}.
     */
    private List<String> alreadyProposed(MessageHandle handle, String threadKeys, String ownKeys, String currency) {
        Set<String> told = new HashSet<>();
        List<String> lines = new ArrayList<>();
        suggestions.proposedUnder(handle.weddingId(), threadKeys, TITLES_TOLD + MOST_OWN).stream()
                .filter(proposed -> !proposed.sourceKey().startsWith(ownKeys))
                .limit(TITLES_TOLD)
                .forEach(proposed -> {
                    told.add(proposed.sourceKey());
                    lines.add(line(proposed, currency));
                });
        suggestions.proposedSince(handle.weddingId(), clock.instant().minus(LATELY), LATELY_TOLD + TITLES_TOLD + MOST_OWN).stream()
                .filter(proposed -> !proposed.sourceKey().startsWith(ownKeys) && !told.contains(proposed.sourceKey()))
                .limit(LATELY_TOLD)
                .forEach(proposed -> lines.add(line(proposed, currency)));
        return lines;
    }

    /** More than one message can propose (eight with documents): room for those that are filtered out afterwards. */
    private static final int MOST_OWN = 10;

    /** {@code PAYMENT | Wpłacić zaliczkę za salę | due 2026-12-01 | 5000.00 PLN}: the day and the amount only where there is one. */
    static String line(Suggestions.Proposed proposed, String currency) {
        StringBuilder line = new StringBuilder(proposed.kind().name()).append(" | ").append(proposed.title());
        if (proposed.dueDate() != null) {
            line.append(" | due ").append(proposed.dueDate());
        } else if (proposed.startDate() != null) {
            line.append(" | starts ").append(proposed.startDate());
        }
        if (proposed.amount() != null) {
            line.append(" | ").append(Money.written(proposed.amount(), currency));
        }
        return line.toString();
    }

    private Result unavailable(MessageHandle handle, MailReading.Why why, Round round) {
        switch (why) {
            case GONE -> {
                return couldNotBeRead(handle, Reason.GONE);
            }
            case NO_TEXT -> {
                return couldNotBeRead(handle, Reason.NO_TEXT);
            }
            case TOO_LARGE -> {
                return couldNotBeRead(handle, Reason.TOO_LARGE);
            }
            case NOT_CONNECTED, NEEDS_RECONNECTING, REFUSED_FOR_NOW -> {
                // The mailbox is left out of the rest of the round; its messages stay as they are.
                if (round.leftOut.add(handle.memberId())) {
                    log.info("The mailbox of member {} cannot be read for now ({}); it is left out of this round", handle.memberId(), why);
                }
                return Result.LEFT;
            }
            case INTERRUPTED -> {
                putOff(handle);
                return Result.LEFT;
            }
        }
        throw new IllegalStateException("why");
    }

    /** A step did not answer. */
    private Result stopped(MessageHandle handle, Outcome.Why why, String step, String letterKey, Round round) {
        switch (why) {
            case DECLINED -> {
                served();
                return couldNotBeRead(handle, Reason.DECLINED, step, letterKey);
            }
            case CANNOT_BE_USED -> {
                served();
                return couldNotBeRead(handle, Reason.UNUSABLE_ANSWER, step, letterKey);
            }
            case TOO_MANY_PROPOSALS -> {
                served();
                return couldNotBeRead(handle, step.equals("extraction") ? Reason.TOO_MANY_PROPOSALS : Reason.UNUSABLE_ANSWER, step, letterKey);
            }
            case TRY_LATER -> {
                waitAWhile();
                return Result.ENDS_THE_ROUND;
            }
            case NOT_ACCEPTED -> {
                keyNotAccepted = true;
                log.warn("The AI service does not accept the key or the account (asked in {}): the reading stops until the server is started again", step);
                return Result.ENDS_THE_ROUND;
            }
            case ASKED_NOTHING -> {
                return Result.ENDS_THE_ROUND;
            }
        }
        throw new IllegalStateException("why");
    }

    /** No letter was had: there is no key. */
    private Result couldNotBeRead(MessageHandle handle, Reason reason) {
        return couldNotBeRead(handle, reason, "fetching", "");
    }

    private Result couldNotBeRead(MessageHandle handle, Reason reason, String step, String letterKey) {
        mark(handle, new CouldNotBeRead(reason), letterKey);
        log.info("Read a message of member {}: could not be read ({}, in {})", handle.memberId(), reason, step);
        return Result.MARKED;
    }

    private void mark(MessageHandle handle, Verdict verdict, String letterKey) {
        mail.mark(handle, verdict, letterKey);
    }

    private static String orEmpty(String text) {
        return text == null ? "" : text;
    }

    // ---------------------------------------------------------------- counting and pausing

    /** Every call that reached the service is counted, also one whose answer could not be used. */
    private void count(UsageRepository.Step step, Used used) {
        if (!used.model().isEmpty()) {
            usage.count(today(clock.instant()), step, used.model(), used.inputTokens(), used.outputTokens());
        }
    }

    /** The service answered: the pauses start again from the shortest. */
    private void served() {
        waitedInARow = 0;
    }

    /** The service asked to wait: for 1 minute, then twice as long each time in a row, at most an hour. */
    private void waitAWhile() {
        int times = ++waitedInARow;
        Duration wait = FIRST_WAIT;
        for (int i = 1; i < times && wait.compareTo(LONGEST_WAIT) < 0; i++) {
            wait = wait.multipliedBy(2);
        }
        if (wait.compareTo(LONGEST_WAIT) > 0) {
            wait = LONGEST_WAIT;
        }
        waitUntil = clock.instant().plus(wait);
        log.info("The AI service asked the reading to wait: {} seconds ({} times in a row)", wait.toSeconds(), times);
    }

    // ---------------------------------------------------------------- messages put off

    /** Puts a message off for the next time in a row it cannot be had. */
    void putOff(MessageHandle handle) {
        String key = mail.keysOf(handle).message();
        Instant now = clock.instant();
        synchronized (putOff) {
            PutOff before = putOff.remove(key);
            int times = before == null ? 1 : before.times() + 1;
            Duration wait = FIRST_PUT_OFF;
            for (int i = 1; i < times && wait.compareTo(LONGEST_PUT_OFF) < 0; i++) {
                wait = wait.multipliedBy(2);
            }
            if (wait.compareTo(LONGEST_PUT_OFF) > 0) {
                wait = LONGEST_PUT_OFF;
            }
            putOff.put(key, new PutOff(times, now.plus(wait)));
            Iterator<String> oldest = putOff.keySet().iterator();
            while (putOff.size() > MOST_PUT_OFF && oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }
    }

    /** How many messages are put off now, expired ones included. */
    int putOffNotes() {
        synchronized (putOff) {
            return putOff.size();
        }
    }

    private void forgetPutOff(MessageHandle handle) {
        String key = mail.keysOf(handle).message();
        synchronized (putOff) {
            putOff.remove(key);
        }
    }

    private boolean isPutOff(MessageHandle handle, Instant now) {
        String key = mail.keysOf(handle).message();
        synchronized (putOff) {
            PutOff note = putOff.get(key);
            return note != null && now.isBefore(note.until());
        }
    }

    private int activePutOffs(Instant now) {
        synchronized (putOff) {
            return (int) putOff.values().stream().filter(note -> now.isBefore(note.until())).count();
        }
    }
}
