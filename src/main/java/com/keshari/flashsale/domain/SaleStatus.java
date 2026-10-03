package com.keshari.flashsale.domain;

/**
 * Lifecycle of a flash sale. SOLD_OUT is not terminal: when abandoned holds are
 * released the sale returns to OPEN, which prevents under-selling.
 */
public enum SaleStatus {
	SCHEDULED, OPEN, SOLD_OUT, CLOSED
}
