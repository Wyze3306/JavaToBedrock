package io.github.wyze3306.jtb.resourcepack.convert;

import org.cloudburstmc.nbt.NbtMap;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;

/**
 * Evaluates the Molang conditions of custom block permutations against one block state: the subset
 * block definitions use, i.e. {@code q.block_state('name')} / {@code query.block_property('name')},
 * number, string and boolean literals, {@code == != < > <= >=}, {@code && || !} and parentheses.
 * Anything else makes the condition false.
 */
final class MolangConditions {

    private final String text;
    private final NbtMap states;
    private int position;

    private MolangConditions(String text, NbtMap states) {
        this.text = text;
        this.states = states;
    }

    /** True when {@code condition} holds for {@code states}; false when it cannot be evaluated. */
    static boolean test(String condition, NbtMap states) {
        if (condition.isBlank()) {
            return true;
        }
        try {
            MolangConditions parser = new MolangConditions(condition, states);
            Object value = parser.or();
            parser.skipSpaces();
            return parser.position >= parser.text.length() && truthy(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private Object or() {
        Object left = and();
        while (consume("||")) {
            Object right = and();
            left = truthy(left) || truthy(right);
        }
        return left;
    }

    private Object and() {
        Object left = unary();
        while (consume("&&")) {
            Object right = unary();
            left = truthy(left) && truthy(right);
        }
        return left;
    }

    private Object unary() {
        if (peek('!') && !peekAt(1, '=')) {
            this.position++;
            return !truthy(unary());
        }
        return comparison();
    }

    private Object comparison() {
        Object left = primary();
        for (String operator : new String[]{"==", "!=", "<=", ">=", "<", ">"}) {
            if (consume(operator)) {
                return compare(left, primary(), operator);
            }
        }
        return left;
    }

    private Object primary() {
        skipSpaces();
        if (this.position >= this.text.length()) {
            throw new IllegalArgumentException("Unexpected end");
        }
        char c = this.text.charAt(this.position);
        if (c == '(') {
            this.position++;
            Object value = or();
            expect(')');
            return value;
        }
        if (c == '\'' || c == '"') {
            return string();
        }
        if (Character.isDigit(c) || c == '-' || c == '.') {
            return number();
        }
        String name = identifier().toLowerCase(Locale.ROOT);
        return switch (name) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            case "q.block_state", "query.block_state", "q.block_property", "query.block_property" -> {
                expect('(');
                skipSpaces();
                String property = string();
                expect(')');
                yield state(property);
            }
            default -> throw new IllegalArgumentException("Unsupported " + name);
        };
    }

    private Object state(String property) {
        Object value = this.states.get(property);
        if (value instanceof Byte b) {
            return b.doubleValue();
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof Boolean b) {
            return b ? 1.0 : 0.0;
        }
        if (value instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException("Unknown state " + property);
    }

    private static Object compare(Object left, Object right, String operator) {
        Double a = number(left);
        Double b = number(right);
        if (a != null && b != null) {
            int c = Double.compare(a, b);
            return switch (operator) {
                case "==" -> c == 0;
                case "!=" -> c != 0;
                case "<" -> c < 0;
                case ">" -> c > 0;
                case "<=" -> c <= 0;
                default -> c >= 0;
            };
        }
        boolean equal = Objects.equals(String.valueOf(left), String.valueOf(right));
        return switch (operator) {
            case "==" -> equal;
            case "!=" -> !equal;
            default -> throw new IllegalArgumentException("Cannot order strings");
        };
    }

    private static @Nullable Double number(Object value) {
        if (value instanceof Double d) {
            return d;
        }
        if (value instanceof Boolean b) {
            return b ? 1.0 : 0.0;
        }
        return null;
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Double d) {
            return d != 0;
        }
        return value instanceof String s && !s.isEmpty();
    }

    private String string() {
        char quote = this.text.charAt(this.position);
        if (quote != '\'' && quote != '"') {
            throw new IllegalArgumentException("Expected a string");
        }
        int end = this.text.indexOf(quote, this.position + 1);
        if (end < 0) {
            throw new IllegalArgumentException("Unterminated string");
        }
        String value = this.text.substring(this.position + 1, end);
        this.position = end + 1;
        return value;
    }

    private Double number() {
        int start = this.position;
        if (this.text.charAt(this.position) == '-') {
            this.position++;
        }
        while (this.position < this.text.length()
                && (Character.isDigit(this.text.charAt(this.position)) || this.text.charAt(this.position) == '.')) {
            this.position++;
        }
        try {
            return Double.parseDouble(this.text.substring(start, this.position));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private String identifier() {
        int start = this.position;
        while (this.position < this.text.length()) {
            char c = this.text.charAt(this.position);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '.') {
                break;
            }
            this.position++;
        }
        if (start == this.position) {
            throw new IllegalArgumentException("Unexpected " + this.text.charAt(this.position));
        }
        return this.text.substring(start, this.position);
    }

    private void skipSpaces() {
        while (this.position < this.text.length() && Character.isWhitespace(this.text.charAt(this.position))) {
            this.position++;
        }
    }

    private boolean peek(char c) {
        skipSpaces();
        return this.position < this.text.length() && this.text.charAt(this.position) == c;
    }

    private boolean peekAt(int offset, char c) {
        return this.position + offset < this.text.length() && this.text.charAt(this.position + offset) == c;
    }

    private boolean consume(String token) {
        skipSpaces();
        if (this.text.startsWith(token, this.position)) {
            this.position += token.length();
            return true;
        }
        return false;
    }

    private void expect(char c) {
        skipSpaces();
        if (this.position >= this.text.length() || this.text.charAt(this.position) != c) {
            throw new IllegalArgumentException("Expected " + c);
        }
        this.position++;
    }
}
