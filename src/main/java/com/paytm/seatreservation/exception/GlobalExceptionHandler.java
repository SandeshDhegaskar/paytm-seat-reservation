package com.paytm.seatreservation.exception;
import com.paytm.seatreservation.dto.ApiModels.ErrorResponse; import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity; import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestControllerAdvice public class GlobalExceptionHandler {
 @ExceptionHandler(DomainException.class) ResponseEntity<ErrorResponse> domain(DomainException e,HttpServletRequest r){return ResponseEntity.status(e.status).body(new ErrorResponse(e.code,e.getMessage(),rid(r)));}
 @ExceptionHandler({IllegalArgumentException.class,org.springframework.web.bind.MethodArgumentNotValidException.class}) ResponseEntity<ErrorResponse> bad(Exception e,HttpServletRequest r){return ResponseEntity.badRequest().body(new ErrorResponse("BAD_REQUEST",e.getMessage(),rid(r)));}
 private String rid(HttpServletRequest r){String x=r.getHeader("X-Request-Id");return x==null?"":x;}
}
