package com.axon.core_service.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SchedulerExecutionLockTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    @Test
    @DisplayName("락을 획득한 인스턴스만 작업을 실행하고 완료 후 해제한다")
    void runIfAcquired_runsAndReleasesWhenLockAcquired() {
        when(redissonClient.getLock("scheduler:lock:behavior-trigger")).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        AtomicBoolean executed = new AtomicBoolean();

        boolean ran = new SchedulerExecutionLock(redissonClient)
                .runIfAcquired("behavior-trigger", () -> executed.set(true));

        assertThat(ran).isTrue();
        assertThat(executed).isTrue();
        verify(lock).unlock();
    }

    @Test
    @DisplayName("다른 인스턴스가 실행 중이면 작업을 건너뛴다")
    void runIfAcquired_skipsWhenLockIsBusy() {
        when(redissonClient.getLock("scheduler:lock:behavior-trigger")).thenReturn(lock);
        when(lock.tryLock()).thenReturn(false);
        AtomicBoolean executed = new AtomicBoolean();

        boolean ran = new SchedulerExecutionLock(redissonClient)
                .runIfAcquired("behavior-trigger", () -> executed.set(true));

        assertThat(ran).isFalse();
        assertThat(executed).isFalse();
        verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("작업이 실패해도 소유한 락은 해제한다")
    void runIfAcquired_releasesLockWhenTaskFails() {
        when(redissonClient.getLock("scheduler:lock:behavior-trigger")).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        SchedulerExecutionLock schedulerExecutionLock = new SchedulerExecutionLock(redissonClient);

        assertThatThrownBy(() -> schedulerExecutionLock.runIfAcquired(
                "behavior-trigger", () -> { throw new IllegalStateException("failed"); }))
                .isInstanceOf(IllegalStateException.class);

        verify(lock).unlock();
    }
}
