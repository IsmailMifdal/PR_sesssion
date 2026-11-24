package io.prsentinel.webhook;

/** Payload correctement signé mais inexploitable. */
public class InvalidPayloadException extends RuntimeException {

    public InvalidPayloadException(String message) {
        super(message);
    }
}
