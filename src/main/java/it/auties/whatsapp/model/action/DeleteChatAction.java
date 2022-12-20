package it.auties.whatsapp.model.action;

import it.auties.protobuf.base.ProtobufProperty;
import it.auties.whatsapp.model.sync.ActionMessageRangeSync;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

import static it.auties.protobuf.base.ProtobufType.MESSAGE;

/**
 * A model clas that represents a deleted chat
 */
@AllArgsConstructor(staticName = "of")
@Data
@Builder(access = AccessLevel.PROTECTED)
@Jacksonized
@Accessors(fluent = true)
public final class DeleteChatAction
        implements Action {
    /**
     * The message range on which this action has effect
     */
    @ProtobufProperty(index = 1, type = MESSAGE, implementation = ActionMessageRangeSync.class)
    private ActionMessageRangeSync messageRange;

    /**
     * The name of this action
     *
     * @return a non-null string
     */
    @Override
    public String indexName() {
        return "deleteChat";
    }
}
