package com.darshan.recon.security;

public enum Role {
    VIEWER,
    OPERATOR,
    APPROVER;

    public static final String PREFIX = "ROLE_";

    public String authority() {
        return PREFIX + name();
    }
}
