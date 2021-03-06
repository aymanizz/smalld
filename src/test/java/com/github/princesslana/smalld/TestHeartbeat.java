package com.github.princesslana.smalld;

import com.eclipsesource.json.Json;
import com.github.princesslana.smalld.test.MockSmallD;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.javacrumbs.jsonunit.assertj.JsonAssertions;
import org.assertj.core.api.Assertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TestHeartbeat {

  private Heartbeat subject;

  private MockSmallD smalld;

  @Mock private SequenceNumber sequenceNumber;

  @BeforeEach
  void subject() {
    smalld = new MockSmallD();
    subject = new Heartbeat(sequenceNumber);
    subject.accept(smalld);
  }

  @Test
  void whenHelloReceived_shouldSendHeartbeat() {
    smalld.receivePayload(ready(500));
    assertTwoHeartbeats(0, 1);
  }

  @Test
  void whenSecondHelloReceived_shouldCancelFirstHeartbeat() {
    smalld.receivePayload(ready(500));
    assertTwoHeartbeats(0, 1);

    smalld.receivePayload(ready(1500));
    assertTwoHeartbeats(1, 2);
  }

  @Test
  void whenSequenceNumber_shouldBeIncludedInHeartbeat() throws Exception {
    Mockito.when(sequenceNumber.getLastSeen()).thenReturn(Optional.of(42L));

    smalld.receivePayload(ready(500));

    String heartbeat = smalld.awaitSentPayload().get();
    JsonAssertions.assertThatJson(heartbeat).node("d").isEqualTo(42);
  }

  @Test
  void whenNoHeartbeatAck_shouldReconnect() throws Exception {
    smalld.receivePayload(ready(500));
    assertHeartbeat(0, 1);
    final CompletableFuture<MockSmallD.LifecycleEvent> event = smalld.awaitLifecycleEvent();
    Awaitility.await()
        .atLeast(500, TimeUnit.MILLISECONDS)
        .atMost(1, TimeUnit.SECONDS)
        .until(event::isDone);
    Assertions.assertThat(event.get()).isEqualTo(MockSmallD.LifecycleEvent.RECONNECT);
  }

  @Test
  void whenHeartbeatReceived_shouldSendHeartbeat() throws Exception {
    smalld.receivePayload(Json.object().add("op", GatewayPayload.OP_HEARTBEAT).toString());

    String sent = smalld.awaitSentPayload().get();
    JsonAssertions.assertThatJson(sent).node("op").isEqualTo(GatewayPayload.OP_HEARTBEAT);
  }

  private String ready(int interval) {
    return Json.object()
        .add("op", 10)
        .add("d", Json.object().add("heartbeat_interval", interval))
        .toString();
  }

  private String heartbeatAck() {
    return Json.object().add("op", GatewayPayload.OP_HEARTBEAT_ACK).toString();
  }

  private void assertHeartbeat(int minSeconds, int maxSeconds) {
    try {
      CompletableFuture<String> sent = smalld.awaitSentPayload();
      Awaitility.await()
          .atLeast(minSeconds, TimeUnit.SECONDS)
          .atMost(maxSeconds, TimeUnit.SECONDS)
          .until(sent::isDone);
      JsonAssertions.assertThatJson(sent.get()).node("op").isEqualTo(GatewayPayload.OP_HEARTBEAT);
    } catch (InterruptedException | ExecutionException e) {
      throw new RuntimeException(e);
    }
  }

  private void assertTwoHeartbeats(int minSeconds, int maxSeconds) {
    for (int i = 0; i < 2; i++) {
      assertHeartbeat(minSeconds, maxSeconds);
      smalld.receivePayload(heartbeatAck());
    }
  }
}
