package it.auties.whatsapp.model.message.payment;

import it.auties.protobuf.base.ProtobufProperty;
import it.auties.whatsapp.model.message.model.MessageKey;
import it.auties.whatsapp.model.message.model.MessageType;
import it.auties.whatsapp.model.message.model.PaymentMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

import static it.auties.protobuf.base.ProtobufType.MESSAGE;

/**
 * A model class that represents a message to decline a {@link RequestPaymentMessage}.
 */
@AllArgsConstructor(staticName = "of")
@NoArgsConstructor
@Data
@Builder
@Jacksonized
@Accessors(fluent = true)
public final class DeclinePaymentRequestMessage
        implements PaymentMessage {
    /**
     * The key of the original {@link RequestPaymentMessage} that this message cancels
     */
    @ProtobufProperty(index = 1, type = MESSAGE, implementation = MessageKey.class)
    private MessageKey key;

    @Override
    public MessageType type() {
        return MessageType.DECLINE_PAYMENT_REQUEST;
    }
}
