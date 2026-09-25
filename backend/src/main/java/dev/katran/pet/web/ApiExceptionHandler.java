package dev.katran.pet.web;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
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

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	// Constraint name -> Problem Detail "detail" for known unique-constraint violations.
	// Known trade-off: this web-layer class knows constraint names of feature packages.
	private static final Map<String, String> CONSTRAINT_CONFLICT_DETAILS = Map.of(
			"uq_category_name_lower", "Category name already exists");

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(
			MethodArgumentNotValidException ex, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		List<InvalidField> errors = ex.getBindingResult().getFieldErrors().stream()
				.map(e -> new InvalidField(e.getField(), e.getDefaultMessage()))
				.sorted(Comparator.comparing(InvalidField::field).thenComparing(InvalidField::message))
				.toList();
		ex.getBody().setProperty("errors", errors);
		return super.handleMethodArgumentNotValid(ex, headers, status, request);
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
		String detail = findConflictDetail(ex);
		if (detail == null) {
			// Unknown or unnamed constraint: not a case we know how to map, so it is an
			// unexpected error, handled the same way as any other unhandled exception.
			return handleUnexpected(ex);
		}
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail);
	}

	private String findConflictDetail(DataIntegrityViolationException ex) {
		Throwable cause = ex;
		while (cause != null) {
			if (cause instanceof ConstraintViolationException cve && cve.getConstraintName() != null) {
				for (Map.Entry<String, String> entry : CONSTRAINT_CONFLICT_DETAILS.entrySet()) {
					if (entry.getKey().equalsIgnoreCase(cve.getConstraintName())) {
						return entry.getValue();
					}
				}
			}
			cause = cause.getCause();
		}
		return null;
	}

	@ExceptionHandler(NotFoundException.class)
	ProblemDetail handleNotFound(NotFoundException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getEntity() + " not found");
		problem.setProperty("id", ex.getId());
		return problem;
	}

	// Catch-all: anything not handled above becomes a generic 500 Problem Detail.
	@ExceptionHandler(Exception.class)
	ProblemDetail handleUnexpected(Exception ex) {
		log.error("Unhandled exception", ex);
		return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
	}

	record InvalidField(String field, String message) {
	}

}
