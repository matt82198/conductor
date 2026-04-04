package dev.conductor.server.brain.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SubtaskStatus}.
 */
class SubtaskStatusTest {

    @Test
    void pendingIsNotTerminal() {
        assertFalse(SubtaskStatus.PENDING.isTerminal());
    }

    @Test
    void runningIsNotTerminal() {
        assertFalse(SubtaskStatus.RUNNING.isTerminal());
    }

    @Test
    void completedIsTerminal() {
        assertTrue(SubtaskStatus.COMPLETED.isTerminal());
    }

    @Test
    void failedIsTerminal() {
        assertTrue(SubtaskStatus.FAILED.isTerminal());
    }

    @Test
    void cancelledIsTerminal() {
        assertTrue(SubtaskStatus.CANCELLED.isTerminal());
    }
}
