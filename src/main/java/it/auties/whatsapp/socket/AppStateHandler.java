package it.auties.whatsapp.socket;

import it.auties.whatsapp.api.ClientType;
import it.auties.whatsapp.binary.BinaryPatchType;
import it.auties.whatsapp.crypto.AesCbc;
import it.auties.whatsapp.crypto.Hmac;
import it.auties.whatsapp.crypto.LTHash;
import it.auties.whatsapp.exception.HmacValidationException;
import it.auties.whatsapp.model.action.*;
import it.auties.whatsapp.model.chat.Chat;
import it.auties.whatsapp.model.chat.ChatMute;
import it.auties.whatsapp.model.contact.Contact;
import it.auties.whatsapp.model.contact.ContactJid;
import it.auties.whatsapp.model.exchange.Attributes;
import it.auties.whatsapp.model.exchange.Node;
import it.auties.whatsapp.model.info.MessageIndexInfo;
import it.auties.whatsapp.model.info.MessageInfo;
import it.auties.whatsapp.model.setting.EphemeralSetting;
import it.auties.whatsapp.model.setting.LocaleSetting;
import it.auties.whatsapp.model.setting.PushNameSetting;
import it.auties.whatsapp.model.setting.UnarchiveChatsSetting;
import it.auties.whatsapp.model.sync.*;
import it.auties.whatsapp.model.sync.PatchRequest.PatchEntry;
import it.auties.whatsapp.util.*;
import lombok.NonNull;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static it.auties.whatsapp.api.ErrorHandler.Location.*;
import static java.lang.System.Logger.Level.WARNING;

class AppStateHandler {
    private static final int TIMEOUT = 120;
    private static final int PULL_ATTEMPTS = 3;

    private final SocketHandler socketHandler;
    private final Map<BinaryPatchType, Integer> attempts;
    private ExecutorService executor;

    protected AppStateHandler(SocketHandler socketHandler) {
        this.socketHandler = socketHandler;
        this.attempts = new HashMap<>();
    }

    private synchronized ExecutorService getOrCreateAppService(){
        if(executor == null || executor.isShutdown()){
            executor = Executors.newSingleThreadExecutor();
        }

        return executor;
    }

    protected CompletableFuture<Void> push(@NonNull ContactJid jid, @NonNull List<PatchRequest> patches) {
        return runPushTask(() -> {
            var clientType = socketHandler.store().clientType();
            var pullOperation = switch (clientType){
                case MOBILE -> CompletableFuture.completedFuture(null);
                case WEB -> pullUninterruptedly(jid, getPatchesTypes(patches));
            };
            return pullOperation.thenComposeAsync(ignored -> sendPush(jid, patches, clientType != ClientType.MOBILE))
                    .orTimeout(TIMEOUT, TimeUnit.SECONDS)
                    .exceptionallyAsync(throwable -> socketHandler.handleFailure(PUSH_APP_STATE, throwable));
        });
    }

    private Set<BinaryPatchType> getPatchesTypes(List<PatchRequest> patches) {
        return patches.stream()
                .map(PatchRequest::type)
                .collect(Collectors.toUnmodifiableSet());
    }

    private CompletableFuture<Void> runPushTask(Supplier<CompletableFuture<?>> task){
        var executor = getOrCreateAppService();
        var future = new CompletableFuture<Void>();
        executor.execute(() -> {
            try {
                task.get().join();
                future.complete(null);
            }catch (Throwable throwable){
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    private CompletableFuture<Void> sendPush(ContactJid jid, List<PatchRequest> patches, boolean readPatches) {
        var requests = patches.stream()
                .map(entry -> createPushRequest(jid, entry))
                .toList();
        var mobile = socketHandler.store().clientType() == ClientType.MOBILE;
        var body = requests.stream()
                .map(request -> createPushRequestNode(request, mobile))
                .toList();
        var syncAttributes = Attributes.of()
                .put("data_namespace", 3, mobile)
                .toMap();
        var sync = Node.of("sync", syncAttributes, body);
        return socketHandler.sendQuery("set", "w:sync:app:state", sync)
                .thenRunAsync(() -> onPush(jid, requests, readPatches));
    }

    private PushRequest createPushRequest(ContactJid jid, PatchRequest request) {
        var oldState = socketHandler.keys()
                .findHashStateByName(jid, request.type())
                .orElseGet(() -> new LTHashState(request.type()));
        var newState = oldState.copy();
        var key = socketHandler.keys().getLatestAppKey(jid);
        var mutationKeys = MutationKeys.of(key.keyData().keyData());
        var syncId = new KeyId(key.keyId().keyId());
        var mutations = request.entries()
                .stream()
                .map(patch -> createMutationSync(patch, mutationKeys, key, syncId))
                .toList();
        var newStateGenerator = new LTHash(newState);
        mutations.forEach(mutation -> newStateGenerator.mix(mutation.indexMac(), mutation.valueMac(), mutation.operation()));
        var result = newStateGenerator.finish();
        newState.hash(result.hash());
        newState.indexValueMap(result.indexValueMap());
        newState.version(newState.version() + 1);
        var snapshotMac = generateSnapshotMac(newState.hash(), newState.version(), request.type(), mutationKeys.snapshotMacKey());
        var concatValueMac = mutations.stream()
                .map(MutationResult::valueMac)
                .toArray(byte[][]::new);
        var patchMac = generatePatchMac(snapshotMac, concatValueMac, newState.version(), request.type(), mutationKeys.patchMacKey());
        var syncs = mutations.stream()
                .map(MutationResult::sync)
                .toList();
        var sync = PatchSync.builder()
                .patchMac(patchMac)
                .snapshotMac(snapshotMac)
                .keyId(syncId)
                .mutations(syncs)
                .build();
        return new PushRequest(request.type(), oldState, newState, sync);
    }

    private MutationResult createMutationSync(PatchEntry patch, MutationKeys mutationKeys, AppStateSyncKey key, KeyId syncId) {
        var index = patch.index().getBytes(StandardCharsets.UTF_8);
        var actionData = ActionDataSync.builder()
                .index(index)
                .value(patch.sync())
                .padding(new byte[0])
                .version(patch.version())
                .build();
        var encoded = Protobuf.writeMessage(actionData);
        var encrypted = AesCbc.encryptAndPrefix(encoded, mutationKeys.encKey());
        var valueMac = generateMac(patch.operation(), encrypted, key.keyId().keyId(), mutationKeys.macKey());
        var indexMac = Hmac.calculateSha256(index, mutationKeys.indexKey());
        var record = RecordSync.builder()
                .index(new IndexSync(indexMac))
                .value(new ValueSync(BytesHelper.concat(encrypted, valueMac)))
                .keyId(syncId)
                .build();
        var sync = MutationSync.builder()
                .operation(patch.operation())
                .record(record)
                .build();
        return new MutationResult(sync, indexMac, valueMac, patch.operation());
    }

    private Node createPushRequestNode(PushRequest request, boolean mobile) {
        var version = request.oldState().version();
        var collectionAttributes = Attributes.of()
                .put("name", request.type())
                .put("version", version, !mobile)
                .put("return_snapshot", false, !mobile)
                .put("order", request.type() != BinaryPatchType.CRITICAL_UNBLOCK_LOW ? "1" : "0", mobile)
                .toMap();
        return Node.of("collection", collectionAttributes,
                Node.of("patch", Protobuf.writeMessage(request.sync())));
    }

    private void onPush(ContactJid jid, List<PushRequest> requests, boolean readPatches) {
        requests.forEach(request -> {
            socketHandler.keys().putState(jid, request.newState());
            if (!readPatches) {
                return;
            }

            var patches = List.of(request.sync().withVersion(new VersionSync(request.newState().version())));
            var results = decodePatches(jid, request.type(), patches, request.oldState());
            results.records().forEach(this::processActions);
        });
    }

    protected void pull(BinaryPatchType... patchTypes) {
        if (patchTypes == null || patchTypes.length == 0) {
            return;
        }

        getOrCreateAppService().execute(() -> pullUninterruptedly(socketHandler.store().jid(), Set.of(patchTypes))
                .thenAcceptAsync(success -> onPull(false, success))
                .exceptionallyAsync(exception -> onPullError(false, exception)));
    }

    protected CompletableFuture<Void> pullInitial() {
        if(socketHandler.keys().initialAppSync()){
            return CompletableFuture.completedFuture(null);
        }

        return pullUninterruptedly(socketHandler.store().jid(), Set.of(BinaryPatchType.values()))
                .thenAcceptAsync(success -> onPull(true, success))
                .exceptionallyAsync(exception -> onPullError(true, exception));
    }

    private void onPull(boolean initial, boolean success) {
        if (!socketHandler.keys().initialAppSync()) {
            socketHandler.keys().initialAppSync((initial && success) || isSyncComplete());
        }

        attempts.clear();
    }

    private boolean isSyncComplete() {
        return Arrays.stream(BinaryPatchType.values())
                .allMatch(this::isSyncComplete);
    }

    private boolean isSyncComplete(BinaryPatchType entry) {
        return socketHandler.keys()
                .findHashStateByName(socketHandler.store().jid(), entry) // FIXME: Is this right?
                .filter(type -> type.version() > 0)
                .isPresent();
    }

    private Void onPullError(boolean initial, Throwable exception) {
        attempts.clear();
        if (initial) {
            return socketHandler.handleFailure(INITIAL_APP_STATE_SYNC, exception);
        }
        return socketHandler.handleFailure(PULL_APP_STATE, exception);
    }

    private CompletableFuture<Boolean> pullUninterruptedly(ContactJid jid, Set<BinaryPatchType> patchTypes) {
        var tempStates = new HashMap<BinaryPatchType, LTHashState>();
        var nodes = getPullNodes(jid, patchTypes, tempStates);
        return socketHandler.sendQuery("set", "w:sync:app:state", Node.of("sync", nodes))
                .thenApplyAsync(this::parseSyncRequest)
                .thenApplyAsync(records -> decodeSyncs(jid, tempStates, records))
                .thenComposeAsync(remaining -> handlePullResult(jid, remaining))
                .orTimeout(TIMEOUT, TimeUnit.SECONDS);
    }

    private CompletableFuture<Boolean> handlePullResult(ContactJid jid, Set<BinaryPatchType> remaining) {
        return remaining.isEmpty() ? CompletableFuture.completedFuture(true) : pullUninterruptedly(jid, remaining);
    }

    private List<Node> getPullNodes(ContactJid jid, Set<BinaryPatchType> patchTypes, Map<BinaryPatchType, LTHashState> tempStates) {
        return patchTypes.stream()
                .map(name -> createStateWithVersion(jid, name))
                .peek(state -> tempStates.put(state.name(), state))
                .map(LTHashState::toNode)
                .toList();
    }

    private LTHashState createStateWithVersion(ContactJid jid, BinaryPatchType name) {
        return socketHandler.keys()
                .findHashStateByName(jid, name)
                .orElseGet(() -> new LTHashState(name));
    }

    private Set<BinaryPatchType> decodeSyncs(ContactJid jid, Map<BinaryPatchType, LTHashState> tempStates, List<SnapshotSyncRecord> records) {
        return records.stream()
                .map(record -> {
                    var chunk = decodeSync(jid, record, tempStates);
                    chunk.records().forEach(this::processActions);
                    return chunk;
                })
                .filter(PatchChunk::hasMore)
                .map(PatchChunk::patchType)
                .collect(Collectors.toUnmodifiableSet());
    }

    private PatchChunk decodeSync(ContactJid jid, SnapshotSyncRecord record, Map<BinaryPatchType, LTHashState> tempStates) {
        try {
            var results = new ArrayList<ActionDataSync>();
            if (record.hasSnapshot()) {
                var snapshot = decodeSnapshot(jid, record.patchType(), record.snapshot());
                snapshot.ifPresent(decodedSnapshot -> {
                    results.addAll(decodedSnapshot.records());
                    tempStates.put(record.patchType(), decodedSnapshot.state());
                    socketHandler.keys().putState(jid, decodedSnapshot.state());
                });
            }
            if (record.hasPatches()) {
                var decodedPatches = decodePatches(jid, record.patchType(), record.patches(), tempStates.get(record.patchType()));
                results.addAll(decodedPatches.records());
                socketHandler.keys().putState(jid, decodedPatches.state());
            }
            return new PatchChunk(record.patchType(), results, record.hasMore());
        } catch (Throwable throwable) {
            var hashState = new LTHashState(record.patchType());
            socketHandler.keys().putState(jid, hashState);
            attempts.put(record.patchType(), attempts.getOrDefault(record.patchType(), 0) + 1);
            if (attempts.get(record.patchType()) >= PULL_ATTEMPTS) {
                throw new RuntimeException("Cannot parse patch(%s tries)".formatted(PULL_ATTEMPTS), throwable);
            }
            return decodeSync(jid, record, tempStates);
        }
    }

    private List<SnapshotSyncRecord> parseSyncRequest(Node node) {
        return Stream.ofNullable(node)
                .map(sync -> sync.findNodes("sync"))
                .flatMap(Collection::stream)
                .map(sync -> sync.findNodes("collection"))
                .flatMap(Collection::stream)
                .map(this::parseSync)
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<SnapshotSyncRecord> parseSync(Node sync) {
        var name = BinaryPatchType.of(sync.attributes().getString("name"));
        var type = sync.attributes().getString("type");
        if (Objects.equals(type, "error")) {
            return Optional.empty();
        }
        var more = sync.attributes().getBoolean("has_more_patches");
        var snapshotSync = sync.findNode("snapshot")
                .flatMap(this::decodeSnapshot)
                .orElse(null);
        var versionCode = sync.attributes().getInt("version");
        var patches = sync.findNode("patches")
                .orElse(sync)
                .findNodes("patch")
                .stream()
                .map(patch -> decodePatch(patch, versionCode))
                .flatMap(Optional::stream)
                .toList();
        return Optional.of(new SnapshotSyncRecord(name, snapshotSync, patches, more));
    }

    private Optional<SnapshotSync> decodeSnapshot(Node snapshot) {
        return snapshot == null ? Optional.empty() : snapshot.contentAsBytes()
                .map(bytes -> Protobuf.readMessage(bytes, ExternalBlobReference.class))
                .map(Medias::download)
                .flatMap(CompletableFuture::join)
                .map(value -> Protobuf.readMessage(value, SnapshotSync.class));
    }

    private Optional<PatchSync> decodePatch(Node patch, long versionCode) {
        if (!patch.hasContent()) {
            return Optional.empty();
        }
        var patchSync = Protobuf.readMessage(patch.contentAsBytes().orElseThrow(), PatchSync.class);
        if (!patchSync.hasVersion()) {
            var version = new VersionSync(versionCode + 1);
            patchSync.version(version);
        }
        return Optional.of(patchSync);
    }

    private void processActions(ActionDataSync mutation) {
        var value = mutation.value();
        if (value == null) {
            return;
        }
        var action = value.action();
        if (action != null) {
            var messageIndex = mutation.messageIndex();
            var targetContact = messageIndex.chatJid().flatMap(socketHandler.store()::findContactByJid);
            var targetChat = messageIndex.chatJid().flatMap(socketHandler.store()::findChatByJid);
            var targetMessage = targetChat.flatMap(chat -> socketHandler.store()
                    .findMessageById(chat, mutation.messageIndex().messageId().orElse(null)));
            if (action instanceof ClearChatAction clearChatAction) {
                clearMessages(targetChat.orElse(null), clearChatAction);
            } else if (action instanceof ContactAction contactAction) {
                updateName(targetContact.orElseGet(() -> createContact(messageIndex)), targetChat.orElseGet(() -> createChat(messageIndex)), contactAction);
            } else if (action instanceof DeleteChatAction) {
                targetChat.ifPresent(Chat::removeMessages);
            } else if (action instanceof DeleteMessageForMeAction) {
                targetMessage.ifPresent(message -> targetChat.ifPresent(chat -> deleteMessage(message, chat)));
            } else if (action instanceof MarkChatAsReadAction markAction) {
                targetChat.ifPresent(chat -> chat.unreadMessagesCount(markAction.read() ? 0 : -1));
            } else if (action instanceof MuteAction muteAction) {
                targetChat.ifPresent(chat -> chat.mute(ChatMute.muted(muteAction.muteEndTimestampSeconds())));
            } else if (action instanceof PinAction pinAction) {
                targetChat.ifPresent(chat -> chat.pinnedTimestampSeconds(pinAction.pinned() ? (int) mutation.value()
                        .timestamp() : 0));
            } else if (action instanceof StarAction starAction) {
                targetMessage.ifPresent(message -> message.starred(starAction.starred()));
            } else if (action instanceof ArchiveChatAction archiveChatAction) {
                targetChat.ifPresent(chat -> chat.archived(archiveChatAction.archived()));
            } else if (action instanceof TimeFormatAction timeFormatAction) {
                socketHandler.store().twentyFourHourFormat(timeFormatAction.twentyFourHourFormatEnabled());
            }
            socketHandler.onAction(action, messageIndex);
        }
        var setting = value.setting();
        if (setting != null) {
            if (setting instanceof EphemeralSetting ephemeralSetting) {
                showEphemeralMessageWarning(ephemeralSetting);
            } else if (setting instanceof LocaleSetting localeSetting) {
                socketHandler.updateLocale(localeSetting.locale(), socketHandler.store().locale());
            } else if (setting instanceof PushNameSetting pushNameSetting) {
                socketHandler.updateUserName(pushNameSetting.name(), socketHandler.store().name());
            } else if (setting instanceof UnarchiveChatsSetting unarchiveChatsSetting) {
                socketHandler.store().unarchiveChats(unarchiveChatsSetting.unarchiveChats());
            }
            socketHandler.onSetting(setting);
        }
        var features = mutation.value().primaryFeature();
        if (features.isPresent() && !features.get().flags().isEmpty()) {
            socketHandler.onFeatures(features.get());
        }
    }

    private Chat createChat(MessageIndexInfo messageIndex) {
        var chat = messageIndex.chatJid().orElseThrow();
        return socketHandler.store().addNewChat(chat);
    }

    private Contact createContact(MessageIndexInfo messageIndex) {
        var chatJid = messageIndex.chatJid().orElseThrow();
        var contact = socketHandler.store().addContact(chatJid);
        socketHandler.onNewContact(contact);
        return contact;
    }

    private void showEphemeralMessageWarning(EphemeralSetting ephemeralSetting) {
        var logger = System.getLogger("AppStateHandler");
        logger.log(WARNING, "An ephemeral status update was received as a setting. " + "Data: %s".formatted(ephemeralSetting) + "This should not be possible." + " Open an issue on Github please");
    }

    private void clearMessages(Chat targetChat, ClearChatAction clearChatAction) {
        if (targetChat == null) {
            return;
        }

        if (clearChatAction.messageRange().isEmpty()) {
            targetChat.removeMessages();
            return;
        }

        clearChatAction.messageRange()
                .stream()
                .map(ActionMessageRangeSync::messages)
                .flatMap(Collection::stream)
                .map(SyncActionMessage::key)
                .filter(Objects::nonNull)
                .forEach(key -> targetChat.removeMessage(entry -> Objects.equals(entry.id(), key.id())));
    }

    private void updateName(Contact contact, Chat chat, ContactAction contactAction) {
        contactAction.fullName().ifPresent(contact::fullName);
        contactAction.firstName().ifPresent(contact::shortName);
        chat.name(contactAction.name());
    }

    private void deleteMessage(MessageInfo message, Chat chat) {
        chat.removeMessage(message);
        socketHandler.onMessageDeleted(message, false);
    }

    private SyncRecord decodePatches(ContactJid jid, BinaryPatchType name, List<PatchSync> patches, LTHashState state) {
        var newState = state.copy();
        var results = patches.stream()
                .map(patch -> decodePatch(jid, name, newState, patch))
                .map(MutationsRecord::records)
                .flatMap(Collection::stream)
                .toList();
        return new SyncRecord(newState, results);
    }

    private MutationsRecord decodePatch(ContactJid jid, BinaryPatchType patchType, LTHashState newState, PatchSync patch) {
        if (patch.hasExternalMutations()) {
            Medias.download(patch.externalMutations())
                    .join()
                    .ifPresent(blob -> handleExternalMutation(patch, blob));
        }

        newState.version(patch.encodedVersion());
        var syncMac = calculatePatchMac(jid, patch, patchType);
        Validate.isTrue(!socketHandler.store().checkPatchMacs() || syncMac.isEmpty() || Arrays.equals(syncMac.get(), patch.patchMac()), "sync_mac", HmacValidationException.class);
        var mutations = decodeMutations(jid, patch.mutations(), newState);
        newState.hash(mutations.result().hash());
        newState.indexValueMap(mutations.result().indexValueMap());
        var snapshotMac = calculateSnapshotMac(jid, patchType, newState, patch);
        Validate.isTrue(!socketHandler.store().checkPatchMacs() || snapshotMac.isEmpty() || Arrays.equals(snapshotMac.get(), patch.snapshotMac()), "patch_mac", HmacValidationException.class);
        return mutations;
    }

    private void handleExternalMutation(PatchSync patch, byte[] blob) {
        var mutationsSync = Protobuf.readMessage(blob, MutationsSync.class);
        patch.mutations().addAll(mutationsSync.mutations());
    }

    private Optional<byte[]> calculateSnapshotMac(ContactJid jid, BinaryPatchType name, LTHashState newState, PatchSync patch) {
        return getMutationKeys(jid, patch.keyId())
                .map(mutationKeys -> generateSnapshotMac(newState.hash(), newState.version(), name, mutationKeys.snapshotMacKey()));
    }

    private Optional<byte[]> calculatePatchMac(ContactJid jid, PatchSync patch, BinaryPatchType patchType) {
        return getMutationKeys(jid, patch.keyId())
                .map(mutationKeys -> generatePatchMac(patch.snapshotMac(), getSyncMutationMac(patch), patch.encodedVersion(), patchType, mutationKeys.patchMacKey()));
    }

    private byte[][] getSyncMutationMac(PatchSync patch) {
        return patch.mutations()
                .stream()
                .map(mutation -> mutation.record().value().blob())
                .map(entry -> Arrays.copyOfRange(entry, entry.length - Spec.Signal.KEY_LENGTH, entry.length))
                .toArray(byte[][]::new);
    }

    private Optional<SyncRecord> decodeSnapshot(ContactJid jid, BinaryPatchType name, SnapshotSync snapshot) {
        var mutationKeys = getMutationKeys(jid, snapshot.keyId());
        if (mutationKeys.isEmpty()) {
            return Optional.empty();
        }
        var newState = new LTHashState(name, snapshot.version().version());
        var mutations = decodeMutations(jid, snapshot.records(), newState);
        newState.hash(mutations.result().hash());
        newState.indexValueMap(mutations.result().indexValueMap());
        Validate.isTrue(!socketHandler.store().checkPatchMacs() || Arrays.equals(snapshot.mac(), generateSnapshotMac(newState.hash(), newState.version(), name, mutationKeys.get()
                .snapshotMacKey())), "decode_snapshot", HmacValidationException.class);
        return Optional.of(new SyncRecord(newState, mutations.records()));
    }

    private Optional<MutationKeys> getMutationKeys(ContactJid jid, KeyId snapshot) {
        return socketHandler.keys()
                .findAppKeyById(jid, snapshot.id())
                .map(AppStateSyncKey::keyData)
                .map(AppStateSyncKeyData::keyData)
                .map(MutationKeys::of);
    }

    private MutationsRecord decodeMutations(ContactJid jid, List<? extends Syncable> syncs, LTHashState state) {
        var generator = new LTHash(state);
        var mutations = syncs.stream()
                .map(mutation -> decodeMutation(jid, mutation.operation(), mutation.record(), generator))
                .flatMap(Optional::stream)
                .collect(Collectors.toList());
        return new MutationsRecord(generator.finish(), mutations);
    }

    private Optional<ActionDataSync> decodeMutation(ContactJid jid, RecordSync.Operation operation, RecordSync sync, LTHash generator) {
        var mutationKeys = getMutationKeys(jid, sync.keyId());
        if (mutationKeys.isEmpty()) {
            return Optional.empty();
        }
        var blob = sync.value().blob();
        var encryptedBlob = Arrays.copyOfRange(blob, 0, blob.length - Spec.Signal.KEY_LENGTH);
        var encryptedMac = Arrays.copyOfRange(blob, blob.length - Spec.Signal.KEY_LENGTH, blob.length);
        Validate.isTrue(!socketHandler.store().checkPatchMacs() || Arrays.equals(encryptedMac, generateMac(operation, encryptedBlob, sync.keyId().id(), mutationKeys.get().macKey())),
                "decode_mutation", HmacValidationException.class);
        var result = AesCbc.decrypt(encryptedBlob, mutationKeys.get().encKey());
        var actionSync = Protobuf.readMessage(result, ActionDataSync.class);
        Validate.isTrue(!socketHandler.store().checkPatchMacs() || Arrays.equals(sync.index().blob(), Hmac.calculateSha256(actionSync.index(), mutationKeys.get()
                .indexKey())), "decode_mutation", HmacValidationException.class);
        generator.mix(sync.index().blob(), encryptedMac, operation);
        return Optional.of(actionSync);
    }

    private byte[] generateMac(RecordSync.Operation operation, byte[] data, byte[] keyId, byte[] key) {
        var keyData = BytesHelper.concat(operation.content(), keyId);
        var last = new byte[Spec.Signal.MAC_LENGTH];
        last[last.length - 1] = (byte) keyData.length;
        var total = BytesHelper.concat(keyData, data, last);
        var sha512 = Hmac.calculateSha512(total, key);
        return Arrays.copyOfRange(sha512, 0, Spec.Signal.KEY_LENGTH);
    }

    private byte[] generateSnapshotMac(byte[] ltHash, long version, BinaryPatchType patchType, byte[] key) {
        var total = BytesHelper.concat(
                ltHash,
                BytesHelper.longToBytes(version),
                patchType.toString().getBytes(StandardCharsets.UTF_8)
        );
        return Hmac.calculateSha256(total, key);
    }

    private byte[] generatePatchMac(byte[] snapshotMac, byte[][] valueMac, long version, BinaryPatchType patchType, byte[] key) {
        var total = BytesHelper.concat(
                snapshotMac,
                BytesHelper.concat(valueMac),
                BytesHelper.longToBytes(version),
                patchType.toString().getBytes(StandardCharsets.UTF_8)
        );
        return Hmac.calculateSha256(total, key);
    }

    protected void dispose() {
        attempts.clear();
        if(executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

    private record SyncRecord(LTHashState state, List<ActionDataSync> records) {

    }

    private record SnapshotSyncRecord(BinaryPatchType patchType, SnapshotSync snapshot, List<PatchSync> patches,
                                      boolean hasMore) {
        public boolean hasSnapshot() {
            return snapshot != null;
        }

        public boolean hasPatches() {
            return patches != null && !patches.isEmpty();
        }
    }

    private record MutationsRecord(LTHash.Result result, List<ActionDataSync> records) {

    }

    private record PatchChunk(BinaryPatchType patchType, List<ActionDataSync> records, boolean hasMore) {

    }

    private record PushRequest(BinaryPatchType type, LTHashState oldState, LTHashState newState, PatchSync sync) {

    }

    public record MutationResult(MutationSync sync, byte[] indexMac, byte[] valueMac, RecordSync.Operation operation) {

    }
}
