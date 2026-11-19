package io.github.trufelek124.weddingplanner.mailreading.internal;

import io.github.trufelek124.weddingplanner.mail.MailReading;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * What the mail module offers, with a test's hand on it: what was read and marked is noted, a message can be made to read as
 * something else, a mark can be made to fail (the server "stops" between handing in and marking), and a link can be made to
 * fail. Everything else is the real thing.
 */
final class WatchedMail implements MailReading {

    private final MailReading real;
    final List<MessageHandle> read = new CopyOnWriteArrayList<>();
    final List<Verdict> marked = new CopyOnWriteArrayList<>();
    volatile Function<MessageHandle, Content> readsAs = handle -> null;
    volatile int marksToFail;
    volatile RuntimeException linkFails;
    volatile boolean noLink;
    volatile UUID noLinkFor;

    WatchedMail(MailReading real) {
        this.real = real;
    }

    @Override
    public boolean isSetUp() {
        return real.isSetUp();
    }

    @Override
    public List<MessageHandle> waiting(int most) {
        return real.waiting(most);
    }

    @Override
    public List<MessageHandle> waiting(int most, Collection<UUID> leavingOut) {
        return real.waiting(most, leavingOut);
    }

    @Override
    public Optional<String> linkTo(MessageHandle message) {
        if (linkFails != null) {
            throw linkFails;
        }
        return noLink || message.memberId().equals(noLinkFor) ? Optional.empty() : real.linkTo(message);
    }

    @Override
    public Content read(MessageHandle message) {
        read.add(message);
        Content instead = readsAs.apply(message);
        return instead != null ? instead : real.read(message);
    }

    @Override
    public Keys keysOf(MessageHandle message) {
        return real.keysOf(message);
    }

    @Override
    public boolean mark(MessageHandle message, Verdict verdict) {
        if (marksToFail > 0) {
            marksToFail--;
            throw new IllegalStateException("The server stopped here; the text of this exception is Secret-Qx");
        }
        marked.add(verdict);
        return real.mark(message, verdict);
    }

    @Override
    public Counts counts(UUID weddingId) {
        return real.counts(weddingId);
    }
}
