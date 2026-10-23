package com.darshan.recon.common.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class MdcTaskDecoratorTest {

    private final MdcTaskDecorator decorator = new MdcTaskDecorator();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void copiesTheSubmittingThreadsMdcIntoTheTaskAndClearsItAfterwards() throws Exception {
        MDC.put("correlationId", "req-1");
        AtomicReference<String> seenInsideTask = new AtomicReference<>();
        AtomicReference<String> leftOnWorker = new AtomicReference<>();
        Runnable decorated = decorator.decorate(() -> seenInsideTask.set(MDC.get("correlationId")));

        Thread worker = new Thread(() -> {
            decorated.run();
            leftOnWorker.set(MDC.get("correlationId"));
        });
        worker.start();
        worker.join();

        assertThat(seenInsideTask).hasValue("req-1");
        assertThat(leftOnWorker).hasValue(null);
        assertThat(MDC.get("correlationId")).isEqualTo("req-1");
    }

    @Test
    void clearsTheMdcEvenWhenTheTaskThrows() {
        MDC.put("correlationId", "req-2");
        Runnable decorated = decorator.decorate(() -> {
            throw new IllegalStateException("boom");
        });
        MDC.clear();

        assertThatThrownBy(decorated::run).isInstanceOf(IllegalStateException.class);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void worksWhenTheSubmittingThreadHasNoMdc() {
        AtomicReference<String> seenInsideTask = new AtomicReference<>("unset");

        decorator.decorate(() -> seenInsideTask.set(MDC.get("correlationId"))).run();

        assertThat(seenInsideTask).hasValue(null);
    }
}
