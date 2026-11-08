package io.github.wyze3306.jtb.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Bedrock formatted text ({@code §} codes) to Adventure components and back.
 * <p>
 * Bedrock shares Java's sixteen colours and {@code §k §l §o §r}, but has no strikethrough or
 * underline: {@code §m} and {@code §n} are colours there, like the other "material" colours
 * {@code §g §h §i §j §p §q §s §t §u §v}. Those are rendered with their exact RGB on Java. A colour
 * code does not reset bold/italic/obfuscated on Bedrock (it does on Java); only {@code §r} does.
 */
public final class BedrockText {

    public static final char SECTION = '§';

    private static final TextColor[] COLORS = new TextColor[128];

    static {
        COLORS['0'] = NamedTextColor.BLACK;
        COLORS['1'] = NamedTextColor.DARK_BLUE;
        COLORS['2'] = NamedTextColor.DARK_GREEN;
        COLORS['3'] = NamedTextColor.DARK_AQUA;
        COLORS['4'] = NamedTextColor.DARK_RED;
        COLORS['5'] = NamedTextColor.DARK_PURPLE;
        COLORS['6'] = NamedTextColor.GOLD;
        COLORS['7'] = NamedTextColor.GRAY;
        COLORS['8'] = NamedTextColor.DARK_GRAY;
        COLORS['9'] = NamedTextColor.BLUE;
        COLORS['a'] = NamedTextColor.GREEN;
        COLORS['b'] = NamedTextColor.AQUA;
        COLORS['c'] = NamedTextColor.RED;
        COLORS['d'] = NamedTextColor.LIGHT_PURPLE;
        COLORS['e'] = NamedTextColor.YELLOW;
        COLORS['f'] = NamedTextColor.WHITE;
        COLORS['g'] = TextColor.color(0xDDD605); // minecoin gold
        COLORS['h'] = TextColor.color(0xE3D4D1); // quartz
        COLORS['i'] = TextColor.color(0xCECACA); // iron
        COLORS['j'] = TextColor.color(0x443A3B); // netherite
        COLORS['m'] = TextColor.color(0x971607); // redstone
        COLORS['n'] = TextColor.color(0xB4684D); // copper
        COLORS['p'] = TextColor.color(0xDEB12D); // gold
        COLORS['q'] = TextColor.color(0x47A036); // emerald
        COLORS['s'] = TextColor.color(0x2CBAA8); // diamond
        COLORS['t'] = TextColor.color(0x21497B); // lapis
        COLORS['u'] = TextColor.color(0x9A5CC6); // amethyst
        COLORS['v'] = TextColor.color(0xEB7114); // resin
    }

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character(SECTION)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private BedrockText() {
    }

    /**
     * Parses Bedrock {@code §}-formatted text. Newlines are kept as is (Java renders them in chat);
     * use {@link #legacyToLines} where every line must be its own component (lore, name tags).
     */
    public static Component legacyToComponent(String text) {
        if (text.indexOf(SECTION) < 0) {
            return Component.text(text);
        }
        TextComponent.Builder root = Component.text();
        parse(text, 0, text.length(), Style.empty(), root);
        return root.build();
    }

    /**
     * Parses Bedrock {@code §}-formatted text into one component per line. Like on Bedrock, the
     * formatting active at the end of a line continues on the next one.
     */
    public static List<Component> legacyToLines(String text) {
        if (text.indexOf('\n') < 0) {
            return List.of(legacyToComponent(text));
        }
        List<Component> lines = new ArrayList<>();
        Style style = Style.empty();
        int start = 0;
        while (true) {
            int end = text.indexOf('\n', start);
            int lineEnd = end < 0 ? text.length() : end;
            TextComponent.Builder line = Component.text();
            style = parse(text, start, lineEnd, style, line);
            lines.add(line.build());
            if (end < 0) {
                return lines;
            }
            start = end + 1;
        }
    }

    /** Appends {@code text[start, end)} to {@code out}, starting with {@code style}; returns the style at the end. */
    private static Style parse(String text, int start, int end, Style style, TextComponent.Builder out) {
        StringBuilder current = new StringBuilder();
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c == SECTION && i + 1 < end) {
                char code = Character.toLowerCase(text.charAt(i + 1));
                Style next = applyCode(style, code);
                if (next != null) {
                    if (!current.isEmpty()) {
                        out.append(Component.text(current.toString(), style));
                        current.setLength(0);
                    }
                    style = next;
                    i++;
                    continue;
                }
                // Bedrock hides a "§" and the character after it even when the code means nothing
                // (servers use such codes as invisible markers for their UI packs).
                i++;
                continue;
            }
            current.append(c);
        }
        if (!current.isEmpty()) {
            out.append(Component.text(current.toString(), style));
        }
        return style;
    }

    private static @Nullable Style applyCode(Style style, char code) {
        if (code < 128 && COLORS[code] != null) {
            // Unlike Java, Bedrock keeps bold, italic and obfuscated across a colour change. Italic is
            // forced off when unset so names and lore are not rendered in Java's default italic.
            Style coloured = style.color(COLORS[code]);
            return coloured.decoration(TextDecoration.ITALIC) == TextDecoration.State.NOT_SET
                    ? coloured.decoration(TextDecoration.ITALIC, false) : coloured;
        }
        return switch (code) {
            case 'k' -> style.decorate(TextDecoration.OBFUSCATED);
            case 'l' -> style.decorate(TextDecoration.BOLD);
            case 'o' -> style.decorate(TextDecoration.ITALIC);
            case 'r' -> Style.style().decoration(TextDecoration.ITALIC, false).build();
            default -> null;
        };
    }

    /** Removes every {@code §x} code. */
    public static String stripFormatting(String text) {
        if (text.indexOf(SECTION) < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == SECTION && i + 1 < text.length()) {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * Serialises a Java component to Bedrock {@code §} text (used for text the Java player types or
     * that must go back to the server, such as sign lines and item names).
     */
    public static String componentToLegacy(Component component) {
        return LEGACY.serialize(component);
    }

    /** Plain text of a component, without any formatting. */
    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
