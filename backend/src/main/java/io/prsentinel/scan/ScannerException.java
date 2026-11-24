package io.prsentinel.scan;

/** Le scanner n'a pas produit de rapport exploitable. */
public class ScannerException extends Exception {
    public ScannerException(String message) {
        super(message);
    }

    public ScannerException(String message, Throwable cause) {
        super(message, cause);
    }
}
