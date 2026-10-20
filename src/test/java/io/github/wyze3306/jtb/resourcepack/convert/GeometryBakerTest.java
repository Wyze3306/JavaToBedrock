package io.github.wyze3306.jtb.resourcepack.convert;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.wyze3306.jtb.resourcepack.JavaAssetNames;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bedrock geometry -> Java model elements, and the shared asset names. */
class GeometryBakerTest {

    private static Geometry geometry(String json) {
        Map<String, Geometry> resolved = Geometry.resolve(Map.of("geometry.test",
                Geometry.parseFile(LenientJson.parse(json).getAsJsonObject()).getFirst()));
        return resolved.get("geometry.test");
    }

    private static JsonObject onlyElement(GeometryBaker.Result result) {
        JsonArray elements = result.model().getAsJsonArray("elements");
        assertEquals(1, elements.size());
        return elements.get(0).getAsJsonObject();
    }

    private static String cube(String cube) {
        return "{\"format_version\": \"1.12.0\", \"minecraft:geometry\": [{\"description\": {\"identifier\": \"geometry.test\","
                + " \"texture_width\": 64, \"texture_height\": 64}, \"bones\": [{\"name\": \"root\", \"pivot\": [0, 0, 0],"
                + " \"cubes\": [" + cube + "]}]}]}";
    }

    @Test
    void fullCubeLandsInTheBlockCell() {
        GeometryBaker.Result result = GeometryBaker.bake(geometry(cube("{\"origin\": [-8, 0, -8], \"size\": [16, 16, 16], \"uv\": [0, 0]}")),
                face -> "0", Set.of());
        JsonObject element = onlyElement(result);
        assertEquals("[0.0,0.0,0.0]", element.get("from").toString());
        assertEquals("[16.0,16.0,16.0]", element.get("to").toString());
        assertFalse(element.has("rotation"));
        assertEquals(1, result.scale());
        // north = [u+sz, v+sz, u+sz+sx, v+sz+sy] pixels of a 64 x 64 texture
        assertEquals("[4.0,4.0,8.0,8.0]", element.getAsJsonObject("faces").getAsJsonObject("north").get("uv").toString());
        assertEquals("[8.0,4.0,4.0,0.0]", element.getAsJsonObject("faces").getAsJsonObject("up").get("uv").toString());
    }

    @Test
    void xIsMirroredAndInflateGrowsBothSides() {
        JsonObject element = onlyElement(GeometryBaker.bake(geometry(cube(
                "{\"origin\": [2, 0, 0], \"size\": [4, 2, 2], \"inflate\": 1, \"uv\": [0, 0]}")), face -> "0", Set.of()));
        // Bedrock x in [2, 6] -> Java [-6, -2] + 8 = [2, 6], minus/plus the inflation.
        assertEquals("[1.0,-1.0,7.0]", element.get("from").toString());
        assertEquals("[7.0,3.0,11.0]", element.get("to").toString());
    }

    @Test
    void mirroredBoxUvSwapsEastAndWestAndFlipsU() {
        String size = "\"size\": [4, 4, 2]";
        JsonObject plain = onlyElement(GeometryBaker.bake(geometry(cube("{\"origin\": [0, 0, 0], " + size + ", \"uv\": [0, 0]}")),
                face -> "0", Set.of())).getAsJsonObject("faces");
        JsonObject mirrored = onlyElement(GeometryBaker.bake(geometry(cube("{\"origin\": [0, 0, 0], " + size
                + ", \"uv\": [0, 0], \"mirror\": true}")), face -> "0", Set.of())).getAsJsonObject("faces");
        JsonArray west = plain.getAsJsonObject("west").getAsJsonArray("uv");
        JsonArray mirroredEast = mirrored.getAsJsonObject("east").getAsJsonArray("uv");
        assertEquals(west.get(0), mirroredEast.get(2));
        assertEquals(west.get(2), mirroredEast.get(0));
        assertEquals(west.get(1), mirroredEast.get(1));
    }

    @Test
    void oversizedModelsAreShrunk() {
        GeometryBaker.Result result = GeometryBaker.bake(geometry(cube("{\"origin\": [-8, 0, -8], \"size\": [16, 64, 16], \"uv\": [0, 0]}")),
                face -> "0", Set.of());
        assertEquals(2, result.scale(), 1e-6);
        assertEquals("[12.0,32.0,12.0]", onlyElement(result).get("to").toString(), "scaled about (8, 0, 8)");
    }

    @Test
    void boneRotationsAreComposed() {
        String json = "{\"format_version\": \"1.12.0\", \"minecraft:geometry\": [{\"description\": {\"identifier\": \"geometry.test\","
                + " \"texture_width\": 16, \"texture_height\": 16}, \"bones\": ["
                + "{\"name\": \"root\", \"pivot\": [0, 0, 0], \"rotation\": [30, 0, 0]},"
                + "{\"name\": \"arm\", \"parent\": \"root\", \"pivot\": [0, 0, 0], \"rotation\": [0, 45, 0],"
                + " \"cubes\": [{\"origin\": [0, 0, 0], \"size\": [1, 1, 1], \"uv\": [0, 0]}]},"
                + "{\"name\": \"hidden\", \"neverRender\": true, \"cubes\": [{\"origin\": [0, 0, 0], \"size\": [1, 1, 1], \"uv\": [0, 0]}]}"
                + "]}]}";
        JsonObject rotation = onlyElement(GeometryBaker.bake(geometry(json), face -> "0", Set.of())).getAsJsonObject("rotation");
        assertTrue(rotation.has("x") && rotation.has("y") && rotation.has("z"), "two axes need the multi-axis form");
        double[] expected = GeometryBaker.rotation(new float[]{30, 0, 0});
        double[] arm = GeometryBaker.rotation(new float[]{0, 45, 0});
        double[] composed = new double[9];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                for (int k = 0; k < 3; k++) {
                    composed[r * 3 + c] += expected[r * 3 + k] * arm[k * 3 + c];
                }
            }
        }
        // Rz(z) * Ry(y) * Rx(x) rebuilt from the JSON gives the composed bone rotation back.
        double[] rebuilt = GeometryBaker.rotation(new float[]{(float) -rotation.get("x").getAsDouble(),
                (float) -rotation.get("y").getAsDouble(), (float) rotation.get("z").getAsDouble()});
        for (int i = 0; i < 9; i++) {
            assertEquals(composed[i], rebuilt[i], 1e-3, "matrix entry " + i);
        }
    }

    @Test
    void hiddenBonesAndMissingTexturesDropCubes() {
        Geometry geometry = geometry(cube("{\"origin\": [0, 0, 0], \"size\": [1, 1, 1], \"uv\": [0, 0]}"));
        assertNull(GeometryBaker.bake(geometry, face -> "0", Set.of("root")));
        assertNull(GeometryBaker.bake(geometry, face -> null, Set.of()));
    }

    @Test
    void perFaceUvAndMaterialInstances() {
        JsonObject faces = onlyElement(GeometryBaker.bake(geometry(cube("{\"origin\": [0, 0, 0], \"size\": [16, 16, 16], \"uv\": {"
                + "\"north\": {\"uv\": [0, 0], \"uv_size\": [16, 16], \"material_instance\": \"front\"},"
                + "\"up\": {\"uv\": [16, 0], \"uv_size\": [16, 16], \"uv_rotation\": 90}}}")),
                face -> face.equals("front") ? "1" : "0", Set.of())).getAsJsonObject("faces");
        assertEquals("#1", faces.getAsJsonObject("north").get("texture").getAsString());
        assertEquals("#0", faces.getAsJsonObject("up").get("texture").getAsString());
        assertEquals(90, faces.getAsJsonObject("up").get("rotation").getAsInt());
        assertEquals("[8.0,4.0,4.0,0.0]", faces.getAsJsonObject("up").get("uv").toString(), "up/down per-face UVs are flipped");
        assertFalse(faces.has("south"), "faces without UV are not rendered");
    }

    @Test
    void legacyGeometryInheritance() {
        Map<String, Geometry.Parsed> parsed = new HashMap<>();
        for (Geometry.Parsed geometry : Geometry.parseFile(LenientJson.parse("{\"format_version\": \"1.8.0\","
                + "\"geometry.parent\": {\"texturewidth\": 32, \"textureheight\": 32, \"bones\": [{\"name\": \"a\"}, {\"name\": \"b\"}]},"
                + "\"geometry.child:geometry.parent\": {\"bones\": [{\"name\": \"b\", \"pivot\": [1, 2, 3]}, {\"name\": \"c\"}]}}")
                .getAsJsonObject())) {
            parsed.put(geometry.identifier(), geometry);
        }
        Geometry child = Geometry.resolve(parsed).get("geometry.child");
        assertEquals(32, child.textureWidth());
        assertEquals(List.of("a", "b", "c"), child.bones().stream().map(Geometry.Bone::name).toList());
        assertEquals(2, child.bones().get(1).pivot()[1]);
    }

    @Test
    void assetNames() {
        assertEquals("jtb:item/demo/ruby_gem", JavaAssetNames.itemModel("Demo:Ruby Gem"));
        assertEquals("jtb:item/minecraft/apple", JavaAssetNames.itemModel("apple"));
        assertEquals("jtb:mob.custom_roar", JavaAssetNames.soundEvent("mob.Custom:roar"));
        assertEquals("jtb:block/custom/demo/lamp_2", JavaAssetNames.customBlockModel("demo:lamp", 2));
        assertEquals("jtb:entity/demo/golem/default_default", JavaAssetNames.entityModel("demo:golem", "default_default"));
        assertEquals("jtb:demo/ruby_helmet", JavaAssetNames.equipmentAsset("demo:ruby_helmet"));
        assertEquals("items/my_gem", JavaAssetNames.path("items/My Gem"));
    }
}
