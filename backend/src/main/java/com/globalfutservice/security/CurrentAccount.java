package com.globalfutservice.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the authenticated caller into a controller method.
 *
 * <p>Binds to {@code null} for anonymous requests, which is what makes guest checkout
 * work without a parallel set of endpoints.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentAccount {
}
