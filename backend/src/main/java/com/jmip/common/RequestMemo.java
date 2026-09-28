package com.jmip.common;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.function.Supplier;

/**
 * V7.9: remembers a value for the rest of one HTTP request, so a request that asks the same
 * question several times (who is signed in, what the market window is) asks the database once.
 *
 * <p>Held in the request's own attributes: it dies with the request, is never shared between
 * requests or users, and needs no invalidation. Outside a request (jobs, tests without one)
 * the supplier simply runs every time.
 */
public final class RequestMemo {

    private static final String PREFIX = RequestMemo.class.getName() + ":";

    private RequestMemo() {
    }

    @SuppressWarnings("unchecked")
    public static <T> T get(String key, Supplier<T> supplier) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return supplier.get();
        }
        String name = PREFIX + key;
        Object cached = attributes.getAttribute(name, RequestAttributes.SCOPE_REQUEST);
        if (cached != null) {
            return (T) cached;
        }
        T value = supplier.get();
        if (value != null) {
            attributes.setAttribute(name, value, RequestAttributes.SCOPE_REQUEST);
        }
        return value;
    }
}
