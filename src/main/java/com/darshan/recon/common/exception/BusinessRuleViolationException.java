package com.darshan.recon.common.exception;

// A well-formed request that breaks a business rule (HTTP 422). The optional error code is a
// stable identifier clients and scripts can branch on without parsing the message.
public class BusinessRuleViolationException extends RuntimeException {

    private final String errorCode;

    public BusinessRuleViolationException(String message) {
        this(null, message);
    }

    public BusinessRuleViolationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
