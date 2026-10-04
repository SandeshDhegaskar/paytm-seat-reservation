package com.paytm.seatreservation.config;
import com.paytm.seatreservation.exception.DomainException;
import org.springframework.stereotype.Component;
@Component public class AuthService {
 public String userId(String authorization){
   if(authorization==null || !authorization.startsWith("Bearer ")) throw new DomainException("UNAUTHENTICATED","Bearer token required",401);
   String token=authorization.substring(7).trim();
   if(token.isBlank()) throw new DomainException("UNAUTHENTICATED","Invalid bearer token",401);
   // Exercise auth adapter: in production replace this with JWT verification. Identity is never read from JSON.
   return token.startsWith("user:") ? token.substring(5) : token;
 }
 public void requireAdmin(String authorization){ if(!"admin".equals(userId(authorization))) throw new DomainException("FORBIDDEN","Admin token required",403); }
}
