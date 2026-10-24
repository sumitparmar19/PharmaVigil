// Thrown when a requested record does not exist; rendered as HTTP 404 problem details.
package io.pharmavigil.common.web;

public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
