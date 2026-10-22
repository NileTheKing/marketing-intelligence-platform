package com.axon.core_service.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.axon.core_service.service.batch.CohortLtvBatchService;
import org.junit.jupiter.api.Test;

class CohortLtvBatchSchedulerTest {

    @Test
    void runsOnlyAfterAcquiringDistributedLock() {
        CohortLtvBatchService batchService = mock(CohortLtvBatchService.class);
        SchedulerExecutionLock lock = mock(SchedulerExecutionLock.class);
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return true;
        }).when(lock).runIfAcquired(eq("cohort-ltv"), any(Runnable.class));

        new CohortLtvBatchScheduler(batchService, lock).runMonthlyCohortLtvBatch();

        verify(batchService).processMonthlyCohortStats();
    }

    @Test
    void skipsWhenAnotherInstanceOwnsLock() {
        CohortLtvBatchService batchService = mock(CohortLtvBatchService.class);
        SchedulerExecutionLock lock = mock(SchedulerExecutionLock.class);
        when(lock.runIfAcquired(eq("cohort-ltv"), any(Runnable.class))).thenReturn(false);

        new CohortLtvBatchScheduler(batchService, lock).runMonthlyCohortLtvBatch();

        verifyNoInteractions(batchService);
    }
}
