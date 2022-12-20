package it.auties.whatsapp.model.info;

import it.auties.protobuf.base.ProtobufProperty;
import it.auties.whatsapp.model.message.model.Message;
import it.auties.whatsapp.model.message.model.MessageKey;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

import static it.auties.protobuf.base.ProtobufType.*;

@AllArgsConstructor
@Data
@Builder
@Jacksonized
@Accessors(fluent = true)
public final class NotificationMessageInfo
        implements Info {
    @ProtobufProperty(index = 1, type = MESSAGE, implementation = MessageKey.class)
    private MessageKey key;

    @ProtobufProperty(index = 2, type = MESSAGE, implementation = Message.class)
    private Message message;

    @ProtobufProperty(index = 3, type = UINT64)
    private long messageTimestamp;

    @ProtobufProperty(index = 4, type = STRING)
    private String participant;
}
