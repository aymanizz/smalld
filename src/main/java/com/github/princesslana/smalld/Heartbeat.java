package com.github.princesslana.smalld;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Sends heartbeat payloads to the Discord Gateway. It will begin sending heartbeats after the HELLO
 * payload is received and reconnects if a heartbeat ack is not received. Sends a HEARTBEAT when a
 * HEARTBEAT event is received.
 */
public class Heartbeat implements Consumer<SmallD> {

  private final ScheduledThreadPoolExecutor heartbeatExecutor;

  private final SequenceNumber sequenceNumber;

  private volatile ScheduledFuture<?> heartbeat;

  private volatile boolean ackReceived = true;

  /**
   * Constructs an instance that will send heartbeats.
   *
   * @param sequenceNumber source from which to retrieve last seen sequence number
   */
  public Heartbeat(SequenceNumber sequenceNumber) {
    this.sequenceNumber = sequenceNumber;

    heartbeatExecutor = new ScheduledThreadPoolExecutor(0, SmallD.DAEMON_THREAD_FACTORY);
    heartbeatExecutor.setRemoveOnCancelPolicy(true);
  }

  @Override
  public void accept(SmallD smalld) {
    smalld.onGatewayPayload(
        s -> {
          GatewayPayload p = GatewayPayload.parse(s);

          switch (p.getOp()) {
            case GatewayPayload.OP_HELLO:
              onHello(smalld, p.getD());
              break;

            case GatewayPayload.OP_HEARTBEAT:
              onHeartbeat(smalld);
              break;

            case GatewayPayload.OP_HEARTBEAT_ACK:
              onHeartbeatAck();
              break;
          }
        });
  }

  private void onHello(SmallD smalld, JsonObject d) {
    if (heartbeat != null) {
      heartbeat.cancel(true);
    }

    long interval = d.getInt("heartbeat_interval", -1);

    heartbeat =
        heartbeatExecutor.schedule(
            () -> startScheduledHeartbeats(smalld, interval), interval, TimeUnit.MILLISECONDS);
  }

  private void startScheduledHeartbeats(SmallD smalld, long interval) {
    if (ackReceived) {
      ackReceived = false;
    } else {
      smalld.reconnect();
      return;
    }

    sendHeartbeat(smalld);

    heartbeat =
        heartbeatExecutor.schedule(
            () -> startScheduledHeartbeats(smalld, interval), interval, TimeUnit.MILLISECONDS);
  }

  private void onHeartbeat(SmallD smalld) {
    sendHeartbeat(smalld);
  }

  private void onHeartbeatAck() {
    ackReceived = true;
  }

  private void sendHeartbeat(SmallD smalld) {
    smalld.sendGatewayPayload(
        Json.object()
            .add("op", GatewayPayload.OP_HEARTBEAT)
            .add("d", sequenceNumber.getLastSeen().map(Json::value).orElse(Json.NULL))
            .toString());
  }
}
