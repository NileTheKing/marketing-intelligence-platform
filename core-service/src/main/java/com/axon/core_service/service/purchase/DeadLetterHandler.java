package com.axon.core_service.service.purchase;

/**
 * 최종 실패한 비즈니스 데이터를 처리하기 위한 추상 인터페이스 (Dead Letter 처리)
 * 구현체에 따라 로그 기록, DB 저장, Kafka DLQ 전송 등 다양한 전략을 취할 수 있음.
 */
public interface DeadLetterHandler<T> {
    void handle(T data, Throwable reason);
}
