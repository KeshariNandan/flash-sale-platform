/**
 * Domain model of a flash sale: {@code FlashSale}, {@code Inventory},
 * {@code Reservation}, {@code CustomerOrder} and their state transitions.
 * <p>
 * This package holds the invariants the whole system exists to protect:
 * <ul>
 *   <li>a sale never sells more units than {@code initialStock};</li>
 *   <li>one user claims at most the per-user limit;</li>
 *   <li>a reservation yields at most one order;</li>
 *   <li>an expired or failed reservation returns its unit to stock.</li>
 * </ul>
 * It depends on nothing outside the JDK and JPA annotations.
 */
package com.keshari.flashsale.domain;
