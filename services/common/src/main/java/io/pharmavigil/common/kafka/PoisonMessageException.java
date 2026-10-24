// Thrown by a consumer when a message can never be processed (not JSON, wrong shape, unknown case). The
// error handler sends it to the dead-letter topic at once instead of retrying.
package io.pharmavigil.common.kafka;

public class PoisonMessageException extends RuntimeException {

    public PoisonMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
