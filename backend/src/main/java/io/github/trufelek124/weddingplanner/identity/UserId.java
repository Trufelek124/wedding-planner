package io.github.trufelek124.weddingplanner.identity;

import java.util.UUID;

/**
 * The signed-in person. A controller method in any module that declares a parameter of this
 * type receives the caller's id; the request is refused with 401 before it gets there otherwise.
 */
public record UserId(UUID value) {

    @Override
    public String toString() {
        return value.toString();
    }
}
