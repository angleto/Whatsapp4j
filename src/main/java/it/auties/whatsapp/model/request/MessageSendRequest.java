package it.auties.whatsapp.model.request;

import it.auties.whatsapp.model.contact.ContactJid;
import it.auties.whatsapp.model.info.MessageInfo;
import lombok.Builder;

import java.util.Map;

@Builder
public record MessageSendRequest(MessageInfo info, ContactJid overrideSender, boolean force, boolean peer,
                                 Map<String, Object> additionalAttributes) {
    public static MessageSendRequest of(MessageInfo info) {
        return MessageSendRequest.builder()
                .info(info)
                .build();
    }

    public boolean hasSenderOverride() {
        return overrideSender != null;
    }
}
