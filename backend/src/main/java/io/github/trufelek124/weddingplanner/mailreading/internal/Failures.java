package io.github.trufelek124.weddingplanner.mailreading.internal;

import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.AnthropicServiceException;
import io.github.trufelek124.weddingplanner.mailreading.Outcome;

/**
 * What a failed call comes to. An exception from the SDK can carry what was sent or what was answered, so nothing of
 * it is read except its kind and its status: its message is never logged, rethrown or kept.
 */
final class Failures {

    private Failures() {}

    static Outcome.Why why(RuntimeException failure) {
        if (failure instanceof AnthropicServiceException service) {
            int status = service.statusCode();
            if (status == 401 || status == 402 || status == 403 || status == 404) {
                // The key, the account, the model: the same answer for every message, so the reading stops.
                return Outcome.Why.NOT_ACCEPTED;
            }
            if (status == 408 || status == 409 || status == 429 || status >= 500) {
                return Outcome.Why.TRY_LATER;
            }
            return Outcome.Why.CANNOT_BE_USED;
        }
        if (failure instanceof AnthropicIoException || failure instanceof AnthropicRetryableException) {
            return Outcome.Why.TRY_LATER;
        }
        if (failure instanceof AnthropicInvalidDataException) {
            return Outcome.Why.CANNOT_BE_USED;
        }
        // Something unforeseen is no reason to give a message a verdict for good.
        return Outcome.Why.TRY_LATER;
    }

    /** Which status, for the log: 0 when the failure had none. */
    static int status(RuntimeException failure) {
        return failure instanceof AnthropicServiceException service ? service.statusCode() : 0;
    }
}
