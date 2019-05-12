package com.github.princesslana.smalld;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestSmallD {

  private static final Logger LOG = LoggerFactory.getLogger(TestSmallD.class);

  private SmallD subject;

  private MockDiscordServer server;

  @BeforeEach
  public void subject() {
    server = new MockDiscordServer();
    subject = server.newSmallD();
  }

  @AfterEach
  public void closeSmallD() {
    subject.close();
  }

  @AfterEach
  public void closeServer() {
    server.close();
  }

  @Test
  public void currentShard_whenNotSetExplicitly_shouldBeZero() {
    SmallD defaultSmallD = new SmallD(Config.builder().setToken(MockDiscordServer.TOKEN).build());
    Assertions.assertThat(defaultSmallD.getCurrentShard()).isEqualTo(0);
  }

  @Test
  public void numberOfShards_whenNotSetExplicitly_shouldBeOne() {
    SmallD defaultSmallD = new SmallD(Config.builder().setToken(MockDiscordServer.TOKEN).build());
    Assertions.assertThat(defaultSmallD.getNumberOfShards()).isEqualTo(1);
  }

  @Test
  public void connect_shouldSendGetGatewayBotRequest() {
    server.enqueueGatewayBotResponse();

    subject.connect();

    RecordedRequest req = server.takeRequest();

    SoftAssertions.assertSoftly(
        s -> {
          s.assertThat(req.getMethod()).isEqualTo("GET");
          s.assertThat(req.getPath()).isEqualTo("/api/v6/gateway/bot");
        });
  }

  @Test
  public void connect_shouldIncudeTokenOnGetGatewayBotRequest() {
    server.enqueueGatewayBotResponse();

    subject.connect();

    RecordedRequest req = server.takeRequest();

    Assertions.assertThat(req.getHeader("Authorization"))
        .isEqualTo("Bot " + MockDiscordServer.TOKEN);
  }

  @Test
  public void connect_whenBadJsonInGetGatewayBotResponse_shouldThrowException() {
    server.enqueue(new MockResponse().setBody("abc"));
    Assertions.assertThatExceptionOfType(SmallDException.class).isThrownBy(subject::connect);
  }

  @Test
  public void connect_whenNoUrlInGetGatewayBotResponse_shouldThrowException() {
    server.enqueue(new MockResponse().setBody("{}"));
    Assertions.assertThatExceptionOfType(SmallDException.class).isThrownBy(subject::connect);
  }

  @Test
  public void connect_when500_shouldThrowException() {
    server.enqueue(new MockResponse().setResponseCode(500));
    Assertions.assertThatExceptionOfType(SmallDException.class).isThrownBy(subject::connect);
  }

  @Test
  public void connect_whenNoResponse_shouldThrowException() {
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
    Assertions.assertThatExceptionOfType(SmallDException.class).isThrownBy(subject::connect);
  }

  @Test
  public void connect_shouldOpenWebSocketToGatewayBotUrl() {
    server.enqueueConnect();

    subject.connect();

    Assert.thatWithinOneSecond(() -> server.gateway().assertOpened());
  }

  @Test
  public void onGatewayPayload_shouldForwardPayload() {
    String expected = "TEST MESSAGE";
    CompletableFuture<String> received = new CompletableFuture<>();

    server.enqueueConnect();

    server.gateway().onOpen((ws, r) -> ws.send(expected));

    subject.onGatewayPayload(received::complete);
    subject.connect();

    Assert.thatWithinOneSecond(() -> Assertions.assertThat(received.get()).isEqualTo(expected));
  }

  @Test
  public void sendGatewayPayload_shouldSendPayload() {
    String payload = "TEST PAYLOAD";

    server.connect(subject);

    subject.sendGatewayPayload(payload);
    Assert.thatWithinOneSecond(() -> server.gateway().assertMessage(payload));
  }

  @Test
  public void get_shouldMakeGetRequest() {
    server.connect(subject);

    String p = "/test/path";

    server.enqueue("");

    subject.get(p);

    RecordedRequest req = server.takeRequest();

    SoftAssertions.assertSoftly(
        s -> {
          s.assertThat(req.getMethod()).isEqualTo("GET");
          s.assertThat(req.getPath()).isEqualTo("/api/v6" + p);
        });
  }

  @Test
  public void get_shouldIncludeUserAgent() {
    server.connect(subject);

    server.enqueue("");

    subject.get("/test/url");

    Assertions.assertThat(server.takeRequest().getHeader("User-Agent"))
        .matches("DiscordBot (\\S+, \\S+)")
        .doesNotContain("null");
  }

  @ParameterizedTest
  @ValueSource(ints = {300, 301, 302, 399})
  public void get_whenHttp3xx_shouldThrowHttpException(int status) {
    server.connect(subject);

    server.enqueue(new MockResponse().setResponseCode(status));

    Assertions.assertThatExceptionOfType(HttpException.class)
        .isThrownBy(() -> subject.get("/test/url"));
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 404, 422, 499})
  public void get_whenHttp4xx_shouldThrowClientException(int status) {
    server.connect(subject);

    server.enqueue(new MockResponse().setResponseCode(status));

    Assertions.assertThatExceptionOfType(HttpException.ClientException.class)
        .isThrownBy(() -> subject.get("/test/url"));
  }

  @ParameterizedTest
  @ValueSource(ints = {500, 502, 503, 599})
  public void get_whenHttp5xx_shouldThrowServerException(int status) {
    server.connect(subject);

    server.enqueue(new MockResponse().setResponseCode(status));

    Assertions.assertThatExceptionOfType(HttpException.ServerException.class)
        .isThrownBy(() -> subject.get("/test/url"));
  }

  @Test
  public void get_whenHttp500_shouldIncludeCode() {
    server.connect(subject);
    server.enqueue(new MockResponse().setResponseCode(500));

    Assertions.assertThatThrownBy(() -> subject.get("test/url"))
        .hasFieldOrPropertyWithValue("code", 500);
  }

  @Test
  public void get_whenHttp500_shouldIncludeStatus() {
    server.connect(subject);
    server.enqueue(new MockResponse().setResponseCode(500));

    Assertions.assertThatThrownBy(() -> subject.get("test/url"))
        .hasFieldOrPropertyWithValue("status", "Server Error");
  }

  @Test
  public void get_whenHttp500_shouldIncludeBody() {
    server.connect(subject);
    server.enqueue(new MockResponse().setResponseCode(500).setBody("TEST BODY"));

    Assertions.assertThatThrownBy(() -> subject.get("test/url"))
        .hasFieldOrPropertyWithValue("body", "TEST BODY");
  }

  @Test
  public void get_shouldReturnResponseOn200() {
    assertReturnsResponseOn200((s, path, payload) -> s.get(path));
  }

  @Test
  public void post_shouldPostPayloadToEndpoint() {
    assertSendsPayloadToEndpoint("POST", SmallD::post);
  }

  @Test
  public void post_whenMultipart_shouldReturnResponseOn200() {
    assertReturnsResponseOn200(
        (s, path, payload) ->
            s.post(path, payload, new Attachment("", "text/plain", new byte[] {})));
  }

  @Test
  public void post_shouldReturnResponseOn200() {
    assertReturnsResponseOn200(SmallD::post);
  }

  @Test
  public void put_shouldPutPayloadToEndpoint() {
    assertSendsPayloadToEndpoint("PUT", SmallD::put);
  }

  @Test
  public void put_shouldReturnResponseOn200() {
    assertReturnsResponseOn200(SmallD::put);
  }

  @Test
  public void patch_shouldPatchPayloadToEndpoint() {
    assertSendsPayloadToEndpoint("PATCH", SmallD::patch);
  }

  @Test
  public void patch_shouldReturnResponseOn200() {
    assertReturnsResponseOn200(SmallD::patch);
  }

  @Test
  public void delete_shouldReturnResponseOn200() {
    assertReturnsResponseOn200((s, path, payload) -> s.delete(path));
  }

  @Test
  public void delete_shouldDeleteToEndpoint() {
    server.connect(subject);

    server.enqueue("");

    subject.delete("/test/url");

    RecordedRequest req = server.takeRequest();

    assertThatRequestWas(req, "DELETE", "/test/url");
  }

  @Test
  public void post_shouldIncludeToken() {
    server.connect(subject);

    server.enqueue("");

    subject.post("/test/url", "");

    RecordedRequest req = server.takeRequest();

    Assertions.assertThat(req.getHeader("Authorization"))
        .isEqualTo("Bot " + MockDiscordServer.TOKEN);
  }

  @Test
  public void post_whenMultipart_shouldSendRequest() {
    server.connect(subject);

    server.enqueue("");

    subject.post("/test/url", "", new Attachment("", "text/plain", new byte[] {}));

    RecordedRequest req = server.takeRequest();
    assertThatRequestWas(req, "POST", "/test/url");
  }

  @Test
  public void post_whenBytesAttachment_shouldSendMultipartBody() {
    assertThatMultipartSendsBody(new Attachment("abc", "text/plain", "xyz".getBytes()));
  }

  @Test
  public void post_whenUrlAttachment_shouldSendMultipartBody() {
    assertThatMultipartSendsBody(
        new Attachment("abc", "text/plain", getClass().getResource("multipart_input.txt")));
  }

  @Test
  public void await_whenClose_shouldComplete() {
    Executors.newSingleThreadScheduledExecutor()
        .schedule(subject::close, 200, TimeUnit.MILLISECONDS);
    Assert.thatWithinOneSecond(subject::await);
  }

  @Test
  public void await_whenNoClose_shouldNotComplete() {
    Assert.thatNotWithinOneSecond(subject::await);
  }

  @Test
  public void await_whenInterrupted_shouldReinterrupt() {
    Assert.thatWithinOneSecond(
        () -> {
          Thread.currentThread().interrupt();
          subject.await();
          Assertions.assertThat(Thread.currentThread().isInterrupted()).isTrue();
        });
  }

  @Test
  public void close_whenNotConnected_shouldNotThrowAnyException() {
    Assertions.assertThatCode(subject::close).doesNotThrowAnyException();
  }

  @Test
  public void close_whenConnected_shouldCloseWebSocket() {
    server.enqueueConnect();

    server.gateway().onOpen((ws, r) -> ws.send("DUMMY"));
    subject.onGatewayPayload(m -> subject.close());

    subject.connect();

    Assert.thatWithinOneSecond(
        () -> {
          server.assertConnected();
          server.gateway().assertClosing(1000, "Closed.");
        });
  }

  @Test
  public void run_shouldConnect() {
    server.enqueueConnect();

    server.gateway().onOpen((ws, r) -> ws.send("DUMMY"));
    subject.onGatewayPayload(m -> subject.close());

    subject.run();

    Assert.thatWithinOneSecond(server::assertConnected);
  }

  @Test
  public void run_whenClose_shouldComplete() {
    server.enqueueConnect();

    server.gateway().onOpen((ws, r) -> ws.send("DUMMY"));
    subject.onGatewayPayload(m -> subject.close());

    Assert.thatWithinOneSecond(subject::run);
  }

  @Test
  public void run_whenNoClose_shouldNotComplete() {
    server.enqueueConnect();

    Assert.thatNotWithinOneSecond(subject::run);
  }

  private void assertThatRequestWas(RecordedRequest req, String method, String path) {
    SoftAssertions.assertSoftly(
        s -> {
          s.assertThat(req.getMethod()).isEqualTo(method);
          s.assertThat(req.getPath()).isEqualTo("/api/v6" + path);
        });
  }

  private void assertSendsPayloadToEndpoint(String method, HttpMethodExecutor exec) {
    server.connect(subject);
    server.enqueue("");

    String payload = "TEST PAYLOAD";
    exec.doRequest(subject, "/test/url", payload);

    RecordedRequest req = server.takeRequest();

    assertThatRequestWas(req, method, "/test/url");
    Assertions.assertThat(req.getBody().readUtf8()).isEqualTo(payload);
  }

  public void assertReturnsResponseOn200(HttpMethodExecutor exec) {
    server.connect(subject);

    String expected = "TEST RESPONSE";
    server.enqueue(expected);

    String actual = exec.doRequest(subject, "/test/url", "");
    Assertions.assertThat(actual).isEqualTo(expected);
  }

  private void assertThatMultipartSendsBody(Attachment attachment) {
    server.connect(subject);

    server.enqueue("");

    subject.post("/test/url", "test_payload", attachment);

    RecordedRequest req = server.takeRequest();

    String body = req.getBody().readUtf8();

    Assertions.assertThat(body).contains("test_payload").contains("name=\"payload_json\"");

    Assertions.assertThat(body)
        .contains("text/plain")
        .contains("name=\"file\"")
        .contains("filename=\"abc\"")
        .contains("xyz");
  }

  private static interface HttpMethodExecutor {
    String doRequest(SmallD subject, String method, String url);
  }
}
