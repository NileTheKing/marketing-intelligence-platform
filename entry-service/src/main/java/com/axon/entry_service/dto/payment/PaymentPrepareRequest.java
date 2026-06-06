package com.axon.entry_service.dto.payment;

import lombok.Data;

@Data
public class PaymentPrepareRequest {
    private String reservationToken;
}
