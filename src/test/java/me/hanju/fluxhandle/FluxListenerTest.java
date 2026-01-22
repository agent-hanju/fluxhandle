package me.hanju.fluxhandle;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FluxListenerTest {

  @Test
  void defaultOnError_shouldNotThrow() {
    FluxListener<String> listener = item -> {};
    assertDoesNotThrow(() -> listener.onError(new RuntimeException("test")));
  }

  @Test
  void defaultOnComplete_shouldNotThrow() {
    FluxListener<String> listener = item -> {};
    assertDoesNotThrow(listener::onComplete);
  }

  @Test
  void defaultOnCancel_shouldNotThrow() {
    FluxListener<String> listener = item -> {};
    assertDoesNotThrow(listener::onCancel);
  }
}
