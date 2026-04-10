package com.example.inventory.exception;

public class DuplicateSkuException extends RuntimeException {

    public DuplicateSkuException(String sku, Throwable cause) {
        super("Duplicate SKU: " + sku, cause);
    }
}
