package com.paytm.seatreservation.exception;
public class DomainException extends RuntimeException { public final String code; public final int status; public DomainException(String code,String message,int status){super(message);this.code=code;this.status=status;} }
