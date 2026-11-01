package com.example.flashsale.exception;

public class OutOfStockException extends RuntimeException {
    public OutOfStockException() {
        super("Out of stock");
    }
}
