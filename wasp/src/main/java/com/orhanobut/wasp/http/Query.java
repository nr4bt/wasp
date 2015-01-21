package com.orhanobut.wasp.http;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Define the query param to append to the url
 *
 * @author Orhan Obut
 */
@Documented
@Target(PARAMETER)
@Retention(RUNTIME)
public @interface Query {
    String value();
}
