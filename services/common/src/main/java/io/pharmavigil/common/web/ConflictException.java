// Thrown when a request conflicts with current state (stock, concurrent edit); rendered as HTTP 409.
package io.pharmavigil.common.web;

public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
