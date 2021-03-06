package com.github.princesslana.smalld.ratelimit;

import com.github.princesslana.smalld.SmallDException;
import java.io.IOException;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import okhttp3.Request;

/**
 * A bucket for {@link RateLimit}s. We can determine the appropriate bucket from the request method
 * and path.
 */
public class RateLimitBucket {

  private static final Collection<Mapping> MAPPINGS = loadBucketMappings();

  private static final Mapping DELETE_MESSAGE_MAPPING =
      Mapping.of("/channels/(\\d+)/messages/(\\d+)", "DELETE /channels/$1/messages/{message.id}");

  private String bucket;

  private RateLimitBucket(String bucket) {
    this.bucket = bucket;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == null) {
      return false;
    }
    if (obj == this) {
      return true;
    }
    if (obj.getClass() != getClass()) {
      return false;
    }
    RateLimitBucket rhs = (RateLimitBucket) obj;

    return Objects.equals(bucket, rhs.bucket);
  }

  @Override
  public int hashCode() {
    return bucket.hashCode();
  }

  @Override
  public String toString() {
    return String.format("RateLimitBucket(%s)", bucket);
  }

  private static Collection<Mapping> loadBucketMappings() {
    try {
      Properties mappings = new Properties();

      mappings.load(RateLimitBucket.class.getResourceAsStream("rate_limit_buckets.properties"));

      return mappings
          .entrySet()
          .stream()
          .map(e -> Mapping.of((String) e.getKey(), (String) e.getValue()))
          .collect(Collectors.toList());
    } catch (IOException e) {
      throw new SmallDException(e);
    }
  }

  /**
   * Creates a {@code RateLimitBucket} from a HTTP method and path.
   *
   * @param method the HTTP request method
   * @param path the HTTP request path
   * @return the {@code RateLimitBucket} for the given method and path
   */
  public static RateLimitBucket from(String method, String path) {
    Stream<Mapping> deleteMessage =
        method.equalsIgnoreCase("DELETE") ? Stream.of(DELETE_MESSAGE_MAPPING) : Stream.of();

    String bucket =
        Stream.concat(deleteMessage, MAPPINGS.stream())
            .map(m -> m.replaceIn(path))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .findFirst()
            .orElse(path);

    return new RateLimitBucket(bucket);
  }

  /**
   * Creates a {@code RateLimitBucket} from a {@link Request}.
   *
   * @param request a HTTP request
   * @return the {@code RateLimitBucket} for the given request
   */
  public static RateLimitBucket from(Request request) {
    return from(request.method(), request.url().encodedPath());
  }

  /** A Mapping between a request path and a {@link RateLimitBucket}. */
  private static class Mapping {

    private final Pattern from;
    private final String to;

    public Mapping(Pattern from, String to) {
      this.from = from;
      this.to = to;
    }

    public Optional<String> replaceIn(String path) {
      Matcher m = from.matcher(path);

      return m.matches() ? Optional.of(m.replaceAll(to)) : Optional.empty();
    }

    public static Mapping of(String from, String to) {
      return new Mapping(Pattern.compile(from), to);
    }
  }
}
