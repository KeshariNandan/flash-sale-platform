package com.keshari.flashsale.domain;

/** Thrown when a lifecycle method is called in a state that does not permit it. */
public class IllegalStateTransitionException extends DomainException {

	public IllegalStateTransitionException(String entity, Object from, String action) {
		super("illegal-transition", entity + " in state " + from + " cannot " + action);
	}
}
