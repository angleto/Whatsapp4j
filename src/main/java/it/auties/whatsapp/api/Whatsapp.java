package it.auties.whatsapp.api;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.ChecksumException;
import com.google.zxing.FormatException;
import com.google.zxing.NotFoundException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import it.auties.curve25519.Curve25519;
import it.auties.linkpreview.LinkPreview;
import it.auties.linkpreview.LinkPreviewMedia;
import it.auties.linkpreview.LinkPreviewResult;
import it.auties.whatsapp.binary.BinaryPatchType;
import it.auties.whatsapp.controller.Keys;
import it.auties.whatsapp.controller.Store;
import it.auties.whatsapp.crypto.*;
import it.auties.whatsapp.listener.*;
import it.auties.whatsapp.model.action.*;
import it.auties.whatsapp.model.business.*;
import it.auties.whatsapp.model.button.template.hsm.HighlyStructuredFourRowTemplate;
import it.auties.whatsapp.model.button.template.hydrated.HydratedFourRowTemplate;
import it.auties.whatsapp.model.call.Call;
import it.auties.whatsapp.model.call.CallStatus;
import it.auties.whatsapp.model.chat.*;
import it.auties.whatsapp.model.chat.PastParticipant.LeaveReason;
import it.auties.whatsapp.model.companion.CompanionLinkResult;
import it.auties.whatsapp.model.contact.Contact;
import it.auties.whatsapp.model.contact.ContactJid;
import it.auties.whatsapp.model.contact.ContactJid.Server;
import it.auties.whatsapp.model.contact.ContactJidProvider;
import it.auties.whatsapp.model.contact.ContactStatus;
import it.auties.whatsapp.model.exchange.*;
import it.auties.whatsapp.model.info.ContextInfo;
import it.auties.whatsapp.model.info.MessageInfo;
import it.auties.whatsapp.model.media.AttachmentProvider;
import it.auties.whatsapp.model.media.AttachmentType;
import it.auties.whatsapp.model.media.MediaFile;
import it.auties.whatsapp.model.message.button.ButtonsMessage;
import it.auties.whatsapp.model.message.button.InteractiveMessage;
import it.auties.whatsapp.model.message.button.TemplateMessage;
import it.auties.whatsapp.model.message.model.*;
import it.auties.whatsapp.model.message.server.ProtocolMessage;
import it.auties.whatsapp.model.message.server.ProtocolMessage.ProtocolMessageType;
import it.auties.whatsapp.model.message.standard.*;
import it.auties.whatsapp.model.message.standard.TextMessage.TextMessagePreviewType;
import it.auties.whatsapp.model.poll.PollAdditionalMetadata;
import it.auties.whatsapp.model.poll.PollUpdateEncryptedMetadata;
import it.auties.whatsapp.model.poll.PollUpdateEncryptedOptions;
import it.auties.whatsapp.model.privacy.GdprAccountReport;
import it.auties.whatsapp.model.privacy.PrivacySettingEntry;
import it.auties.whatsapp.model.privacy.PrivacySettingType;
import it.auties.whatsapp.model.privacy.PrivacySettingValue;
import it.auties.whatsapp.model.setting.LocaleSetting;
import it.auties.whatsapp.model.setting.PushNameSetting;
import it.auties.whatsapp.model.signal.auth.*;
import it.auties.whatsapp.model.signal.auth.UserAgent.UserAgentPlatform;
import it.auties.whatsapp.model.signal.keypair.SignalKeyPair;
import it.auties.whatsapp.model.sync.*;
import it.auties.whatsapp.model.sync.HistorySyncNotification.Type;
import it.auties.whatsapp.model.sync.PatchRequest.PatchEntry;
import it.auties.whatsapp.model.sync.RecordSync.Operation;
import it.auties.whatsapp.socket.SocketHandler;
import it.auties.whatsapp.socket.SocketState;
import it.auties.whatsapp.util.*;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import lombok.experimental.Accessors;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * A class used to interface a user to WhatsappWeb's WebSocket
 */
@Data
@Accessors(fluent = true)
@SuppressWarnings({"unused", "UnusedReturnValue"})
public class Whatsapp {
    // The instances are added and removed when the client connects/disconnects
    // This is to make sure that the instances remain in memory only as long as it's needed
    private static final Map<UUID, Whatsapp> instances = new ConcurrentHashMap<>();

    private final SocketHandler socketHandler;

    /**
     * Checks if a connection exists
     *
     * @param uuid the non-null uuid
     * @return a boolean
     */
    public static boolean isConnected(@NonNull UUID uuid) {
        return SocketHandler.isConnected(uuid);
    }

    /**
     * Checks if a connection exists
     *
     * @param phoneNumber the non-null phone number
     * @return a boolean
     */
    public static boolean isConnected(long phoneNumber) {
        return SocketHandler.isConnected(phoneNumber);
    }

    /**
     * Checks if a connection exists
     *
     * @param alias the non-null alias
     * @return a boolean
     */
    public static boolean isConnected(String alias) {
        return SocketHandler.isConnected(alias);
    }

    /**
     * Advanced builder if you need more customization
     */
    @Builder(builderMethodName = "customBuilder")
    private static Whatsapp builder(@NonNull Store store, @NonNull Keys keys, ErrorHandler errorHandler, WebVerificationSupport webVerificationSupport, Executor socketExecutor){
        Validate.isTrue(Objects.equals(store.uuid(), keys.uuid()),
                "UUIDs for store and keys don't match: %s != %s", store.uuid(), keys.uuid());
        var knownInstance = instances.get(store.uuid());
        if(knownInstance != null){
            return knownInstance;
        }

        var checkedSupport = getWebVerificationMethod(store, keys, webVerificationSupport);
        var result = new Whatsapp(store, keys, errorHandler, checkedSupport, socketExecutor);
        result.addDisconnectedListener(reason -> instances.remove(store.uuid()));
        return result;
    }

    private static WebVerificationSupport getWebVerificationMethod(Store store, Keys keys, WebVerificationSupport webVerificationSupport) {
        if(store.clientType() != ClientType.WEB){
            return null;
        }

        if(!keys.registered() && webVerificationSupport == null) {
            return QrHandler.toTerminal();
        }

        return webVerificationSupport;
    }

    private Whatsapp(@NonNull Store store, @NonNull Keys keys, ErrorHandler errorHandler, WebVerificationSupport webVerificationSupport, Executor socketExecutor) {
        this.socketHandler = new SocketHandler(this, store, keys, errorHandler, webVerificationSupport, socketExecutor);
        if(store.autodetectListeners()){
            return;
        }

        store().addListeners(ListenerScanner.scan(this));
    }


    /**
     * Creates a new web api
     * The web api is based around the WhatsappWeb client
     *
     * @return a web api builder
     */
    public static ConnectionBuilder<WebOptionsBuilder> webBuilder(){
        return new ConnectionBuilder<>(ClientType.WEB);
    }

    /**
     * Creates a new mobile api
     * The mobile api is based around the Whatsapp App available on IOS and Android
     *
     * @return a web mobile builder
     */
    public static ConnectionBuilder<MobileOptionsBuilder> mobileBuilder(){
        return new ConnectionBuilder<>(ClientType.MOBILE);
    }

    /**
     * Connects to Whatsapp
     *
     * @return a future
     */
    public synchronized CompletableFuture<Whatsapp> connect(){
        return socketHandler.connect()
                .thenRunAsync(() -> instances.put(store().uuid(), this))
                .thenApply(ignored -> this);
    }

    /**
     * Connects to Whatsapp
     *
     * @return a future
     */
    public synchronized CompletableFuture<Void> connectAwaitingLogout(){
        return socketHandler.connect()
                .thenRunAsync(() -> instances.put(store().uuid(), this))
                .thenCompose(ignored -> socketHandler.logoutFuture());
    }

    /**
     * Returns whether the connection is active or not
     *
     * @return a boolean
     */
    public boolean isConnected(){
        return socketHandler.state() == SocketState.CONNECTED;
    }

    /**
     * Returns the keys associated with this session
     *
     * @return a non-null WhatsappKeys
     */
    public Keys keys() {
        return socketHandler.keys();
    }

    /**
     * Returns the store associated with this session
     *
     * @return a non-null WhatsappStore
     */
    public Store store() {
        return socketHandler.store();
    }

    /**
     * Disconnects from Whatsapp Web's WebSocket if a previous connection exists
     *
     * @return a future
     */
    public synchronized CompletableFuture<Void> disconnect() {
        return socketHandler.disconnect(DisconnectReason.DISCONNECTED);
    }

    /**
     * Waits for this connection to close
     */
    public void awaitDisconnection() {
        socketHandler.logoutFuture().join();
    }

    /**
     * Disconnects and reconnects to Whatsapp Web's WebSocket if a previous connection exists
     *
     * @return a future
     */
    public CompletableFuture<Void> reconnect() {
        return socketHandler.disconnect(DisconnectReason.RECONNECTING);
    }

    /**
     * Disconnects from Whatsapp Web's WebSocket and logs out of WhatsappWeb invalidating the previous
     * saved credentials. The next time the API is used, the QR code will need to be scanned again.
     *
     * @return a future
     */
    public CompletableFuture<Void> logout() {
        if (store().jid() == null) {
            return socketHandler.disconnect(DisconnectReason.LOGGED_OUT);
        }

        var metadata = Map.of("jid", store().jid(), "reason", "user_initiated");
        var device = Node.of("remove-companion-device", metadata);
        return socketHandler.sendQuery("set", "md", device)
                .thenRun(() -> {});
    }

    /**
     * Changes a privacy setting in Whatsapp's settings. If the value is
     * {@link PrivacySettingValue#CONTACTS_EXCEPT}, the excluded parameter should also be filled or an
     * exception will be thrown, otherwise it will be ignored.
     *
     * @param type     the non-null setting to change
     * @param value    the non-null value to attribute to the setting
     * @param excluded the non-null excluded contacts if value is {@link PrivacySettingValue#CONTACTS_EXCEPT}
     * @return the same instance wrapped in a completable future
     */
    @SafeVarargs
    public final <T extends ContactJidProvider> CompletableFuture<Whatsapp> changePrivacySetting(@NonNull PrivacySettingType type, @NonNull PrivacySettingValue value, @NonNull T @NonNull ... excluded) {
        Validate.isTrue(type.isSupported(value),
                "Cannot change setting %s to %s: this toggle cannot be used because Whatsapp doesn't support it", value.name(), type.name());
        var attributes = Attributes.of()
                .put("name", type.data())
                .put("value", value.data())
                .put("dhash", "none", () -> value == PrivacySettingValue.CONTACTS_EXCEPT)
                .toMap();
        var excludedJids = Arrays.stream(excluded).map(ContactJidProvider::toJid).toList();
        var children = value != PrivacySettingValue.CONTACTS_EXCEPT ? null : excludedJids.stream()
                .map(entry -> Node.of("user", Map.of("jid", entry, "action", "add")))
                .toList();
        return socketHandler.sendQuery("set", "privacy", Node.of("privacy", Node.of("category", attributes, children)))
                .thenRunAsync(() -> onPrivacyFeatureChanged(type, value, excludedJids))
                .thenApply(ignored -> this);
    }

    private void onPrivacyFeatureChanged(PrivacySettingType type, PrivacySettingValue value, List<ContactJid> excludedJids) {
        var newEntry = new PrivacySettingEntry(type, value, excludedJids);
        var oldEntry = store().findPrivacySetting(type);
        store().addPrivacySetting(type, newEntry);
        socketHandler.onPrivacySettingChanged(oldEntry, newEntry);
    }

    /**
     * Changes the default ephemeral timer of new chats.
     *
     * @param timer the new ephemeral timer
     * @return the same instance wrapped in a completable future
     */
    public CompletableFuture<Whatsapp> changeNewChatsEphemeralTimer(@NonNull ChatEphemeralTimer timer) {
        return socketHandler.sendQuery("set", "disappearing_mode", Node.of("disappearing_mode", Map.of("duration", timer.period()
                        .toSeconds())))
                .thenRunAsync(() -> store().newChatsEphemeralTimer(timer))
                .thenApply(ignored -> this);
    }

    /**
     * Creates a new request to get a document containing all the data that was collected by Whatsapp
     * about this user. It takes three business days to receive it. To query the result status, use
     * {@link Whatsapp#getGdprAccountInfoStatus()}
     *
     * @return the same instance wrapped in a completable future
     */
    public CompletableFuture<Whatsapp> createGdprAccountInfo() {
        return socketHandler.sendQuery("get", "urn:xmpp:whatsapp:account", Node.of("gdpr", Map.of("gdpr", "request")))
                .thenApply(ignored -> this);
    }

    /**
     * Queries the document containing all the data that was collected by Whatsapp about this user. To
     * create a request for this document, use {@link Whatsapp#createGdprAccountInfo()}
     *
     * @return the same instance wrapped in a completable future
     */
    // TODO: Implement ready and error states
    public CompletableFuture<GdprAccountReport> getGdprAccountInfoStatus() {
        return socketHandler.sendQuery("get", "urn:xmpp:whatsapp:account", Node.of("gdpr", Map.of("gdpr", "status")))
                .thenApplyAsync(result -> GdprAccountReport.ofPending(result.attributes().getLong("timestamp")));
    }

    /**
     * Changes the name of this user
     *
     * @param newName the non-null new name
     * @return the same instance wrapped in a completable future
     */
    public CompletableFuture<Whatsapp> changeName(@NonNull String newName) {
        var oldName = store().name();
        return socketHandler.send(Node.of("presence", Map.of("name", newName)))
                .thenRunAsync(() -> socketHandler.updateUserName(newName, oldName))
                .thenApply(ignored -> this);
    }

    /**
     * Changes the status(i.e. user description) of this user
     *
     * @param newStatus the non-null new status
     * @return the same instance wrapped in a completable future
     */
    public CompletableFuture<Whatsapp> changeStatus(@NonNull String newStatus) {
        return socketHandler.sendQuery("set", "status", Node.of("status", newStatus.getBytes(StandardCharsets.UTF_8)))
                .thenRunAsync(() -> store().name(newStatus))
                .thenApply(ignored -> this);
    }

    /**
     * Sends a request to Whatsapp in order to receive updates when the status of a contact changes.
     * These changes include the last known presence and the seconds the contact was last seen.
     *
     * @param jid the contact whose status the api should receive updates on
     * @return a CompletableFuture
     */
    public <T extends ContactJidProvider> CompletableFuture<T> subscribeToPresence(@NonNull T jid) {
        return socketHandler.subscribeToPresence(jid).thenApplyAsync(ignored -> jid);
    }

    /**
     * Remove a reaction from a message
     *
     * @param message the non-null message
     * @return a CompletableFuture
     */
    public CompletableFuture<MessageInfo> removeReaction(@NonNull MessageMetadataProvider message) {
        return sendReaction(message, (String) null);
    }

    /**
     * Send a reaction to a message
     *
     * @param message  the non-null message
     * @param reaction the reaction to send, null if you want to remove the reaction. If a string that
     *                 isn't an emoji supported by Whatsapp is used, it will not get displayed
     *                 correctly. Use {@link Whatsapp#sendReaction(MessageMetadataProvider, Emoji)} if
     *                 you need a typed emoji enum.
     * @return a CompletableFuture
     */
    public CompletableFuture<MessageInfo> sendReaction(@NonNull MessageMetadataProvider message, String reaction) {
        var key = MessageKey.builder()
                .chatJid(message.chat().jid())
                .senderJid(message.senderJid())
                .fromMe(Objects.equals(message.senderJid().toWhatsappJid(), store().jid().toWhatsappJid()))
                .id(message.id())
                .build();
        var reactionMessage = ReactionMessage.builder()
                .key(key)
                .content(reaction)
                .timestamp(Instant.now().toEpochMilli())
                .build();
        return sendMessage(message.chat(), reactionMessage);
    }

    /**
     * Builds and sends a message from a chat and a message
     *
     * @param chat    the chat where the message should be sent
     * @param message the message to send
     * @return a CompletableFuture
     */
    public CompletableFuture<MessageInfo> sendMessage(@NonNull ContactJidProvider chat, @NonNull Message message) {
        return sendMessage(chat, MessageContainer.of(message));
    }

    /**
     * Builds and sends a message from a chat and a message
     *
     * @param chat    the chat where the message should be sent
     * @param message the message to send
     * @return a CompletableFuture
     */
    public CompletableFuture<MessageInfo> sendMessage(@NonNull ContactJidProvider chat, @NonNull MessageContainer message) {
        var key = MessageKey.builder()
                .chatJid(chat.toJid())
                .fromMe(true)
                .senderJid(store().jid())
                .build();
        var info = MessageInfo.builder()
                .senderJid(store().jid())
                .key(key)
                .message(message)
                .timestampSeconds(Clock.nowSeconds())
                .broadcast(chat.toJid().hasServer(Server.BROADCAST))
                .build();
        return sendMessage(info);
    }

    /**
     * Sends a message info to a chat
     *
     * @param info the info to send
     * @return a CompletableFuture
     */
    public CompletableFuture<MessageInfo> sendMessage(@NonNull MessageInfo info) {
        return sendMessage(MessageSendRequest.of(info));
    }

    /**
     * Sends a message info to a chat
     *
     * @param request the request to send
     * @return a CompletableFuture
     */
    public CompletableFuture<MessageInfo> sendMessage(@NonNull MessageSendRequest request) {
        store().attribute(request.info());
        return attributeMessageMetadata(request.info())
                .thenComposeAsync(ignored -> socketHandler.sendMessage(request))
                .thenApplyAsync(ignored -> request.info());
    }

    private CompletableFuture<Void> attributeMessageMetadata(MessageInfo info) {
        info.key().chatJid(info.chatJid().toWhatsappJid());
        info.key().senderJid(info.senderJid() == null ? null : info.senderJid().toWhatsappJid());
        fixEphemeralMessage(info);
        var content = info.message().content();
        if (content instanceof MediaMessage mediaMessage) {
            return attributeMediaMessage(mediaMessage);
        } else if (content instanceof ButtonMessage buttonMessage) {
            return attributeButtonMessage(info, buttonMessage);
        } else if (content instanceof TextMessage textMessage) {
            attributeTextMessage(textMessage);
        } else if (content instanceof PollCreationMessage pollCreationMessage) {
            attributePollCreationMessage(info, pollCreationMessage);
        } else if (content instanceof PollUpdateMessage pollUpdateMessage) {
            attributePollUpdateMessage(info, pollUpdateMessage);
        } else if (content instanceof GroupInviteMessage groupInviteMessage) {
            attributeGroupInviteMessage(info, groupInviteMessage);
        }

        return CompletableFuture.completedFuture(null);
    }

    /**
     * Marks a chat as read.
     *
     * @param chat the target chat
     * @return a CompletableFuture
     */
    public <T extends ContactJidProvider> CompletableFuture<T> markRead(@NonNull T chat) {
        return mark(chat, true).thenComposeAsync(ignored -> markAllAsRead(chat)).thenApplyAsync(ignored -> chat);
    }

    private void fixEphemeralMessage(MessageInfo info) {
        if (info.message().hasCategory(MessageCategory.SERVER)) {
            return;
        }
        if (info.chat().isEphemeral()) {
            info.message()
                    .contentWithContext()
                    .map(ContextualMessage::contextInfo)
                    .ifPresent(contextInfo -> createEphemeralContext(info.chat(), contextInfo));
            info.message(info.message().toEphemeral());
            return;
        }

        if (info.message().type() != MessageType.EPHEMERAL) {
            return;
        }

        info.message(info.message().unbox());
    }

    private void attributeTextMessage(TextMessage textMessage) {
        if (store().textPreviewSetting() == TextPreviewSetting.DISABLED) {
            return;
        }
        var match = LinkPreview.createPreview(textMessage.text()).orElse(null);
        if (match == null) {
            return;
        }
        var uri = match.result().uri().toString();
        if (store().textPreviewSetting() == TextPreviewSetting.ENABLED_WITH_INFERENCE && !match.text()
                .equals(uri)) {
            textMessage.text(textMessage.text().replace(match.text(), uri));
        }
        var imageUri = match.result()
                .images()
                .stream()
                .reduce(this::compareDimensions)
                .map(LinkPreviewMedia::uri)
                .orElse(null);
        var videoUri = match.result()
                .videos()
                .stream()
                .reduce(this::compareDimensions)
                .map(LinkPreviewMedia::uri)
                .orElse(null);
        textMessage.matchedText(uri);
        textMessage.canonicalUrl(Objects.requireNonNullElse(videoUri, match.result().uri()).toString());
        textMessage.thumbnail(Medias.download(imageUri).orElse(null));
        textMessage.description(match.result().siteDescription());
        textMessage.title(match.result().title());
        textMessage.previewType(videoUri != null ? TextMessagePreviewType.VIDEO : TextMessagePreviewType.NONE);
    }

    private CompletableFuture<Void> attributeMediaMessage(MediaMessage mediaMessage) {
        return Medias.upload(mediaMessage.decodedMedia().orElseThrow(), mediaMessage.mediaType().toAttachmentType(), store().mediaConnection())
                .thenAccept(upload -> attributeMediaMessage(mediaMessage, upload));
    }

    private AttachmentProvider attributeMediaMessage(MediaMessage mediaMessage, MediaFile upload) {
        return mediaMessage.mediaSha256(upload.fileSha256())
                .mediaEncryptedSha256(upload.fileEncSha256())
                .mediaKey(upload.mediaKey())
                .mediaUrl(upload.url())
                .mediaDirectPath(upload.directPath())
                .mediaSize(upload.fileLength());
    }

    private void attributePollCreationMessage(MessageInfo info, PollCreationMessage pollCreationMessage) {
        var pollEncryptionKey = Objects.requireNonNullElseGet(pollCreationMessage.encryptionKey(), KeyHelper::senderKey);
        pollCreationMessage.encryptionKey(pollEncryptionKey);
        info.messageSecret(pollEncryptionKey);
        info.message().deviceInfo().messageSecret(pollEncryptionKey);
        var metadata = new PollAdditionalMetadata(false);
        info.pollAdditionalMetadata(metadata);
    }

    private void attributePollUpdateMessage(MessageInfo info, PollUpdateMessage pollUpdateMessage) {
        if (pollUpdateMessage.encryptedMetadata() != null) {
            return;
        }
        var iv = BytesHelper.random(12);
        var additionalData = "%s\0%s".formatted(pollUpdateMessage.pollCreationMessageKey().id(), store().jid().toWhatsappJid());
        var encryptedOptions = pollUpdateMessage.votes().stream().map(entry -> Sha256.calculate(entry.name())).toList();
        var pollUpdateEncryptedOptions = Protobuf.writeMessage(new PollUpdateEncryptedOptions(encryptedOptions));
        var originalPollInfo = store()
                .findMessageByKey(pollUpdateMessage.pollCreationMessageKey())
                .orElseThrow(() -> new NoSuchElementException("Missing original poll message"));
        var originalPollMessage = (PollCreationMessage) originalPollInfo.message().content();
        var originalPollSender = originalPollInfo.senderJid().toWhatsappJid().toString().getBytes(StandardCharsets.UTF_8);
        var modificationSenderJid = info.senderJid().toWhatsappJid();
        pollUpdateMessage.voter(modificationSenderJid);
        var modificationSender = modificationSenderJid.toString().getBytes(StandardCharsets.UTF_8);
        var secretName = pollUpdateMessage.secretName().getBytes(StandardCharsets.UTF_8);
        var useSecretPayload = BytesHelper.concat(
                pollUpdateMessage.pollCreationMessageKey().id().getBytes(StandardCharsets.UTF_8),
                originalPollSender,
                modificationSender,
                secretName
        );
        var useCaseSecret = Hkdf.extractAndExpand(originalPollMessage.encryptionKey(), useSecretPayload, 32);
        var pollUpdateEncryptedPayload = AesGcm.encrypt(iv, pollUpdateEncryptedOptions, useCaseSecret, additionalData.getBytes(StandardCharsets.UTF_8));
        var pollUpdateEncryptedMetadata = new PollUpdateEncryptedMetadata(pollUpdateEncryptedPayload, iv);
        pollUpdateMessage.encryptedMetadata(pollUpdateEncryptedMetadata);
    }

    private CompletableFuture<Void> attributeButtonMessage(MessageInfo info, ButtonMessage buttonMessage) {
        if (buttonMessage instanceof ButtonsMessage buttonsMessage
                && buttonsMessage.header().isPresent()
                && buttonsMessage.header().get() instanceof MediaMessage mediaMessage) {
            return attributeMediaMessage(mediaMessage);
        } else if (buttonMessage instanceof TemplateMessage templateMessage && templateMessage.format().isPresent()) {
            var templateFormatter = templateMessage.format().get();
            if (templateFormatter instanceof HighlyStructuredFourRowTemplate highlyStructuredFourRowTemplate
                    && highlyStructuredFourRowTemplate.title().isPresent()
                    && highlyStructuredFourRowTemplate.title().get() instanceof MediaMessage mediaMessage) {
                return attributeMediaMessage(mediaMessage);
            } else if (templateFormatter instanceof HydratedFourRowTemplate hydratedFourRowTemplate
                    && hydratedFourRowTemplate.title().isPresent()
                    && hydratedFourRowTemplate.title().get() instanceof MediaMessage mediaMessage) {
                return attributeMediaMessage(mediaMessage);
            }else {
                return CompletableFuture.completedFuture(null);
            }
        } else if (buttonMessage instanceof InteractiveMessage interactiveMessage
                && interactiveMessage.header().isPresent()
                && interactiveMessage.header().get().attachment().isPresent()
                && interactiveMessage.header().get().attachment().get() instanceof MediaMessage mediaMessage) {
            return attributeMediaMessage(mediaMessage);
        } else {
             return CompletableFuture.completedFuture(null);
        }
    }

    // This is not needed probably, but Whatsapp uses a text message by default, so maybe it makes sense
    private void attributeGroupInviteMessage(MessageInfo info, GroupInviteMessage groupInviteMessage) {
        Validate.isTrue(groupInviteMessage.code() != null, "Invalid message code");
        var url = "https://chat.whatsapp.com/%s".formatted(groupInviteMessage.code());
        var preview = LinkPreview.createPreview(URI.create(url))
                .stream()
                .map(LinkPreviewResult::images)
                .map(Collection::stream)
                .map(Stream::findFirst)
                .flatMap(Optional::stream)
                .findFirst()
                .map(LinkPreviewMedia::uri)
                .orElse(null);
        var replacement = TextMessage.builder()
                .text(groupInviteMessage.caption() != null ? "%s: %s".formatted(groupInviteMessage.caption(), url) : url)
                .description("WhatsApp Group Invite")
                .title(groupInviteMessage.groupName())
                .previewType(TextMessagePreviewType.NONE)
                .thumbnail(Medias.download(preview).orElse(null))
                .matchedText(url)
                .canonicalUrl(url)
                .build();
        info.message(MessageContainer.of(replacement));
    }

    private <T extends ContactJidProvider> CompletableFuture<T> mark(@NonNull T chat, boolean read) {
        if(store().clientType() == ClientType.MOBILE){
            // TODO: Send notification to companions
            store().findChatByJid(chat.toJid())
                    .ifPresent(entry -> entry.markedAsUnread(read));
            return CompletableFuture.completedFuture(chat);
        }

        var range = createRange(chat, false);
        var markAction = new MarkChatAsReadAction(read, range);
        var syncAction = ActionValueSync.of(markAction);
        var entry = PatchEntry.of(syncAction, Operation.SET, 3, chat.toJid().toString());
        var request = new PatchRequest(BinaryPatchType.REGULAR_HIGH, List.of(entry));
        return socketHandler.pushPatch(request).thenApplyAsync(ignored -> chat);
    }

    private CompletableFuture<Void> markAllAsRead(ContactJidProvider chat) {
        var all = store()
                .findChatByJid(chat.toJid())
                .stream()
                .map(Chat::unreadMessages)
                .flatMap(Collection::stream)
                .map(this::markRead)
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(all);
    }

    private LinkPreviewMedia compareDimensions(LinkPreviewMedia first, LinkPreviewMedia second) {
        return first.width() * first.height() > second.width() * second.height() ? first : second;
    }

    private ActionMessageRangeSync createRange(ContactJidProvider chat, boolean allMessages) {
        var known = store().findChatByJid(chat.toJid()).orElseGet(() -> store().addNewChat(chat.toJid()));
        return new ActionMessageRangeSync(known, allMessages);
    }

    /**
     * Marks a message as read
     *
     * @param info the target message
     * @return a CompletableFuture
     */
    public CompletableFuture<MessageInfo> markRead(@NonNull MessageInfo info) {
        var type = store().findPrivacySetting(PrivacySettingType.READ_RECEIPTS)
                .value() == PrivacySettingValue.EVERYONE ? "read" : "read-self";
        socketHandler.sendReceipt(info.chatJid(), info.senderJid(), List.of(info.id()), type);
        var count = info.chat().unreadMessagesCount();
        if (count > 0) {
            info.chat().unreadMessagesCount(count - 1);
        }
        return CompletableFuture.completedFuture(info.status(MessageStatus.READ));
    }

    private void createEphemeralContext(Chat chat, ContextInfo contextInfo) {
        var period = chat.ephemeralMessageDuration().period().toSeconds();
        contextInfo.ephemeralExpiration((int) period);
    }

    /**
     * Send a reaction to a message
     *
     * @param message  the non-null message
     * @param reaction the reaction to send, null if you want to remove the reaction
     * @return a CompletableFuture
     */
    public CompletableFuture<MessageInfo> sendReaction(@NonNull MessageMetadataProvider message, Emoji reaction) {
        return sendReaction(message, Objects.toString(reaction));
    }

    /**
     * Builds and sends a message from a chat and a message
     *
     * @param chat    the chat where the message should be sent
     * @param message the message to send
     * @return a CompletableFuture
     */
    public CompletableFuture<MessageInfo> sendMessage(@NonNull ContactJidProvider chat, @NonNull String message) {
        return sendMessage(chat, MessageContainer.of(message));
    }

    /**
     * Builds and sends a message from a chat, a message and a quoted message
     *
     * @param chat          the chat where the message should be sent
     * @param message       the message to send
     * @param quotedMessage the quoted message
     * @return a CompletableFuture
     */
    public CompletableFuture<MessageInfo> sendMessage(@NonNull ContactJidProvider chat, @NonNull String message, @NonNull MessageMetadataProvider quotedMessage) {
        return sendMessage(chat, TextMessage.of(message), quotedMessage);
    }

    /**
     * Builds and sends a message from a chat, a message and a quoted message
     *
     * @param chat          the chat where the message should be sent
     * @param message       the message to send
     * @param quotedMessage the quoted message
     * @return a CompletableFuture
     */
    public CompletableFuture<MessageInfo> sendMessage(@NonNull ContactJidProvider chat, @NonNull ContextualMessage message, @NonNull MessageMetadataProvider quotedMessage) {
        Validate.isTrue(!quotedMessage.message().isEmpty(), "Cannot quote an empty message");
        Validate.isTrue(!quotedMessage.message().hasCategory(MessageCategory.SERVER), "Cannot quote a server message");
        return sendMessage(chat, message, ContextInfo.of(quotedMessage));
    }

    /**
     * Builds and sends a message from a chat, a message and a context
     *
     * @param chat        the chat where the message should be sent
     * @param message     the message to send
     * @param contextInfo the context of the message to send
     * @return a CompletableFuture
     */
    public CompletableFuture<MessageInfo> sendMessage(@NonNull ContactJidProvider chat, @NonNull ContextualMessage message, @NonNull ContextInfo contextInfo) {
        message.contextInfo(contextInfo);
        return sendMessage(chat, message);
    }

    /**
     * Awaits for a single response to a message
     *
     * @param info the non-null message whose response is pending
     * @return a non-null result
     */
    public CompletableFuture<MessageInfo> awaitReply(@NonNull MessageInfo info) {
        return awaitReply(info.id());
    }

    /**
     * Awaits for a single response to a message
     *
     * @param id the non-null id of message whose response is pending
     * @return a non-null result
     */
    public CompletableFuture<MessageInfo> awaitReply(@NonNull String id) {
        return store().addPendingReply(id);
    }

    /**
     * Executes a query to determine whether a user has an account on Whatsapp
     *
     * @param contact the contact to check
     * @return a CompletableFuture that wraps a non-null response
     */
    public CompletableFuture<HasWhatsappResponse> hasWhatsapp(@NonNull ContactJidProvider contact) {
        return hasWhatsapp(new ContactJidProvider[]{contact}).thenApply(result -> result.get(contact.toJid()));
    }

    /**
     * Executes a query to determine whether any number of users have an account on Whatsapp
     *
     * @param contacts the contacts to check
     * @return a CompletableFuture that wraps a non-null map
     */
    public CompletableFuture<Map<ContactJid, HasWhatsappResponse>> hasWhatsapp(@NonNull ContactJidProvider... contacts) {
        var contactNodes = Arrays.stream(contacts)
                .map(jid -> Node.of("user", Node.of("contact", jid.toJid().toPhoneNumber())))
                .toArray(Node[]::new);
        return socketHandler.sendInteractiveQuery(Node.of("contact"), contactNodes)
                .thenApplyAsync(this::parseHasWhatsappResponse);
    }

    private Map<ContactJid, HasWhatsappResponse> parseHasWhatsappResponse(List<Node> nodes) {
        return nodes.stream()
                .map(HasWhatsappResponse::new)
                .collect(Collectors.toMap(HasWhatsappResponse::contact, Function.identity()));
    }

    /**
     * Queries the block list
     *
     * @return a CompletableFuture
     */
    public CompletableFuture<List<ContactJid>> queryBlockList() {
        return socketHandler.queryBlockList();
    }

    /**
     * Queries the display name of a contact
     *
     * @param contactJid the non-null contact
     * @return a CompletableFuture
     */
    public CompletableFuture<Optional<String>> queryName(@NonNull ContactJidProvider contactJid) {
        var contact = store().findContactByJid(contactJid);
        if(contact.isPresent()){
            return CompletableFuture.completedFuture(Optional.ofNullable(contact.get().chosenName()));
        }

        var query = new MexQueryRequest(List.of(new MexQueryRequest.User(contactJid.toJid().user())), List.of("STATUS"));
        return socketHandler.sendQuery("get", "w:mex", Node.of("query", Json.writeValueAsBytes(query)))
                .thenApplyAsync(this::parseNameResponse);
    }

    private Optional<String> parseNameResponse(Node result) {
        return result.findNode("result")
                .flatMap(Node::contentAsString)
                .map(json -> Json.readValue(json, MexQueryResult.class))
                .map(MexQueryResult::data)
                .map(String::valueOf);
    }

    /**
     * Queries the written whatsapp status of a Contact
     *
     * @param chat the target contact
     * @return a CompletableFuture that wraps an optional contact status response
     */
    public CompletableFuture<Optional<ContactStatusResponse>> queryAbout(@NonNull ContactJidProvider chat) {
        return socketHandler.queryAbout(chat);
    }

    /**
     * Queries the profile picture
     *
     * @param chat the chat of the chat to query
     * @return a CompletableFuture that wraps nullable jpg url hosted on Whatsapp's servers
     */
    public CompletableFuture<Optional<URI>> queryPicture(@NonNull ContactJidProvider chat) {
        return socketHandler.queryPicture(chat);
    }

    /**
     * Queries the metadata of a group
     *
     * @param chat the target group
     * @return a CompletableFuture
     */
    public CompletableFuture<GroupMetadata> queryGroupMetadata(@NonNull ContactJidProvider chat) {
        return socketHandler.queryGroupMetadata(chat.toJid());
    }

    /**
     * Queries a business profile, if any exists
     *
     * @param contact the target contact
     * @return a CompletableFuture
     */
    public CompletableFuture<Optional<BusinessProfile>> queryBusinessProfile(@NonNull ContactJidProvider contact) {
        return socketHandler.sendQuery("get", "w:biz", Node.of("business_profile", Map.of("v", 116),
                        Node.of("profile", Map.of("jid", contact.toJid()))))
                .thenApplyAsync(this::getBusinessProfile);
    }

    private Optional<BusinessProfile> getBusinessProfile(Node result) {
        return result.findNode("business_profile")
                .flatMap(entry -> entry.findNode("profile"))
                .map(BusinessProfile::of);
    }

    /**
     * Queries all the known business categories
     *
     * @return a CompletableFuture
     */
    public CompletableFuture<List<BusinessCategory>> queryBusinessCategories() {
        return socketHandler.queryBusinessCategories();
    }

    /**
     * Queries the invite code of a group
     *
     * @param chat the target group
     * @return a CompletableFuture
     */
    public CompletableFuture<String> queryGroupInviteCode(@NonNull ContactJidProvider chat) {
        return socketHandler.sendQuery(chat.toJid(), "get", "w:g2", Node.of("invite"))
                .thenApplyAsync(Whatsapp::parseInviteCode);
    }

    private static String parseInviteCode(Node result) {
        return result.findNode("invite")
                .orElseThrow(() -> new NoSuchElementException("Missing invite code in invite response"))
                .attributes()
                .getRequiredString("code");
    }

    /**
     * Revokes the invite code of a group
     *
     * @param chat the target group
     * @return a CompletableFuture
     */
    public <T extends ContactJidProvider> CompletableFuture<T> revokeGroupInvite(@NonNull T chat) {
        return socketHandler.sendQuery(chat.toJid(), "set", "w:g2", Node.of("invite")).thenApplyAsync(ignored -> chat);
    }

    /**
     * Accepts the invite for a group
     *
     * @param inviteCode the invite countryCode
     * @return a CompletableFuture
     */
    public CompletableFuture<Optional<Chat>> acceptGroupInvite(@NonNull String inviteCode) {
        return socketHandler.sendQuery(Server.GROUP.toJid(), "set", "w:g2", Node.of("invite", Map.of("code", inviteCode)))
                .thenApplyAsync(this::parseAcceptInvite);
    }

    private Optional<Chat> parseAcceptInvite(Node result) {
        return result.findNode("group")
                .flatMap(group -> group.attributes().getJid("jid"))
                .map(jid -> store().findChatByJid(jid).orElseGet(() -> store().addNewChat(jid)));
    }

    /**
     * Changes your presence for everyone on Whatsapp
     *
     * @param available whether you are online or not
     * @return a CompletableFuture
     */
    public CompletableFuture<Boolean> changePresence(boolean available) {
        var status = socketHandler.store().online();
        if(status == available){
            return CompletableFuture.completedFuture(status);
        }

        var presence = available ? ContactStatus.AVAILABLE : ContactStatus.UNAVAILABLE;
        var node = Node.of("presence", Map.of("name", store().name(), "type", presence.data()));
        return socketHandler.sendWithNoResponse(node)
                .thenAcceptAsync(socketHandler -> updateSelfPresence(null, presence))
                .thenApplyAsync(ignored -> available);
    }

    private void updateSelfPresence(ContactJidProvider chatJid, ContactStatus presence) {
        if(chatJid == null){
            store().online(presence == ContactStatus.AVAILABLE);
        }

        var self = store().findContactByJid(store().jid().toWhatsappJid());
        if (self.isEmpty()) {
            return;
        }
        if (presence == ContactStatus.AVAILABLE || presence == ContactStatus.UNAVAILABLE) {
            self.get().lastKnownPresence(presence);
        }
        if (chatJid != null) {
            store().findChatByJid(chatJid).ifPresent(chat -> chat.presences().put(self.get().jid(), presence));
        }
        self.get().lastSeen(ZonedDateTime.now());
    }

    /**
     * Changes your presence for a specific chat
     *
     * @param chatJid  the target chat
     * @param presence the new status
     * @return a CompletableFuture
     */
    public <T extends ContactJidProvider> CompletableFuture<T> changePresence(@NonNull T chatJid, @NonNull ContactStatus presence) {
        var knownPresence = store().findChatByJid(chatJid)
                .map(Chat::presences)
                .map(entry -> entry.get(store().jid().toWhatsappJid()))
                .orElse(null);
        if(knownPresence == ContactStatus.COMPOSING || knownPresence == ContactStatus.RECORDING){
            var node = Node.of("chatstate", Map.of("to", chatJid.toJid()), Node.of("paused"));
            return socketHandler.sendWithNoResponse(node)
                    .thenApplyAsync(ignored -> chatJid);
        }


        if(presence == ContactStatus.COMPOSING || presence == ContactStatus.RECORDING){
            var tag = presence == ContactStatus.RECORDING ? ContactStatus.COMPOSING.data() : presence.data();
            var node = Node.of("chatstate",
                    Map.of("to", chatJid.toJid()),
                    Node.of(ContactStatus.COMPOSING.data(), presence == ContactStatus.RECORDING ? Map.of("media", "audio") : Map.of()));
            return socketHandler.sendWithNoResponse(node)
                    .thenAcceptAsync(socketHandler -> updateSelfPresence(chatJid, presence))
                    .thenApplyAsync(ignored -> chatJid);
        }

        var node = Node.of("presence", Map.of("type", presence.data(), "name", store().name()));
        return socketHandler.sendWithNoResponse(node)
                .thenAcceptAsync(socketHandler -> updateSelfPresence(chatJid, presence))
                .thenApplyAsync(ignored -> chatJid);
    }

    /**
     * Promotes any number of contacts to admin in a group
     *
     * @param group    the target group
     * @param contacts the target contacts
     * @return a CompletableFuture
     */
    public CompletableFuture<List<ContactJid>> promote(@NonNull ContactJidProvider group, @NonNull ContactJidProvider @NonNull ... contacts) {
        return executeActionOnGroupParticipant(group, GroupAction.PROMOTE, contacts);
    }

    /**
     * Demotes any number of contacts to admin in a group
     *
     * @param group    the target group
     * @param contacts the target contacts
     * @return a CompletableFuture
     */
    public CompletableFuture<List<ContactJid>> demote(@NonNull ContactJidProvider group, @NonNull ContactJidProvider @NonNull ... contacts) {
        return executeActionOnGroupParticipant(group, GroupAction.DEMOTE, contacts);
    }

    /**
     * Adds any number of contacts to a group
     *
     * @param group    the target group
     * @param contacts the target contact/s
     * @return a CompletableFuture
     */
    public CompletableFuture<List<ContactJid>> addGroupParticipant(@NonNull ContactJidProvider group, @NonNull ContactJidProvider @NonNull ... contacts) {
        return executeActionOnGroupParticipant(group, GroupAction.ADD, contacts);
    }

    /**
     * Removes any number of contacts from group
     *
     * @param group    the target group
     * @param contacts the target contact/s
     * @return a CompletableFuture
     */
    public CompletableFuture<List<ContactJid>> removeGroupParticipant(@NonNull ContactJidProvider group, @NonNull ContactJidProvider @NonNull ... contacts) {
        return executeActionOnGroupParticipant(group, GroupAction.REMOVE, contacts);
    }

    private CompletableFuture<List<ContactJid>> executeActionOnGroupParticipant(ContactJidProvider group, GroupAction action, ContactJidProvider... jids) {
        var body = Arrays.stream(jids)
                .map(ContactJidProvider::toJid)
                .map(jid -> Node.of("participant", Map.of("jid", checkGroupParticipantJid(jid, "Cannot execute action on yourself"))))
                .map(innerBody -> Node.of(action.data(), innerBody))
                .toArray(Node[]::new);
        return socketHandler.sendQuery(group.toJid(), "set", "w:g2", body)
                .thenApplyAsync(result -> parseGroupActionResponse(result, group, action));
    }

    private ContactJid checkGroupParticipantJid(ContactJid jid, String errorMessage) {
        Validate.isTrue(!Objects.equals(jid.toWhatsappJid(), store().jid().toWhatsappJid()), errorMessage);
        return jid;
    }

    private List<ContactJid> parseGroupActionResponse(Node result, ContactJidProvider groupJid, GroupAction action) {
        var results = result.findNode(action.data())
                .orElseThrow(() -> new NoSuchElementException("An erroneous group operation was executed"))
                .findNodes("participant")
                .stream()
                .filter(participant -> !participant.attributes().hasKey("error"))
                .map(participant -> participant.attributes().getJid("jid"))
                .flatMap(Optional::stream)
                .toList();
        var chat = groupJid instanceof Chat entry ? entry : store()
                .findChatByJid(groupJid)
                .orElse(null);
        if (chat != null) {
            results.forEach(entry -> handleGroupAction(action, chat, entry));
        }

        return results;
    }

    private void handleGroupAction(GroupAction action, Chat chat, ContactJid entry) {
        switch (action) {
            case ADD -> chat.addParticipant(entry, GroupRole.USER);
            case REMOVE -> {
                chat.removeParticipant(entry);
                chat.addPastParticipant(new PastParticipant(entry, LeaveReason.REMOVED, Clock.nowSeconds()));
            }
            case PROMOTE -> chat.findParticipant(entry)
                    .ifPresent(participant -> participant.role(GroupRole.ADMIN));
            case DEMOTE -> chat.findParticipant(entry)
                    .ifPresent(participant -> participant.role(GroupRole.USER));
        }
    }

    /**
     * Changes the name of a group
     *
     * @param group   the target group
     * @param newName the new name for the group
     * @return a CompletableFuture
     * @throws IllegalArgumentException if the provided new name is empty or blank
     */
    public <T extends ContactJidProvider> CompletableFuture<T> changeGroupSubject(@NonNull T group, @NonNull String newName) {
        var body = Node.of("subject", newName.getBytes(StandardCharsets.UTF_8));
        return socketHandler.sendQuery(group.toJid(), "set", "w:g2", body).thenApplyAsync(ignored -> group);
    }

    /**
     * Changes the description of a group
     *
     * @param group       the target group
     * @param description the new name for the group, can be null if you want to remove it
     * @return a CompletableFuture
     */
    public <T extends ContactJidProvider> CompletableFuture<T> changeGroupDescription(@NonNull T group, String description) {
        return socketHandler.queryGroupMetadata(group.toJid())
                .thenApplyAsync(GroupMetadata::descriptionId)
                .thenComposeAsync(descriptionId -> changeGroupDescription(group, description, descriptionId))
                .thenApplyAsync(ignored -> group);
    }

    private CompletableFuture<Void> changeGroupDescription(ContactJidProvider group, String description, String descriptionId) {
        var descriptionNode = Optional.ofNullable(description)
                .map(content -> Node.of("body", content.getBytes(StandardCharsets.UTF_8)))
                .orElse(null);
        var attributes = Attributes.of()
                .put("id", MessageKey.randomId(), () -> description != null)
                .put("delete", true, () -> description == null)
                .put("prev", descriptionId, () -> descriptionId != null)
                .toMap();
        var body = Node.of("description", attributes, descriptionNode);
        return socketHandler.sendQuery(group.toJid(), "set", "w:g2", body)
                .thenRunAsync(() -> onDescriptionSet(group, description));
    }

    private void onDescriptionSet(ContactJidProvider groupJid, String description) {
        if (groupJid instanceof Chat chat) {
            chat.description(description);
            return;
        }

        var group = store().findChatByJid(groupJid);
        group.ifPresent(chat -> chat.description(description));
    }

    /**
     * Changes a group setting
     *
     * @param group   the non-null group affected by this change
     * @param setting the non-null setting
     * @param policy  the non-null policy
     * @return a future
     */
    public <T extends ContactJidProvider> CompletableFuture<T> changeGroupSetting(@NonNull T group, @NonNull GroupSetting setting, @NonNull SettingPolicy policy) {
        var body = Node.of(policy != SettingPolicy.ANYONE ? setting.on() : setting.off());
        return socketHandler.sendQuery(group.toJid(), "set", "w:g2", body)
                .thenApplyAsync(ignored -> group);
    }

    /**
     * Changes the profile picture of yourself
     *
     * @param image the new image, can be null if you want to remove it
     * @return a CompletableFuture
     */
    public CompletableFuture<ContactJid> changeProfilePicture(byte[] image) {
        return changeGroupPicture(store().jid(), image);
    }

    /**
     * Changes the picture of a group
     *
     * @param group the target group
     * @param image the new image, can be null if you want to remove it
     * @return a CompletableFuture
     */
    public <T extends ContactJidProvider> CompletableFuture<T> changeGroupPicture(@NonNull T group, URI image) {
        return changeGroupPicture(group, image == null ? null : Medias.download(image)
                .orElseThrow(() -> new IllegalArgumentException("Invalid uri: %s".formatted(image))));
    }

    /**
     * Changes the picture of a group
     *
     * @param group the target group
     * @param image the new image, can be null if you want to remove it
     * @return a CompletableFuture
     */
    public <T extends ContactJidProvider> CompletableFuture<T> changeGroupPicture(@NonNull T group, byte[] image) {
        var profilePic = image != null ? Medias.getProfilePic(image) : null;
        var body = Node.of("picture", Map.of("type", "image"), profilePic);
        return socketHandler.sendQuery(group.toJid().toWhatsappJid(), "set", "w:profile:picture", body)
                .thenApplyAsync(ignored -> group);
    }

    /**
     * Creates a new group
     *
     * @param subject  the new group's name
     * @param contacts at least one contact to add to the group
     * @return a CompletableFuture
     */
    public CompletableFuture<GroupMetadata> createGroup(@NonNull String subject, @NonNull ContactJidProvider... contacts) {
        return createGroup(subject, ChatEphemeralTimer.OFF, contacts);
    }

    /**
     * Creates a new group
     *
     * @param subject  the new group's name
     * @param timer    the default ephemeral timer for messages sent in this group
     * @param contacts at least one contact to add to the group
     * @return a CompletableFuture
     */
    public CompletableFuture<GroupMetadata> createGroup(@NonNull String subject, @NonNull ChatEphemeralTimer timer, @NonNull ContactJidProvider... contacts) {
        return createGroup(subject, timer, null, contacts);
    }

    /**
     * Creates a new group
     *
     * @param subject     the new group's name
     * @param timer       the default ephemeral timer for messages sent in this group
     * @param parentGroup the community to whom the new group will be linked
     * @return a CompletableFuture
     */
    public CompletableFuture<GroupMetadata> createGroup(@NonNull String subject, @NonNull ChatEphemeralTimer timer, ContactJidProvider parentGroup) {
        return createGroup(subject, timer, parentGroup, new ContactJidProvider[0]);
    }

    /**
     * Creates a new group
     *
     * @param subject     the new group's name
     * @param timer       the default ephemeral timer for messages sent in this group
     * @param parentCommunity the community to whom the new group will be linked
     * @param contacts    at least one contact to add to the group, not enforced if part of a community
     * @return a CompletableFuture
     */
    public CompletableFuture<GroupMetadata> createGroup(@NonNull String subject, @NonNull ChatEphemeralTimer timer, ContactJidProvider parentCommunity, @NonNull ContactJidProvider... contacts) {
        Validate.isTrue(!subject.isBlank(), "The subject of a group cannot be blank");
        var minimumMembersCount = parentCommunity == null ? 1 : 0;
        Validate.isTrue(contacts.length >= minimumMembersCount, "Expected at least %s members for this group", minimumMembersCount);
        var children = new ArrayList<Node>();
        if (parentCommunity != null) {
            children.add(Node.of("linked_parent", Map.of("jid", parentCommunity.toJid())));
        }
        if (timer != ChatEphemeralTimer.OFF) {
            children.add(Node.of("ephemeral", Map.of("expiration", timer.periodSeconds())));
        }
        Arrays.stream(contacts)
                .map(contact -> Node.of("participant", Map.of("jid", checkGroupParticipantJid(contact.toJid(), "Cannot create group with yourself as a participant"))))
                .forEach(children::add);
        var key = HexFormat.of().formatHex(BytesHelper.random(12));
        var body = Node.of("create", Map.of("subject", subject, "key", key), children);
        return socketHandler.sendQuery(Server.GROUP.toJid(), "set", "w:g2", body)
                .thenApplyAsync(this::parseGroupResponse);
    }

    private GroupMetadata parseGroupResponse(Node response) {
        return Optional.ofNullable(response)
                .flatMap(node -> node.findNode("group"))
                .map(GroupMetadata::of)
                .map(this::addNewGroup)
                .orElseThrow(() -> new NoSuchElementException("Missing group response, something went wrong: %s".formatted(findErrorNode(response))));
    }

    private GroupMetadata addNewGroup(GroupMetadata result) {
        var chat = Chat.builder()
                .jid(result.jid())
                .description(result.description().orElse(null))
                .participants(result.participants())
                .founder(result.founder().orElse(null))
                .foundationTimestampSeconds(result.foundationTimestamp().toEpochSecond())
                .build();
        store().addChat(chat);
        return result;
    }

    private String findErrorNode(Node result) {
        return Optional.ofNullable(result)
                .flatMap(node -> node.findNode("error"))
                .map(Node::toString)
                .orElseGet(() -> Objects.toString(result));
    }

    /**
     * Leaves a group
     *
     * @param group the target group
     * @throws IllegalArgumentException if the provided chat is not a group
     */
    public <T extends ContactJidProvider> CompletableFuture<T> leaveGroup(@NonNull T group) {
        var body = Node.of("leave", Node.of("group", Map.of("id", group.toJid())));
        return socketHandler.sendQuery(Server.GROUP.toJid(), "set", "w:g2", body)
                .thenApplyAsync(ignored -> handleLeaveGroup(group));
    }

    private <T extends ContactJidProvider> T handleLeaveGroup(T group) {
        var chat = group instanceof Chat entry ? entry : store()
                .findChatByJid(group)
                .orElse(null);
        if(chat != null) {
            var pastParticipant = PastParticipant.builder()
                    .jid(store().jid().toWhatsappJid())
                    .reason(LeaveReason.REMOVED)
                    .timestampSeconds(Clock.nowSeconds())
                    .build();
            chat.addPastParticipant(pastParticipant);
        }

        return group;
    }

    /**
     * Links any number of groups to a community
     *
     * @param community the non-null community where the groups will be added
     * @param groups the non-null groups to add
     * @return a CompletableFuture that wraps a map guaranteed to contain every group that was provided as input paired to whether the request was successful
     */
    public CompletableFuture<Map<ContactJid, Boolean>> linkGroupsToCommunity(@NonNull ContactJidProvider community, @NonNull ContactJidProvider... groups){
        var body = Arrays.stream(groups)
                .map(entry -> Node.of("group", Map.of("jid", entry.toJid())))
                .toArray(Node[]::new);
        return socketHandler.sendQuery(community.toJid(), "set", "w:g2", Node.of("links", Node.of("link", Map.of("link_type", "sub_group"), body)))
                .thenApplyAsync(result -> parseLinksResponse(result, groups));
    }

    private Map<ContactJid, Boolean> parseLinksResponse(Node result, @NonNull ContactJidProvider[] groups) {
        var success = result.findNode("links")
                .stream()
                .map(entry -> entry.findNodes("link"))
                .flatMap(Collection::stream)
                .filter(entry -> entry.attributes().hasValue("link_type", "sub_group"))
                .map(entry -> entry.findNode("group"))
                .flatMap(Optional::stream)
                .map(entry -> entry.attributes().getJid("jid"))
                .flatMap(Optional::stream)
                .collect(Collectors.toUnmodifiableSet());
        return Arrays.stream(groups)
                .map(ContactJidProvider::toJid)
                .collect(Collectors.toUnmodifiableMap(Function.identity(), success::contains));
    }

    /**
     * Unlinks a group from a community
     *
     * @param community the non-null parent community
     * @param group the non-null group to unlink
     * @return a CompletableFuture that indicates whether the request was successful
     */
    public CompletableFuture<Boolean> unlinkGroupFromCommunity(@NonNull ContactJidProvider community, @NonNull ContactJidProvider group){
        return socketHandler.sendQuery(community.toJid(), "set", "w:g2", Node.of("unlink", Map.of("unlink_type", "sub_group"), Node.of("group", Map.of("jid", group.toJid()))))
                .thenApplyAsync(result -> parseUnlinkResponse(result, group));
    }

    private boolean parseUnlinkResponse(Node result, @NonNull ContactJidProvider group) {
        return result.findNode("unlink")
                .filter(entry -> entry.attributes().hasValue("unlink_type", "sub_group"))
                .flatMap(entry -> entry.findNode("group"))
                .map(entry -> entry.attributes().hasValue("jid", group.toJid().toString()))
                .isPresent();
    }

    /**
     * Mutes a chat indefinitely
     *
     * @param chat the target chat
     * @return a CompletableFuture
     */
    public <T extends ContactJidProvider> CompletableFuture<T> mute(@NonNull T chat) {
        return mute(chat, ChatMute.muted());
    }

    /**
     * Mutes a chat
     *
     * @param chat the target chat
     * @param mute the type of mute
     * @return a CompletableFuture
     */
    public <T extends ContactJidProvider> CompletableFuture<T> mute(@NonNull T chat, @NonNull ChatMute mute) {
        if(store().clientType() == ClientType.MOBILE){
            // TODO: Send notification to companions
            store().findChatByJid(chat)
                    .ifPresent(entry -> entry.mute(mute));
            return CompletableFuture.completedFuture(chat);
        }

        var muteAction = new MuteAction(true, mute.type() == ChatMute.Type.MUTED_FOR_TIMEFRAME ? mute.endTimeStamp() * 1000L : mute.endTimeStamp(), false);
        var syncAction = ActionValueSync.of(muteAction);
        var entry = PatchEntry.of(syncAction, Operation.SET, 2, chat.toJid().toString());
        var request = new PatchRequest(BinaryPatchType.REGULAR_HIGH, List.of(entry));
        return socketHandler.pushPatch(request).thenApplyAsync(ignored -> chat);
    }

    /**
     * Unmutes a chat
     *
     * @param chat the target chat
     * @return a CompletableFuture
     */
    public <T extends ContactJidProvider> CompletableFuture<T> unmute(@NonNull T chat) {
        if(store().clientType() == ClientType.MOBILE){
            // TODO: Send notification to companions
            store().findChatByJid(chat)
                    .ifPresent(entry -> entry.mute(ChatMute.notMuted()));
            return CompletableFuture.completedFuture(chat);
        }

        var muteAction = new MuteAction(false, null, false);
        var syncAction = ActionValueSync.of(muteAction);
        var entry = PatchEntry.of(syncAction, Operation.SET, 2, chat.toJid().toString());
        var request = new PatchRequest(BinaryPatchType.REGULAR_HIGH, List.of(entry));
        return socketHandler.pushPatch(request).thenApplyAsync(ignored -> chat);
    }

    /**
     * Blocks a contact
     *
     * @param chat the target chat
     * @return a CompletableFuture
     */
    public <T extends ContactJidProvider> CompletableFuture<T> block(@NonNull T chat) {
        var body = Node.of("item", Map.of("action", "block", "jid", chat.toJid()));
        return socketHandler.sendQuery("set", "blocklist", body).thenApplyAsync(ignored -> chat);
    }

    /**
     * Unblocks a contact
     *
     * @param chat the target chat
     * @return a CompletableFuture
     */
    public <T extends ContactJidProvider> CompletableFuture<T> unblock(@NonNull T chat) {
        var body = Node.of("item", Map.of("action", "unblock", "jid", chat.toJid()));
        return socketHandler.sendQuery("set", "blocklist", body).thenApplyAsync(ignored -> chat);
    }

    /**
     * Enables ephemeral messages in a chat, this means that messages will be automatically cancelled
     * in said chat after a week
     *
     * @param chat the target chat
     * @return a CompletableFuture
     */
    public <T extends ContactJidProvider> CompletableFuture<T> changeEphemeralTimer(@NonNull T chat, @NonNull ChatEphemeralTimer timer) {
        return switch (chat.toJid().server()) {
            case USER, WHATSAPP -> {
                var message = ProtocolMessage.builder()
                        .protocolType(ProtocolMessage.ProtocolMessageType.EPHEMERAL_SETTING)
                        .ephemeralExpiration(timer.period().toSeconds())
                        .build();
                yield sendMessage(chat, message).thenApplyAsync(ignored -> chat);
            }
            case GROUP -> {
                var body = timer == ChatEphemeralTimer.OFF ? Node.of("not_ephemeral") : Node.of("ephemeral", Map.of("expiration", timer.period()
                        .toSeconds()));
                yield socketHandler.sendQuery(chat.toJid(), "set", "w:g2", body).thenApplyAsync(ignored -> chat);
            }
            default ->
                    throw new IllegalArgumentException("Unexpected chat %s: ephemeral messages are only supported for conversations and groups".formatted(chat.toJid()));
        };
    }

    /**
     * Marks a message as played
     *
     * @param info the target message
     * @return a CompletableFuture
     */
    public CompletableFuture<MessageInfo> markPlayed(@NonNull MessageInfo info) {
        if (store().findPrivacySetting(PrivacySettingType.READ_RECEIPTS).value() != PrivacySettingValue.EVERYONE) {
            return CompletableFuture.completedFuture(info);
        }
        socketHandler.sendReceipt(info.chatJid(), info.senderJid(), List.of(info.id()), "played");
        return CompletableFuture.completedFuture(info.status(MessageStatus.PLAYED));
    }

    /**
     * Marks a chat as unread
     *
     * @param chat the target chat
     * @return a CompletableFuture
     */
    public <T extends ContactJidProvider> CompletableFuture<T> markUnread(@NonNull T chat) {
        return mark(chat, false);
    }

    /**
     * Pins a chat to the top. A maximum of three chats can be pinned to the top. This condition can
     * be checked using;.
     *
     * @param chat the target chat
     * @return a CompletableFuture
     */
    public <T extends ContactJidProvider> CompletableFuture<T> pin(@NonNull T chat) {
        return pin(chat, true);
    }

    /**
     * Unpins a chat from the top
     *
     * @param chat the target chat
     * @return a CompletableFuture
     */
    public <T extends ContactJidProvider> CompletableFuture<T> unpin(@NonNull T chat) {
        return pin(chat, false);
    }

    private <T extends ContactJidProvider> CompletableFuture<T> pin(T chat, boolean pin) {
        if(store().clientType() == ClientType.MOBILE){
            // TODO: Send notification to companions
            store().findChatByJid(chat)
                    .ifPresent(entry -> entry.pinnedTimestampSeconds(pin ? (int) Clock.nowSeconds() : 0));
            return CompletableFuture.completedFuture(chat);
        }

        var pinAction = new PinAction(pin);
        var syncAction = ActionValueSync.of(pinAction);
        var entry = PatchEntry.of(syncAction, Operation.SET, 5, chat.toJid().toString());
        var request = new PatchRequest(BinaryPatchType.REGULAR_LOW, List.of(entry));
        return socketHandler.pushPatch(request).thenApplyAsync(ignored -> chat);
    }

    /**
     * Stars a message
     *
     * @param info the target message
     * @return a CompletableFuture
     */
    public CompletableFuture<MessageInfo> star(@NonNull MessageInfo info) {
        return star(info, true);
    }

    private CompletableFuture<MessageInfo> star(MessageInfo info, boolean star) {
        if(store().clientType() == ClientType.MOBILE){
            // TODO: Send notification to companions
            info.starred(star);
            return CompletableFuture.completedFuture(info);
        }

        var starAction = new StarAction(star);
        var syncAction = ActionValueSync.of(starAction);
        var entry = PatchEntry.of(syncAction, Operation.SET, 3, info.chatJid()
                .toString(), info.id(), fromMeToFlag(info), participantToFlag(info));
        var request = new PatchRequest(BinaryPatchType.REGULAR_HIGH, List.of(entry));
        return socketHandler.pushPatch(request).thenApplyAsync(ignored -> info);
    }

    private String fromMeToFlag(MessageInfo info) {
        return booleanToInt(info.fromMe());
    }

    private String participantToFlag(MessageInfo info) {
        return info.chatJid().hasServer(Server.GROUP) && !info.fromMe() ? info.senderJid().toString() : "0";
    }

    private String booleanToInt(boolean keepStarredMessages) {
        return keepStarredMessages ? "1" : "0";
    }

    /**
     * Removes star from a message
     *
     * @param info the target message
     * @return a CompletableFuture
     */
    public CompletableFuture<MessageInfo> unstar(@NonNull MessageInfo info) {
        return star(info, false);
    }

    /**
     * Archives a chat. If said chat is pinned, it will be unpinned.
     *
     * @param chat the target chat
     * @return a CompletableFuture
     */
    public <T extends ContactJidProvider> CompletableFuture<T> archive(@NonNull T chat) {
        return archive(chat, true);
    }

    private <T extends ContactJidProvider> CompletableFuture<T> archive(T chat, boolean archive) {
        if(store().clientType() == ClientType.MOBILE){
            // TODO: Send notification to companions
            store().findChatByJid(chat)
                    .ifPresent(entry -> entry.archived(archive));
            return CompletableFuture.completedFuture(chat);
        }

        var range = createRange(chat, false);
        var archiveAction = new ArchiveChatAction(archive, range);
        var syncAction = ActionValueSync.of(archiveAction);
        var entry = PatchEntry.of(syncAction, Operation.SET, 3, chat.toJid().toString());
        var request = new PatchRequest(BinaryPatchType.REGULAR_LOW, List.of(entry));
        return socketHandler.pushPatch(request).thenApplyAsync(ignored -> chat);
    }

    /**
     * Unarchives a chat
     *
     * @param chat the target chat
     * @return a CompletableFuture
     */
    public <T extends ContactJidProvider> CompletableFuture<T> unarchive(@NonNull T chat) {
        return archive(chat, false);
    }

    /**
     * Deletes a message
     *
     * @param info     the non-null message to delete
     * @param everyone whether the message should be deleted for everyone or only for this client and
     *                 its companions
     * @return a CompletableFuture
     */
    public CompletableFuture<MessageInfo> delete(@NonNull MessageInfo info, boolean everyone) {
        if (everyone) {
            var message = ProtocolMessage.builder()
                    .protocolType(ProtocolMessage.ProtocolMessageType.REVOKE)
                    .key(info.key())
                    .build();
            var sender = info.chat().toJid().hasServer(Server.GROUP) ? store().jid() : null;
            var key = MessageKey.builder().chatJid(info.chatJid()).fromMe(true).senderJid(sender).build();
            var revokeInfo = MessageInfo.builder()
                    .senderJid(sender)
                    .key(key)
                    .message(MessageContainer.of(message))
                    .timestampSeconds(Clock.nowSeconds())
                    .build();
            var request = MessageSendRequest.builder()
                    .info(revokeInfo)
                    .additionalAttributes(Map.of("edit", info.chat().isGroup() && !info.fromMe() ? "8" : "7"))
                    .build();
            return socketHandler.sendMessage(request).thenApplyAsync(ignored -> info);
        }

        if(store().clientType() == ClientType.MOBILE){
            // TODO: Send notification to companions
            info.chat().removeMessage(info);
            return CompletableFuture.completedFuture(info);
        }

        var range = createRange(info.chatJid(), false);
        var deleteMessageAction = new DeleteMessageForMeAction(false, info.timestampSeconds());
        var syncAction = ActionValueSync.of(deleteMessageAction);
        var entry = PatchEntry.of(syncAction, Operation.SET, 3, info.chatJid()
                .toString(), info.id(), fromMeToFlag(info), participantToFlag(info));
        var request = new PatchRequest(BinaryPatchType.REGULAR_HIGH, List.of(entry));
        return socketHandler.pushPatch(request).thenApplyAsync(ignored -> info);
    }

    /**
     * Deletes a chat for this client and its companions using a modern version of Whatsapp Important:
     * this message doesn't seem to work always as of now
     *
     * @param chat the non-null chat to delete
     * @return a CompletableFuture
     */
    public <T extends ContactJidProvider> CompletableFuture<T> delete(@NonNull T chat) {
        if(store().clientType() == ClientType.MOBILE){
            // TODO: Send notification to companions
            store().removeChat(chat.toJid());
            return CompletableFuture.completedFuture(chat);
        }

        var range = createRange(chat.toJid(), false);
        var deleteChatAction = new DeleteChatAction(range);
        var syncAction = ActionValueSync.of(deleteChatAction);
        var entry = PatchEntry.of(syncAction, Operation.SET, 6, chat.toJid().toString(), "1");
        var request = new PatchRequest(BinaryPatchType.REGULAR_HIGH, List.of(entry));
        return socketHandler.pushPatch(request).thenApplyAsync(ignored -> chat);
    }

    /**
     * Clears the content of a chat for this client and its companions using a modern version of
     * Whatsapp Important: this message doesn't seem to work always as of now
     *
     * @param chat                the non-null chat to clear
     * @param keepStarredMessages whether starred messages in this chat should be kept
     * @return a CompletableFuture
     */
    public <T extends ContactJidProvider> CompletableFuture<T> clear(@NonNull T chat, boolean keepStarredMessages) {
        if(store().clientType() == ClientType.MOBILE){
            // TODO: Send notification to companions
            store().findChatByJid(chat.toJid())
                    .ifPresent(Chat::removeMessages);
            return CompletableFuture.completedFuture(chat);
        }

        var known = store().findChatByJid(chat);
        var range = createRange(chat.toJid(), true);
        var clearChatAction = new ClearChatAction(range);
        var syncAction = ActionValueSync.of(clearChatAction);
        var entry = PatchEntry.of(syncAction, Operation.SET, 6, chat.toJid().toString(), booleanToInt(keepStarredMessages), "0");
        var request = new PatchRequest(BinaryPatchType.REGULAR_HIGH, List.of(entry));
        return socketHandler.pushPatch(request).thenApplyAsync(ignored -> chat);
    }

    /**
     * Change the description of this business profile
     *
     * @param description the new description, can be null
     * @return a CompletableFuture
     */
    public CompletableFuture<String> changeBusinessDescription(String description) {
        return changeBusinessAttribute("description", description);
    }

    private CompletableFuture<String> changeBusinessAttribute(String key, String value) {
        return socketHandler.sendQuery("set", "w:biz", Node.of("business_profile", Map.of("v", "3", "mutation_type", "delta"), Node.of(key, Objects.requireNonNullElse(value, "").getBytes(StandardCharsets.UTF_8))))
                .thenAcceptAsync(result -> checkBusinessAttributeConflict(key, value, result))
                .thenApplyAsync(ignored -> value);
    }

    private void checkBusinessAttributeConflict(String key, String value, Node result) {
        var keyNode = result.findNode("profile").flatMap(entry -> entry.findNode(key));
        if (keyNode.isEmpty()) {
            return;
        }
        var actual = keyNode.get()
                .contentAsString()
                .orElseThrow(() -> new NoSuchElementException("Missing business %s response, something went wrong: %s".formatted(key, findErrorNode(result))));
        Validate.isTrue(value == null || value.equals(actual), "Cannot change business %s: conflict(expected %s, got %s)", key, value, actual);
    }

    /**
     * Change the address of this business profile
     *
     * @param address the new address, can be null
     * @return a CompletableFuture
     */
    public CompletableFuture<String> changeBusinessAddress(String address) {
        return changeBusinessAttribute("address", address);
    }

    /**
     * Change the email of this business profile
     *
     * @param email the new email, can be null
     * @return a CompletableFuture
     */
    public CompletableFuture<String> changeBusinessEmail(String email) {
        Validate.isTrue(email == null || isValidEmail(email), "Invalid email: %s", email);
        return changeBusinessAttribute("email", email);
    }

    private boolean isValidEmail(String email) {
        return Pattern.compile("^(.+)@(\\S+)$")
                .matcher(email)
                .matches();
    }

    /**
     * Change the categories of this business profile
     *
     * @param categories the new categories, can be null
     * @return a CompletableFuture
     */
    public CompletableFuture<List<BusinessCategory>> changeBusinessCategories(List<BusinessCategory> categories) {
        return socketHandler.sendQuery("set", "w:biz", Node.of("business_profile", Map.of("v", "3", "mutation_type", "delta"), Node.of("categories", createCategories(categories))))
                .thenApplyAsync(ignored -> categories);
    }

    private Collection<Node> createCategories(List<BusinessCategory> categories) {
        if (categories == null) {
            return List.of();
        }
        return categories.stream().map(entry -> Node.of("category", Map.of("id", entry.id()))).toList();
    }

    /**
     * Change the websites of this business profile
     *
     * @param websites the new websites, can be null
     * @return a CompletableFuture
     */
    public CompletableFuture<List<URI>> changeBusinessWebsites(List<URI> websites) {
        return socketHandler.sendQuery("set", "w:biz", Node.of("business_profile", Map.of("v", "3", "mutation_type", "delta"), createWebsites(websites)))
                .thenApplyAsync(ignored -> websites);
    }

    private static List<Node> createWebsites(List<URI> websites) {
        if (websites == null) {
            return List.of();
        }
        return websites.stream()
                .map(entry -> Node.of("website", entry.toString().getBytes(StandardCharsets.UTF_8)))
                .toList();
    }

    /**
     * Query the catalog of this business
     *
     * @return a CompletableFuture
     */
    public CompletableFuture<List<BusinessCatalogEntry>> queryBusinessCatalog() {
        return queryBusinessCatalog(10);
    }

    /**
     * Query the catalog of this business
     *
     * @param productsLimit the maximum number of products to query
     * @return a CompletableFuture
     */
    public CompletableFuture<List<BusinessCatalogEntry>> queryBusinessCatalog(int productsLimit) {
        return queryBusinessCatalog(store().jid().toWhatsappJid(), productsLimit);
    }

    /**
     * Query the catalog of a business
     *
     * @param contact       the business
     * @param productsLimit the maximum number of products to query
     * @return a CompletableFuture
     */
    public CompletableFuture<List<BusinessCatalogEntry>> queryBusinessCatalog(@NonNull ContactJidProvider contact, int productsLimit) {
        return socketHandler.sendQuery("get", "w:biz:catalog", Node.of("product_catalog", Map.of("jid", contact, "allow_shop_source", "true"), Node.of("limit", String.valueOf(productsLimit)
                        .getBytes(StandardCharsets.UTF_8)), Node.of("width", "100".getBytes(StandardCharsets.UTF_8)), Node.of("height", "100".getBytes(StandardCharsets.UTF_8))))
                .thenApplyAsync(this::parseCatalog);
    }

    private List<BusinessCatalogEntry> parseCatalog(Node result) {
        return Objects.requireNonNull(result, "Cannot query business catalog, missing response node")
                .findNode("product_catalog")
                .map(entry -> entry.findNodes("product"))
                .stream()
                .flatMap(Collection::stream)
                .map(BusinessCatalogEntry::of)
                .toList();
    }

    /**
     * Query the catalog of a business
     *
     * @param contact the business
     * @return a CompletableFuture
     */
    public CompletableFuture<List<BusinessCatalogEntry>> queryBusinessCatalog(@NonNull ContactJidProvider contact) {
        return queryBusinessCatalog(contact, 10);
    }

    /**
     * Query the collections of this business
     *
     * @return a CompletableFuture
     */
    public CompletableFuture<?> queryBusinessCollections() {
        return queryBusinessCollections(50);
    }

    /**
     * Query the collections of this business
     *
     * @param collectionsLimit the maximum number of collections to query
     * @return a CompletableFuture
     */
    public CompletableFuture<?> queryBusinessCollections(int collectionsLimit) {
        return queryBusinessCollections(store().jid().toWhatsappJid(), collectionsLimit);
    }

    /**
     * Query the collections of a business
     *
     * @param contact          the business
     * @param collectionsLimit the maximum number of collections to query
     * @return a CompletableFuture
     */
    public CompletableFuture<List<BusinessCollectionEntry>> queryBusinessCollections(@NonNull ContactJidProvider contact, int collectionsLimit) {
        return socketHandler.sendQuery("get", "w:biz:catalog", Map.of("smax_id", "35"), Node.of("collections", Map.of("biz_jid", contact), Node.of("collection_limit", String.valueOf(collectionsLimit)
                        .getBytes(StandardCharsets.UTF_8)), Node.of("item_limit", String.valueOf(collectionsLimit)
                        .getBytes(StandardCharsets.UTF_8)), Node.of("width", "100".getBytes(StandardCharsets.UTF_8)), Node.of("height", "100".getBytes(StandardCharsets.UTF_8))))
                .thenApplyAsync(this::parseCollections);
    }

    private List<BusinessCollectionEntry> parseCollections(Node result) {
        return Objects.requireNonNull(result, "Cannot query business collections, missing response node")
                .findNode("collections")
                .stream()
                .map(entry -> entry.findNodes("collection"))
                .flatMap(Collection::stream)
                .map(BusinessCollectionEntry::of)
                .toList();
    }

    /**
     * Query the collections of a business
     *
     * @param contact the business
     * @return a CompletableFuture
     */
    public CompletableFuture<?> queryBusinessCollections(@NonNull ContactJidProvider contact) {
        return queryBusinessCollections(contact, 50);
    }

    /**
     * Downloads a media from Whatsapp's servers. If the media is available, it will be returned
     * asynchronously. Otherwise, a retry request will be issued. If that also fails, an exception
     * will be thrown. The difference between this method and {@link MediaMessage#decodedMedia()} is
     * that this automatically attempts a retry request.
     *
     * @param info the non-null message info wrapping the media
     * @return a CompletableFuture
     */
    public CompletableFuture<byte[]> downloadMedia(@NonNull MessageInfo info) {
        Validate.isTrue(info.message()
                .category() == MessageCategory.MEDIA, "Expected media message, got: %s(%s)", info.message()
                .category(), info.message().type());
        return downloadMedia(info, false);
    }

    private CompletableFuture<byte[]> downloadMedia(MessageInfo info, boolean retried) {
        var mediaMessage = (MediaMessage) info.message().content();
        var result = mediaMessage.decodedMedia();
        if (result.isEmpty()) {
            Validate.isTrue(!retried, "Media reupload failed");
            return requireMediaReupload(info).thenComposeAsync(entry -> downloadMedia(entry, true));
        }
        return CompletableFuture.completedFuture(result.get());
    }

    /**
     * Asks Whatsapp for a media reupload for a specific media
     *
     * @param info the non-null message info wrapping the media
     * @return a CompletableFuture
     */
    public CompletableFuture<MessageInfo> requireMediaReupload(@NonNull MessageInfo info) {
        Validate.isTrue(info.message()
                .category() == MessageCategory.MEDIA, "Expected media message, got: %s(%s)", info.message()
                .category(), info.message().type());
        var mediaMessage = (MediaMessage) info.message().content();
        var retryKey = Hkdf.extractAndExpand(mediaMessage.mediaKey(), "WhatsApp Media Retry Notification".getBytes(StandardCharsets.UTF_8), 32);
        var retryIv = BytesHelper.random(12);
        var retryIdData = info.key().id().getBytes(StandardCharsets.UTF_8);
        var receipt = Protobuf.writeMessage(new ServerErrorReceipt(info.id()));
        var ciphertext = AesGcm.encrypt(retryIv, receipt, retryKey, retryIdData);
        var rmrAttributes = Attributes.of()
                .put("jid", info.chatJid())
                .put("from_me", String.valueOf(info.fromMe()))
                .put("participant", info.senderJid(), () -> !Objects.equals(info.chatJid(), info.senderJid()))
                .toMap();
        var node = Node.of("receipt", Map.of("id", info.key().id(), "to", store()
                .jid()
                .toWhatsappJid(), "type", "server-error"), Node.of("encrypt", Node.of("enc_p", ciphertext), Node.of("enc_iv", retryIv)), Node.of("rmr", rmrAttributes));
        return socketHandler.send(node, result -> result.hasDescription("notification"))
                .thenApplyAsync(result -> parseMediaReupload(info, mediaMessage, retryKey, retryIdData, result));
    }

    private MessageInfo parseMediaReupload(MessageInfo info, MediaMessage mediaMessage, byte[] retryKey, byte[] retryIdData, Node node) {
        Validate.isTrue(!node.hasNode("error"), "Erroneous response from media reupload: %s", node.attributes()
                .getInt("code"));
        var encryptNode = node.findNode("encrypt")
                .orElseThrow(() -> new NoSuchElementException("Missing encrypt node in media reupload"));
        var mediaPayload = encryptNode.findNode("enc_p")
                .flatMap(Node::contentAsBytes)
                .orElseThrow(() -> new NoSuchElementException("Missing encrypted payload node in media reupload"));
        var mediaIv = encryptNode.findNode("enc_iv")
                .flatMap(Node::contentAsBytes)
                .orElseThrow(() -> new NoSuchElementException("Missing encrypted iv node in media reupload"));
        var mediaRetryNotificationData = AesGcm.decrypt(mediaIv, mediaPayload, retryKey, retryIdData);
        var mediaRetryNotification = Protobuf.readMessage(mediaRetryNotificationData, MediaRetryNotification.class);
        Validate.isTrue(mediaRetryNotification.directPath() != null, "Media retry upload failed: %s", mediaRetryNotification);
        mediaMessage.mediaUrl(Medias.createMediaUrl(mediaRetryNotification.directPath()));
        mediaMessage.mediaDirectPath(mediaRetryNotification.directPath());
        return info;
    }

    /**
     * Sends a custom node to Whatsapp
     *
     * @param node the non-null node to send
     * @return the response from Whatsapp
     */
    public CompletableFuture<Node> sendNode(@NonNull Node node) {
        return socketHandler.send(node);
    }

    /**
     * Creates a new community
     *
     * @param subject the non-null name of the new community
     * @param body    the nullable description of the new community
     * @return a CompletableFuture
     */
    public CompletableFuture<GroupMetadata> createCommunity(@NonNull String subject, String body) {
        var descriptionId = HexFormat.of().formatHex(BytesHelper.random(12));
        var entry = Node.of("create", Map.of("subject", subject),
                Node.of("description", Map.of("id", descriptionId),
                        Node.of("body", Objects.requireNonNullElse(body, "").getBytes(StandardCharsets.UTF_8))),
                Node.of("parent", Map.of("default_membership_approval_mode", "request_required")),
                Node.of("allow_non_admin_sub_group_creation"));
        return socketHandler.sendQuery(Server.GROUP.toJid(), "set", "w:g2", entry).thenApplyAsync(node -> {
            node.assertNode("group", () -> "Missing community response, something went wrong: " + findErrorNode(node));
            return GroupMetadata.of(node);
        });
    }

    /**
     * Changes a community setting
     *
     * @param community the non-null community affected by this change
     * @param setting the non-null setting
     * @param policy the non-null policy
     * @return a future
     */
    public <T extends ContactJidProvider> CompletableFuture<T> changeCommunitySetting(@NonNull T community, @NonNull CommunitySetting setting, @NonNull SettingPolicy policy) {
        var tag = policy == SettingPolicy.ANYONE ? setting.on() : setting.off();
        return socketHandler.sendQuery(Server.GROUP.toJid(), "set", "w:g2", Node.of(tag))
                .thenApplyAsync(ignored -> community);
    }


    /**
     * Unlinks all the companions of this device
     *
     * @return a future
     */
    public CompletableFuture<Whatsapp> unlinkDevices(){
        return socketHandler.sendQuery("set", "md", Node.of("remove-companion-device", Map.of("all", true, "reason", "user_initiated")))
                .thenRun(() -> store().removeLinkedCompanions())
                .thenApply(ignored -> this);
    }

    /**
     * Unlinks a specific companion
     *
     * @param companion the non-null companion to unlink
     * @return a future
     */
    public CompletableFuture<Whatsapp> unlinkDevice(@NonNull ContactJid companion){
        Validate.isTrue(companion.hasAgent(), "Expected companion, got jid without agent: %s", companion);
        return socketHandler.sendQuery("set", "md", Node.of("remove-companion-device", Map.of("jid", companion, "reason", "user_initiated")))
                .thenRun(() -> store().removeLinkedCompanion(companion))
                .thenApply(ignored -> this);
    }

    /**
     * Links a companion to this device
     *
     * @param qrCode the non-null qr code as an image
     * @return a future
     */
    public CompletableFuture<CompanionLinkResult> linkDevice(byte @NonNull [] qrCode){
        try {
            var inputStream = new ByteArrayInputStream(qrCode);
            var luminanceSource = new BufferedImageLuminanceSource(ImageIO.read(inputStream));
            var hybridBinarizer = new HybridBinarizer(luminanceSource);
            var binaryBitmap = new BinaryBitmap(hybridBinarizer);
            var reader = new QRCodeReader();
            var result = reader.decode(binaryBitmap);
            return linkDevice(result.getText());
        }catch (IOException | NotFoundException | ChecksumException | FormatException exception){
            throw new IllegalArgumentException("Cannot read qr code", exception);
        }
    }

    /**
     * Links a companion to this device
     * Mobile api only
     *
     * @param qrCodeData the non-null qr code as a String
     * @return a future
     */
    public CompletableFuture<CompanionLinkResult> linkDevice(@NonNull String qrCodeData) {
        Validate.isTrue(store().clientType() == ClientType.MOBILE, "Device linking is only available for the mobile api");
        var maxDevices = getMaxLinkedDevices();
        if (store().linkedDevices().size() > maxDevices) {
            return CompletableFuture.completedFuture(CompanionLinkResult.MAX_DEVICES_ERROR);
        }

        var qrCodeParts = qrCodeData.split(",");
        Validate.isTrue(qrCodeParts.length >= 4, "Expected qr code to be made up of at least four parts");
        var ref = qrCodeParts[0];
        var publicKey = Base64.getDecoder().decode(qrCodeParts[1]);
        var advIdentity = Base64.getDecoder().decode(qrCodeParts[2]);
        var identityKey = Base64.getDecoder().decode(qrCodeParts[3]);
        return socketHandler.sendQuery("set", "w:sync:app:state", Node.of("delete_all_data"))
                .thenComposeAsync(ignored -> linkDevice(advIdentity, identityKey, ref, publicKey));
    }

    private CompletableFuture<CompanionLinkResult> linkDevice(byte[] advIdentity, byte[] identityKey, String ref, byte[] publicKey) {
        var deviceIdentity = DeviceIdentity.builder()
                .rawId(KeyHelper.agent())
                .keyIndex(store().linkedDevices().size() + 1)
                .timestamp(Clock.nowSeconds())
                .build();
        var deviceIdentityBytes = Protobuf.writeMessage(deviceIdentity);
        var accountSignatureMessage = BytesHelper.concat(
                Spec.Whatsapp.ACCOUNT_SIGNATURE_HEADER,
                deviceIdentityBytes,
                advIdentity
        );
        var accountSignature = Curve25519.sign(keys().identityKeyPair().privateKey(), accountSignatureMessage, true);
        var signedDeviceIdentity = SignedDeviceIdentity.builder()
                .accountSignature(accountSignature)
                .accountSignatureKey(keys().identityKeyPair().publicKey())
                .details(deviceIdentityBytes)
                .build();
        var signedDeviceIdentityBytes = Protobuf.writeMessage(signedDeviceIdentity);
        var deviceIdentityHmac = SignedDeviceIdentityHMAC.builder()
                .hmac(Hmac.calculateSha256(signedDeviceIdentityBytes, identityKey))
                .details(signedDeviceIdentityBytes)
                .build();
        var knownDevices = store().linkedDevices()
                .stream()
                .map(ContactJid::device)
                .toList();
        var keyIndexList = KeyIndexList.builder()
                .rawId(deviceIdentity.rawId())
                .timestamp(deviceIdentity.timestamp())
                // .validIndexes(knownDevices)
                .build();
        var keyIndexListBytes = Protobuf.writeMessage(keyIndexList);
        var deviceSignatureMessage = BytesHelper.concat(Spec.Whatsapp.DEVICE_MOBILE_SIGNATURE_HEADER, keyIndexListBytes);
        var keyAccountSignature = Curve25519.sign(keys().identityKeyPair().privateKey(), deviceSignatureMessage, true);
        var signedKeyIndexList = SignedKeyIndexList.builder()
                .accountSignature(keyAccountSignature)
                .details(keyIndexListBytes)
                .build();
        return socketHandler.sendQuery("set", "md", Node.of("pair-device",
                        Node.of("ref", ref),
                        Node.of("pub-key", publicKey),
                        Node.of("device-identity", Protobuf.writeMessage(deviceIdentityHmac)),
                        Node.of("key-index-list", Map.of("ts", deviceIdentity.timestamp()), Protobuf.writeMessage(signedKeyIndexList))))
                .thenComposeAsync(result -> handleCompanionPairing(result, deviceIdentity.keyIndex()));
    }

    private int getMaxLinkedDevices() {
        var maxDevices = socketHandler.store().properties().get("linked_device_max_count");
        if(maxDevices == null){
            return Spec.Whatsapp.MAX_COMPANIONS;
        }

        try {
            return Integer.parseInt(maxDevices);
        }catch (NumberFormatException exception){
            return Spec.Whatsapp.MAX_COMPANIONS;
        }
    }

    private CompletableFuture<CompanionLinkResult> handleCompanionPairing(Node result, int keyId) {
        if(result.attributes().hasValue("type", "error")){
            var error = result.findNode("error")
                    .filter(entry -> entry.attributes().hasValue("text", "resource-limit"))
                    .map(entry -> CompanionLinkResult.MAX_DEVICES_ERROR)
                    .orElse(CompanionLinkResult.RETRY_ERROR);
            return CompletableFuture.completedFuture(error);
        }

        var device = result.findNode("device")
                .flatMap(entry -> entry.attributes().getJid("jid"))
                .orElse(null);
        if(device == null){
            return CompletableFuture.completedFuture(CompanionLinkResult.RETRY_ERROR);
        }

        return awaitCompanionRegistration(device)
                .thenComposeAsync(ignored -> socketHandler.sendQuery("get", "encrypt", Node.of("key", Node.of("user", Map.of("jid", device)))))
                .thenComposeAsync(encryptResult -> handleCompanionEncrypt(encryptResult, device, keyId));
    }

    private CompletableFuture<Void> awaitCompanionRegistration(ContactJid device) {
        var future = new CompletableFuture<Void>();
        OnLinkedDevices listener = data -> {
            if(data.contains(device)) {
                future.complete(null);
            }
        };
        addLinkedDevicesListener(listener);
        return future.orTimeout(Spec.Whatsapp.COMPANION_PAIRING_TIMEOUT, TimeUnit.SECONDS)
                .exceptionally(ignored -> null)
                .thenRun(() -> removeListener(listener));
    }

    private CompletableFuture<CompanionLinkResult> handleCompanionEncrypt(Node result, ContactJid companion, int keyId) {
        store().addLinkedDevice(companion, keyId);
        socketHandler.parseSessions(result);
        return sendInitialSecurityMessage(companion)
                .thenComposeAsync(ignore -> sendAppStateKeysMessage(companion))
                .thenComposeAsync(ignore -> sendInitialNullMessage(companion))
                .thenComposeAsync(ignore -> sendInitialStatusMessage(companion))
                .thenComposeAsync(ignore -> sendPushNamesMessage(companion))
                .thenComposeAsync(ignore -> sendInitialBootstrapMessage(companion))
                .thenComposeAsync(ignore -> sendRecentMessage(companion))
                .thenComposeAsync(ignored -> syncCompanionState(companion))
                .thenApplyAsync(ignored -> CompanionLinkResult.SUCCESS);
    }

    private CompletableFuture<Void> syncCompanionState(ContactJid companion) {
        var criticalUnblockLowRequest = createCriticalUnblockLowRequest();
        var criticalBlockRequest = createCriticalBlockRequest();
        return socketHandler.pushPatches(companion, List.of(criticalUnblockLowRequest, criticalBlockRequest)).thenComposeAsync(ignored -> {
            var regularLowRequests = createRegularLowRequests();
            var regularRequests = createRegularRequests();
            return socketHandler.pushPatches(companion, List.of(regularLowRequests, regularRequests));
        });
    }

    private PatchRequest createRegularRequests(){
        return new PatchRequest(BinaryPatchType.REGULAR, List.of());
    }

    private PatchRequest createRegularLowRequests() {
        var timeFormatEntry = createTimeFormatEntry();
        var primaryVersion = new PrimaryVersionAction(store().version().toString());
        var sessionVersionEntry = createPrimaryVersionEntry(primaryVersion, "session@s.whatsapp.net");
        var keepVersionEntry = createPrimaryVersionEntry(primaryVersion, "current@s.whatsapp.net");
        var nuxEntry = createNuxRequest();
        var androidEntry = createAndroidEntry();
        var entries = Stream.of(timeFormatEntry, sessionVersionEntry, keepVersionEntry, nuxEntry, androidEntry)
                .filter(Objects::nonNull)
                .toList();
        // TODO: Archive chat actions, StickerAction
        return new PatchRequest(BinaryPatchType.REGULAR_LOW, entries);
    }

    private PatchRequest createCriticalBlockRequest() {
        var localeEntry = createLocaleEntry();
        var pushNameEntry = createPushNameEntry();
        return new PatchRequest(BinaryPatchType.CRITICAL_BLOCK, List.of(localeEntry, pushNameEntry));
    }

    private PatchRequest createCriticalUnblockLowRequest() {
        var criticalUnblockLow = createContactEntries();
        return new PatchRequest(BinaryPatchType.CRITICAL_UNBLOCK_LOW, criticalUnblockLow);
    }

    private List<PatchEntry> createContactEntries() {
        return store().contacts()
                .stream()
                .filter(entry -> entry.shortName() != null || entry.fullName() != null)
                .map(this::createContactRequestEntry)
                .collect(Collectors.toList());
    }

    private PatchEntry createPushNameEntry() {
        var pushNameSetting = new PushNameSetting(store().name());
        return PatchEntry.of(ActionValueSync.of(pushNameSetting), Operation.SET, 1);
    }

    private PatchEntry createLocaleEntry() {
        var localeSetting = new LocaleSetting(store().locale());
        return PatchEntry.of(ActionValueSync.of(localeSetting), Operation.SET, 3);
    }

    private PatchEntry createAndroidEntry() {
        var osType = store().device().osType();
        if (osType != UserAgentPlatform.ANDROID && osType != UserAgentPlatform.SMB_ANDROID) {
            return null;
        }

        var action = new AndroidUnsupportedActions(true);
        return PatchEntry.of(ActionValueSync.of(action), Operation.SET);
    }

    private PatchEntry createNuxRequest() {
        var timeFormatAction = new NuxAction(true);
        var timeFormatSync = ActionValueSync.of(timeFormatAction);
        return PatchEntry.of(timeFormatSync, Operation.SET, 7, "keep@s.whatsapp.net");
    }

    private PatchEntry createPrimaryVersionEntry(PrimaryVersionAction primaryVersion, String to) {
        var timeFormatSync = ActionValueSync.of(primaryVersion);
        return PatchEntry.of(timeFormatSync, Operation.SET, 7, to);
    }

    private PatchEntry createTimeFormatEntry() {
        var timeFormatAction = new TimeFormatAction(store().twentyFourHourFormat());
        var timeFormatSync = ActionValueSync.of(timeFormatAction);
        return PatchEntry.of(timeFormatSync, Operation.SET);
    }

    private PatchEntry createContactRequestEntry(Contact contact) {
        var action = new ContactAction(null, contact.shortName(), contact.fullName());
        var sync = ActionValueSync.of(action);
        return PatchEntry.of(sync, Operation.SET, 2, contact.jid().toString());
    }

    private CompletableFuture<Void> sendRecentMessage(ContactJid jid) {
        var pushNames = HistorySync.builder()
                .conversations(List.of())
                .syncType(HistorySync.Type.RECENT)
                .build();
        return sendHistoryProtocolMessage(jid, pushNames, Type.PUSH_NAME);
    }

    private CompletableFuture<Void> sendPushNamesMessage(ContactJid jid) {
        var pushNamesData = store()
                .contacts()
                .stream()
                .map(entry -> entry.chosenName() == null ? null : new PushName(entry.jid().toString(), entry.chosenName()))
                .filter(Objects::nonNull)
                .toList();
        var pushNames = HistorySync.builder()
                .pushNames(pushNamesData)
                .syncType(HistorySync.Type.PUSH_NAME)
                .build();
        return sendHistoryProtocolMessage(jid, pushNames, Type.PUSH_NAME);
    }

    private CompletableFuture<Void> sendInitialStatusMessage(ContactJid jid) {
        var initialStatus = HistorySync.builder()
                .statusV3Messages(new ArrayList<>(store().status()))
                .syncType(HistorySync.Type.INITIAL_STATUS_V3)
                .build();
        return sendHistoryProtocolMessage(jid, initialStatus, Type.INITIAL_STATUS_V3);
    }

    private CompletableFuture<Void> sendInitialBootstrapMessage(ContactJid jid) {
        var chats = store().chats()
                .stream()
                .toList();
        var initialBootstrap = HistorySync.builder()
                .conversations(chats)
                .syncType(HistorySync.Type.INITIAL_BOOTSTRAP)
                .build();
        return sendHistoryProtocolMessage(jid, initialBootstrap, Type.INITIAL_BOOTSTRAP);
    }

    private CompletableFuture<Void> sendInitialNullMessage(ContactJid jid) {
        var pastParticipants = store().chats()
                .stream()
                .map(this::getPastParticipants)
                .filter(Objects::nonNull)
                .toList();
        var initialBootstrap = HistorySync.builder()
                .syncType(HistorySync.Type.NON_BLOCKING_DATA)
                .pastParticipants(pastParticipants)
                .build();
        return sendHistoryProtocolMessage(jid, initialBootstrap, null);
    }

    private PastParticipants getPastParticipants(Chat chat) {
        if (chat.pastParticipants().isEmpty()) {
            return null;
        }

        return PastParticipants.builder()
                .groupJid(chat.jid())
                .pastParticipants(new ArrayList<>(chat.pastParticipants()))
                .build();
    }

    private CompletableFuture<Void> sendAppStateKeysMessage(ContactJid companion) {
        var preKeys = IntStream.range(0, 10)
                .mapToObj(index -> createAppKey(companion, index))
                .toList();
        keys().addAppKeys(companion, preKeys);
        var appStateSyncKeyShare = AppStateSyncKeyShare.builder()
                .keys(preKeys)
                .build();
        var result = ProtocolMessage.builder()
                .protocolType(ProtocolMessageType.APP_STATE_SYNC_KEY_SHARE)
                .appStateSyncKeyShare(appStateSyncKeyShare)
                .build();
        return socketHandler.sendPeerMessage(companion, result);
    }

    private AppStateSyncKey createAppKey(ContactJid jid, int index) {
        return AppStateSyncKey.builder()
                .keyId(new AppStateSyncKeyId(KeyHelper.appKeyId()))
                .keyData(createAppKeyData(jid, index))
                .build();
    }

    private AppStateSyncKeyData createAppKeyData(ContactJid jid, int index) {
        return AppStateSyncKeyData.builder()
                .keyData(SignalKeyPair.random().publicKey())
                .fingerprint(createAppKeyFingerprint(jid, index))
                .timestamp(Clock.nowMilliseconds())
                .build();
    }

    // FIXME: ModernProtobuf bug with UINT32 packed values
    private AppStateSyncKeyFingerprint createAppKeyFingerprint(ContactJid jid, int index) {
        return AppStateSyncKeyFingerprint.builder()
                .rawId(KeyHelper.senderKeyId())
                .currentIndex(index)
                // .deviceIndexes(new ArrayList<>(store().linkedDevicesKeys().values()))
                .build();
    }

    private CompletableFuture<Void> sendInitialSecurityMessage(ContactJid jid) {
        var protocolMessage = ProtocolMessage.builder()
                .protocolType(ProtocolMessageType.INITIAL_SECURITY_NOTIFICATION_SETTING_SYNC)
                .initialSecurityNotificationSettingSync(new InitialSecurityNotificationSettingSync(true))
                .build();
        return socketHandler.sendPeerMessage(jid, protocolMessage);
    }

    private CompletableFuture<Void> sendHistoryProtocolMessage(ContactJid jid, HistorySync historySync, HistorySyncNotification.Type type) {
        var syncBytes = Protobuf.writeMessage(historySync);
        return Medias.upload(syncBytes, AttachmentType.HISTORY_SYNC, store().mediaConnection())
                .thenApplyAsync(upload -> createHistoryProtocolMessage(upload, type))
                .thenComposeAsync(result -> socketHandler.sendPeerMessage(jid, result));
    }

    private ProtocolMessage createHistoryProtocolMessage(MediaFile upload, HistorySyncNotification.Type type) {
        var notification = HistorySyncNotification.builder()
                .mediaSha256(upload.fileSha256())
                .mediaEncryptedSha256(upload.fileEncSha256())
                .mediaKey(upload.mediaKey())
                .mediaDirectPath(upload.directPath())
                .mediaSize(upload.fileLength())
                .syncType(type)
                .build();
        return ProtocolMessage.builder()
                .protocolType(ProtocolMessageType.HISTORY_SYNC_NOTIFICATION)
                .historySyncNotification(notification)
                .build();
    }

    /**
     * Gets the verified name certificate
     *
     * @return a future
     */
    public CompletableFuture<Optional<BusinessVerifiedNameCertificate>> queryBusinessCertificate(@NonNull ContactJidProvider provider) {
        return socketHandler.sendQuery("get", "w:biz", Node.of("verified_name", Map.of("jid", provider.toJid())))
                .thenApplyAsync(this::parseCertificate);
    }

    private Optional<BusinessVerifiedNameCertificate> parseCertificate(Node result) {
        return result.findNode("verified_name")
                .flatMap(Node::contentAsBytes)
                .map(data -> Protobuf.readMessage(data, BusinessVerifiedNameCertificate.class));
    }

    /**
     * Enables two-factor authentication
     * Mobile API only
     *
     * @param code the six digits non-null numeric code
     * @return a future
     */
    public CompletableFuture<?> enable2fa(@NonNull String code) {
        return set2fa(code, null);
    }

    /**
     * Enables two-factor authentication
     * Mobile API only
     *
     * @param code  the six digits non-null numeric code
     * @param email the nullable recovery email
     * @return a future
     */
    public CompletableFuture<Boolean> enable2fa(@NonNull String code, String email) {
        return set2fa(code, email);
    }

    /**
     * Disables two-factor authentication
     * Mobile API only
     *
     * @return a future
     */
    public CompletableFuture<Boolean> disable2fa() {
        return set2fa(null, null);
    }

    private CompletableFuture<Boolean> set2fa(String code, String email) {
        Validate.isTrue(store().clientType() == ClientType.MOBILE, "2FA is only available for the mobile api");
        Validate.isTrue(code == null || (code.matches("^[0-9]*$") && code.length() == 6),
                "Invalid 2fa code: expected a numeric six digits string");
        Validate.isTrue(email == null || isValidEmail(email),
                "Invalid email: %s", email);
        var body = new ArrayList<Node>();
        body.add(Node.of("code", Objects.requireNonNullElse(code, "").getBytes(StandardCharsets.UTF_8)));
        if(code != null && email != null){
            body.add(Node.of("email", email.getBytes(StandardCharsets.UTF_8)));
        }
        return socketHandler.sendQuery("set", "urn:xmpp:whatsapp:account", Node.of("2fa", body))
                .thenApplyAsync(result -> !result.hasNode("error"));
    }

    /**
     * Starts a call with a contact
     * Mobile API only
     *
     * @param contact the non-null contact
     * @return a future
     */
    public CompletableFuture<Call> startCall(@NonNull ContactJidProvider contact) {
        Validate.isTrue(store().clientType() == ClientType.MOBILE, "Calling is only available for the mobile api");
        return socketHandler.querySessions(contact.toJid())
                .thenComposeAsync(ignored -> sendCallMessage(contact));
    }

    private CompletableFuture<Call> sendCallMessage(ContactJidProvider provider) {
        var callId = MessageKey.randomId();
        var audioStream = Node.of("audio", Map.of("rate", 8000, "enc", "opus"));
        var audioStreamTwo = Node.of("audio", Map.of("rate", 16000, "enc", "opus"));
        var net = Node.of("net", Map.of("medium", 3));
        var encopt = Node.of("encopt", Map.of("keygen", 2));
        var enc = createCallNode(provider);
        var capability = Node.of("capability", Map.of("ver", 1), URLDecoder.decode("%01%04%f7%09%c4:", StandardCharsets.UTF_8));
        var callCreator = "%s.%s:%s@s.whatsapp.net".formatted(store().jid().user(), store().jid().device(), store().jid().device());
        var offer = Node.of("offer",
                Map.of("call-creator", callCreator, "call-id", callId, "device_class", 2016),
                audioStream, audioStreamTwo, net, enc, capability, encopt);
        return socketHandler.send(Node.of("call", Map.of("to", provider.toJid()), offer))
                .thenApply(result -> onCallSent(provider, callId, result));
    }

    private Call onCallSent(ContactJidProvider provider, String callId, Node result) {
        var call = new Call(provider.toJid(), store().jid(), callId, ZonedDateTime.now(), false, CallStatus.RINGING, false);
        store().addCall(call);
        socketHandler.onCall(call);
        return call;
    }

    private Node createCallNode(ContactJidProvider provider) {
        var call = CallMessage.builder()
                .key(SignalKeyPair.random().publicKey())
                .build();
        var message = MessageContainer.of(call);
        socketHandler.querySessions(provider.toJid());
        var cipher = new SessionCipher(provider.toJid().toSignalAddress(), keys());
        var encodedMessage = BytesHelper.messageToBytes(message);
        var cipheredMessage = cipher.encrypt(encodedMessage);
        return Node.of("enc",
                Map.of("v", 2, "type", cipheredMessage.type(), "count", 0), cipheredMessage.message());
    }


    /**
     * Rejects an incoming call or stops an active call
     * Mobile API only
     *
     * @param callId the non-null id of the call to reject
     * @return a future
     */
    public CompletableFuture<Boolean> stopCall(@NonNull String callId) {
        Validate.isTrue(store().clientType() == ClientType.MOBILE, "Calling is only available for the mobile api");
        return store().findCallById(callId)
                .map(this::stopCall)
                .orElseGet(() -> CompletableFuture.completedFuture(false));
    }

    /**
     * Rejects an incoming call or stops an active call
     * Mobile API only
     *
     * @param call the non-null call to reject
     * @return a future
     */
    public CompletableFuture<Boolean> stopCall(@NonNull Call call) {
        Validate.isTrue(store().clientType() == ClientType.MOBILE, "Calling is only available for the mobile api");
        var callCreator = "%s.%s:%s@s.whatsapp.net".formatted(call.caller().user(), call.caller().device(), call.caller().device());
        if(Objects.equals(call.caller().user(), store().jid().user())) {
            var rejectNode = Node.of("terminate", Map.of("reason", "timeout", "call-id", call.id(), "call-creator", callCreator));
            var body = Node.of("call", Map.of("to", call.chat()), rejectNode);
            return socketHandler.send(body)
                    .thenApplyAsync(result -> !result.hasNode("error"));
        }

        var rejectNode = Node.of("reject", Map.of("call-id", call.id(), "call-creator", callCreator, "count", 0));
        var body = Node.of("call", Map.of("from", socketHandler.store().jid(), "to", call.caller()), rejectNode);
        return socketHandler.send(body)
                .thenApplyAsync(result -> !result.hasNode("error"));
    }

    /**
     * Registers a listener
     *
     * @param listener the listener to register
     * @return the same instance
     */
    public Whatsapp addListener(Listener listener) {
        store().addListener(listener);
        return this;
    }

    /**
     * Unregisters a listener
     *
     * @param listener the listener to unregister
     * @return the same instance
     */
    public Whatsapp removeListener(Listener listener) {
        store().removeListener(listener);
        return this;
    }

    /**
     * Registers an action listener
     *
     * @param onAction the listener to register
     * @return the same instance
     */
    public Whatsapp addActionListener(OnAction onAction) {
        return addListener(onAction);
    }

    /**
     * Registers a chat recent messages listener
     *
     * @param onChatRecentMessages the listener to register
     * @return the same instance
     */
    public Whatsapp addChatMessagesSyncListener(OnChatMessagesSync onChatRecentMessages) {
        return addListener(onChatRecentMessages);
    }

    /**
     * Registers a chats listener
     *
     * @param onChats the listener to register
     * @return the same instance
     */
    public Whatsapp addChatsListener(OnChats onChats) {
        return addListener(onChats);
    }

    /**
     * Registers a contact presence listener
     *
     * @param onContactPresence the listener to register
     * @return the same instance
     */
    public Whatsapp addContactPresenceListener(OnContactPresence onContactPresence) {
        return addListener(onContactPresence);
    }

    /**
     * Registers a contacts listener
     *
     * @param onContacts the listener to register
     * @return the same instance
     */
    public Whatsapp addContactsListener(OnContacts onContacts) {
        return addListener(onContacts);
    }

    /**
     * Registers a message status listener
     *
     * @param onConversationMessageStatus the listener to register
     * @return the same instance
     */
    public Whatsapp addConversationMessageStatusListener(OnConversationMessageStatus onConversationMessageStatus) {
        return addListener(onConversationMessageStatus);
    }

    /**
     * Registers a message status listener
     *
     * @param onAnyMessageStatus the listener to register
     * @return the same instance
     */
    public Whatsapp addAnyMessageStatusListener(OnAnyMessageStatus onAnyMessageStatus) {
        return addListener(onAnyMessageStatus);
    }

    /**
     * Registers a disconnected listener
     *
     * @param onDisconnected the listener to register
     * @return the same instance
     */
    public Whatsapp addDisconnectedListener(OnDisconnected onDisconnected) {
        return addListener(onDisconnected);
    }

    /**
     * Registers a features listener
     *
     * @param onFeatures the listener to register
     * @return the same instance
     */
    public Whatsapp addFeaturesListener(OnFeatures onFeatures) {
        return addListener(onFeatures);
    }

    /**
     * Registers a logged in listener
     *
     * @param onLoggedIn the listener to register
     * @return the same instance
     */
    public Whatsapp addLoggedInListener(OnLoggedIn onLoggedIn) {
        return addListener(onLoggedIn);
    }

    /**
     * Registers a message deleted listener
     *
     * @param onMessageDeleted the listener to register
     * @return the same instance
     */
    public Whatsapp addMessageDeletedListener(OnMessageDeleted onMessageDeleted) {
        return addListener(onMessageDeleted);
    }

    /**
     * Registers a metadata listener
     *
     * @param onMetadata the listener to register
     * @return the same instance
     */
    public Whatsapp addMetadataListener(OnMetadata onMetadata) {
        return addListener(onMetadata);
    }

    /**
     * Registers a new contact listener
     *
     * @param onNewContact the listener to register
     * @return the same instance
     */
    public Whatsapp addNewContactListener(OnNewContact onNewContact) {
        return addListener(onNewContact);
    }

    /**
     * Registers a new message listener
     *
     * @param onNewMessage the listener to register
     * @return the same instance
     */
    public Whatsapp addNewMessageListener(OnNewMessage onNewMessage) {
        return addListener(onNewMessage);
    }

    /**
     * Registers a new message listener
     *
     * @param onNewMessage the listener to register
     * @return the same instance
     */
    public Whatsapp addNewMessageListener(OnNewMarkedMessage onNewMessage) {
        return addListener(onNewMessage);
    }

    /**
     * Registers a new status listener
     *
     * @param onNewMediaStatus the listener to register
     * @return the same instance
     */
    public Whatsapp addNewStatusListener(OnNewMediaStatus onNewMediaStatus) {
        return addListener(onNewMediaStatus);
    }

    /**
     * Registers a received node listener
     *
     * @param onNodeReceived the listener to register
     * @return the same instance
     */
    public Whatsapp addNodeReceivedListener(OnNodeReceived onNodeReceived) {
        return addListener(onNodeReceived);
    }

    /**
     * Registers a sent node listener
     *
     * @param onNodeSent the listener to register
     * @return the same instance
     */
    public Whatsapp addNodeSentListener(OnNodeSent onNodeSent) {
        return addListener(onNodeSent);
    }

    /**
     * Registers a setting listener
     *
     * @param onSetting the listener to register
     * @return the same instance
     */
    public Whatsapp addSettingListener(OnSetting onSetting) {
        return addListener(onSetting);
    }

    /**
     * Registers a status listener
     *
     * @param onMediaStatus the listener to register
     * @return the same instance
     */
    public Whatsapp addMediaStatusListener(OnMediaStatus onMediaStatus) {
        return addListener(onMediaStatus);
    }

    /**
     * Registers an event listener
     *
     * @param onSocketEvent the listener to register
     * @return the same instance
     */
    public Whatsapp addSocketEventListener(OnSocketEvent onSocketEvent) {
        return addListener(onSocketEvent);
    }

    /**
     * Registers an action listener
     *
     * @param onAction the listener to register
     * @return the same instance
     */
    public Whatsapp addActionListener(OnWhatsappAction onAction) {
        return addListener(onAction);
    }

    /**
     * Registers a sync progress listener
     *
     * @param onSyncProgress the listener to register
     * @return the same instance
     */
    public Whatsapp addHistorySyncProgressListener(OnHistorySyncProgress onSyncProgress) {
        return addListener(onSyncProgress);
    }

    /**
     * Registers a chat recent messages listener
     *
     * @param onChatRecentMessages the listener to register
     * @return the same instance
     */
    public Whatsapp addChatMessagesSyncListener(OnWhatsappChatMessagesSync onChatRecentMessages) {
        return addListener(onChatRecentMessages);
    }

    /**
     * Registers a chats listener
     *
     * @param onChats the listener to register
     * @return the same instance
     */
    public Whatsapp addChatsListener(OnChatMessagesSync onChats) {
        return addListener(onChats);
    }

    /**
     * Registers a contact presence listener
     *
     * @param onContactPresence the listener to register
     * @return the same instance
     */
    public Whatsapp addContactPresenceListener(OnWhatsappContactPresence onContactPresence) {
        return addListener(onContactPresence);
    }

    /**
     * Registers a contacts listener
     *
     * @param onContacts the listener to register
     * @return the same instance
     */
    public Whatsapp addContactsListener(OnWhatsappContacts onContacts) {
        return addListener(onContacts);
    }

    /**
     * Registers a message status listener
     *
     * @param onWhatsappConversationMessageStatus the listener to register
     * @return the same instance
     */
    public Whatsapp addConversationMessageStatusListener(OnWhatsappConversationMessageStatus onWhatsappConversationMessageStatus) {
        return addListener(onWhatsappConversationMessageStatus);
    }

    /**
     * Registers a message status listener
     *
     * @param onMessageStatus the listener to register
     * @return the same instance
     */
    public Whatsapp addAnyMessageStatusListener(OnWhatsappAnyMessageStatus onMessageStatus) {
        return addListener(onMessageStatus);
    }

    /**
     * Registers a disconnected listener
     *
     * @param onDisconnected the listener to register
     * @return the same instance
     */
    public Whatsapp addDisconnectedListener(OnWhatsappDisconnected onDisconnected) {
        return addListener(onDisconnected);
    }

    /**
     * Registers a features listener
     *
     * @param onFeatures the listener to register
     * @return the same instance
     */
    public Whatsapp addFeaturesListener(OnWhatsappFeatures onFeatures) {
        return addListener(onFeatures);
    }

    /**
     * Registers a logged in listener
     *
     * @param onLoggedIn the listener to register
     * @return the same instance
     */
    public Whatsapp addLoggedInListener(OnWhatsappLoggedIn onLoggedIn) {
        return addListener(onLoggedIn);
    }

    /**
     * Registers a message deleted listener
     *
     * @param onMessageDeleted the listener to register
     * @return the same instance
     */
    public Whatsapp addMessageDeletedListener(OnWhatsappMessageDeleted onMessageDeleted) {
        return addListener(onMessageDeleted);
    }

    /**
     * Registers a metadata listener
     *
     * @param onMetadata the listener to register
     * @return the same instance
     */
    public Whatsapp addMetadataListener(OnWhatsappMetadata onMetadata) {
        return addListener(onMetadata);
    }

    /**
     * Registers a new message listener
     *
     * @param onNewMessage the listener to register
     * @return the same instance
     */
    public Whatsapp addNewMessageListener(OnWhatsappNewMessage onNewMessage) {
        return addListener(onNewMessage);
    }

    /**
     * Registers a new message listener
     *
     * @param onNewMessage the listener to register
     * @return the same instance
     */
    public Whatsapp addNewMessageListener(OnWhatsappNewMarkedMessage onNewMessage) {
        return addListener(onNewMessage);
    }

    /**
     * Registers a new status listener
     *
     * @param onNewStatus the listener to register
     * @return the same instance
     */
    public Whatsapp addNewStatusListener(OnWhatsappNewMediaStatus onNewStatus) {
        return addListener(onNewStatus);
    }

    /**
     * Registers a received node listener
     *
     * @param onNodeReceived the listener to register
     * @return the same instance
     */
    public Whatsapp addNodeReceivedListener(OnWhatsappNodeReceived onNodeReceived) {
        return addListener(onNodeReceived);
    }

    /**
     * Registers a sent node listener
     *
     * @param onNodeSent the listener to register
     * @return the same instance
     */
    public Whatsapp addNodeSentListener(OnWhatsappNodeSent onNodeSent) {
        return addListener(onNodeSent);
    }

    /**
     * Registers a setting listener
     *
     * @param onSetting the listener to register
     * @return the same instance
     */
    public Whatsapp addSettingListener(OnWhatsappSetting onSetting) {
        return addListener(onSetting);
    }

    /**
     * Registers a status listener
     *
     * @param onStatus the listener to register
     * @return the same instance
     */
    public Whatsapp addMediaStatusListener(OnWhatsappMediaStatus onStatus) {
        return addListener(onStatus);
    }

    /**
     * Registers an event listener
     *
     * @param onSocketEvent the listener to register
     * @return the same instance
     */
    public Whatsapp addSocketEventListener(OnWhatsappSocketEvent onSocketEvent) {
        return addListener(onSocketEvent);
    }

    /**
     * Registers a sync progress listener
     *
     * @param onSyncProgress the listener to register
     * @return the same instance
     */
    public Whatsapp addHistorySyncProgressListener(OnWhatsappHistorySyncProgress onSyncProgress) {
        return addListener(onSyncProgress);
    }

    /**
     * Registers a message reply listener
     *
     * @param onMessageReply the listener to register
     * @return the same instance
     */
    public Whatsapp addMessageReplyListener(OnWhatsappMessageReply onMessageReply) {
        return addListener(onMessageReply);
    }

    /**
     * Registers a message reply listener for a specific message
     *
     * @param info           the non-null target message
     * @param onMessageReply the non-null listener
     */
    public Whatsapp addMessageReplyListener(@NonNull MessageInfo info, @NonNull OnMessageReply onMessageReply) {
        return addMessageReplyListener(info.id(), onMessageReply);
    }

    /**
     * Registers a message reply listener
     *
     * @param onMessageReply the listener to register
     * @return the same instance
     */
    public Whatsapp addMessageReplyListener(OnMessageReply onMessageReply) {
        return addListener(onMessageReply);
    }

    /**
     * Registers a message reply listener for a specific message
     *
     * @param info           the non-null target message
     * @param onMessageReply the non-null listener
     */
    public Whatsapp addMessageReplyListener(@NonNull MessageInfo info, @NonNull OnWhatsappMessageReply onMessageReply) {
        return addMessageReplyListener(info.id(), onMessageReply);
    }

    /**
     * Registers a message reply listener for a specific message
     *
     * @param id             the non-null id of the target message
     * @param onMessageReply the non-null listener
     */
    public Whatsapp addMessageReplyListener(@NonNull String id, @NonNull OnMessageReply onMessageReply) {
        return addMessageReplyListener((info, quoted) -> {
            if (!info.id().equals(id)) {
                return;
            }

            onMessageReply.onMessageReply(info, quoted);
        });
    }

    /**
     * Registers a message reply listener for a specific message
     *
     * @param id             the non-null id of the target message
     * @param onMessageReply the non-null listener
     */
    public Whatsapp addMessageReplyListener(@NonNull String id, @NonNull OnWhatsappMessageReply onMessageReply) {
        return addMessageReplyListener(((whatsapp, info, quoted) -> {
            if (!info.id().equals(id)) {
                return;
            }

            onMessageReply.onMessageReply(whatsapp, info, quoted);
        }));
    }

    /**
     * Registers a name change listener
     *
     * @param onUserNameChange the non-null listener
     */
    public Whatsapp addUserNameChangeListener(@NonNull OnUserNameChange onUserNameChange) {
        return addListener(onUserNameChange);
    }

    /**
     * Registers a name change listener
     *
     * @param onNameChange the non-null listener
     */
    public Whatsapp addUserNameChangeListener(@NonNull OnWhatsappUserNameChange onNameChange) {
        return addListener(onNameChange);
    }

    /**
     * Registers a status change listener
     *
     * @param onUserAboutChange the non-null listener
     */
    public Whatsapp addUserStatusChangeListener(@NonNull OnUserAboutChange onUserAboutChange) {
        return addListener(onUserAboutChange);
    }

    /**
     * Registers a status change listener
     *
     * @param onUserStatusChange the non-null listener
     */
    public Whatsapp addUserStatusChangeListener(@NonNull OnWhatsappUserAboutChange onUserStatusChange) {
        return addListener(onUserStatusChange);
    }

    /**
     * Registers a picture change listener
     *
     * @param onUserPictureChange the non-null listener
     */
    public Whatsapp addUserPictureChangeListener(@NonNull OnUserPictureChange onUserPictureChange) {
        return addListener(onUserPictureChange);
    }

    /**
     * Registers a picture change listener
     *
     * @param onUserPictureChange the non-null listener
     */
    public Whatsapp addUserPictureChangeListener(@NonNull OnWhatsappUserPictureChange onUserPictureChange) {
        return addListener(onUserPictureChange);
    }

    /**
     * Registers a profile picture listener
     *
     * @param onContactPictureChange the non-null listener
     */
    public Whatsapp addContactPictureChangeListener(@NonNull OnContactPictureChange onContactPictureChange) {
        return addListener(onContactPictureChange);
    }

    /**
     * Registers a profile picture listener
     *
     * @param onProfilePictureChange the non-null listener
     */
    public Whatsapp addContactPictureChangeListener(@NonNull OnWhatsappContactPictureChange onProfilePictureChange) {
        return addListener(onProfilePictureChange);
    }

    /**
     * Registers a group picture listener
     *
     * @param onGroupPictureChange the non-null listener
     */
    public Whatsapp addGroupPictureChangeListener(@NonNull OnGroupPictureChange onGroupPictureChange) {
        return addListener(onGroupPictureChange);
    }

    /**
     * Registers a group picture listener
     *
     * @param onGroupPictureChange the non-null listener
     */
    public Whatsapp addGroupPictureChangeListener(@NonNull OnWhatsappContactPictureChange onGroupPictureChange) {
        return addListener(onGroupPictureChange);
    }

    /**
     * Registers a contact blocked listener
     *
     * @param onContactBlocked the non-null listener
     */
    public Whatsapp addContactBlockedListener(@NonNull OnContactBlocked onContactBlocked) {
        return addListener(onContactBlocked);
    }

    /**
     * Registers a contact blocked listener
     *
     * @param onContactBlocked the non-null listener
     */
    public Whatsapp addContactBlockedListener(@NonNull OnWhatsappContactBlocked onContactBlocked) {
        return addListener(onContactBlocked);
    }

    /**
     * Registers a privacy setting changed listener
     *
     * @param onPrivacySettingChanged the listener to register
     * @return the same instance
     */
    public Whatsapp addPrivacySettingChangedListener(OnPrivacySettingChanged onPrivacySettingChanged) {
        return addListener(onPrivacySettingChanged);
    }


    /**
     * Registers a privacy setting changed listener
     *
     * @param onWhatsappPrivacySettingChanged the listener to register
     * @return the same instance
     */
    public Whatsapp addPrivacySettingChangedListener(OnWhatsappPrivacySettingChanged onWhatsappPrivacySettingChanged) {
        return addListener(onWhatsappPrivacySettingChanged);
    }

    /**
     * Registers a companion devices changed listener
     *
     * @param onLinkedDevices the listener to register
     * @return the same instance
     */
    public Whatsapp addLinkedDevicesListener(OnLinkedDevices onLinkedDevices) {
        return addListener(onLinkedDevices);
    }

    /**
     * Registers a companion devices changed listener
     *
     * @param onWhatsappLinkedDevices the listener to register
     * @return the same instance
     */
    public Whatsapp addLinkedDevicesListener(OnWhatsappLinkedDevices onWhatsappLinkedDevices) {
        return addListener(onWhatsappLinkedDevices);
    }

    /**
     * Registers a registration code listener for the mobile api
     *
     * @param onRegistrationCode the listener to register
     * @return the same instance
     */
    public Whatsapp addRegistrationCodeListener(OnRegistrationCode onRegistrationCode) {
        return addListener(onRegistrationCode);
    }

    /**
     * Registers a registration code listener for the mobile api
     *
     * @param onWhatsappRegistrationCode the listener to register
     * @return the same instance
     */
    public Whatsapp addLinkedDevicesListener(OnWhatsappRegistrationCode onWhatsappRegistrationCode) {
        return addListener(onWhatsappRegistrationCode);
    }

    /**
     * Registers a call listener
     *
     * @param onCall the listener to register
     * @return the same instance
     */
    public Whatsapp addRegistrationCodeListener(OnCall onCall) {
        return addListener(onCall);
    }

    /**
     * Registers a call listener
     *
     * @param onWhatsappCall the listener to register
     * @return the same instance
     */
    public Whatsapp addLinkedDevicesListener(OnWhatsappCall onWhatsappCall) {
        return addListener(onWhatsappCall);
    }
}
