package io.github.trufelek124.weddingplanner.mailreading;

import java.util.Objects;

/** Who wrote a message. The name may be empty; the address is as the mail has it. */
public record Sender(String name, String address) {

    public Sender {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(address, "address");
    }

    /** Somebody else's name and address: nothing of them is printed. */
    @Override
    public String toString() {
        return "Sender[]";
    }
}
