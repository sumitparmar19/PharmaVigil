// Problem-details mapping: known errors keep their message; unexpected errors never leak internals.
package io.pharmavigil.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ProblemDetail;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void mapsKnownErrors() {
        assertThat(handler.notFound(new NotFoundException("no case 7")).getStatus()).isEqualTo(404);
        assertThat(handler.conflict(new ConflictException("not enough stock")).getDetail())
                .isEqualTo("not enough stock");
        assertThat(handler.staleEdit(new OptimisticLockingFailureException("v1")).getStatus()).isEqualTo(409);
        assertThat(handler.invalid(new IllegalArgumentException("bad quantity")).getStatus()).isEqualTo(400);
        assertThat(handler.forbidden(new org.springframework.security.access.AccessDeniedException("x")).getStatus())
                .isEqualTo(403);
        assertThat(handler.unauthenticated(new org.springframework.security.authentication.BadCredentialsException("x"))
                .getStatus()).isEqualTo(401);
    }

    @Test
    void springClientErrorsKeepTheirStatus() {
        assertThat(handler.unexpected(new org.springframework.web.servlet.resource.NoResourceFoundException(
                org.springframework.http.HttpMethod.GET, "/nope", "nope")).getStatus()).isEqualTo(404);
        assertThat(handler.unexpected(new org.springframework.web.HttpRequestMethodNotSupportedException("PUT"))
                .getStatus()).isEqualTo(405);
        assertThat(handler.unreadable(new org.springframework.http.converter.HttpMessageNotReadableException("x",
                (org.springframework.http.HttpInputMessage) null)).getStatus()).isEqualTo(400);
    }

    @Test
    void unexpectedErrorsHideDetails() {
        ProblemDetail pd = handler.unexpected(new RuntimeException("ORA-00942: table or view does not exist at x.y.Z"));
        assertThat(pd.getStatus()).isEqualTo(500);
        assertThat(pd.getDetail()).doesNotContain("ORA-").doesNotContain("x.y.Z").startsWith("Something went wrong");
        assertThat(pd.getProperties()).containsKey("reference");
    }
}
