package com.keshari.flashsale.domain;

/**
 * Base class for rule violations raised by the domain model. Carries a stable,
 * machine-readable {@code type} that the API layer maps to an RFC 9457 problem
 * type (for example {@code sale-not-open}, {@code illegal-transition}).
 */
public class DomainException extends RuntimeException {

	private final String type;

	public DomainException(String type, String message) {
		super(message);
		this.type = type;
	}

	public String type() {
		return type;
	}
}
