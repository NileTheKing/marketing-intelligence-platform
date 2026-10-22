package com.axon.entry_service.payment.dto;

import lombok.Data;

@Data
public class PaymentPrepareRequest {
    private String reservationToken;
}
