package com.jmip.entity;

/**
 * What a user may do. Stored by name, so adding a role is adding a constant.
 */
public enum UserRole {
    USER;

    /** The Spring Security authority for this role, e.g. {@code ROLE_USER}. */
    public String authority() {
        return "ROLE_" + name();
    }
}
