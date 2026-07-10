package com.oAT.ai.agent.fallback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class FallbackTypeConverter {
    private static final Logger LOGGER = LoggerFactory.getLogger(FallbackTypeConverter.class);

    private static final List<DateTimeFormatter> LOCAL_DATE_FORMATTERS = Arrays.asList(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyyMMdd")
    );

    private static final List<DateTimeFormatter> LOCAL_DATE_TIME_FORMATTERS = Arrays.asList(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    );

    private static final Set<String> NULL_LIKE_VALUES = new HashSet<>(Arrays.asList(
            "", "null", "none", "nil", "n/a", "na", "undefined", "empty"
    ));

    /**
     * 将参数值转换为目标类型
     */
    public Object convertType(Object value, Class<?> targetType) {
        return convertType(value, targetType, targetType);
    }

    public Object convertType(Object value, Class<?> targetType, Type genericType) {
        if (value == null) {
            return null;
        }
        Object emptyStructureValue = convertEmptyStructureString(value, targetType, genericType);
        if (emptyStructureValue != null) {
            return emptyStructureValue;
        }
        if (isNullLikeValue(value, targetType)) {
            return null;
        }
        if (targetType == Object.class) {
            return value;
        }
        if (Optional.class == targetType) {
            return convertOptional(value, genericType);
        }
        if (targetType.isAssignableFrom(value.getClass())) {
            return value;
        }
        if (targetType == String.class) {
            return value.toString();
        }
        if (targetType == int.class || targetType == Integer.class) {
            return convertInteger(value);
        }
        if (targetType == long.class || targetType == Long.class) {
            return convertLong(value);
        }
        if (targetType == double.class || targetType == Double.class) {
            return convertDouble(value);
        }
        if (targetType == float.class || targetType == Float.class) {
            return convertFloat(value);
        }
        if (targetType == short.class || targetType == Short.class) {
            return convertShort(value);
        }
        if (targetType == byte.class || targetType == Byte.class) {
            return convertByte(value);
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return convertBoolean(value);
        }
        if (targetType == char.class || targetType == Character.class) {
            String str = value.toString();
            return str.isEmpty() ? '\0' : str.charAt(0);
        }
        if (targetType == BigDecimal.class) {
            return value instanceof BigDecimal ? value : new BigDecimal(value.toString());
        }
        if (targetType == BigInteger.class) {
            return value instanceof BigInteger ? value : new BigInteger(value.toString());
        }
        if (targetType == LocalDate.class) {
            return convertToLocalDate(value);
        }
        if (targetType == LocalDateTime.class) {
            return convertToLocalDateTime(value);
        }
        if (targetType == Instant.class) {
            return convertToInstant(value);
        }
        if (targetType == OffsetDateTime.class) {
            return convertToOffsetDateTime(value);
        }
        if (targetType == ZonedDateTime.class) {
            return convertToZonedDateTime(value);
        }
        if (targetType.isEnum()) {
            return convertEnum(value, targetType);
        }
        if (targetType.isArray() && value instanceof List<?> listValue) {
            return convertArray(listValue, targetType.getComponentType());
        }
        if (Collection.class.isAssignableFrom(targetType) && value instanceof List<?> listValue) {
            return convertCollection(listValue, targetType, genericType);
        }
        if (Map.class.isAssignableFrom(targetType) && value instanceof Map<?, ?> mapValue) {
            return convertMap(mapValue, genericType);
        }
        if (value instanceof String stringValue) {
            Object convertedFromString = convertStructuredString(stringValue, targetType, genericType);
            if (convertedFromString != null) {
                return convertedFromString;
            }
            Object splitConverted = convertDelimitedString(stringValue, targetType, genericType);
            if (splitConverted != null) {
                return splitConverted;
            }
        }
        if (value instanceof Map<?, ?> mapValue) {
            return convertBean(mapValue, targetType);
        }
        if (value instanceof List<?> listValue && targetType.isArray()) {
            return convertArray(listValue, targetType.getComponentType());
        }
        if (Number.class.isAssignableFrom(targetType) && value instanceof Number) {
            return value;
        }
        return value;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object convertEnum(Object value, Class<?> targetType) {
        String enumName = value.toString();
        for (Object constant : targetType.getEnumConstants()) {
            if (((Enum) constant).name().equalsIgnoreCase(enumName)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("无法转换枚举值: " + enumName + " -> " + targetType.getSimpleName());
    }

    public Boolean convertBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        String text = value == null ? null : value.toString().trim();
        if (text == null || text.isEmpty()) {
            return null;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        if (Arrays.asList("true", "yes", "y", "on", "1", "enabled", "enable", "ok").contains(normalized)) {
            return Boolean.TRUE;
        }
        if (Arrays.asList("false", "no", "n", "off", "0", "disabled", "disable").contains(normalized)) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("无法解析布尔值: " + text);
    }

    public Integer convertInteger(Object value) {
        return toIntegralNumber(normalizeNumber(value), Integer.MIN_VALUE, Integer.MAX_VALUE, "Integer").intValueExact();
    }

    public Long convertLong(Object value) {
        return toIntegralNumber(normalizeNumber(value), Long.MIN_VALUE, Long.MAX_VALUE, "Long").longValueExact();
    }

    public Double convertDouble(Object value) {
        BigDecimal decimal = normalizeNumber(value);
        double doubleValue = decimal.doubleValue();
        if (Double.isInfinite(doubleValue)) {
            throw new IllegalArgumentException("数值超出 Double 范围: " + decimal);
        }
        return doubleValue;
    }

    public Float convertFloat(Object value) {
        BigDecimal decimal = normalizeNumber(value);
        float floatValue = decimal.floatValue();
        if (Float.isInfinite(floatValue)) {
            throw new IllegalArgumentException("数值超出 Float 范围: " + decimal);
        }
        return floatValue;
    }

    public Short convertShort(Object value) {
        return toIntegralNumber(normalizeNumber(value), Short.MIN_VALUE, Short.MAX_VALUE, "Short").shortValueExact();
    }

    public Byte convertByte(Object value) {
        return toIntegralNumber(normalizeNumber(value), Byte.MIN_VALUE, Byte.MAX_VALUE, "Byte").byteValueExact();
    }

    public BigDecimal toIntegralNumber(BigDecimal decimal, long min, long max, String targetType) {
        try {
            BigDecimal normalized = decimal.stripTrailingZeros();
            if (normalized.scale() > 0) {
                throw new IllegalArgumentException("数值包含非零小数部分，无法转换为 " + targetType + ": " + decimal);
            }
            long value = normalized.longValueExact();
            if (value < min || value > max) {
                throw new IllegalArgumentException("数值超出 " + targetType + " 范围: " + decimal);
            }
            return BigDecimal.valueOf(value);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("数值无法精确转换为 " + targetType + ": " + decimal, e);
        }
    }

    public BigDecimal normalizeNumber(Object value) {
        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal;
        }
        if (value instanceof BigInteger bigInteger) {
            return new BigDecimal(bigInteger);
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        String text = value == null ? null : value.toString().trim();
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("无法解析数字：空值");
        }
        String normalized = text.replaceAll(",", "").replaceAll("_", "");
        if (normalized.matches("^-?\\d+\\.0+$")) {
            normalized = normalized.substring(0, normalized.indexOf('.'));
        }
        return new BigDecimal(normalized);
    }

    public boolean isNullLikeValue(Object value, Class<?> targetType) {
        if (!(value instanceof String stringValue)) {
            return false;
        }
        if (targetType == String.class || targetType == Object.class || targetType.isEnum()) {
            return false;
        }
        String normalized = stringValue.trim().toLowerCase(Locale.ROOT);
        if (NULL_LIKE_VALUES.contains(normalized)) {
            return true;
        }
        return false;
    }

    public Object convertEmptyStructureString(Object value, Class<?> targetType, Type genericType) {
        if (!(value instanceof String stringValue)) {
            return null;
        }
        String normalized = stringValue.trim().toLowerCase(Locale.ROOT);
        if ("[]".equals(normalized)) {
            if (targetType.isArray()) {
                return Array.newInstance(targetType.getComponentType(), 0);
            }
            if (Collection.class.isAssignableFrom(targetType)) {
                return convertCollection(Collections.emptyList(), targetType, genericType);
            }
        }
        if ("{}".equals(normalized) && Map.class.isAssignableFrom(targetType)) {
            return convertMap(Collections.emptyMap(), genericType);
        }
        return null;
    }

    public Object convertArray(List<?> listValue, Class<?> componentType) {
        if (componentType == null) {
            return listValue.toArray();
        }
        Object array = Array.newInstance(componentType, listValue.size());
        for (int i = 0; i < listValue.size(); i++) {
            Array.set(array, i, convertType(listValue.get(i), componentType));
        }
        return array;
    }

    public Object convertCollection(List<?> listValue, Class<?> targetType, Type genericType) {
        Collection<Object> collection = instantiateCollection(targetType);
        Class<?> elementType = extractCollectionElementType(genericType);
        Type elementGenericType = extractCollectionElementGenericType(genericType);
        for (Object item : listValue) {
            collection.add(elementType != null ? convertType(item, elementType,
                    elementGenericType != null ? elementGenericType : elementType) : item);
        }
        return collection;
    }

    public Map<Object, Object> convertMap(Map<?, ?> mapValue, Type genericType) {
        Map<Object, Object> result = new LinkedHashMap<>();
        Type keyType = extractMapKeyType(genericType);
        Type valueType = extractMapValueType(genericType);
        Class<?> keyClass = resolveRawClass(keyType);
        Class<?> valueClass = resolveRawClass(valueType);
        for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
            Object convertedKey = keyClass != null
                    ? convertType(entry.getKey(), keyClass, keyType != null ? keyType : keyClass)
                    : entry.getKey();
            Object convertedValue = valueClass != null
                    ? convertType(entry.getValue(), valueClass, valueType != null ? valueType : valueClass)
                    : entry.getValue();
            result.put(convertedKey, convertedValue);
        }
        return result;
    }

    public Optional<?> convertOptional(Object value, Type genericType) {
        Type wrappedType = extractOptionalWrappedType(genericType);
        Class<?> wrappedClass = resolveRawClass(wrappedType);
        if (wrappedClass == null) {
            return Optional.ofNullable(value);
        }
        return Optional.ofNullable(convertType(value, wrappedClass, wrappedType != null ? wrappedType : wrappedClass));
    }

    public Object convertStructuredString(String stringValue, Class<?> targetType, Type genericType) {
        String trimmed = stringValue == null ? null : stringValue.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            return null;
        }
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return null;
        }
        try {
            Object parsed = new LightweightJsonParser(trimmed).parseValue();
            return convertType(parsed, targetType, genericType);
        } catch (Exception e) {
            LOGGER.debug("Structured string conversion skipped for target {}: {}",
                    targetType.getSimpleName(), e.getMessage());
            return null;
        }
    }

    public Object convertDelimitedString(String stringValue, Class<?> targetType, Type genericType) {
        String trimmed = stringValue == null ? null : stringValue.trim();
        if (trimmed == null || trimmed.isEmpty() || !(trimmed.contains(",") || trimmed.contains(";"))) {
            return null;
        }
        List<String> tokens = splitDelimitedValues(trimmed);
        if (tokens.isEmpty()) {
            return null;
        }
        if (targetType.isArray()) {
            return convertArray(tokens, targetType.getComponentType());
        }
        if (Collection.class.isAssignableFrom(targetType)) {
            return convertCollection(tokens, targetType, genericType);
        }
        return null;
    }

    public List<String> splitDelimitedValues(String input) {
        List<String> values = new ArrayList<>();
        for (String part : input.split("\\s*[;,]\\s*")) {
            if (!part.isEmpty()) {
                values.add(part.trim());
            }
        }
        return values;
    }

    public LocalDate convertToLocalDate(Object value) {
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof Number number) {
            return instantFromEpoch(number.longValue()).atZone(ZoneId.systemDefault()).toLocalDate();
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        if (isEpochText(text)) {
            return instantFromEpoch(Long.parseLong(text)).atZone(ZoneId.systemDefault()).toLocalDate();
        }
        for (DateTimeFormatter formatter : LOCAL_DATE_FORMATTERS) {
            try {
                return LocalDate.parse(text, formatter);
            } catch (DateTimeParseException ignored) {
            }
        }
        return convertToLocalDateTime(text).toLocalDate();
    }

    public LocalDateTime convertToLocalDateTime(Object value) {
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof Number number) {
            return LocalDateTime.ofInstant(instantFromEpoch(number.longValue()), ZoneId.systemDefault());
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        if (isEpochText(text)) {
            return LocalDateTime.ofInstant(instantFromEpoch(Long.parseLong(text)), ZoneId.systemDefault());
        }
        for (DateTimeFormatter formatter : LOCAL_DATE_TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(text, formatter);
            } catch (DateTimeParseException ignored) {
            }
        }
        try {
            return OffsetDateTime.parse(text).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return ZonedDateTime.parse(text).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return Instant.parse(text).atZone(ZoneId.systemDefault()).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
        }
        throw new IllegalArgumentException("无法解析日期时间: " + text);
    }

    public Instant convertToInstant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Number number) {
            return instantFromEpoch(number.longValue());
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        if (isEpochText(text)) {
            return instantFromEpoch(Long.parseLong(text));
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return ZonedDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        return convertToLocalDateTime(text).atZone(ZoneId.systemDefault()).toInstant();
    }

    public OffsetDateTime convertToOffsetDateTime(Object value) {
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime;
        }
        if (value instanceof Number number) {
            return instantFromEpoch(number.longValue()).atZone(ZoneId.systemDefault()).toOffsetDateTime();
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        if (isEpochText(text)) {
            return instantFromEpoch(Long.parseLong(text)).atZone(ZoneId.systemDefault()).toOffsetDateTime();
        }
        try {
            return OffsetDateTime.parse(text);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return ZonedDateTime.parse(text).toOffsetDateTime();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return Instant.parse(text).atZone(ZoneId.systemDefault()).toOffsetDateTime();
        } catch (DateTimeParseException ignored) {
        }
        return convertToLocalDateTime(text).atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    public ZonedDateTime convertToZonedDateTime(Object value) {
        if (value instanceof ZonedDateTime zonedDateTime) {
            return zonedDateTime;
        }
        if (value instanceof Number number) {
            return instantFromEpoch(number.longValue()).atZone(ZoneId.systemDefault());
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        if (isEpochText(text)) {
            return instantFromEpoch(Long.parseLong(text)).atZone(ZoneId.systemDefault());
        }
        try {
            return ZonedDateTime.parse(text);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(text).toZonedDateTime();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return Instant.parse(text).atZone(ZoneId.systemDefault());
        } catch (DateTimeParseException ignored) {
        }
        return convertToLocalDateTime(text).atZone(ZoneId.systemDefault());
    }

    public boolean isEpochText(String text) {
        return text.matches("^-?\\d{10,17}$");
    }

    public Instant instantFromEpoch(long epochValue) {
        long normalized = Math.abs(epochValue) < 100_000_000_000L ? epochValue * 1000 : epochValue;
        return Instant.ofEpochMilli(normalized);
    }

    public Collection<Object> instantiateCollection(Class<?> targetType) {
        if (targetType.isInterface()) {
            if (Set.class.isAssignableFrom(targetType)) {
                return new LinkedHashSet<>();
            }
            return new ArrayList<>();
        }
        try {
            @SuppressWarnings("unchecked")
            Collection<Object> collection = (Collection<Object>) targetType.getDeclaredConstructor().newInstance();
            return collection;
        } catch (Exception e) {
            if (Set.class.isAssignableFrom(targetType)) {
                return new LinkedHashSet<>();
            }
            return new ArrayList<>();
        }
    }

    public Class<?> extractCollectionElementType(Type genericType) {
        return resolveRawClass(extractCollectionElementGenericType(genericType));
    }

    public Type extractCollectionElementGenericType(Type genericType) {
        if (!(genericType instanceof ParameterizedType parameterizedType)) {
            return null;
        }
        Type[] actualTypes = parameterizedType.getActualTypeArguments();
        return actualTypes.length == 0 ? null : actualTypes[0];
    }

    public Type extractMapKeyType(Type genericType) {
        if (!(genericType instanceof ParameterizedType parameterizedType)) {
            return null;
        }
        Type[] actualTypes = parameterizedType.getActualTypeArguments();
        return actualTypes.length > 0 ? actualTypes[0] : null;
    }

    public Type extractMapValueType(Type genericType) {
        if (!(genericType instanceof ParameterizedType parameterizedType)) {
            return null;
        }
        Type[] actualTypes = parameterizedType.getActualTypeArguments();
        return actualTypes.length > 1 ? actualTypes[1] : null;
    }

    public Type extractOptionalWrappedType(Type genericType) {
        if (!(genericType instanceof ParameterizedType parameterizedType)) {
            return null;
        }
        Type[] actualTypes = parameterizedType.getActualTypeArguments();
        return actualTypes.length > 0 ? actualTypes[0] : null;
    }

    public Class<?> resolveRawClass(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterizedType && parameterizedType.getRawType() instanceof Class<?> rawClass) {
            return rawClass;
        }
        return null;
    }

    public Object convertBean(Map<?, ?> mapValue, Class<?> targetType) {
        try {
            Object bean = targetType.getDeclaredConstructor().newInstance();
            Map<String, Object> normalizedSource = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                if (entry.getKey() != null) {
                    registerAlias(normalizedSource, String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            Set<String> assignedFields = new HashSet<>();
            for (Field field : getAllFields(targetType)) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                String normalizedFieldName = normalizeName(field.getName());
                if (!normalizedSource.containsKey(normalizedFieldName)) {
                    continue;
                }
                Object rawFieldValue = normalizedSource.get(normalizedFieldName);
                field.setAccessible(true);
                field.set(bean, convertType(rawFieldValue, field.getType(), field.getGenericType()));
                assignedFields.add(normalizedFieldName);
            }
            applySetterValues(bean, targetType, normalizedSource, assignedFields);
            return bean;
        } catch (Exception e) {
            throw new IllegalArgumentException("复杂参数类型转换失败: " + targetType.getSimpleName(), e);
        }
    }

    public void registerAlias(Map<String, Object> target, String rawKey, Object value) {
        if (rawKey == null || rawKey.trim().isEmpty()) {
            return;
        }
        String trimmed = rawKey.trim();
        target.putIfAbsent(normalizeName(trimmed), value);
        target.putIfAbsent(normalizeName(toSnakeCase(trimmed)), value);
        target.putIfAbsent(normalizeName(toKebabCase(trimmed)), value);
    }

    public List<Field> getAllFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        return fields;
    }

    public void applySetterValues(Object bean, Class<?> targetType, Map<String, Object> normalizedSource,
                                   Set<String> assignedFields) throws ReflectiveOperationException {
        for (Method method : targetType.getMethods()) {
            if (!isSetter(method)) {
                continue;
            }
            String propertyName = normalizeName(method.getName().substring(3));
            if (assignedFields.contains(propertyName) || !normalizedSource.containsKey(propertyName)) {
                continue;
            }
            Object rawValue = normalizedSource.get(propertyName);
            Object convertedValue = convertType(rawValue, method.getParameterTypes()[0], method.getGenericParameterTypes()[0]);
            method.invoke(bean, convertedValue);
            assignedFields.add(propertyName);
        }
    }

    public boolean isSetter(Method method) {
        return method.getName().startsWith("set")
                && method.getName().length() > 3
                && method.getParameterCount() == 1
                && method.getReturnType() == void.class;
    }

    /**
     * 获取指定类型的默认值
     */
    public Object getDefaultForType(Class<?> type) {
        if (type.isPrimitive()) {
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == double.class) return 0.0;
            if (type == float.class) return 0f;
            if (type == short.class) return (short) 0;
            if (type == byte.class) return (byte) 0;
            if (type == boolean.class) return false;
            if (type == char.class) return '\0';
        }
        return null;
    }

    private String normalizeName(String rawName) {
        if (rawName == null) {
            return "";
        }
        String normalized = rawName.trim();
        if (normalized.isEmpty()) {
            return "";
        }
        return normalized.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private String toSnakeCase(String value) {
        return splitCamelCase(value, "_");
    }

    private String toKebabCase(String value) {
        return splitCamelCase(value, "-");
    }

    private String splitCamelCase(String value, String delimiter) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return value.replaceAll("([a-z0-9])([A-Z])", "$1" + delimiter + "$2").toLowerCase(Locale.ROOT);
    }

}
