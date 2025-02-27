package it.auties.whatsapp.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.io.UncheckedIOException;

import static com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY;
import static com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE;
import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT;
import static com.fasterxml.jackson.annotation.PropertyAccessor.*;
import static com.fasterxml.jackson.databind.DeserializationFeature.*;
import static com.fasterxml.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS;
import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_ENUMS_USING_INDEX;
import static java.lang.System.Logger.Level.ERROR;

@UtilityClass
public class Json {
    private final ObjectMapper json;
    static {
        try {
            json = new ObjectMapper().registerModule(new Jdk8Module())
                    .registerModule(new JavaTimeModule())
                    .setSerializationInclusion(NON_DEFAULT)
                    .enable(FAIL_ON_EMPTY_BEANS)
                    .enable(WRITE_ENUMS_USING_INDEX)
                    .enable(READ_ENUMS_USING_TO_STRING)
                    .enable(ACCEPT_SINGLE_VALUE_AS_ARRAY)
                    .disable(FAIL_ON_UNKNOWN_PROPERTIES)
                    .setVisibility(ALL, ANY)
                    .setVisibility(GETTER, NONE)
                    .setVisibility(IS_GETTER, NONE);
        } catch (Throwable throwable) {
            var logger = System.getLogger("Json");
            logger.log(ERROR, "An exception occurred while initializing json", throwable);
            throw new RuntimeException("Cannot initialize json", throwable);
        }
    }

    public byte[] writeValueAsBytes(Object object){
        try {
            return json.writer().writeValueAsBytes(object);
        }catch (IOException exception){
            throw new UncheckedIOException("Cannot write json", exception);
        }
    }

    public String writeValueAsString(Object object){
        return writeValueAsString(object, false);
    }

    public String writeValueAsString(Object object, boolean pretty){
        try {
            var writer = pretty ? json.writerWithDefaultPrettyPrinter() : json.writer();
            return writer.writeValueAsString(object);
        }catch (IOException exception){
            throw new UncheckedIOException("Cannot write json", exception);
        }
    }

    public <T> T readValue(String value, Class<T> clazz){
        try {
            return json.readValue(value, clazz);
        }catch (IOException exception){
            throw new UncheckedIOException("Cannot read json", exception);
        }
    }

    public <T> T readValue(String value, TypeReference<T> clazz){
        try {
            return json.readValue(value, clazz);
        }catch (IOException exception){
            throw new UncheckedIOException("Cannot read json", exception);
        }
    }
}
