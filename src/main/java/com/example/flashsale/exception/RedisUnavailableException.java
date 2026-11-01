package com.example.flashsale.exception;

public class RedisUnavailableException extends RuntimeException {
    public RedisUnavailableException(Throwable cause) {
        super("Redis unavailable", cause);
    }
}
