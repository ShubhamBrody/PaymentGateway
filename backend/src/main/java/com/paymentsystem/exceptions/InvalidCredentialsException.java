package com.paymentsystem.exceptions;

public class InvalidCredentialsException extends RuntimeException {

    // One message for unknown emails and wrong passwords avoids revealing which accounts exist.
    public InvalidCredentialsException() {
        super("Email or password is incorrect");
    }
}
