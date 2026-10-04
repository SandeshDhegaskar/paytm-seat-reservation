package com.paytm.seatreservation.dto;
import jakarta.validation.constraints.*;
import java.util.List;
public record CreateShowRequest(@NotBlank String name, @NotEmpty List<@NotBlank String> seats,
                                @PositiveOrZero long price_paise, @Positive Integer per_user_limit) {}
