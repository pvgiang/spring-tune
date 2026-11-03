package io.github.winky.springtune.samples.brokenconfig;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** Runs on Boot's applicationTaskExecutor with its default unbounded queue (ST-ASYNC-001). */
@Service
class AuditService {

    @Async
    public void record(String event) {
        // A real implementation would call a slow downstream here.
    }
}
