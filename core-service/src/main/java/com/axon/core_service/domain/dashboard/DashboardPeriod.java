package com.axon.core_service.domain.dashboard;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum DashboardPeriod {
    ONE_DAY("1d", 1),
    SEVEN_DAYS("7d", 7),
    THIRTY_DAYS("30d", 30),
    CUSTOM("custom", null);

    private final String code;
    private final Integer days;

    public static DashboardPeriod fromCode(String code) {
        for (DashboardPeriod period : DashboardPeriod.values()) {
            if (period.getCode().equals(code)) {
                return period;
            }
        }
        throw new IllegalArgumentException("Invalid DashboardPeriod code: " + code);
    }
}
