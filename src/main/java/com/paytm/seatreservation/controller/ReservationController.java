package com.paytm.seatreservation.controller;
import com.paytm.seatreservation.config.AuthService; import com.paytm.seatreservation.dto.ApiModels.ReservationResponse; import com.paytm.seatreservation.service.ReservationService;
import org.springframework.web.bind.annotation.*; import java.util.UUID;
@RestController @RequestMapping("/reservations") public class ReservationController {
 private final ReservationService service; private final AuthService auth; public ReservationController(ReservationService s,AuthService a){service=s;auth=a;}
 @PostMapping("/{id}/cancel") public ReservationResponse cancel(@PathVariable UUID id,@RequestHeader("Authorization") String token){return service.cancel(id,auth.userId(token));}
}
