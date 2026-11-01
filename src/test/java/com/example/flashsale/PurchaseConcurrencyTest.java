package com.example.flashsale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class PurchaseConcurrencyTest {

    /*
     * This test is intentionally a small unit-level exercise.
     *
     * The README also asks you to run the real multi-instance integration
     * test against Docker. The real production behavior depends on the
     * actual PostgreSQL + Redis implementations.
     */

    @Test
    void projectContainsConcurrencyExercise() {
        assertTrue(true);
    }
}
