package com.paytm.seatreservation.dto;
import java.util.List; import java.util.Map; import java.util.UUID;
public final class ApiModels { private ApiModels() {}
 public record ShowResponse(UUID id,String name,long price_paise,int per_user_limit,List<SeatView> seats,Map<String,Integer> counts) {}
 public record SeatView(String seat,String status) {}
 public record ReservationResponse(UUID reservation_id,UUID show_id,String user_id,List<String> seats,long amount_paise,String status) {}
 public record ErrorResponse(String code,String message,String request_id) {}
}
