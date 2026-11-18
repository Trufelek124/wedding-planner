package io.github.trufelek124.weddingplanner.inspirations.internal;

import io.github.trufelek124.weddingplanner.inspirations.internal.InspirationRepository.PendingPreview;
import io.github.trufelek124.weddingplanner.inspirations.internal.InspirationTypes.PreviewStatus;
import io.github.trufelek124.weddingplanner.inspirations.internal.PreviewReader.Outcome;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fetches previews for saved links, apart from the request that saved them: a save never waits for
 * someone else's web server. What is still to be fetched is written in the database, so nothing is
 * forgotten when the server restarts.
 */
@Component
@EnableScheduling
class PreviewWorker {

    /** After this many tries without an answer a link is left without a preview. */
    static final int MOST_ATTEMPTS = 3;

    private static final int AT_ONCE = 10;
    private static final Duration PAUSE_PER_ATTEMPT = Duration.ofMinutes(2);

    private final InspirationRepository inspirations;
    private final PictureService pictures;
    private final PreviewReader reader;
    private final TransactionTemplate transactions;
    private final TaskExecutor executor;
    private final Clock clock;
    private final boolean automatic;
    private final AtomicBoolean running = new AtomicBoolean();

    PreviewWorker(
            InspirationRepository inspirations,
            PictureService pictures,
            PreviewReader reader,
            TransactionTemplate transactions,
            @Qualifier("applicationTaskExecutor") TaskExecutor executor,
            Clock clock,
            @Value("${app.previews.automatic}") boolean automatic) {
        this.inspirations = inspirations;
        this.pictures = pictures;
        this.reader = reader;
        this.transactions = transactions;
        this.executor = executor;
        this.clock = clock;
        this.automatic = automatic;
    }

    /** A link was just saved: fetch its preview soon, once the save is certain. */
    void wakeOnceSaved() {
        if (!automatic) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                executor.execute(PreviewWorker.this::runOnce);
            }
        });
    }

    /** Picks up what a wake-up missed: links saved just before a restart, and the ones to try again. */
    @Scheduled(fixedDelayString = "${app.previews.poll-interval}", initialDelayString = "${app.previews.poll-interval}")
    void poll() {
        if (automatic) {
            runOnce();
        }
    }

    /**
     * Fetches the previews whose turn has come.
     *
     * @return how many links were looked at; 0 also when another run is under way
     */
    int runOnce() {
        if (!running.compareAndSet(false, true)) {
            return 0;
        }
        try {
            List<PendingPreview> due = inspirations.pendingPreviews(clock.instant(), AT_ONCE);
            due.forEach(this::fetch);
            return due.size();
        } finally {
            running.set(false);
        }
    }

    private void fetch(PendingPreview pending) {
        Outcome outcome;
        try {
            outcome = reader.read(new URI(pending.url()));
        } catch (URISyntaxException | RuntimeException unreadable) {
            outcome = new Outcome.Nothing();
        }
        Outcome fetched = outcome;
        // The fetching is done; only now is the database touched, and briefly.
        transactions.executeWithoutResult(transaction -> record(pending, fetched));
    }

    private void record(PendingPreview pending, Outcome outcome) {
        if (!inspirations.stillWaitsForPreview(pending.id(), pending.url())) {
            return; // the link was changed or the inspiration deleted while its page was being fetched
        }
        switch (outcome) {
            case Outcome.Found found -> {
                boolean kept = found.picture() != null && pictures.keepPreview(pending.id(), found.picture());
                boolean something = kept || !found.title().isEmpty();
                inspirations.finishPreview(
                        pending.id(), pending.url(), something ? PreviewStatus.FETCHED : PreviewStatus.NONE, found.title());
            }
            case Outcome.Nothing nothing -> inspirations.finishPreview(pending.id(), pending.url(), PreviewStatus.NONE, "");
            case Outcome.TryAgain again -> {
                int attempts = pending.attempts() + 1;
                if (attempts >= MOST_ATTEMPTS) {
                    inspirations.finishPreview(pending.id(), pending.url(), PreviewStatus.NONE, "");
                } else {
                    inspirations.tryPreviewLater(
                            pending.id(), pending.url(), attempts, clock.instant().plus(PAUSE_PER_ATTEMPT.multipliedBy(attempts)));
                }
            }
        }
    }
}
