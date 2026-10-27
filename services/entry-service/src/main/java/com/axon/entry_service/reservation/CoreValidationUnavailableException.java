package com.axon.entry_service.reservation;

public class CoreValidationUnavailableException extends RuntimeException {
    public CoreValidationUnavailableException(Throwable cause) {
        super("참여 자격을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.", cause);
    }
}
