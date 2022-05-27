package it.auties.whatsapp.model.message.standard;

import it.auties.protobuf.api.model.ProtobufProperty;
import it.auties.whatsapp.api.Whatsapp;
import it.auties.whatsapp.controller.WhatsappStore;
import it.auties.whatsapp.model.info.ContextInfo;
import it.auties.whatsapp.model.info.MessageInfo;
import it.auties.whatsapp.model.message.model.MediaMessage;
import it.auties.whatsapp.model.message.model.MediaMessageType;
import it.auties.whatsapp.util.Clock;
import it.auties.whatsapp.util.Medias;
import lombok.*;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

import java.util.NoSuchElementException;

import static it.auties.protobuf.api.model.ProtobufProperty.Type.*;
import static it.auties.whatsapp.model.message.model.MediaMessageType.DOCUMENT;
import static it.auties.whatsapp.util.Medias.Format.FILE;
import static java.util.Objects.requireNonNullElse;
import static java.util.Objects.requireNonNullElseGet;

/**
 * A model class that represents a WhatsappMessage sent by a contact and that holds a document inside.
 * This class is only a model, this means that changing its values will have no real effect on WhatsappWeb's servers.
 * Instead, methods inside {@link Whatsapp} should be used.
 */
@AllArgsConstructor
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder(builderMethodName = "newRawDocumentMessage", buildMethodName = "create")
@Jacksonized
@Accessors(fluent = true)
public final class DocumentMessage extends MediaMessage {
  /**
   * The upload url of the encoded document that this object wraps
   */
  @ProtobufProperty(index = 1, type = STRING)
  private String url;

  /**
   * The mime type of the audio that this object wraps.
   * Most of the endTimeStamp this is {@link MediaMessageType#defaultMimeType()}
   */
  @ProtobufProperty(index = 2, type = STRING)
  private String mimetype;

  /**
   * The title of the document that this object wraps
   */
  @ProtobufProperty(index = 3, type = STRING)
  private String title;

  /**
   * The sha256 of the decoded media that this object wraps
   */
  @ProtobufProperty(index = 4, type = BYTES)
  private byte[] fileSha256;

  /**
   * The unsigned size of the decoded media that this object wraps
   */
  @ProtobufProperty(index = 5, type = UINT64)
  private Long fileLength;

  /**
   * The unsigned length in pages of the document that this object wraps
   */
  @ProtobufProperty(index = 6, type = UINT32)
  private Integer pageCount;

  /**
   * The media key of the document that this object wraps.
   */
  @ProtobufProperty(index = 7, type = BYTES)
  private byte[] key; 

  /**
   * The name of the document that this object wraps
   */
  @ProtobufProperty(index = 8, type = STRING)
  private String fileName;

  /**
   * The sha256 of the encoded media that this object wraps
   */
  @ProtobufProperty(index = 9, type = BYTES)
  private byte[] fileEncSha256;

  /**
   * The direct path to the encoded media that this object wraps
   */
  @ProtobufProperty(index = 10, type = STRING)
  private String directPath;

  /**
   * The timestamp, that is the seconds elapsed since {@link java.time.Instant#EPOCH}, for {@link DocumentMessage#key()}
   */
  @ProtobufProperty(index = 11, type = UINT64)
  private Long mediaKeyTimestamp;
  
  /**
   * The thumbnail for this document encoded as jpeg in an array of bytes
   */
  @ProtobufProperty(index = 16, type = STRING)
  private byte[] thumbnail;

  /**
   * Constructs a new builder to create a DocumentMessage.
   * The result can be later sent using {@link Whatsapp#sendMessage(MessageInfo)}
   *
   * @param storeId     the id of the store where this message will be stored
   * @param media       the non-null document that the new message wraps
   * @param mimeType    the mime type of the new message, by default {@link MediaMessageType#defaultMimeType()}
   * @param title       the title of the document that the new message wraps
   * @param pageCount   the number of pages of the document that the new message wraps
   * @param fileName    the name of the document that the new message wraps
   * @param thumbnail   the thumbnail of the document that the new message wraps
   * @param contextInfo the context info that the new message wraps
   * @return a non-null new message
   */
  @Builder(builderClassName = "SimpleDocumentMessageBuilder", builderMethodName = "newDocumentMessage", buildMethodName = "create")
  private static DocumentMessage builder(int storeId, byte @NonNull [] media, String mimeType, String title, int pageCount, String fileName, byte[] thumbnail, ContextInfo contextInfo) {
    var store = WhatsappStore.findStoreById(storeId)
            .orElseThrow(() -> new NoSuchElementException("Cannot create document message, invalid store id: %s".formatted(storeId)));
    var upload = Medias.upload(media, DOCUMENT, store);
    return DocumentMessage.newRawDocumentMessage()
            .storeId(storeId)
            .fileSha256(upload.fileSha256())
            .fileEncSha256(upload.fileEncSha256())
            .key(upload.mediaKey())
            .mediaKeyTimestamp(Clock.now())
            .url(upload.url())
            .directPath(upload.directPath())
            .fileLength(upload.fileLength())
            .mimetype(requireNonNullElse(mimeType, DOCUMENT.defaultMimeType()))
            .fileName(fileName)
            .pageCount(pageCount)
            .title(title)
            .thumbnail(requireNonNullElseGet(thumbnail, () -> Medias.getThumbnail(media, FILE).orElse(null)))
            .contextInfo(contextInfo)
            .create();
  }

  /**
   * Returns the media type of the document that this object wraps
   *
   * @return {@link MediaMessageType#DOCUMENT}
   */
  @Override
  public MediaMessageType type() {
    return MediaMessageType.DOCUMENT;
  }
}
