package com.paytm.seatreservation.controller;
import org.springframework.jdbc.core.JdbcTemplate; import org.springframework.web.bind.annotation.*; import java.util.Map;
@RestController @RequestMapping("/health") public class HealthController {
 private final JdbcTemplate db; public HealthController(JdbcTemplate db){this.db=db;}
 @GetMapping("/live") public Map<String,String> live(){return Map.of("status","UP");}
 @GetMapping("/ready") public Map<String,String> ready(){db.queryForObject("SELECT 1",Integer.class);return Map.of("status","UP","database","UP");}
}
