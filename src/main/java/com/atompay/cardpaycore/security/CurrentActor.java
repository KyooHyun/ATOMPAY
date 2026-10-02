package com.atompay.cardpaycore.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentActor {

    public static final String SYSTEM = "system";

    private CurrentActor() {
    }

    /** Authenticated username, or {@value #SYSTEM} for calls made outside a request (jobs, tests). */
    public static String resolve() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return SYSTEM;
        }
        return auth.getName();
    }
}
