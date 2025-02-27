package it.auties.whatsapp.model.message.standard;

import it.auties.protobuf.base.ProtobufProperty;
import it.auties.whatsapp.model.message.model.ContextualMessage;
import it.auties.whatsapp.model.message.model.MessageCategory;
import it.auties.whatsapp.model.message.model.MessageType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

import static it.auties.protobuf.base.ProtobufType.MESSAGE;
import static it.auties.protobuf.base.ProtobufType.STRING;

/**
 * A model class that represents a message holding a list of contacts inside
 */
@AllArgsConstructor
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@Jacksonized
@Accessors(fluent = true)
public final class ContactsArrayMessage extends ContextualMessage {
    /**
     * The name of the contact the first contact that this message wraps
     */
    @ProtobufProperty(index = 1, type = STRING)
    private String name;

    /**
     * A list of {@link ContactMessage} that this message wraps
     */
    @ProtobufProperty(index = 2, type = MESSAGE, implementation = ContactMessage.class, repeated = true)
    private List<ContactMessage> contacts;

    @Override
    public MessageType type() {
        return MessageType.CONTACT_ARRAY;
    }

    @Override
    public MessageCategory category() {
        return MessageCategory.STANDARD;
    }
}