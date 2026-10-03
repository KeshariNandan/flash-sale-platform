package com.keshari.flashsale.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the authenticated buyer's id into a controller parameter. Resolution
 * is centralised in {@link CurrentBuyerArgumentResolver} so controllers never
 * read identity from the request body and the source (header today, JWT
 * subject once the resource server is configured) can change in one place.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentBuyer {
}
