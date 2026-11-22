package nz.paymentrelay.payment.api;

import java.net.URI;
import java.util.List;
import java.util.Map;
import nz.paymentrelay.payment.IdempotencyConflictException;
import nz.paymentrelay.payment.PaymentNotFoundException;
import nz.paymentrelay.payment.PaymentValidationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error comes back as RFC 7807 application/problem+json with a fixed "type". Validation
 * errors also get a per-field "errors" list that a client can show next to its form fields.
 * Extending ResponseEntityExceptionHandler gives consistent 400s for malformed JSON, bad dates,
 * missing headers and so on without extra code.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(), "message", String.valueOf(fe.getDefaultMessage())))
                .toList();
        return ResponseEntity.badRequest().body(validationProblem(errors));
    }

    @ExceptionHandler(PaymentValidationException.class)
    ResponseEntity<ProblemDetail> onBusinessValidation(PaymentValidationException ex) {
        List<Map<String, String>> errors = ex.getViolations().stream()
                .map(v -> Map.of("field", v.field(), "message", v.message()))
                .toList();
        return ResponseEntity.badRequest().body(validationProblem(errors));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ProblemDetail> onIdempotencyConflict(IdempotencyConflictException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setType(URI.create("urn:payment-relay:idempotency-key-reused"));
        pd.setTitle("Idempotency key reused with a different request");
        return ResponseEntity.unprocessableEntity().body(pd);
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    ResponseEntity<ProblemDetail> onNotFound(PaymentNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create("urn:payment-relay:payment-not-found"));
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(pd);
    }

    private static ProblemDetail validationProblem(List<Map<String, String>> errors) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "One or more fields are invalid");
        pd.setType(URI.create("urn:payment-relay:validation-error"));
        pd.setTitle("Validation failed");
        pd.setProperty("errors", errors);
        return pd;
    }
}
