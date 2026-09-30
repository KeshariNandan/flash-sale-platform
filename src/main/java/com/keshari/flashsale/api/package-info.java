/**
 * Inbound HTTP layer: REST controllers, request/response DTOs and error mapping.
 * <p>
 * Controllers are thin. They validate input, delegate to an application service
 * and translate the outcome into an HTTP status (202 accepted, 409 sold out /
 * duplicate, 404 unknown sale). No business rules live here.
 */
package com.keshari.flashsale.api;
