package it.auties.whatsapp.model.sync;

import it.auties.whatsapp.binary.BinaryPatchType;
import it.auties.whatsapp.model.exchange.Attributes;
import it.auties.whatsapp.model.exchange.Node;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static it.auties.whatsapp.model.exchange.Node.of;

@AllArgsConstructor
@Builder
@Jacksonized
@Data
@Accessors(fluent = true)
public class LTHashState {
    private BinaryPatchType name;

    private long version;

    private byte[] hash;

    private Map<String, byte[]> indexValueMap;

    public LTHashState(BinaryPatchType name) {
        this(name, 0);
    }

    public LTHashState(BinaryPatchType name, long version) {
        this.name = name;
        this.version = version;
        this.hash = new byte[128];
        this.indexValueMap = new HashMap<>();
    }

    public Node toNode() {
        var attributes = Attributes.of()
                .put("name", name)
                .put("version", version)
                .put("return_snapshot", version == 0)
                .toMap();
        return of("collection", attributes);
    }

    public LTHashState copy() {
        return new LTHashState(name, version, Arrays.copyOf(hash, hash.length), new HashMap<>(indexValueMap));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LTHashState that
                && this.version == that.version()
                && this.name == that.name()
                && Arrays.equals(this.hash, that.hash()) && checkIndexEquality(that);
    }

    @Override
    public int hashCode() {
        var result = Objects.hash(name, version, indexValueMap);
        result = 31 * result + Arrays.hashCode(hash);
        return result;
    }

    private boolean checkIndexEquality(LTHashState that) {
        if (indexValueMap.size() != that.indexValueMap().size()) {
            return false;
        }
        return indexValueMap().entrySet()
                .stream()
                .allMatch(entry -> checkIndexEntryEquality(that, entry.getKey(), entry.getValue()));
    }

    private static boolean checkIndexEntryEquality(LTHashState that, String thisKey, byte[] thisValue) {
        var thatValue = that.indexValueMap().get(thisKey);
        return thatValue != null && Arrays.equals(thatValue, thisValue);
    }
}