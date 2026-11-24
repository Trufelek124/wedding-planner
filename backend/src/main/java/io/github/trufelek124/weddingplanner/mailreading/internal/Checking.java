package io.github.trufelek124.weddingplanner.mailreading.internal;

import io.github.trufelek124.weddingplanner.mailreading.MailExtraction;
import io.github.trufelek124.weddingplanner.mailreading.MailExtraction.Amount;
import io.github.trufelek124.weddingplanner.mailreading.MailExtraction.Kind;
import io.github.trufelek124.weddingplanner.mailreading.MailExtraction.PaymentKind;
import io.github.trufelek124.weddingplanner.mailreading.MailExtraction.Place;
import io.github.trufelek124.weddingplanner.mailreading.MailExtraction.Proposed;
import io.github.trufelek124.weddingplanner.mailreading.MailTriage;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What comes back from the service is not trusted (S04 section 5): the shape is checked again here, and a proposal that
 * fails any check is left out while the others stay. Nothing in what the model answers is ever interpreted as
 * something to do; there is nothing here to interpret it with, only values.
 */
final class Checking {

    /** The planner's own lengths, as its forms hold them: a title and a note (plan entries), a vendor's fields, a category's name, the suggestion's passage. */
    static final int TITLE_LENGTH = 200;
    static final int VENDOR_NAME_LENGTH = 120;
    static final int NOTES_LENGTH = 10_000;
    static final int CATEGORY_LENGTH = 60;
    static final int PHONE_LENGTH = 40;
    static final int EMAIL_LENGTH = 254;
    static final int WEBSITE_LENGTH = 2_000;
    static final int PASSAGE_LENGTH = 1_000;

    private static final Pattern AMOUNT = Pattern.compile("[0-9]{1,12}(\\.[0-9]{1,2})?");
    private static final Pattern CURRENCY = Pattern.compile("[A-Za-z]{3}");
    private static final Pattern WHITE = Pattern.compile("[\\s\\p{Z}]+", Pattern.UNICODE_CHARACTER_CLASS);

    private Checking() {}

    static Optional<MailTriage.Triaged> triaged(RawTriage raw) {
        if (raw == null || raw.concernsTheWedding() == null || raw.howSure() == null || raw.howSure() < 0 || raw.howSure() > 100) {
            return Optional.empty();
        }
        return Optional.of(new MailTriage.Triaged(raw.concernsTheWedding(), raw.howSure()));
    }

    /**
     * Every text that was sent, told apart by where it stood: a passage is looked for in these only, and where it is found
     * is the server's own finding (D-014).
     *
     * @param message the message's own text
     * @param earlier the earlier messages of the thread that were kept
     * @param documents the text of the documents
     */
    record Sent(String message, List<String> earlier, List<String> documents) {

        Sent {
            earlier = List.copyOf(earlier);
            documents = List.copyOf(documents);
        }

        @Override
        public String toString() {
            return "Sent[]";
        }
    }

    /** The squeezed texts, by where they stood. */
    record Haystacks(String message, List<String> earlier, List<String> documents) {

        Place where(String wanted) {
            if (documents.stream().anyMatch(text -> text.contains(wanted))) {
                return Place.DOCUMENT;
            }
            if (message.contains(wanted)) {
                return Place.MESSAGE;
            }
            if (earlier.stream().anyMatch(text -> text.contains(wanted))) {
                return Place.EARLIER_MESSAGE;
            }
            return null;
        }
    }

    /**
     * @param sent every text that was sent. A passage is looked for in these only.
     * @return the checked proposals, or empty when the answer cannot be used at all (no list, a missing proposal, more than five)
     */
    static Optional<MailExtraction.Extracted> extracted(RawExtraction raw, Sent sent) {
        return extracted(raw, sent, MailExtraction.MOST_PROPOSALS);
    }

    /** As {@link #extracted(RawExtraction, Sent)}; {@code most} is five, or eight where a document was sent. */
    static Optional<MailExtraction.Extracted> extracted(RawExtraction raw, Sent sent, int most) {
        if (raw == null || raw.proposals() == null || raw.proposals().size() > most) {
            return Optional.empty();
        }
        Haystacks haystacks = new Haystacks(
                squeezed(sent.message()),
                sent.earlier().stream().map(Checking::squeezed).toList(),
                sent.documents().stream().map(Checking::squeezed).toList());
        List<Proposed> kept = new ArrayList<>();
        for (RawExtraction.Proposal proposal : raw.proposals()) {
            if (proposal == null) {
                return Optional.empty();
            }
            proposed(proposal, haystacks).ifPresent(kept::add);
        }
        return Optional.of(new MailExtraction.Extracted(kept));
    }

    static Optional<Proposed> proposed(RawExtraction.Proposal raw, Haystacks squeezedTexts) {
        Kind kind = kind(raw.kind());
        if (kind == null || raw.howSure() == null || raw.howSure() < 0 || raw.howSure() > 100) {
            return Optional.empty();
        }
        String title = oneLine(raw.title());
        String vendorName = oneLine(raw.vendorName());
        if (title.isEmpty() || length(title) > (kind == Kind.VENDOR ? VENDOR_NAME_LENGTH : TITLE_LENGTH) || length(vendorName) > VENDOR_NAME_LENGTH) {
            return Optional.empty();
        }
        String notes = lines(raw.notes());
        String category = oneLine(raw.category());
        String email = oneLine(raw.vendorEmail());
        String phone = oneLine(raw.vendorPhone());
        String website = oneLine(raw.vendorWebsite());
        String passage = lines(raw.passage());
        if (length(notes) > NOTES_LENGTH || length(category) > CATEGORY_LENGTH || length(email) > EMAIL_LENGTH
                || length(phone) > PHONE_LENGTH || length(website) > WEBSITE_LENGTH || length(passage) > PASSAGE_LENGTH) {
            return Optional.empty();
        }
        // The passage must stand in what was sent, compared without regard to line breaks and runs of spaces.
        String wanted = squeezed(raw.passage());
        Place place = wanted.isEmpty() || squeezed(passage).isEmpty() ? null : squeezedTexts.where(wanted);
        if (place == null) {
            return Optional.empty();
        }
        LocalDate first = day(raw.firstDay());
        LocalDate due = day(raw.dueDay());
        if ((first == null && !blank(raw.firstDay())) || (due == null && !blank(raw.dueDay()))) {
            return Optional.empty();
        }
        Amount amount = null;
        String amountText = raw.amount() == null ? "" : raw.amount().strip();
        String currency = raw.currency() == null ? "" : raw.currency().strip();
        if (!amountText.isEmpty()) {
            // No currency named is let through as an empty one: the caller says in the notes that the planner cannot count it.
            if (!AMOUNT.matcher(amountText).matches() || (!currency.isEmpty() && !CURRENCY.matcher(currency).matches())) {
                return Optional.empty();
            }
            amount = new Amount(amountText, currency.toUpperCase(Locale.ROOT));
        } else if (!currency.isEmpty() && !CURRENCY.matcher(currency).matches()) {
            return Optional.empty();
        }
        PaymentKind paymentKind = null;
        String paymentText = raw.paymentKind() == null ? "" : raw.paymentKind().strip();
        if (!paymentText.isEmpty()) {
            paymentKind = paymentKind(paymentText);
            if (paymentKind == null) {
                return Optional.empty();
            }
            if (kind != Kind.PAYMENT) {
                paymentKind = null;
            }
        }
        return Optional.of(new Proposed(
                kind, title, notes, first, due, amount, paymentKind, none(category), none(vendorName), none(email), none(phone),
                none(website), raw.howSure(), passage, place));
    }

    private static Kind kind(String text) {
        if (text == null) {
            return null;
        }
        for (Kind kind : Kind.values()) {
            if (kind.name().equals(text.strip())) {
                return kind;
            }
        }
        return null;
    }

    private static PaymentKind paymentKind(String text) {
        for (PaymentKind kind : PaymentKind.values()) {
            if (kind.name().equals(text)) {
                return kind;
            }
        }
        return null;
    }

    /** A real day written YYYY-MM-DD; null for none or for anything else (the caller tells the two apart). */
    private static LocalDate day(String text) {
        if (blank(text)) {
            return null;
        }
        try {
            return LocalDate.parse(text.strip());
        } catch (DateTimeParseException notADay) {
            return null;
        }
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private static String none(String text) {
        return text.isEmpty() ? null : text;
    }

    private static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    /** The text without line breaks and runs of spaces, in one normal form: what a passage is looked for in. */
    static String squeezed(String text) {
        return WHITE.matcher(Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFC)).replaceAll(" ").strip();
    }

    /** A single-line text: control characters, line breaks included, become a space; marks that only steer text direction or hide are taken out. */
    static String oneLine(String text) {
        return clean(text, false);
    }

    /** A text of several lines: line breaks stay (as \n), every other control character goes. */
    static String lines(String text) {
        return clean(text, true);
    }

    private static String clean(String text, boolean keepLines) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        String prepared = keepLines ? text.replace("\r\n", "\n").replace('\r', '\n') : text;
        prepared.codePoints().forEach(point -> {
            int type = Character.getType(point);
            if (point == '\n' && keepLines) {
                out.append('\n');
            } else if (keepLines && type == Character.CONTROL && point != '\t') {
                // Taken out, and nothing put in its place: it stood between words only by accident.
                return;
            } else if (type == Character.CONTROL || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                out.append(' ');
            } else if (type == Character.FORMAT || type == Character.SURROGATE || type == Character.UNASSIGNED || type == Character.PRIVATE_USE) {
                // Taken out: it either says nothing or steers how the text looks.
                return;
            } else {
                out.appendCodePoint(point);
            }
        });
        String cleaned = out.toString().strip();
        // A control character that stood between words must not leave two spaces behind.
        return keepLines ? cleaned : cleaned.replaceAll(" {2,}", " ");
    }
}
