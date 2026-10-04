package com.paytm.seatreservation.service;
import io.micrometer.core.instrument.*; import org.springframework.stereotype.Service; import java.util.concurrent.ConcurrentHashMap; import java.util.concurrent.atomic.AtomicInteger;
@Service public class MetricsService {
 private final MeterRegistry registry; private final ConcurrentHashMap<String,AtomicInteger> gauges=new ConcurrentHashMap<>();
 public MetricsService(MeterRegistry r){registry=r;}
 public void confirmed(){registry.counter("reservations_confirmed_total").increment();}
 public void declined(String reason){registry.counter("reservations_declined_total","reason",reason).increment();}
 public void available(String showId,int n){AtomicInteger a=gauges.computeIfAbsent(showId,k->registry.gauge("seats_available",Tags.of("show_id",k),new AtomicInteger())); a.set(n);}
}
