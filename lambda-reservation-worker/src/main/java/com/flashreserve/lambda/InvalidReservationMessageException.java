package com.flashreserve.lambda;

/**
 * A reservation message that can never be processed successfully, no matter
 * how often it is retried: malformed JSON, wrong field types, missing
 * fields, or failed validation (for example quantity <= 0).
 *
 * The SQS handler acknowledges these messages instead of retrying them, so
 * one bad message cannot block its FIFO message group.
 *
 * Extends IllegalArgumentException so existing callers that expect an
 * IllegalArgumentException for invalid input keep working.
 */
public class InvalidReservationMessageException
        extends IllegalArgumentException {

    public InvalidReservationMessageException(String message) {
        super(message);
    }

    public InvalidReservationMessageException(
            String message, Throwable cause) {
        super(message, cause);
    }
}
