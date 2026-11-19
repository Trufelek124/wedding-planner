package io.github.trufelek124.weddingplanner.mailreading.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What a message says of a business's contact details, kept only when it has the shape of what it claims to be. The
 * values are somebody's words: nothing here prints one.
 */
final class ContactDetails {

    static final int EMAIL_LENGTH = 254;
    static final int PHONE_LENGTH = 40;
    static final int WEBSITE_LENGTH = 2_000;

    private static final Pattern EMAIL = Pattern.compile("[^\\s@<>(),;:\\\\\"\\[\\]\\p{Cntrl}]+@[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+");
    private static final Pattern HAS_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://.*");
    /** A host and nothing before it: {@code palace.example}, {@code www.palace.example/offer}. */
    private static final Pattern PLAIN_HOST = Pattern.compile(
            "^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+(/[^\\s\\p{Cntrl}\\\\]*)?$");
    private static final Pattern SPACE_OR_CONTROL = Pattern.compile("[\\s\\p{Cntrl}\\\\]");

    private ContactDetails() {}

    /** The address when it has the form of one; otherwise null. */
    static String email(String value) {
        if (value == null || value.length() > EMAIL_LENGTH || !EMAIL.matcher(value).matches()) {
            return null;
        }
        return value;
    }

    /** The number when it is short enough; otherwise null. */
    static String phone(String value) {
        return value == null || value.isBlank() || value.length() > PHONE_LENGTH ? null : value;
    }

    /**
     * An {@code http} or {@code https} address, or null. A host written without a scheme gets {@code https://}; anything
     * else ({@code javascript:}, {@code ftp:}, {@code mailto:}, a path, a phrase) is left out.
     */
    static String website(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String address = value.strip();
        if (HAS_SCHEME.matcher(address).matches()) {
            return httpAddress(address) ? address : null;
        }
        if (PLAIN_HOST.matcher(address).matches()) {
            String made = "https://" + address;
            return made.length() <= WEBSITE_LENGTH && httpAddress(made) ? made : null;
        }
        return null;
    }

    private static boolean httpAddress(String address) {
        if (address.length() > WEBSITE_LENGTH || SPACE_OR_CONTROL.matcher(address).find()) {
            return false;
        }
        try {
            URI uri = new URI(address);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null && uri.getRawUserInfo() == null;
        } catch (URISyntaxException notAnAddress) {
            return false;
        }
    }
}
