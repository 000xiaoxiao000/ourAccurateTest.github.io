package com.oAT.ai.agent.fallback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻量 JSON 解析器，避免 AI 模块为 fallback 参数解析额外引入 JSON 依赖。
 */
public class LightweightJsonParser {
    private final String text;
    private int index;

    public LightweightJsonParser(String text) {
        this.text = text;
    }

    public Object parseValue() {
        skipWhitespace();
        if (index >= text.length()) {
            throw new IllegalArgumentException("JSON 内容为空");
        }
        char current = text.charAt(index);
        return switch (current) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> parseString();
            case 't' -> parseLiteral("true", Boolean.TRUE);
            case 'f' -> parseLiteral("false", Boolean.FALSE);
            case 'n' -> parseLiteral("null", null);
            default -> {
                if (current == '-' || Character.isDigit(current)) {
                    yield parseNumber();
                }
                throw new IllegalArgumentException("非法 JSON 字符: " + current);
            }
        };
    }

    private Map<String, Object> parseObject() {
        expect('{');
        Map<String, Object> result = new LinkedHashMap<>();
        skipWhitespace();
        if (peek('}')) {
            index++;
            return result;
        }
        while (true) {
            skipWhitespace();
            String key = parseString();
            skipWhitespace();
            expect(':');
            Object value = parseValue();
            result.put(key, value);
            skipWhitespace();
            if (peek('}')) {
                index++;
                return result;
            }
            expect(',');
        }
    }

    private List<Object> parseArray() {
        expect('[');
        List<Object> result = new ArrayList<>();
        skipWhitespace();
        if (peek(']')) {
            index++;
            return result;
        }
        while (true) {
            result.add(parseValue());
            skipWhitespace();
            if (peek(']')) {
                index++;
                return result;
            }
            expect(',');
        }
    }

    private String parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (index < text.length()) {
            char current = text.charAt(index++);
            if (current == '"') {
                return sb.toString();
            }
            if (current == '\\') {
                if (index >= text.length()) {
                    throw new IllegalArgumentException("非法 JSON 转义");
                }
                char escaped = text.charAt(index++);
                switch (escaped) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> sb.append(parseUnicode());
                    default -> throw new IllegalArgumentException("未知 JSON 转义: \\" + escaped);
                }
                continue;
            }
            sb.append(current);
        }
        throw new IllegalArgumentException("JSON 字符串未闭合");
    }

    private char parseUnicode() {
        if (index + 4 > text.length()) {
            throw new IllegalArgumentException("非法 Unicode 转义");
        }
        String hex = text.substring(index, index + 4);
        index += 4;
        return (char) Integer.parseInt(hex, 16);
    }

    private Object parseNumber() {
        int start = index;
        if (text.charAt(index) == '-') {
            index++;
        }
        consumeDigits();
        boolean floating = false;
        if (peek('.')) {
            floating = true;
            index++;
            consumeDigits();
        }
        if (peek('e') || peek('E')) {
            floating = true;
            index++;
            if (peek('+') || peek('-')) {
                index++;
            }
            consumeDigits();
        }
        String numberText = text.substring(start, index);
        if (floating) {
            return Double.parseDouble(numberText);
        }
        long longValue = Long.parseLong(numberText);
        return (longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE)
                ? (int) longValue
                : longValue;
    }

    private Object parseLiteral(String literal, Object value) {
        if (!text.startsWith(literal, index)) {
            throw new IllegalArgumentException("非法 JSON 字面量");
        }
        index += literal.length();
        return value;
    }

    private void consumeDigits() {
        int start = index;
        while (index < text.length() && Character.isDigit(text.charAt(index))) {
            index++;
        }
        if (start == index) {
            throw new IllegalArgumentException("非法数字格式");
        }
    }

    private void expect(char expected) {
        skipWhitespace();
        if (index >= text.length() || text.charAt(index) != expected) {
            throw new IllegalArgumentException("期望字符 '" + expected + "'");
        }
        index++;
    }

    private boolean peek(char expected) {
        return index < text.length() && text.charAt(index) == expected;
    }

    private void skipWhitespace() {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
    }
}
