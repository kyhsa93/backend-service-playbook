package com.example.accountservice.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OutboxEventDispatcherTest {

    private static class RecordingHandler implements OutboxEventHandler {
        private final String eventType;
        private final boolean fails;
        private final AtomicInteger callCount = new AtomicInteger();

        RecordingHandler(String eventType, boolean fails) {
            this.eventType = eventType;
            this.fails = fails;
        }

        @Override
        public String eventType() {
            return eventType;
        }

        @Override
        public void handle(String payload) throws Exception {
            callCount.incrementAndGet();
            if (fails) {
                throw new RuntimeException("boom");
            }
        }

        int calls() {
            return callCount.get();
        }
    }

    @Test
    void
            dispatch_when_multiple_handlers_are_registered_for_the_same_eventType_then_calls_all_of_them()
                    throws Exception {
        RecordingHandler first = new RecordingHandler("SomeEvent", false);
        RecordingHandler second = new RecordingHandler("SomeEvent", false);
        OutboxEventDispatcher dispatcher = new OutboxEventDispatcher(List.of(first, second));

        dispatcher.dispatch("SomeEvent", "{}");

        assertThat(first.calls()).isEqualTo(1);
        assertThat(second.calls()).isEqualTo(1);
    }

    @Test
    void
            dispatch_when_one_handler_throws_then_still_calls_the_other_handler_rather_than_stopping_early() {
        RecordingHandler failing = new RecordingHandler("SomeEvent", true);
        RecordingHandler succeeding = new RecordingHandler("SomeEvent", false);
        OutboxEventDispatcher dispatcher = new OutboxEventDispatcher(List.of(failing, succeeding));

        assertThatThrownBy(() -> dispatcher.dispatch("SomeEvent", "{}"))
                .hasMessageContaining("boom");

        assertThat(failing.calls()).isEqualTo(1);
        assertThat(succeeding.calls()).isEqualTo(1);
    }

    @Test
    void
            dispatch_when_a_handler_throws_then_rethrows_so_the_caller_can_leave_the_message_unacked() {
        RecordingHandler failing = new RecordingHandler("SomeEvent", true);
        OutboxEventDispatcher dispatcher = new OutboxEventDispatcher(List.of(failing));

        assertThatThrownBy(() -> dispatcher.dispatch("SomeEvent", "{}"))
                .hasMessageContaining("boom");
    }

    @Test
    void dispatch_when_no_handler_is_registered_for_the_eventType_then_throws_illegal_state() {
        OutboxEventDispatcher dispatcher = new OutboxEventDispatcher(List.of());

        assertThatThrownBy(() -> dispatcher.dispatch("UnregisteredEvent", "{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UnregisteredEvent");
    }

    @Test
    void dispatch_when_a_single_handler_succeeds_then_does_not_throw() {
        RecordingHandler handler = new RecordingHandler("SomeEvent", false);
        OutboxEventDispatcher dispatcher = new OutboxEventDispatcher(List.of(handler));

        assertThatCode(() -> dispatcher.dispatch("SomeEvent", "{}")).doesNotThrowAnyException();
        assertThat(handler.calls()).isEqualTo(1);
    }
}
