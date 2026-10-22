package com.darshan.recon.common.exception;

public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }

    public NotFoundException(String resource, Object id) {
        this(resource + " " + id + " not found");
    }
}
