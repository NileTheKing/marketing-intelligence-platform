package com.axon.core_service.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * Ensures that a state-mutating scheduled job has a single runner across Core instances.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchedulerExecutionLock {

    private static final String LOCK_PREFIX = "scheduler:lock:";

    private final RedissonClient redissonClient;

    /**
     * Runs the task only when this instance acquires the job lock. A busy lock is a normal skip.
     * Redisson's watchdog keeps the lock alive while the owning process is running.
     */
    public boolean runIfAcquired(String jobName, Runnable task) {
        RLock lock = redissonClient.getLock(LOCK_PREFIX + jobName);
        if (!lock.tryLock()) {
            log.info("Skipping scheduled job because another instance owns it: job={}", jobName);
            return false;
        }

        try {
            task.run();
            return true;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
