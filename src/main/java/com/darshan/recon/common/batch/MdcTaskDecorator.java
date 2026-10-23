package com.darshan.recon.common.batch;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

// MDC is thread-local. Copying the submitting thread's map into the pooled thread keeps the
// correlation id and the job ids on every log line a job or a partition writes, and
// clearing it afterwards keeps a pooled thread from carrying stale ids into its next task.
public final class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable task) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            if (context != null) {
                MDC.setContextMap(context);
            }
            try {
                task.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
