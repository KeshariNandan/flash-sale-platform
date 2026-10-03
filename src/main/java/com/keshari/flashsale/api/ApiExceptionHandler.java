package com.keshari.flashsale.api;

import com.keshari.flashsale.application.NotFoundException;
import com.keshari.flashsale.domain.DomainException;
import com.keshari.flashsale.domain.IllegalStateTransitionException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * Translates exceptions into RFC 9457 problem-details responses with a stable,
 * machine-readable {@code type} (e.g. {@code sold-out}, {@code illegal-transition})
 * so clients and the load generator branch on the type, never on message text.
 * Unexpected errors return 500 with a correlation id that is also logged; no
 * stack trace is exposed.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
	static final String TYPE_PREFIX = "https://flashsale.example/problems/";

	static ProblemDetail problem(HttpStatus status, String type, String detail) {
		ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
		pd.setType(URI.create(TYPE_PREFIX + type));
		pd.setTitle(type);
		return pd;
	}

	@ExceptionHandler(NotFoundException.class)
	ProblemDetail notFound(NotFoundException ex) {
		return problem(HttpStatus.NOT_FOUND, ex.type(), ex.getMessage());
	}

	@ExceptionHandler(IllegalStateTransitionException.class)
	ProblemDetail illegalTransition(IllegalStateTransitionException ex) {
		return problem(HttpStatus.CONFLICT, ex.type(), ex.getMessage());
	}

	/** Remaining domain rule violations are caller errors (invalid configuration, etc). */
	@ExceptionHandler(DomainException.class)
	ProblemDetail domain(DomainException ex) {
		return problem(HttpStatus.BAD_REQUEST, ex.type(), ex.getMessage());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ProblemDetail validation(MethodArgumentNotValidException ex) {
		Map<String, String> fields = new LinkedHashMap<>();
		ex.getBindingResult().getFieldErrors()
				.forEach(fe -> fields.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
		ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "validation-error", "request validation failed");
		pd.setProperty("errors", fields);
		return pd;
	}

	@ExceptionHandler(MissingRequestHeaderException.class)
	ProblemDetail missingHeader(MissingRequestHeaderException ex) {
		return problem(HttpStatus.BAD_REQUEST, "validation-error", "missing header " + ex.getHeaderName());
	}

	@ExceptionHandler(HandlerMethodValidationException.class)
	ProblemDetail parameterValidation(HandlerMethodValidationException ex) {
		return problem(HttpStatus.BAD_REQUEST, "validation-error", "request validation failed: " + ex.getReason());
	}

	@ExceptionHandler(CurrentBuyerArgumentResolver.UnauthenticatedException.class)
	ProblemDetail unauthenticated(CurrentBuyerArgumentResolver.UnauthenticatedException ex) {
		return problem(HttpStatus.UNAUTHORIZED, "unauthenticated", ex.getMessage());
	}

	@ExceptionHandler(Exception.class)
	ProblemDetail unexpected(Exception ex) {
		String correlationId = UUID.randomUUID().toString();
		log.error("unhandled exception correlationId={}", correlationId, ex);
		ProblemDetail pd = problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "unexpected error");
		pd.setProperty("correlationId", correlationId);
		return pd;
	}
}
