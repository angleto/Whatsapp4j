package it.auties.whatsapp.github;

import com.goterl.lazysodium.LazySodiumJava;
import com.goterl.lazysodium.SodiumJava;
import com.goterl.lazysodium.utils.LibraryLoader;
import it.auties.whatsapp.api.Whatsapp;
import it.auties.whatsapp.util.Json;
import it.auties.whatsapp.utils.ConfigUtils;
import it.auties.whatsapp.util.Smile;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.examples.ByteArrayHandler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.Security;
import java.util.Base64;
import java.util.Objects;

import static java.net.URI.create;
import static java.net.http.HttpRequest.BodyPublishers.ofString;

@UtilityClass
public class GithubSecrets {
    private final LazySodiumJava SODIUM = new LazySodiumJava(new SodiumJava(LibraryLoader.Mode.BUNDLED_ONLY));
    private final String REQUEST_PATH = "https://api.github.com/repos/Auties00/WhatsappWeb4j";
    private final String PUBLIC_KEY_PATH = "actions/secrets/public-key";
    private final String UPDATE_SECRET_PATH = "actions/secrets/%s";
    private final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private final GithubKey PUBLIC_KEY = getPublicKey();

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    public void updateCredentials() throws IOException {
        updatePassword();
        updateKeys();
        updateStore();
        updateContactName();
    }

    private void updateContactName() throws IOException {
        var contactName = loadContactName();
        uploadSecret(GithubActions.CONTACT_NAME, contactName);
    }

    private void updatePassword() throws IOException {
        var password = loadGpgPassword();
        uploadSecret(GithubActions.GPG_PASSWORD, password);
    }

    private void updateStore() {
        var store = getStoreAsJson();
        writeGpgSecret(GithubActions.STORE_NAME, store);
    }

    private void updateKeys() {
        var credentials = getCredentialsAsJson();
        writeGpgSecret(GithubActions.CREDENTIALS_NAME, credentials);
    }

    private String encrypt(byte[] data) {
        var publicKeyBytes = Base64.getDecoder().decode(PUBLIC_KEY.key());
        var cypher = new byte[data.length + 48];
        var result = SODIUM.cryptoBoxSeal(cypher, data, data.length, publicKeyBytes);
        if (!result) {
            throw new IllegalStateException("crypto_box_seal failed");
        }
        return Base64.getEncoder().encodeToString(cypher);
    }

    @SneakyThrows
    private GithubKey getPublicKey() {
        var request = createPublicKeyRequest();
        var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        var result = Json.readValue(response.body(), GithubKey.class);
        if (result.message() != null) {
            throw new IllegalArgumentException("Cannot get public key: %s".formatted(response.body()));
        }
        return result;
    }

    private HttpRequest createPublicKeyRequest() throws IOException {
        return HttpRequest.newBuilder()
                .GET()
                .uri(create("%s/%s".formatted(REQUEST_PATH, PUBLIC_KEY_PATH)))
                .header("Authorization", "Bearer %s".formatted(loadGithubToken()))
                .header("Accept", "application/vnd.github.v3+json")
                .build();
    }

    private String loadGithubToken() throws IOException {
        var config = ConfigUtils.loadConfiguration();
        return Objects.requireNonNull(config.getProperty("github_token"), "Missing github_token in configuration");
    }

    @SneakyThrows
    private void writeGpgSecret(String name, byte[] value) {
        var gpgCypheredCredentials = ByteArrayHandler.encrypt(value, loadGpgPassword().toCharArray(), name, SymmetricKeyAlgorithmTags.AES_256, true);
        var path = Path.of("ci/%s.gpg".formatted(name));
        Files.write(path, gpgCypheredCredentials, StandardOpenOption.CREATE);
    }

    @SneakyThrows
    private void uploadSecret(String key, String value) {
        var encrypted = encrypt(value.getBytes(StandardCharsets.UTF_8));
        var upload = new GithubUpload(PUBLIC_KEY.keyId(), encrypted);
        var request = HttpRequest.newBuilder()
                .PUT(ofString(Json.writeValueAsString(upload)))
                .uri(create("%s/%s".formatted(REQUEST_PATH, UPDATE_SECRET_PATH.formatted(key))))
                .header("Accept", "application/vnd.github.v3+json")
                .header("Authorization", "Bearer %s".formatted(loadGithubToken()))
                .build();
        var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 201 && response.statusCode() != 204) {
            throw new IllegalStateException("Cannot update %s: %s(status code %s)".formatted(key, response.body(), response.statusCode()));
        }
        System.out.printf("Sent %s(status code %s)%n", key, response.statusCode());
    }

    private String loadGpgPassword() throws IOException {
        var config = ConfigUtils.loadConfiguration();
        return Objects.requireNonNull(config.getProperty("gpg_password"), "Missing github_password in configuration");
    }

    private String loadContactName() throws IOException {
        var config = ConfigUtils.loadConfiguration();
        return Objects.requireNonNull(config.getProperty("contact"), "Missing github_token in configuration");
    }

    private byte[] getStoreAsJson() {
        try {
            return Smile.writeValueAsBytes(Whatsapp.webBuilder().lastConnection().registered().orElseThrow().store());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private byte[] getCredentialsAsJson() {
        try {
            return Smile.writeValueAsBytes(Whatsapp.webBuilder().lastConnection().registered().orElseThrow().keys());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
