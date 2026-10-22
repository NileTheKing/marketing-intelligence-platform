package com.axon.entry_service.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Getter
public class PaymentConfirmationRequest {
    private String reservationToken;
}
