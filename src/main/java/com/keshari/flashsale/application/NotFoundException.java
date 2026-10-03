package com.keshari.flashsale.application;

import com.keshari.flashsale.domain.DomainException;

/** A referenced sale or reservation does not exist; mapped to 404. */
public class NotFoundException extends DomainException {

	public NotFoundException(String what, Object id) {
		super("not-found", what + " " + id + " not found");
	}
}
