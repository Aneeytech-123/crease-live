package com.crease.live.service;

/** Domain exceptions, mapped to HTTP status codes by the web layer. */
public final class Exceptions {
    private Exceptions() {
    }

    /** 404 */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) { super(message); }
    }

    /** 409: the match is not in a state that allows the operation. */
    public static class MatchStateException extends RuntimeException {
        public MatchStateException(String message) { super(message); }
    }

    /** 422: the delivery described is not possible under the Laws. */
    public static class InvalidBallException extends RuntimeException {
        public InvalidBallException(String message) { super(message); }
    }
}
