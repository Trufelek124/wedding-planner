package io.github.trufelek124.weddingplanner.mail.internal;

import io.github.trufelek124.weddingplanner.mail.internal.MailSource.Earlier;
import io.github.trufelek124.weddingplanner.mail.internal.MailSource.Fetch;
import io.github.trufelek124.weddingplanner.mail.internal.MailSource.Found;
import io.github.trufelek124.weddingplanner.mail.internal.MailSource.Gone;
import io.github.trufelek124.weddingplanner.mail.internal.MailSource.Letter;
import io.github.trufelek124.weddingplanner.mail.internal.MailSource.NoText;
import io.github.trufelek124.weddingplanner.mail.internal.MailSource.Person;
import io.github.trufelek124.weddingplanner.mail.internal.MailSource.Text;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
import tools.jackson.databind.JsonNode;

/**
 * What a Gmail thread (as {@code users.threads.get} gives it with its messages in full) says, in the words of
 * {@link MailSource}: the message asked for with the earlier messages of its thread. It reads only what is in the
 * answer and never asks for anything: an attachment (a part with a file name, an attachment identifier or a
 * disposition as attachment) is skipped whatever its type. Everything it holds is somebody else's words and none of
 * it is logged or put in an exception.
 */
final class GmailLetters {

    /** What is not a message of the mailbox's reading: drafts, spam and the bin. What the owner sent is kept. */
    private static final Set<String> LEFT_OUT = Set.of("DRAFT", "SPAM", "TRASH");

    private static final int LONGEST_NAME = 200;
    private static final int LONGEST_ADDRESS = 320;
    private static final int LONGEST_SUBJECT = 1000;
    private static final int DEEPEST_PARTS = 24;

    private static final Pattern CHARSET = Pattern.compile("charset\\s*=\\s*\"?([^\";\\s]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENCODED_WORD = Pattern.compile("=\\?([^?\\s]+)\\?([BbQq])\\?([^?\\s]*)\\?=");
    /** Elements that stand apart from what is around them by an empty line, and those that only start a line. */
    private static final Set<String> APART = Set.of(
            "p", "ul", "ol", "table", "blockquote", "pre", "section", "article", "h1", "h2", "h3", "h4", "h5", "h6", "dl", "figure");
    private static final Set<String> LINES = Set.of("div", "li", "tr", "hr", "header", "footer", "dt", "dd", "address");

    private GmailLetters() {}

    /** The message with its thread. {@link Gone} when the thread does not hold it or it is in the bin, spam or a draft. */
    static Fetch of(JsonNode thread, String messageId) {
        List<JsonNode> messages = new ArrayList<>();
        JsonNode all = thread.get("messages");
        if (all != null && all.isArray()) {
            all.forEach(messages::add);
        }
        int at = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messageId.equals(messages.get(i).path("id").asString(""))) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            return new Gone();
        }
        JsonNode target = messages.get(at);
        if (leftOut(target)) {
            return new Gone();
        }
        String body = textOf(target.get("payload"));
        if (body.isBlank()) {
            return new NoText();
        }
        Instant arrived = arrived(target);
        List<Integer> before = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (i == at || leftOut(messages.get(i))) {
                continue;
            }
            int compared = arrived(messages.get(i)).compareTo(arrived);
            if (compared < 0 || (compared == 0 && i < at)) {
                before.add(i);
            }
        }
        before.sort(Comparator.<Integer, Instant>comparing(i -> arrived(messages.get(i))).thenComparing(Comparator.naturalOrder()));
        List<Earlier> earlier = new ArrayList<>();
        for (int i : before) {
            JsonNode message = messages.get(i);
            earlier.add(new Earlier(person(header(message, "From")), arrived(message), cut(textOf(message.get("payload")))));
        }
        return new Found(new Letter(
                person(header(target, "From")), bounded(decodedWords(header(target, "Subject")), LONGEST_SUBJECT), arrived, cut(body),
                earlier, identifierOf(header(target, "Message-ID"))));
    }

    /**
     * What the sender called the letter: the {@code Message-ID} header's value without the spaces and the angle brackets
     * around it, and nothing else changed (a different case is another identifier). Empty when there is none or it is blank.
     */
    static String identifierOf(String header) {
        String identifier = header.strip();
        if (identifier.startsWith("<")) {
            identifier = identifier.substring(1);
        }
        if (identifier.endsWith(">")) {
            identifier = identifier.substring(0, identifier.length() - 1);
        }
        return identifier.strip();
    }

    private static boolean leftOut(JsonNode message) {
        JsonNode labels = message.get("labelIds");
        if (labels != null && labels.isArray()) {
            for (JsonNode label : labels) {
                if (label.isString() && LEFT_OUT.contains(label.asString())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Instant arrived(JsonNode message) {
        JsonNode value = message.get("internalDate");
        if (value != null && value.isValueNode() && !value.isNull()) {
            try {
                return Instant.ofEpochMilli(Long.parseLong(value.asString().trim()));
            } catch (RuntimeException notANumber) {
                // Falls through: an arrival that cannot be read is the beginning of time, not a failure.
            }
        }
        return Instant.EPOCH;
    }

    // ---------------------------------------------------------------- the headers

    /** The value of the header of this message's own part, or empty. Folded lines are made one. */
    private static String header(JsonNode message, String name) {
        return headerOf(message.get("payload"), name);
    }

    private static String headerOf(JsonNode part, String name) {
        JsonNode headers = part == null ? null : part.get("headers");
        if (headers != null && headers.isArray()) {
            for (JsonNode header : headers) {
                if (name.equalsIgnoreCase(header.path("name").asString(""))) {
                    return header.path("value").asString("").replaceAll("\\r?\\n[ \\t]+", " ").replaceAll("[\\p{Cntrl}&&[^\\t]]", " ").trim();
                }
            }
        }
        return "";
    }

    /** {@code Name <address>}, {@code "Name" <address>}, {@code <address>} or {@code address}. */
    static Person person(String raw) {
        String name = "";
        String address = raw;
        int open = raw.lastIndexOf('<');
        int close = raw.lastIndexOf('>');
        if (open >= 0 && close > open) {
            address = raw.substring(open + 1, close);
            name = raw.substring(0, open).trim();
            if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
                name = name.substring(1, name.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
            }
        }
        return new Person(bounded(decodedWords(name).trim(), LONGEST_NAME), bounded(address.trim(), LONGEST_ADDRESS));
    }

    private static String bounded(String text, int most) {
        return text.length() <= most ? text : text.substring(0, most);
    }

    /** RFC 2047 words ({@code =?UTF-8?B?...?=}, {@code =?ISO-8859-2?Q?...?=}) turned into the text they stand for. */
    static String decodedWords(String text) {
        if (!text.contains("=?")) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        Matcher matcher = ENCODED_WORD.matcher(text);
        int end = 0;
        String runCharset = null;
        ByteArrayOutputStream run = new ByteArrayOutputStream();
        while (matcher.find()) {
            String gap = text.substring(end, matcher.start());
            boolean adjacent = runCharset != null && gap.isBlank();
            String charset = matcher.group(1).replaceFirst("\\*.*$", "").toLowerCase(Locale.ROOT);
            byte[] bytes = bytesOf(matcher.group(2), matcher.group(3));
            if (bytes == null) {
                flush(out, run, runCharset);
                runCharset = null;
                out.append(gap).append(matcher.group());
            } else {
                if (!adjacent || !charset.equals(runCharset)) {
                    flush(out, run, runCharset);
                    runCharset = charset;
                    // Between two words of different kinds the blanks stay; between two encoded words they go.
                    if (!adjacent) {
                        out.append(gap);
                    }
                }
                run.writeBytes(bytes);
            }
            end = matcher.end();
        }
        flush(out, run, runCharset);
        return out.append(text.substring(end)).toString();
    }

    private static void flush(StringBuilder out, ByteArrayOutputStream run, String charset) {
        if (run.size() > 0) {
            out.append(new String(run.toByteArray(), charsetOf(charset)));
            run.reset();
        }
    }

    private static byte[] bytesOf(String encoding, String word) {
        try {
            if (encoding.equalsIgnoreCase("B")) {
                return Base64.getDecoder().decode(word);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (int i = 0; i < word.length(); i++) {
                char c = word.charAt(i);
                if (c == '_') {
                    bytes.write(' ');
                } else if (c == '=' && i + 2 < word.length()) {
                    bytes.write(Integer.parseInt(word.substring(i + 1, i + 3), 16));
                    i += 2;
                } else if (c == '=') {
                    return null;
                } else {
                    bytes.write(c);
                }
            }
            return bytes.toByteArray();
        } catch (RuntimeException notEncodedAsSaid) {
            return null;
        }
    }

    private static Charset charsetOf(String name) {
        try {
            return name == null ? StandardCharsets.UTF_8 : Charset.forName(name);
        } catch (RuntimeException unknown) {
            return StandardCharsets.UTF_8;
        }
    }

    // ---------------------------------------------------------------- the text

    /** The text of a message: its plain part, or where there is none its HTML part as plain text. Empty when there is none. */
    private static String textOf(JsonNode payload) {
        List<String> plain = new ArrayList<>();
        List<String> html = new ArrayList<>();
        collect(payload, plain, html, 0);
        String text = joined(plain);
        return text.isBlank() ? joined(html) : text;
    }

    private static String joined(List<String> parts) {
        return String.join("\n\n", parts.stream().filter(part -> !part.isBlank()).toList()).strip();
    }

    private static void collect(JsonNode part, List<String> plain, List<String> html, int depth) {
        if (part == null || !part.isObject() || depth > DEEPEST_PARTS || attachment(part)) {
            return;
        }
        String type = part.path("mimeType").asString("").toLowerCase(Locale.ROOT).trim();
        JsonNode children = part.get("parts");
        if (children != null && children.isArray()) {
            for (JsonNode child : children) {
                collect(child, plain, html, depth + 1);
            }
        } else if (type.equals("text/plain")) {
            plain.add(plainText(decoded(part)));
        } else if (type.equals("text/html")) {
            html.add(htmlText(decoded(part)));
        }
    }

    /** A part with a file name, an attachment identifier or the disposition "attachment" is a file, not the message's text. */
    private static boolean attachment(JsonNode part) {
        if (!part.path("filename").asString("").isBlank()) {
            return true;
        }
        JsonNode body = part.get("body");
        if (body != null && body.hasNonNull("attachmentId")) {
            return true;
        }
        return headerOf(part, "Content-Disposition").toLowerCase(Locale.ROOT).startsWith("attachment");
    }

    /** The part's body: base64url, in the part's own charset (UTF-8 when it names none, or one that is not known). */
    private static String decoded(JsonNode part) {
        String data = part.path("body").path("data").asString("");
        if (data.isEmpty()) {
            return "";
        }
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(data);
        } catch (IllegalArgumentException notBase64) {
            return "";
        }
        Matcher charset = CHARSET.matcher(headerOf(part, "Content-Type"));
        return new String(bytes, charsetOf(charset.find() ? charset.group(1) : null));
    }

    static String plainText(String text) {
        return tidy(text.replace("\r\n", "\n").replace('\r', '\n'));
    }

    /** An HTML page as the text a person would read: no markup, no script, no style, no comment; blocks on their own lines. */
    static String htmlText(String html) {
        Document document = Jsoup.parse(html);
        document.select("script, style, noscript, template, head, svg, iframe, object, embed, applet").remove();
        StringBuilder out = new StringBuilder();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode text) {
                    out.append(text.text());
                } else if (node instanceof Element element) {
                    if (element.nameIs("br")) {
                        out.append('\n');
                    } else if (APART.contains(element.normalName())) {
                        newlines(out, 2);
                    } else if (LINES.contains(element.normalName())) {
                        newlines(out, 1);
                    } else if (element.nameIs("td") || element.nameIs("th")) {
                        out.append(' ');
                    }
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element element) {
                    if (APART.contains(element.normalName())) {
                        newlines(out, 2);
                    } else if (LINES.contains(element.normalName())) {
                        newlines(out, 1);
                    }
                }
            }
        }, document.body() == null ? document : document.body());
        return tidy(out.toString());
    }

    /** Makes the text end in at least this many line breaks (not more than are there already). */
    private static void newlines(StringBuilder out, int wanted) {
        int there = 0;
        while (there < wanted && out.length() > there && out.charAt(out.length() - 1 - there) == '\n') {
            there++;
        }
        for (int i = there; i < wanted; i++) {
            out.append('\n');
        }
    }

    /** Blanks made one, lines trimmed, no more than one empty line in a row. */
    private static String tidy(String text) {
        String[] lines = text.replace('\u00a0', ' ').replace("\u200b", "").split("\n", -1);
        StringBuilder out = new StringBuilder();
        int empty = 0;
        for (String line : lines) {
            String trimmed = line.replaceAll("[ \\t]+", " ").strip();
            if (trimmed.isEmpty()) {
                empty++;
                continue;
            }
            if (out.length() > 0) {
                out.append(empty > 0 ? "\n\n" : "\n");
            }
            empty = 0;
            out.append(trimmed);
        }
        return out.toString();
    }

    /** Cut at {@link MailSource#MOST_CHARACTERS} characters, not in the middle of a character. */
    static Text cut(String text) {
        if (text.length() <= MailSource.MOST_CHARACTERS) {
            return new Text(text, false);
        }
        int end = MailSource.MOST_CHARACTERS;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return new Text(text.substring(0, end), true);
    }
}
