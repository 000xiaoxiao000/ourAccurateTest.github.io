package com.oAT.web.common;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.util.StdDateFormat;
import org.springframework.util.ObjectUtils;

import java.io.IOException;
import java.text.ParseException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

@SuppressWarnings("unused")
public class UtilJson {

    public static final ObjectMapper JSON_MAPPER = newObjectMapper();

    private static ObjectMapper newObjectMapper() {
        ObjectMapper result = new ObjectMapper();
        result.configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);
        result.configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);
        result.setSerializationInclusion(Include.NON_NULL);
        result.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        SimpleModule dateModule = new SimpleModule();
        dateModule.addDeserializer(Date.class, new CompatibleDateDeserializer());
        result.registerModule(dateModule);

        return result;
    }

    private static class CompatibleDateDeserializer extends JsonDeserializer<Date> {
        private static final List<String> DATE_FORMATS = List.of(
                "yyyy-MM-dd HH:mm:ss,SSS",
                "yyyy-MM-dd HH:mm:ss.SSS",
                "yyyy-MM-dd HH:mm:ss"
        );

        @Override
        public Date deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonToken token = parser.currentToken();
            if (token == JsonToken.VALUE_NUMBER_INT) {
                return new Date(parser.getLongValue());
            }
            if (token != JsonToken.VALUE_STRING) {
                return (Date) context.handleUnexpectedToken(Date.class, parser);
            }
            String value = parser.getText().trim();
            if (value.isEmpty()) {
                return null;
            }
            if (value.matches("^-?\\d+$")) {
                return new Date(Long.parseLong(value));
            }
            for (String format : DATE_FORMATS) {
                Date parsed = parse(value, format);
                if (parsed != null) {
                    return parsed;
                }
            }
            try {
                return new StdDateFormat().parse(value);
            } catch (ParseException e) {
                throw InvalidFormatException.from(parser, "Cannot deserialize Date from value '" + value + "'", value, Date.class);
            }
        }

        private Date parse(String value, String format) {
            SimpleDateFormat dateFormat = new SimpleDateFormat(format);
            dateFormat.setLenient(false);
            ParsePosition position = new ParsePosition(0);
            Date parsed = dateFormat.parse(value, position);
            return parsed != null && position.getIndex() == value.length() ? parsed : null;
        }
    }

    public static ObjectMapper getObjectMapper() {
        return JSON_MAPPER;
    }

    public static String writeValueAsString(Object value) {
        try {
            return value == null ? null : JSON_MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalArgumentException(e); // TIP: 原则上，不对异常包装，这里为什么要包装？因为正常情况不会发生IOException
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(Object value) throws IllegalArgumentException {
        return convertValue(value, Map.class);
    }

    public static <T> T convertValue(Object value, Class<T> clazz) throws IllegalArgumentException {
        if (ObjectUtils.isEmpty(value)) {return null;}
        try {
            if (value instanceof String)
            {value = JSON_MAPPER.readTree((String) value);}
            return JSON_MAPPER.convertValue(value, clazz);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

}
