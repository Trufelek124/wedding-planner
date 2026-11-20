package io.github.trufelek124.weddingplanner.mailreading;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Step two: what does a message that concerns the wedding ask the owners to do? A larger model reads the whole
 * message, the earlier messages of its thread, today's date, the wedding's date and what was already proposed (from the
 * thread, and lately from any mail of the wedding), and proposes at most five entries - eight when the text of the message's
 * PDF documents is sent with it (D-011). What comes back has been checked here (S04 section 5): a proposal that fails a check
 * is left out and the others stay; more proposals than that is no answer at all.
 */
public interface MailExtraction {

    /** At most this many characters of text in all: the message's own and the earlier messages of its thread. */
    int MOST_CHARACTERS = 60_000;

    /** An answer with more proposals than this is not used. */
    int MOST_PROPOSALS = 5;

    /** The same for a request that carries the text of at least one document: an offer often names several payments. */
    int MOST_PROPOSALS_WITH_DOCUMENTS = 8;

    /** How many documents one request carries at most. */
    int MOST_DOCUMENTS = 3;

    /** At most this many characters of documents' text in all, over every document of one request. */
    int MOST_DOCUMENT_CHARACTERS = 40_000;

    /**
     * Never throws for what the service does or answers. With no key set it answers {@code ASKED_NOTHING} and asks
     * nothing of anybody.
     */
    Outcome<Extracted> extract(Input message);

    /**
     * @param from who wrote the message
     * @param subject may be empty
     * @param text the whole text of the message; it is always sent in full
     * @param earlier the earlier messages of the thread, <b>oldest first</b>. When they do not fit in
     *     {@link #MOST_CHARACTERS} together with the text, the oldest are left out, whole, and the request says so
     * @param today the date where the wedding takes place, today
     * @param weddingDate the wedding's date, or null while none is set
     * @param alreadyProposed what was already proposed, one text per proposal: first what came from this thread, then what
     *     came lately from other mail of the wedding, so that none is proposed again
     * @param documents the text of the PDF documents that came with the message, in the order of the mail: at most
     *     {@link #MOST_DOCUMENTS}, {@link #MOST_DOCUMENT_CHARACTERS} characters in all. Somebody else's words like the message's
     */
    record Input(
            Sender from,
            String subject,
            Instant arrived,
            String text,
            List<EarlierMessage> earlier,
            LocalDate today,
            LocalDate weddingDate,
            List<String> alreadyProposed,
            List<Document> documents) {

        /** A request that carries no documents. */
        public Input(
                Sender from,
                String subject,
                Instant arrived,
                String text,
                List<EarlierMessage> earlier,
                LocalDate today,
                LocalDate weddingDate,
                List<String> alreadyProposed) {
            this(from, subject, arrived, text, earlier, today, weddingDate, alreadyProposed, List.of());
        }

        public Input {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(arrived, "arrived");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(today, "today");
            earlier = List.copyOf(earlier);
            alreadyProposed = List.copyOf(alreadyProposed);
            documents = List.copyOf(documents);
            if (documents.size() > MOST_DOCUMENTS
                    || documents.stream().mapToLong(document -> document.text().codePointCount(0, document.text().length())).sum()
                            > MOST_DOCUMENT_CHARACTERS) {
                throw new IllegalArgumentException("documents");
            }
        }

        /** How many proposals an answer to this request may hold. */
        public int mostProposals() {
            return documents.isEmpty() ? MOST_PROPOSALS : MOST_PROPOSALS_WITH_DOCUMENTS;
        }

        /** Somebody else's words: nothing of them is printed. */
        @Override
        public String toString() {
            return "Input[]";
        }
    }

    /**
     * A PDF document that came with the message, as the server took its text out of it. Somebody else's words.
     *
     * @param name the file's name, as one line of plain text; may be empty
     * @param text what the document says, never empty
     * @param cut whether the document goes on beyond this text: it has more pages or more words than were taken, or the room
     *     for documents ended here
     */
    record Document(String name, String text, boolean cut) {

        public Document {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(text, "text");
        }

        @Override
        public String toString() {
            return "Document[]";
        }
    }

    /** A message of the thread that arrived before the one asked about. */
    record EarlierMessage(Sender from, Instant arrived, String text) {

        public EarlierMessage {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(arrived, "arrived");
            Objects.requireNonNull(text, "text");
        }

        @Override
        public String toString() {
            return "EarlierMessage[]";
        }
    }

    enum Kind {
        TASK,
        PAYMENT,
        PLAN_ITEM,
        VENDOR
    }

    enum PaymentKind {
        DEPOSIT,
        INSTALMENT,
        FINAL
    }

    /**
     * An amount as the mail writes it. The model does no arithmetic on money: turning this into whole numbers of the
     * smallest unit, and deciding what to do with another currency, is the caller's.
     *
     * @param value digits with a point and at most two decimals, never negative: "2500", "2500.50"
     * @param currency three capital letters, as the mail names it: "PLN"; empty when the mail names no currency
     */
    record Amount(String value, String currency) {}

    /**
     * One checked proposal. Every text is plain text with no control characters, within the planner's lengths; what
     * the mail does not say is null. {@code passage} is found, word for word, in the text that was sent.
     *
     * @param title for a vendor, its name
     * @param notes may be empty
     * @param startDate a plan item's first day
     * @param dueDate the day it is due or ends
     * @param amount only where the mail names one
     * @param paymentKind only for a payment, and only where the mail says which kind
     * @param category a category by name; matching it with the planner's is not done here
     * @param sure how sure the model is, 0 to 100
     */
    record Proposed(
            Kind kind,
            String title,
            String notes,
            LocalDate startDate,
            LocalDate dueDate,
            Amount amount,
            PaymentKind paymentKind,
            String category,
            String vendorName,
            String vendorEmail,
            String vendorPhone,
            String vendorWebsite,
            int sure,
            String passage) {

        /** Holds parts of somebody's mail: nothing of it is printed. */
        @Override
        public String toString() {
            return "Proposed[kind=" + kind + "]";
        }
    }

    /**
     * Zero to five checked proposals (eight with documents): a message that concerns the wedding but asks for nothing has none.
     */
    record Extracted(List<Proposed> proposals) {

        public Extracted {
            proposals = List.copyOf(proposals);
            if (proposals.size() > MOST_PROPOSALS_WITH_DOCUMENTS) {
                throw new IllegalArgumentException("proposals");
            }
        }

        @Override
        public String toString() {
            return "Extracted[proposals=" + proposals.size() + "]";
        }
    }
}
