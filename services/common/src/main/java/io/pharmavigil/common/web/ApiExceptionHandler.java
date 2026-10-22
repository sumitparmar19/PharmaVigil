// Turns exceptions into RFC 7807 problem details. Unexpected errors return a reference ID only: stack
// traces and internal messages are logged server-side and never sent to users.
package io.pharmavigil.common.web;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.validation.ConstraintViolationException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail forbidden(AccessDeniedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "You do not have permission for this action.");
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail unauthenticated(AuthenticationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication required.");
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail staleEdit(OptimisticLockingFailureException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "This record was changed by someone else. Reload it and apply your change again.");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            IllegalArgumentException.class})
    ProblemDetail invalid(Exception e) {
        String detail = e instanceof MethodArgumentNotValidException m
                ? m.getBindingResult().getFieldErrors().stream()
                        .map(f -> f.getField() + ": " + f.getDefaultMessage()).sorted().reduce((a, b) -> a + "; " + b)
                        .orElse("invalid request")
                : e.getMessage();
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpMessageNotReadableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request body could not be read.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        if (e instanceof ErrorResponse standard) {
            // Spring MVC's own client errors (unknown path 404, wrong method 405, wrong media type 415...)
            // keep their status; their details name only the request, never internals.
            return standard.getBody();
        }
        String ref = UUID.randomUUID().toString();
        log.error("Unhandled error, reference {}", ref, e);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong. Reference: " + ref);
        pd.setProperty("reference", ref);
        return pd;
    }
}
