package it.auties.whatsapp.socket;

import it.auties.whatsapp.api.WebHistoryLength;
import it.auties.whatsapp.crypto.*;
import it.auties.whatsapp.model.action.ContactAction;
import it.auties.whatsapp.model.business.BusinessVerifiedNameCertificate;
import it.auties.whatsapp.model.chat.*;
import it.auties.whatsapp.model.chat.Chat.EndOfHistoryTransferType;
import it.auties.whatsapp.model.contact.Contact;
import it.auties.whatsapp.model.contact.ContactJid;
import it.auties.whatsapp.model.contact.ContactJid.Server;
import it.auties.whatsapp.model.contact.ContactJid.Type;
import it.auties.whatsapp.model.contact.ContactStatus;
import it.auties.whatsapp.model.exchange.Attributes;
import it.auties.whatsapp.model.exchange.MessageSendRequest;
import it.auties.whatsapp.model.exchange.Node;
import it.auties.whatsapp.model.info.MessageIndexInfo;
import it.auties.whatsapp.model.info.MessageInfo;
import it.auties.whatsapp.model.message.button.*;
import it.auties.whatsapp.model.message.model.*;
import it.auties.whatsapp.model.message.payment.PaymentOrderMessage;
import it.auties.whatsapp.model.message.server.DeviceSentMessage;
import it.auties.whatsapp.model.message.server.ProtocolMessage;
import it.auties.whatsapp.model.message.server.SenderKeyDistributionMessage;
import it.auties.whatsapp.model.message.standard.*;
import it.auties.whatsapp.model.setting.EphemeralSetting;
import it.auties.whatsapp.model.signal.keypair.SignalSignedKeyPair;
import it.auties.whatsapp.model.signal.message.SignalDistributionMessage;
import it.auties.whatsapp.model.signal.message.SignalMessage;
import it.auties.whatsapp.model.signal.message.SignalPreKeyMessage;
import it.auties.whatsapp.model.signal.sender.SenderKeyName;
import it.auties.whatsapp.model.sync.HistorySync;
import it.auties.whatsapp.model.sync.PushName;
import it.auties.whatsapp.util.*;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static it.auties.whatsapp.api.ErrorHandler.Location.MESSAGE;
import static it.auties.whatsapp.api.ErrorHandler.Location.UNKNOWN;
import static it.auties.whatsapp.model.sync.HistorySync.Type.*;
import static it.auties.whatsapp.util.Spec.Signal.*;

class MessageHandler {
    private static final int HISTORY_SYNC_TIMEOUT = 10;

    private final SocketHandler socketHandler;
    private final Map<ContactJid, List<PastParticipant>> pastParticipantsQueue;
    private final Set<Chat> historyCache;
    private final Logger logger;
    private final Set<ContactJid> attributedGroups;
    private final EnumSet<HistorySync.Type> historySyncTypes;
    private ExecutorService executor;
    private CompletableFuture<?> historySyncTask;

    protected MessageHandler(SocketHandler socketHandler) {
        this.socketHandler = socketHandler;
        this.pastParticipantsQueue = new ConcurrentHashMap<>();
        this.historyCache = ConcurrentHashMap.newKeySet();
        this.attributedGroups = ConcurrentHashMap.newKeySet();
        this.logger = System.getLogger("MessageHandler");
        this.historySyncTypes = EnumSet.noneOf(HistorySync.Type.class);
    }

    private synchronized ExecutorService getOrCreateMessageService(){
        if(executor == null || executor.isShutdown()){
            executor = Executors.newSingleThreadExecutor();
        }

        return executor;
    }

    protected synchronized CompletableFuture<Void> encode(MessageSendRequest request) {
        var future = new CompletableFuture<Void>();
        getOrCreateMessageService().execute(() -> {
            encodeMessageNode(request)
                    .thenRunAsync(() -> attributeOutgoingMessage(request))
                    .exceptionallyAsync(throwable -> onEncodeError(request, throwable))
                    .join();
            future.complete(null);
        });
        return future;
    }

    private CompletableFuture<Node> encodeMessageNode(MessageSendRequest request) {
        return request.peer() || isConversation(request.info()) ? encodeConversation(request) : encodeGroup(request);
    }

    private Void onEncodeError(MessageSendRequest request, Throwable throwable) {
        request.info().status(MessageStatus.ERROR);
        return socketHandler.handleFailure(MESSAGE, throwable);
    }

    private void attributeOutgoingMessage(MessageSendRequest request) {
        if(request.peer()){
            return;
        }

        saveMessage(request.info(), false);
        attributeMessageReceipt(request.info());
    }

    private CompletableFuture<Node> encodeGroup(MessageSendRequest request) {
        var encodedMessage = BytesHelper.messageToBytes(request.info().message());
        var senderName = new SenderKeyName(request.info().chatJid().toString(), socketHandler.store().jid().toSignalAddress());
        var groupBuilder = new GroupBuilder(socketHandler.keys());
        var signalMessage = groupBuilder.createOutgoing(senderName);
        var groupCipher = new GroupCipher(senderName, socketHandler.keys());
        var groupMessage = groupCipher.encrypt(encodedMessage);
        var messageNode = createMessageNode(request, groupMessage);
        if (request.hasRecipientOverride()) {
            return getDevices(request.recipients(), false)
                    .thenComposeAsync(allDevices -> createGroupNodes(request, signalMessage, allDevices, request.force()))
                    .thenApplyAsync(preKeys -> createEncodedMessageNode(request, preKeys, messageNode))
                    .thenComposeAsync(socketHandler::send);
        }

        if (request.force()) {
            return socketHandler.queryGroupMetadata(request.info().chatJid())
                    .thenComposeAsync(this::getGroupDevices)
                    .thenComposeAsync(allDevices -> createGroupNodes(request, signalMessage, allDevices, true))
                    .thenApplyAsync(preKeys -> createEncodedMessageNode(request, preKeys, messageNode))
                    .thenComposeAsync(socketHandler::send);
        }

        return socketHandler.queryGroupMetadata(request.info().chatJid())
                .thenComposeAsync(this::getGroupDevices)
                .thenComposeAsync(allDevices -> createGroupNodes(request, signalMessage, allDevices, false))
                .thenApplyAsync(preKeys -> createEncodedMessageNode(request, preKeys, messageNode))
                .thenComposeAsync(socketHandler::send);
    }

    private CompletableFuture<Node> encodeConversation(MessageSendRequest request) {
        var sender = socketHandler.store().jid();
        if(sender == null){
            return CompletableFuture.failedFuture(new IllegalStateException("Cannot create message: user is not signed in"));
        }

        var encodedMessage = BytesHelper.messageToBytes(request.info().message());
        var knownDevices = getRecipients(request, sender);
        var chatJid = request.info().chatJid();
        if(request.peer()){
            var peerNode = createMessageNode(request, chatJid, encodedMessage, true);
            var encodedMessageNode = createEncodedMessageNode(request, List.of(peerNode), null);
            return socketHandler.send(encodedMessageNode);
        }

        var deviceMessage = new DeviceSentMessage(request.info().chatJid().toString(), request.info().message(), null);
        var encodedDeviceMessage = BytesHelper.messageToBytes(deviceMessage);
        return getDevices(knownDevices, true)
                .thenComposeAsync(allDevices -> createConversationNodes(request, allDevices, encodedMessage, encodedDeviceMessage))
                .thenApplyAsync(sessions -> createEncodedMessageNode(request, sessions, null))
                .thenComposeAsync(socketHandler::send);
    }

    private List<ContactJid> getRecipients(MessageSendRequest request, ContactJid sender) {
        if(request.peer()){
            return List.of(request.info().chatJid());
        }

        if (request.hasRecipientOverride()) {
            return request.recipients();
        }

        return List.of(sender.toWhatsappJid(), request.info().chatJid());
    }

    private boolean isConversation(MessageInfo info) {
        return info.chatJid().hasServer(Server.WHATSAPP)
                || info.chatJid().hasServer(Server.USER);
    }

    private Node createEncodedMessageNode(MessageSendRequest request, List<Node> preKeys, Node descriptor) {
        var body = new ArrayList<Node>();
        if (!preKeys.isEmpty()) {
            if (request.peer()) {
                body.addAll(preKeys);
            } else {
                body.add(Node.of("participants", preKeys));
            }
        }

        if (descriptor != null) {
            body.add(descriptor);
        }

        if (!request.peer() && hasPreKeyMessage(preKeys)) {
            socketHandler.keys().companionIdentity()
                    .ifPresent(companionIdentity -> body.add(Node.of("device-identity", Protobuf.writeMessage(companionIdentity))));
        }

        var attributes = Attributes.ofNullable(request.additionalAttributes())
                .put("id", request.info().id())
                .put("to", request.info().chatJid())
                .put("t", request.info().timestampSeconds(), !request.peer())
                .put("type", "text")
                .put("category", "peer", request::peer)
                .put("duration", "900", request.info().message().type() == MessageType.LIVE_LOCATION)
                .put("device_fanout", false, request.info().message().type() == MessageType.BUTTONS)
                .put("push_priority", "high", isAppStateKeyShare(request))
                .toMap();
        return Node.of("message", attributes, body);
    }

    private boolean isAppStateKeyShare(MessageSendRequest request) {
        return request.peer()
                && request.info().message().content() instanceof ProtocolMessage protocolMessage
                && protocolMessage.protocolType() == ProtocolMessage.ProtocolMessageType.APP_STATE_SYNC_KEY_SHARE;
    }

    private boolean hasPreKeyMessage(List<Node> participants) {
        return participants.stream()
                .map(Node::children)
                .flatMap(Collection::stream)
                .map(node -> node.attributes().getOptionalString("type"))
                .flatMap(Optional::stream)
                .anyMatch(PKMSG::equals);
    }

    private CompletableFuture<List<Node>> createConversationNodes(MessageSendRequest request, List<ContactJid> contacts, byte[] message, byte[] deviceMessage) {
        var partitioned = contacts.stream()
                .collect(Collectors.partitioningBy(contact -> Objects.equals(contact.user(), socketHandler.store().jid().user())));
        var companions = querySessions(partitioned.get(true), request.force())
                .thenApplyAsync(ignored -> createMessageNodes(request, partitioned.get(true), deviceMessage));
        var others = querySessions(partitioned.get(false), request.force())
                .thenApplyAsync(ignored -> createMessageNodes(request, partitioned.get(false), message));
        return companions.thenCombineAsync(others, (first, second) -> toSingleList(first, second));
    }

    private CompletableFuture<List<Node>> createGroupNodes(MessageSendRequest request, byte[] distributionMessage, List<ContactJid> participants, boolean force) {
        var missingParticipants = participants.stream()
                .filter(participant -> force || !request.info().chat().participantsPreKeys().contains(participant))
                .toList();
        if (missingParticipants.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        var whatsappMessage = new SenderKeyDistributionMessage(request.info().chatJid().toString(), distributionMessage);
        var paddedMessage = BytesHelper.messageToBytes(whatsappMessage);
        return querySessions(missingParticipants, force)
                .thenApplyAsync(ignored -> createMessageNodes(request, missingParticipants, paddedMessage))
                .thenApplyAsync(results -> savePreKeys(request.info().chat(), missingParticipants, results));
    }

    private List<Node> savePreKeys(Chat group, List<ContactJid> missingParticipants, List<Node> results) {
        group.participantsPreKeys().addAll(missingParticipants);
        return results;
    }

    protected CompletableFuture<Void> querySessions(List<ContactJid> contacts, boolean force) {
        var missingSessions = contacts.stream()
                .filter(contact -> force || !socketHandler.keys().hasSession(contact.toSignalAddress()))
                .map(contact -> Node.of("user", Map.of("jid", contact)))
                .toList();
        return missingSessions.isEmpty() ? CompletableFuture.completedFuture(null) : querySession(missingSessions);
    }

    private CompletableFuture<Void> querySession(List<Node> children){
        return socketHandler.sendQuery("get", "encrypt", Node.of("key", children))
                .thenAcceptAsync(this::parseSessions);
    }

    private List<Node> createMessageNodes(MessageSendRequest request, List<ContactJid> contacts, byte[] message) {
        return contacts.stream()
                .map(contact -> createMessageNode(request, contact, message, false))
                .toList();
    }

    private Node createMessageNode(MessageSendRequest request, ContactJid contact, byte[] message, boolean peer) {
        var cipher = new SessionCipher(contact.toSignalAddress(), socketHandler.keys());
        var encrypted = cipher.encrypt(message);
        var messageNode = createMessageNode(request, encrypted);
        return peer ? messageNode : Node.of("to", Map.of("jid", contact), messageNode);
    }

    private CompletableFuture<List<ContactJid>> getGroupDevices(GroupMetadata metadata) {
        return getDevices(metadata.participantsJids(), false);
    }

    protected CompletableFuture<List<ContactJid>> getDevices(List<ContactJid> contacts, boolean excludeSelf) {
        return queryDevices(contacts, excludeSelf)
                .thenApplyAsync(missingDevices -> excludeSelf ? toSingleList(contacts, missingDevices) : missingDevices);
    }

    private CompletableFuture<List<ContactJid>> queryDevices(List<ContactJid> contacts, boolean excludeSelf) {
        var contactNodes = contacts.stream()
                .map(contact -> Node.of("user", Map.of("jid", contact)))
                .toList();
        var body = Node.of("usync",
                Map.of("sid", MessageKey.randomId(), "mode", "query", "last", "true", "index", "0", "context", "message"),
                Node.of("query", Node.of("devices", Map.of("version", "2"))),
                Node.of("list", contactNodes));
        return socketHandler.sendQuery("get", "usync", body)
                .thenApplyAsync(result -> parseDevices(result, excludeSelf));
    }

    private List<ContactJid> parseDevices(Node node, boolean excludeSelf) {
        return node.children()
                .stream()
                .map(child -> child.findNode("list"))
                .flatMap(Optional::stream)
                .map(Node::children)
                .flatMap(Collection::stream)
                .map(entry -> parseDevice(entry, excludeSelf))
                .flatMap(Collection::stream)
                .toList();
    }

    private List<ContactJid> parseDevice(Node wrapper, boolean excludeSelf) {
        var jid = wrapper.attributes()
                .getJid("jid")
                .orElseThrow(() -> new NoSuchElementException("Missing jid for sync device"));
        return wrapper.findNode("devices")
                .orElseThrow(() -> new NoSuchElementException("Missing devices"))
                .findNode("device-list")
                .orElseThrow(() -> new NoSuchElementException("Missing device list"))
                .children()
                .stream()
                .map(child -> parseDeviceId(child, jid, excludeSelf))
                .flatMap(Optional::stream)
                .map(id -> ContactJid.ofDevice(jid.user(), id))
                .toList();
    }

    private Optional<Integer> parseDeviceId(Node child, ContactJid jid, boolean excludeSelf) {
        var deviceId = child.attributes().getInt("id");
        return child.description().equals("device")
                && (!excludeSelf || deviceId != 0)
                && (!jid.user().equals(socketHandler.store().jid().user()) || socketHandler.store().jid().device() != deviceId)
                && (deviceId == 0 || child.attributes().hasKey("key-index")) ? Optional.of(deviceId) : Optional.empty();
    }

    protected void parseSessions(Node node) {
        node.findNode("list")
                .orElseThrow(() -> new IllegalArgumentException("Cannot parse sessions: " + node))
                .findNodes("user")
                .forEach(this::parseSession);
    }

    private void parseSession(Node node) {
        Validate.isTrue(!node.hasNode("error"), "Erroneous session node", SecurityException.class);
        var jid = node.attributes()
                .getJid("jid")
                .orElseThrow(() -> new NoSuchElementException("Missing jid for session"));
        var registrationId = node.findNode("registration")
                .map(id -> BytesHelper.bytesToInt(id.contentAsBytes().orElseThrow(), 4))
                .orElseThrow(() -> new NoSuchElementException("Missing id"));
        var identity = node.findNode("identity")
                .flatMap(Node::contentAsBytes)
                .map(KeyHelper::withHeader)
                .orElseThrow(() -> new NoSuchElementException("Missing identity"));
        var signedKey = node.findNode("skey")
                .flatMap(SignalSignedKeyPair::of)
                .orElseThrow(() -> new NoSuchElementException("Missing signed key"));
        var key = node.findNode("key")
                .flatMap(SignalSignedKeyPair::of)
                .orElse(null);
        var builder = new SessionBuilder(jid.toSignalAddress(), socketHandler.keys());
        builder.createOutgoing(registrationId, identity, signedKey, key);
    }

    public synchronized void decode(Node node) {
        getOrCreateMessageService().execute(() -> {
            try {
                var businessName = getBusinessName(node);
                var encrypted = node.findNodes("enc");
                if (node.hasNode("unavailable") && !node.hasNode("enc")) {
                    decodeMessage(node, null, businessName);
                    return;
                }
                encrypted.forEach(message -> decodeMessage(node, message, businessName));
            } catch (Throwable throwable) {
                socketHandler.handleFailure(MESSAGE, throwable);
            }
        });
    }

    private String getBusinessName(Node node) {
        return node.attributes()
                .getOptionalString("verified_name")
                .or(() -> getBusinessNameFromNode(node))
                .orElse(null);
    }

    private static Optional<String> getBusinessNameFromNode(Node node) {
        return node.findNode("verified_name")
                .flatMap(Node::contentAsBytes)
                .map(bytes -> Protobuf.readMessage(bytes, BusinessVerifiedNameCertificate.class))
                .map(certificate -> certificate.details().name());
    }

    private Node createMessageNode(MessageSendRequest request, CipheredMessageResult groupMessage) {
        var mediaType = getMediaType(request.info().message());
        var attributes = Attributes.of()
                .put("v", "2")
                .put("type", groupMessage.type())
                .put("mediatype", mediaType, Objects::nonNull);
        return Node.of("enc", attributes, groupMessage.message());
    }

    private String getMediaType(MessageContainer container) {
        var content = container.content();
        if (content instanceof ImageMessage) {
            return "image";
        } else if (content instanceof VideoMessage videoMessage) {
            return videoMessage.gifPlayback() ? "gif" : "video";
        } else if (content instanceof AudioMessage audioMessage) {
            return audioMessage.voiceMessage() ? "ptt" : "audio";
        } else if (content instanceof ContactMessage) {
            return "vcard";
        } else if (content instanceof DocumentMessage) {
            return "document";
        } else if (content instanceof ContactsArrayMessage) {
            return "contact_array";
        } else if (content instanceof LiveLocationMessage) {
            return "livelocation";
        } else if (content instanceof StickerMessage) {
            return "sticker";
        } else if (content instanceof ListMessage) {
            return "list";
        } else if (content instanceof ListResponseMessage) {
            return "list_response";
        } else if (content instanceof ButtonsResponseMessage) {
            return "buttons_response";
        } else if (content instanceof PaymentOrderMessage) {
            return "order";
        } else if (content instanceof ProductMessage) {
            return "product";
        } else if (content instanceof NativeFlowResponseMessage) {
            return "native_flow_response";
        } else if (content instanceof ButtonsMessage buttonsMessage) {
            return buttonsMessage.headerType().hasMedia() ? buttonsMessage.headerType().name().toLowerCase() : null;
        } else {
            return null;
        }
    }

    private void decodeMessage(Node infoNode, Node messageNode, String businessName) {
        try {
            var offline = infoNode.attributes().hasKey("offline");
            var pushName = infoNode.attributes().getNullableString("notify");
            var timestamp = infoNode.attributes().getLong("t");
            var id = infoNode.attributes().getRequiredString("id");
            var from = infoNode.attributes()
                    .getJid("from")
                    .orElseThrow(() -> new NoSuchElementException("Missing from"));
            var recipient = infoNode.attributes().getJid("recipient").orElse(from);
            var participant = infoNode.attributes().getJid("participant").orElse(null);
            var messageBuilder = MessageInfo.builder();
            var keyBuilder = MessageKey.builder();
            var userCompanionJid = socketHandler.store().jid();
            if(userCompanionJid == null){
                return; // This means that the session got disconnected while processing
            }
            var receiver = userCompanionJid.toWhatsappJid();
            if (from.hasServer(ContactJid.Server.WHATSAPP) || from.hasServer(ContactJid.Server.USER)) {
                keyBuilder.chatJid(recipient);
                keyBuilder.senderJid(from);
                keyBuilder.fromMe(Objects.equals(from, receiver));
                messageBuilder.senderJid(from);
            } else {
                keyBuilder.chatJid(from);
                keyBuilder.senderJid(Objects.requireNonNull(participant, "Missing participant in group message"));
                keyBuilder.fromMe(Objects.equals(participant.toWhatsappJid(), receiver));
                messageBuilder.senderJid(Objects.requireNonNull(participant, "Missing participant in group message"));
            }
            var key = keyBuilder.id(id).build();
            if(Objects.equals(key.senderJid().orElse(null), socketHandler.store().jid())) {
                sendReceipt(infoNode, id, key.chatJid(), key.senderJid().orElse(null), key.fromMe());
                return;
            }

            if (messageNode == null) {
                logger.log(Level.WARNING, "Cannot decode message(id: %s, from: %s)".formatted(id, from));
                sendReceipt(infoNode, id, key.chatJid(), key.senderJid().orElse(null), key.fromMe());
                return;
            }

            var type = messageNode.attributes().getRequiredString("type");
            var encodedMessage = messageNode.contentAsBytes().orElse(null);
            var decodedMessage = decodeMessageBytes(type, encodedMessage, from, participant);
            if (decodedMessage.hasError()) {
                logger.log(Level.WARNING, "Cannot decode message(id: %s, from: %s): %s".formatted(id, from, decodedMessage.error().getMessage()));
                sendReceipt(infoNode, id, key.chatJid(), key.senderJid().orElse(null), key.fromMe());
                return;
            }

            var messageContainer = BytesHelper.bytesToMessage(decodedMessage.message()).unbox();
            var info = messageBuilder.key(key)
                    .broadcast(key.chatJid().hasServer(Server.BROADCAST))
                    .pushName(pushName)
                    .status(MessageStatus.DELIVERED)
                    .businessVerifiedName(businessName)
                    .timestampSeconds(timestamp)
                    .message(messageContainer)
                    .build();
            attributeMessageReceipt(info);
            socketHandler.store().attribute(info);
            saveMessage(info, offline);
            sendReceipt(infoNode, id, key.chatJid(), key.senderJid().orElse(null), key.fromMe());
            socketHandler.onReply(info);
        } catch (Throwable throwable) {
            socketHandler.handleFailure(MESSAGE, throwable);
        }
    }

    private void sendReceipt(Node infoNode, String id, ContactJid chatJid, ContactJid senderJid, boolean fromMe) {
        var participant = fromMe && senderJid == null ? chatJid : senderJid;
        var category = infoNode.attributes().getString("category");
        var receiptType = getReceiptType(category, fromMe);
        socketHandler.sendMessageAck(infoNode);
        socketHandler.sendReceipt(chatJid, participant, List.of(id), receiptType);
    }

    private String getReceiptType(String category, boolean fromMe) {
        if(Objects.equals(category, "peer")){
            return "peer_msg";
        }

        if(fromMe){
            return "sender";
        }

        if(!socketHandler.store().online()){
            return "inactive";
        }

        return null;
    }

    private MessageDecodeResult decodeMessageBytes(String type, byte[] encodedMessage, ContactJid from, ContactJid participant) {
        try {
            if (encodedMessage == null) {
                return new MessageDecodeResult(null, new IllegalArgumentException("Missing encoded message"));
            }
            var result = switch (type) {
                case SKMSG -> {
                    Objects.requireNonNull(participant, "Cannot decipher skmsg without participant");
                    var senderName = new SenderKeyName(from.toString(), participant.toSignalAddress());
                    var signalGroup = new GroupCipher(senderName, socketHandler.keys());
                    yield signalGroup.decrypt(encodedMessage);
                }
                case PKMSG -> {
                    var user = from.hasServer(ContactJid.Server.WHATSAPP) ? from : participant;
                    Objects.requireNonNull(user, "Cannot decipher pkmsg without user");
                    var session = new SessionCipher(user.toSignalAddress(), socketHandler.keys());
                    var preKey = SignalPreKeyMessage.ofSerialized(encodedMessage);
                    yield session.decrypt(preKey);
                }
                case MSG -> {
                    var user = from.hasServer(ContactJid.Server.WHATSAPP) ? from : participant;
                    Objects.requireNonNull(user, "Cannot decipher msg without user");
                    var session = new SessionCipher(user.toSignalAddress(), socketHandler.keys());
                    var signalMessage = SignalMessage.ofSerialized(encodedMessage);
                    yield session.decrypt(signalMessage);
                }
                default -> throw new IllegalArgumentException("Unsupported encoded message type: %s".formatted(type));
            };
            return new MessageDecodeResult(result, null);
        } catch (Throwable throwable) {
            return new MessageDecodeResult(null, throwable);
        }
    }

    private void attributeMessageReceipt(MessageInfo info) {
        var self = socketHandler.store().jid().toWhatsappJid();
        if (!info.fromMe() || !info.chatJid().equals(self)) {
            return;
        }
        info.receipt().readTimestampSeconds(info.timestampSeconds());
        info.receipt().deliveredJids().add(self);
        info.receipt().readJids().add(self);
        info.status(MessageStatus.READ);
    }

    private void saveMessage(MessageInfo info, boolean offline) {
        if(info.message().content() instanceof SenderKeyDistributionMessage distributionMessage) {
            handleDistributionMessage(distributionMessage, info.senderJid());
        }
        if (info.chatJid().type() == Type.STATUS) {
            socketHandler.store().addStatus(info);
            socketHandler.onNewStatus(info);
            return;
        }
        if (info.message().hasCategory(MessageCategory.SERVER)) {
            if (info.message().content() instanceof ProtocolMessage protocolMessage) {
                handleProtocolMessage(info, protocolMessage);
            }
            return;
        }
        var result = info.chat().addNewMessage(info);
        if (!result || info.timestampSeconds() <= socketHandler.store().initializationTimeStamp()) {
            return;
        }
        if (info.chat().archived() && socketHandler.store().unarchiveChats()) {
            info.chat().archived(false);
        }
        info.sender()
                .filter(this::isTyping)
                .ifPresent(sender -> socketHandler.onUpdateChatPresence(ContactStatus.AVAILABLE, sender.jid(), info.chat()));
        if (!info.ignore() && !info.fromMe()) {
            info.chat().unreadMessagesCount(info.chat().unreadMessagesCount() + 1);
        }
        socketHandler.onNewMessage(info, offline);
    }

    private void handleDistributionMessage(SenderKeyDistributionMessage distributionMessage, ContactJid from) {
        var groupName = new SenderKeyName(distributionMessage.groupId(), from.toSignalAddress());
        var builder = new GroupBuilder(socketHandler.keys());
        var message = SignalDistributionMessage.ofSerialized(distributionMessage.data());
        builder.createIncoming(groupName, message);
    }

    private void handleProtocolMessage(MessageInfo info, ProtocolMessage protocolMessage) {
        switch (protocolMessage.protocolType()) {
            case HISTORY_SYNC_NOTIFICATION -> onHistorySyncNotification(info, protocolMessage);
            case APP_STATE_SYNC_KEY_SHARE -> onAppStateSyncKeyShare(protocolMessage);
            case REVOKE -> onMessageRevoked(info, protocolMessage);
            case EPHEMERAL_SETTING -> onEphemeralSettings(info, protocolMessage);
        }
    }

    private void onEphemeralSettings(MessageInfo info, ProtocolMessage protocolMessage) {
        info.chat()
                .ephemeralMessagesToggleTime(info.timestampSeconds())
                .ephemeralMessageDuration(ChatEphemeralTimer.of(protocolMessage.ephemeralExpiration()));
        var setting = new EphemeralSetting((int) protocolMessage.ephemeralExpiration(), info.timestampSeconds());
        socketHandler.onSetting(setting);
    }

    private void onMessageRevoked(MessageInfo info, ProtocolMessage protocolMessage) {
        socketHandler.store()
                .findMessageById(info.chat(), protocolMessage.key().id())
                .ifPresent(message -> onMessageDeleted(info, message));
    }

    private void onAppStateSyncKeyShare(ProtocolMessage protocolMessage) {
        socketHandler.keys()
                .addAppKeys(socketHandler.store().jid(), protocolMessage.appStateSyncKeyShare().keys());
        socketHandler.pullInitialPatches()
                .exceptionallyAsync(throwable -> socketHandler.handleFailure(UNKNOWN, throwable));
    }

    private void onHistorySyncNotification(MessageInfo info, ProtocolMessage protocolMessage) {
        if(isZeroHistorySyncComplete()){
            return;
        }

        downloadHistorySync(protocolMessage)
                .thenAcceptAsync(history -> onHistoryNotification(info, history))
                .exceptionallyAsync(throwable -> socketHandler.handleFailure(MESSAGE, throwable));
    }

    private boolean isZeroHistorySyncComplete() {
        return socketHandler.store().historyLength() == WebHistoryLength.ZERO
                && historySyncTypes.contains(INITIAL_STATUS_V3)
                && historySyncTypes.contains(PUSH_NAME)
                && historySyncTypes.contains(INITIAL_BOOTSTRAP)
                && historySyncTypes.contains(NON_BLOCKING_DATA);
    }

    private boolean isTyping(Contact sender) {
        return sender.lastKnownPresence() == ContactStatus.COMPOSING
                || sender.lastKnownPresence() == ContactStatus.RECORDING;
    }

    private CompletableFuture<HistorySync> downloadHistorySync(ProtocolMessage protocolMessage) {
        return Medias.download(protocolMessage.historySyncNotification())
                .thenApplyAsync(entry -> entry.orElseThrow(() -> new NoSuchElementException("Cannot download history sync")))
                .thenApplyAsync(result -> Protobuf.readMessage(BytesHelper.decompress(result), HistorySync.class));
    }

    private void onHistoryNotification(MessageInfo info, HistorySync history) {
        handleHistorySync(history);
        if (history.progress() != null) {
            scheduleTimeoutSync(history);
            socketHandler.onHistorySyncProgress(history.progress(), history.syncType() == RECENT);
        }
        socketHandler.sendReceipt(info.chatJid(), null, List.of(info.id()), "hist_sync");
    }

    private void scheduleTimeoutSync(HistorySync history) {
        var executor = CompletableFuture.delayedExecutor(HISTORY_SYNC_TIMEOUT, TimeUnit.SECONDS);
        if(historySyncTask != null){
            historySyncTask.cancel(true);
        }
        this.historySyncTask = CompletableFuture.runAsync(() -> handleChatsSync(history, true), executor);
    }

    private void onMessageDeleted(MessageInfo info, MessageInfo message) {
        info.chat().removeMessage(message);
        message.revokeTimestampSeconds(Clock.nowSeconds());
        socketHandler.onMessageDeleted(message, true);
    }

    private void handleHistorySync(HistorySync history) {
        try {
            switch (history.syncType()) {
                case INITIAL_STATUS_V3 -> handleInitialStatus(history);
                case PUSH_NAME -> handlePushNames(history);
                case INITIAL_BOOTSTRAP -> handleInitialBootstrap(history);
                case RECENT, FULL -> handleChatsSync(history, false);
                case NON_BLOCKING_DATA -> handleNonBlockingData(history);
            }
        }finally {
            historySyncTypes.add(history.syncType());
        }
    }

    private void handleInitialStatus(HistorySync history) {
        var store = socketHandler.store();
        for (var messageInfo : history.statusV3Messages()) {
            store.addStatus(messageInfo);
        }
        socketHandler.onStatus();
    }

    private void handlePushNames(HistorySync history) {
        for (var pushName : history.pushNames()) {
            handNewPushName(pushName);
        }
        socketHandler.onContacts();
    }

    private void handNewPushName(PushName pushName) {
        var jid = ContactJid.of(pushName.id());
        var contact = socketHandler.store()
                .findContactByJid(jid)
                .orElseGet(() -> createNewContact(jid));
        contact.chosenName(pushName.name());
        var action = new ContactAction(pushName.name(), null, null);
        socketHandler.onAction(action, MessageIndexInfo.of("contact", jid, null, true));
    }

    private Contact createNewContact(ContactJid jid) {
        var contact = socketHandler.store().addContact(jid);
        socketHandler.onNewContact(contact);
        return contact;
    }

    private void handleInitialBootstrap(HistorySync history) {
        if(socketHandler.store().historyLength() != WebHistoryLength.ZERO){
            historyCache.addAll(history.conversations());
        }

        handleConversations(history);
        socketHandler.onChats();
    }

    private void handleChatsSync(HistorySync history, boolean forceDone) {
        if(socketHandler.store().historyLength() == WebHistoryLength.ZERO){
            return;
        }

        handleConversations(history);
        for (var cached : historyCache) {
            var chat = socketHandler.store()
                    .findChatByJid(cached.jid())
                    .orElse(cached);
            var done = forceDone || !history.conversations().contains(cached);
            if(done){
                chat.endOfHistoryTransferType(EndOfHistoryTransferType.COMPLETE_AND_NO_MORE_MESSAGE_REMAIN_ON_PRIMARY);
            }
            socketHandler.onChatRecentMessages(chat, done);
        }
        historyCache.removeIf(entry -> !history.conversations().contains(entry));
    }

    private void handleConversations(HistorySync history) {
        var store = socketHandler.store();
        for (var chat : history.conversations()) {
            var pastParticipants = pastParticipantsQueue.remove(chat.jid());
            if (pastParticipants != null) {
                chat.addPastParticipants(pastParticipants);
            }

            store.addChat(chat);
        }
    }

    private void handleNonBlockingData(HistorySync history) {
        for (var pastParticipants : history.pastParticipants()) {
            handlePastParticipants(pastParticipants);
        }
    }

    private void handlePastParticipants(PastParticipants pastParticipants) {
        socketHandler.store()
                .findChatByJid(pastParticipants.groupJid())
                .ifPresentOrElse(chat -> chat.addPastParticipants(pastParticipants.pastParticipants()),
                        () ->  pastParticipantsQueue.put(pastParticipants.groupJid(), pastParticipants.pastParticipants()));
    }

    @SafeVarargs
    private <T> List<T> toSingleList(List<T>... all) {
        return Stream.of(all)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .toList();
    }

    protected void dispose() {
        historyCache.clear();
        if(executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
        historySyncTask = null;
        historySyncTypes.clear();
    }

    private record MessageDecodeResult(byte[] message, Throwable error) {
        public boolean hasError() {
            return error != null;
        }
    }
}
