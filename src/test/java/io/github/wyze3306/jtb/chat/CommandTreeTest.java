package io.github.wyze3306.jtb.chat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.data.command.CommandData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandEnumConstraint;
import org.cloudburstmc.protocol.bedrock.data.command.CommandEnumData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOverloadData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParam;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParamData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandPermissionLevel;
import org.geysermc.mcprotocollib.protocol.data.game.command.CommandNode;
import org.geysermc.mcprotocollib.protocol.data.game.command.CommandParser;
import org.geysermc.mcprotocollib.protocol.data.game.command.CommandType;
import org.geysermc.mcprotocollib.protocol.data.game.command.SuggestionType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundCommandsPacket;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandTreeTest {

    private static final CommandTree.SuggestionData DATA = new CommandTree.SuggestionData() {
        @Override
        public Collection<String> softEnum(String name) {
            return name.equals("Tags") ? List.of("builder", "red team") : List.of();
        }

        @Override
        public Collection<String> playerNames() {
            return List.of("Tester5", "Some Player");
        }

        @Override
        public Collection<String> itemIdentifiers() {
            return List.of("minecraft:diamond", "minecraft:diamond_sword", "minecraft:stone");
        }

        @Override
        public Collection<String> blockIdentifiers() {
            return List.of("minecraft:stone", "minecraft:diamond_block");
        }
    };

    // ------------------------------------------------------------------ Bedrock data helpers

    static CommandParamData param(String name, CommandParam type, boolean optional) {
        CommandParamData data = new CommandParamData();
        data.setName(name);
        data.setType(type);
        data.setOptional(optional);
        return data;
    }

    static CommandParamData enumParam(String name, String enumName, boolean optional, boolean soft, String... values) {
        Map<String, Set<CommandEnumConstraint>> map = new LinkedHashMap<>();
        for (String value : values) {
            map.put(value, EnumSet.noneOf(CommandEnumConstraint.class));
        }
        CommandParamData data = new CommandParamData();
        data.setName(name);
        data.setEnumData(new CommandEnumData(enumName, map, soft));
        data.setOptional(optional);
        return data;
    }

    static CommandData command(String name, List<String> aliases, CommandParamData[]... overloads) {
        CommandOverloadData[] data = new CommandOverloadData[overloads.length];
        for (int i = 0; i < overloads.length; i++) {
            data[i] = new CommandOverloadData(false, overloads[i]);
        }
        CommandEnumData aliasEnum = null;
        if (!aliases.isEmpty()) {
            Map<String, Set<CommandEnumConstraint>> map = new LinkedHashMap<>();
            for (String alias : aliases) {
                map.put(alias, EnumSet.noneOf(CommandEnumConstraint.class));
            }
            map.put(name, EnumSet.noneOf(CommandEnumConstraint.class));
            aliasEnum = new CommandEnumData(name + "Aliases", map, false);
        }
        return new CommandData(name, "description", EnumSet.noneOf(CommandData.Flag.class), CommandPermissionLevel.ANY,
                aliasEnum, List.of(), data);
    }

    /** A PowerNukkitX-like command set. */
    static List<CommandData> sampleCommands() {
        return List.of(
                command("list", List.of(), new CommandParamData[0]),
                command("tp", List.of("teleport"),
                        new CommandParamData[]{param("destination", CommandParam.SELECTION, false)},
                        new CommandParamData[]{param("victim", CommandParam.SELECTION, false), param("destination", CommandParam.SELECTION, false)},
                        new CommandParamData[]{param("destination", CommandParam.POSITION_FLOAT, false),
                                param("yRot", CommandParam.RVAL, true), param("xRot", CommandParam.RVAL, true)}),
                command("give", List.of(),
                        new CommandParamData[]{param("player", CommandParam.SELECTION, false),
                                enumParam("itemName", "Item", false, false),
                                param("amount", CommandParam.INT, true), param("data", CommandParam.INT, true),
                                param("components", CommandParam.JSON_OBJECT, true)}),
                command("gamemode", List.of(),
                        new CommandParamData[]{enumParam("gameMode", "GameMode", false, false, "survival", "creative", "s", "c"),
                                param("player", CommandParam.SELECTION, true)}),
                command("say", List.of(), new CommandParamData[]{param("message", CommandParam.MESSAGE_ROOT, false)}),
                command("setblock", List.of(), new CommandParamData[]{param("position", CommandParam.POSITION, false),
                        enumParam("tileName", "Block", false, false), param("blockStates", CommandParam.BLOCK_STATE_ARRAY, true)}),
                command("tag", List.of(),
                        new CommandParamData[]{param("entity", CommandParam.SELECTION, false), enumParam("add", "TagAdd", false, false, "add"),
                                enumParam("name", "Tags", false, true)}),
                command("execute", List.of(),
                        new CommandParamData[]{enumParam("subcommand", "Option_As", false, false, "as"), param("origin", CommandParam.SELECTION, false),
                                enumParam("chainedCommand", "ExecuteChainedOption_0", false, false, "run", "as")},
                        new CommandParamData[]{enumParam("subcommand", "Option_Run", false, false, "run"), param("command", CommandParam.COMMAND, false)}));
    }

    static CommandTree sampleTree() {
        List<CommandData> commands = sampleCommands();
        return new CommandTreeBuilder(commands).addAll(commands, true).build();
    }

    // ------------------------------------------------------------------ tree shape

    @Test
    void commandsAndAliases() {
        CommandTree tree = sampleTree();
        for (String name : List.of("list", "tp", "teleport", "give", "gamemode", "say", "tag", "execute")) {
            assertTrue(tree.hasCommand(name), name);
        }
        CommandTree.Node list = tree.root().literalChild("list");
        assertTrue(list.executable);
        CommandTree.Node teleport = tree.root().literalChild("teleport");
        assertEquals(tree.root().literalChild("tp"), teleport.redirect);
    }

    @Test
    void siblingTargetsAreMergedAndOptionalTailsExecutable() {
        CommandTree.Node tp = sampleTree().root().literalChild("tp");
        assertFalse(tp.executable);
        // destination/victim targets share one entity node, plus one vec3 node.
        assertEquals(2, tp.children.size());
        CommandTree.Node target = tp.children.values().iterator().next();
        assertEquals(CommandParser.ENTITY, target.argument.parser());
        assertEquals("destination|victim: target", target.displayName());
        assertTrue(target.executable);
        assertEquals(1, target.children.size());
        CommandTree.Node position = tp.children.values().stream().filter(n -> n.argument.parser() == CommandParser.VEC3).findFirst().orElseThrow();
        assertTrue(position.executable);
        CommandTree.Node yRot = position.children.values().iterator().next();
        assertEquals(CommandParser.ANGLE, yRot.argument.parser());
        assertTrue(yRot.executable);
    }

    @Test
    void enumsBecomeLiteralsAndSoftEnumsAskTheServer() {
        CommandTree tree = sampleTree();
        CommandTree.Node gamemode = tree.root().literalChild("gamemode");
        assertNotNull(gamemode.literalChild("creative"));
        assertTrue(gamemode.literalChild("creative").executable);
        // Both literals share the optional player node.
        assertEquals(gamemode.literalChild("survival").children.values().iterator().next(),
                gamemode.literalChild("creative").children.values().iterator().next());
        CommandTree.Node add = tree.root().literalChild("tag").children.values().iterator().next().literalChild("add");
        CommandTree.Node name = add.children.values().iterator().next();
        assertEquals(CommandParser.STRING, name.argument.parser());
        assertTrue(name.asksServer());
    }

    @Test
    void commandParametersRedirect() {
        CommandTree tree = sampleTree();
        CommandTree.Node execute = tree.root().literalChild("execute");
        assertEquals(tree.root(), execute.literalChild("run").redirect);
        CommandTree.Node origin = execute.literalChild("as").children.values().iterator().next();
        assertEquals(execute, origin.redirect);
        assertTrue(origin.children.isEmpty());
    }

    @Test
    void messageIsGreedyStringNotSignedMessage() {
        CommandTree.Node message = sampleTree().root().literalChild("say").children.values().iterator().next();
        assertEquals(CommandParser.STRING, message.argument.parser());
        assertEquals(CommandArgument.Syntax.GREEDY, message.argument.syntax());
    }

    // ------------------------------------------------------------------ serialisation

    @Test
    void packetSerialisesAndRoundTrips() {
        ClientboundCommandsPacket packet = sampleTree().toPacket();
        for (CommandNode node : packet.getNodes()) {
            if (node.getType() == CommandType.ARGUMENT) {
                assertNotNull(node.getProperties(), node.getName());
            }
        }
        ByteBuf buffer = Unpooled.buffer();
        packet.serialize(buffer);
        ClientboundCommandsPacket read = new ClientboundCommandsPacket(buffer);
        assertEquals(packet.getNodes().length, read.getNodes().length);
        assertEquals(0, read.getFirstNodeIndex());
        CommandNode root = read.getNodes()[0];
        assertEquals(CommandType.ROOT, root.getType());
        List<String> names = java.util.Arrays.stream(root.getChildIndices()).mapToObj(i -> read.getNodes()[i].getName()).toList();
        assertTrue(names.containsAll(List.of("list", "tp", "give", "teleport")));
        // Redirect indices point to real nodes, and every node is reachable exactly once.
        for (CommandNode node : read.getNodes()) {
            node.getRedirectIndex().ifPresent(i -> assertTrue(i < read.getNodes().length));
        }
    }

    @Test
    void onlyOneAskServerChildPerParent() {
        List<CommandData> commands = List.of(command("test", List.of(),
                new CommandParamData[]{enumParam("a", "Tags", false, true)},
                new CommandParamData[]{param("b", CommandParam.SELECTION, false)}));
        ClientboundCommandsPacket packet = new CommandTreeBuilder(commands).addAll(commands, true).build().toPacket();
        CommandNode test = java.util.Arrays.stream(packet.getNodes()).filter(n -> "test".equals(n.getName())).findFirst().orElseThrow();
        long asking = java.util.Arrays.stream(test.getChildIndices())
                .filter(i -> SuggestionType.ASK_SERVER.getResourceLocation().equals(packet.getNodes()[i].getSuggestionType())).count();
        assertEquals(1, asking);
    }

    @Test
    void cheatsOffKeepsOnlyNonCheatCommands() {
        List<CommandData> commands = List.of(command("give", List.of(), new CommandParamData[0]),
                new CommandData("me", "", EnumSet.of(CommandData.Flag.NOT_CHEAT), CommandPermissionLevel.ANY, null, List.of(),
                        new CommandOverloadData[0]));
        CommandTree tree = new CommandTreeBuilder(commands).addAll(commands, false).build();
        assertFalse(tree.hasCommand("give"));
        assertTrue(tree.hasCommand("me"));
    }

    // ------------------------------------------------------------------ suggestions

    @Test
    void suggestsPlayerNamesForTargets() {
        CommandTree.Completion completion = sampleTree().complete("/tp Tes", DATA);
        assertEquals(4, completion.start());
        assertEquals(3, completion.length());
        assertEquals(List.of("Tester5"), completion.matches());

        CommandTree.Completion second = sampleTree().complete("/tp Tester5 ", DATA);
        assertTrue(second.matches().containsAll(List.of("@a", "@s", "Tester5", "\"Some Player\"")));
        assertEquals(12, second.start());
    }

    @Test
    void suggestsItemsByPathToo() {
        CommandTree.Completion completion = sampleTree().complete("/give @s dia", DATA);
        assertEquals(List.of("minecraft:diamond", "minecraft:diamond_sword"), completion.matches());
        assertEquals(9, completion.start());
    }

    @Test
    void suggestsBlocksAfterAPosition() {
        CommandTree.Completion completion = sampleTree().complete("/setblock ~ ~1 ~ dia", DATA);
        assertEquals(List.of("minecraft:diamond_block"), completion.matches());
        assertEquals(17, completion.start());
        assertTrue(sampleTree().complete("/setblock ~ ~1", DATA).matches().isEmpty());
    }

    @Test
    void selectorsWithSpacedArgumentsAreSkipped() {
        CommandTree.Completion completion = sampleTree().complete("/give @a[r=10, c=1] minecraft:st", DATA);
        assertEquals(List.of("minecraft:stone"), completion.matches());
    }

    @Test
    void softEnumValuesAreQuotedWhenNeeded() {
        CommandTree.Completion completion = sampleTree().complete("/tag @s add r", DATA);
        assertEquals(List.of("\"red team\""), completion.matches());
    }

    @Test
    void suggestionsFollowRedirects() {
        CommandTree.Completion completion = sampleTree().complete("/execute as @s run tp Tes", DATA);
        assertEquals(List.of("Tester5"), completion.matches());
    }

    @Test
    void nothingForUnknownCommands() {
        assertTrue(sampleTree().complete("/nope Tes", DATA).matches().isEmpty());
        assertNull(sampleTree().root().literalChild("nope"));
    }
}
