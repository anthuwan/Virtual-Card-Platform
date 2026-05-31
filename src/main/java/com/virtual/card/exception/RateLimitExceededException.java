package com.virtual.card.exception;

public class RateLimitExceededException extends RuntimeException {

    public RateLimitExceededException(String clientIp) {
        super("Rate limit exceeded for client: " + clientIp);
    }
}
