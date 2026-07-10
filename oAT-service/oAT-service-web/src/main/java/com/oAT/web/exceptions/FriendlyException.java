package com.oAT.web.exceptions;

public class FriendlyException extends RuntimeException {

    public FriendlyException(String message) {
        super(message);
    }

    public FriendlyException(String message, Throwable cause) {
        super(message, cause);
    }
}
