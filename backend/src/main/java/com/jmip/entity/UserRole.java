package com.jmip.entity;

/**
 * What a user may do. Stored by name, so adding a role is adding a constant.
 */
public enum UserRole {
    USER,

    /**
     * V8.9: platform administration. Never chosen at signup (signup always creates USER); only
     * granted to verified accounts listed in JMIP_ADMIN_EMAILS, at startup. An admin keeps every
     * USER permission as well.
     */
    ADMIN;

    /** The Spring Security authority for this role, e.g. {@code ROLE_USER}. */
    public String authority() {
        return "ROLE_" + name();
    }
}
