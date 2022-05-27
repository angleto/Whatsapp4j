package it.auties.whatsapp.model.signal.auth;

import com.fasterxml.jackson.annotation.JsonCreator;
import it.auties.protobuf.api.model.ProtobufMessage;
import it.auties.protobuf.api.model.ProtobufProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

import java.util.Arrays;

import static it.auties.protobuf.api.model.ProtobufProperty.Type.BOOLEAN;
import static it.auties.protobuf.api.model.ProtobufProperty.Type.MESSAGE;

@AllArgsConstructor
@Data
@Builder
@Jacksonized
@Accessors(fluent = true)
public class DNSSource implements ProtobufMessage {
  @ProtobufProperty(index = 15, type = MESSAGE, concreteType = DNSSourceDNSResolutionMethod.class)
  private DNSSourceDNSResolutionMethod dnsMethod;

  @ProtobufProperty(index = 16, type = BOOLEAN)
  private boolean appCached;

  @AllArgsConstructor
  @Accessors(fluent = true)
  public enum DNSSourceDNSResolutionMethod {
    SYSTEM(0),
    GOOGLE(1),
    HARDCODED(2),
    OVERRIDE(3),
    FALLBACK(4);

    @Getter
    private final int index;

    @JsonCreator
    public static DNSSourceDNSResolutionMethod forIndex(int index) {
      return Arrays.stream(values())
          .filter(entry -> entry.index() == index)
          .findFirst()
          .orElse(null);
    }
  }
}
