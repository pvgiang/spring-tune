package io.github.winky.springtune.check;

/** Invalid input or configuration. Maps to CLI exit code 2. */
public class CheckException extends RuntimeException {

    public CheckException(String message) {
        super(message);
    }

    public CheckException(String message, Throwable cause) {
        super(message, cause);
    }
}
