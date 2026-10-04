package com.paytm.seatreservation.controller;
import com.paytm.seatreservation.config.AuthService; import com.paytm.seatreservation.dto.*; import com.paytm.seatreservation.dto.ApiModels.*; import com.paytm.seatreservation.service.ReservationService;
import jakarta.validation.Valid; import org.springframework.http.ResponseEntity; import org.springframework.web.bind.annotation.*; import java.net.URI; import java.util.UUID;
@RestController @RequestMapping("/shows") public class ShowController {
 private final ReservationService service; private final AuthService auth; public ShowController(ReservationService s,AuthService a){service=s;auth=a;}
 @PostMapping public ResponseEntity<ShowResponse> create(@RequestHeader("Authorization") String token,@Valid @RequestBody CreateShowRequest r){auth.requireAdmin(token);ShowResponse x=service.createShow(r);return ResponseEntity.created(URI.create("/shows/"+x.id())).body(x);}
 @GetMapping("/{id}") public ShowResponse get(@PathVariable UUID id){return service.getShow(id);}
 @PostMapping("/{id}/reserve") public ResponseEntity<ReservationResponse> reserve(@PathVariable UUID id,@RequestHeader("Authorization") String token,@Valid @RequestBody ReserveRequest r){String user=auth.userId(token);return ResponseEntity.status(201).body(service.reserve(id,user,r));}
}
