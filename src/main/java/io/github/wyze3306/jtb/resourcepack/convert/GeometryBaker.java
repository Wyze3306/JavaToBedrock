package io.github.wyze3306.jtb.resourcepack.convert;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Bakes a Bedrock geometry into the elements of one Java block/item model, bone transforms
 * included (each cube becomes one element rotated by the product of its bones' rotations).
 * <p>
 * Conventions, as Blockbench and CubeConverter (GPL-3.0, oryxel1) convert Bedrock models: Bedrock
 * model space is X-mirrored relative to Java model space ({@code x -> -x}, the box spans
 * {@code [-(origin.x + size.x), -origin.x]}), Bedrock rotations {@code (rx, ry, rz)} become the
 * right-handed Java-space rotation {@code Rz(rz) * Ry(-ry) * Rx(-rx)}, and the Java model origin is
 * offset by {@code (8, 0, 8)} so the Bedrock origin (bottom centre) lands at the bottom centre of
 * the Java model. Unlike CubeConverter, inflation grows both sides, bone-level {@code mirror} and
 * {@code inflate} apply to cubes, and bone rotations are not dropped.
 * <p>
 * Box UV follows Blockbench's layout (east, west, up, down, south, north); mirrored cubes flip U and
 * swap east/west like Java's {@code ModelPart}. Per-face {@code up}/{@code down} UVs are flipped on
 * both axes as Blockbench does on import. Java element bounds are {@code [-16, 32]}: larger models
 * are shrunk about {@code (8, 0, 8)} and {@link Result#scale()} tells how much to scale the display
 * back up.
 */
public final class GeometryBaker {

    private static final float MIN = -16;
    private static final float MAX = 32;
    private static final double EPSILON = 1e-4;
    private static final String[] FACES = {"north", "east", "south", "west", "up", "down"};

    /**
     * @param model the Java model JSON ({@code elements} and {@code textures})
     * @param scale display scale that undoes the shrinking ({@code 1 / k}, 1 when it fitted)
     */
    public record Result(JsonObject model, float scale, int elements) {
    }

    private GeometryBaker() {
    }

    /**
     * @param faceTexture  Bedrock material instance name of a face (the face name for faces without
     *                     one) -> Java texture variable (without {@code #}), or null to drop the face
     * @param hiddenBones  bones (lower case) hidden together with their descendants
     * @return null when the geometry has no visible cube
     */
    public static @Nullable Result bake(Geometry geometry, Function<String, @Nullable String> faceTexture,
                                        Set<String> hiddenBones) {
        Map<String, Geometry.Bone> bones = new HashMap<>();
        for (Geometry.Bone bone : geometry.bones()) {
            bones.putIfAbsent(bone.name().toLowerCase(Locale.ROOT), bone);
        }
        Map<String, Transform> transforms = new HashMap<>();
        List<Element> elements = new ArrayList<>();
        for (Geometry.Bone bone : geometry.bones()) {
            if (bone.cubes().isEmpty() || bone.neverRender() || isHidden(bone, bones, hiddenBones)) {
                continue;
            }
            Transform world = transform(bone, bones, transforms, 0);
            for (Geometry.Cube cube : bone.cubes()) {
                Element element = element(cube, world, geometry, faceTexture);
                if (element != null) {
                    elements.add(element);
                }
            }
        }
        if (elements.isEmpty()) {
            return null;
        }
        double k = 1;
        for (Element element : elements) {
            for (int axis = 0; axis < 3; axis++) {
                k = Math.min(k, fit(element.from[axis], ANCHOR[axis]));
                k = Math.min(k, fit(element.to[axis], ANCHOR[axis]));
            }
        }
        JsonArray array = new JsonArray();
        for (Element element : elements) {
            array.add(element.toJson(k, geometry));
        }
        JsonObject model = new JsonObject();
        model.add("elements", array);
        return new Result(model, (float) (1 / k), elements.size());
    }

    private static final double[] ANCHOR = {8, 0, 8};

    /** Largest k <= 1 keeping {@code anchor + k (value - anchor)} inside the Java bounds. */
    private static double fit(double value, double anchor) {
        if (value > MAX) {
            return (MAX - anchor) / (value - anchor);
        }
        if (value < MIN) {
            return (MIN - anchor) / (value - anchor);
        }
        return 1;
    }

    private static boolean isHidden(Geometry.Bone bone, Map<String, Geometry.Bone> bones, Set<String> hidden) {
        if (hidden.isEmpty()) {
            return false;
        }
        Set<String> seen = new HashSet<>();
        Geometry.Bone current = bone;
        while (current != null && seen.add(current.name().toLowerCase(Locale.ROOT))) {
            if (hidden.contains(current.name().toLowerCase(Locale.ROOT))) {
                return true;
            }
            current = current.parent() == null ? null : bones.get(current.parent().toLowerCase(Locale.ROOT));
        }
        return false;
    }

    // ------------------------------------------------------------------ transforms

    /** A rigid transform in Java model space: {@code v -> r * v + t}. */
    private record Transform(double[] r, double[] t) {
        static final Transform IDENTITY = new Transform(identity(), new double[3]);
    }

    private static Transform transform(Geometry.Bone bone, Map<String, Geometry.Bone> bones, Map<String, Transform> cache,
                                       int depth) {
        String key = bone.name().toLowerCase(Locale.ROOT);
        Transform cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        Transform parent = Transform.IDENTITY;
        if (bone.parent() != null && depth < 64) {
            Geometry.Bone parentBone = bones.get(bone.parent().toLowerCase(Locale.ROOT));
            if (parentBone != null && parentBone != bone) {
                parent = transform(parentBone, bones, cache, depth + 1);
            }
        }
        double[] pivot = toJava(bone.pivot());
        double[] rotation = rotation(bone.rotation());
        // W = W_parent * T(p) * R * T(-p)
        double[] r = multiply(parent.r(), rotation);
        double[] local = subtract(pivot, apply(rotation, pivot));
        double[] t = add(apply(parent.r(), local), parent.t());
        Transform result = new Transform(r, t);
        cache.put(key, result);
        return result;
    }

    private static double[] toJava(float[] bedrock) {
        return new double[]{-bedrock[0], bedrock[1], bedrock[2]};
    }

    /** Java-space rotation matrix of a Bedrock rotation: {@code Rz(rz) * Ry(-ry) * Rx(-rx)}. */
    static double[] rotation(float[] degrees) {
        if (degrees[0] == 0 && degrees[1] == 0 && degrees[2] == 0) {
            return identity();
        }
        return multiply(axis(2, degrees[2]), multiply(axis(1, -degrees[1]), axis(0, -degrees[0])));
    }

    private static double[] axis(int axis, double degrees) {
        double a = Math.toRadians(degrees);
        double c = Math.cos(a);
        double s = Math.sin(a);
        return switch (axis) {
            case 0 -> new double[]{1, 0, 0, 0, c, -s, 0, s, c};
            case 1 -> new double[]{c, 0, s, 0, 1, 0, -s, 0, c};
            default -> new double[]{c, -s, 0, s, c, 0, 0, 0, 1};
        };
    }

    private static double[] identity() {
        return new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
    }

    private static double[] multiply(double[] a, double[] b) {
        double[] out = new double[9];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                out[row * 3 + col] = a[row * 3] * b[col] + a[row * 3 + 1] * b[3 + col] + a[row * 3 + 2] * b[6 + col];
            }
        }
        return out;
    }

    private static double[] apply(double[] m, double[] v) {
        return new double[]{
                m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
                m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
                m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
    }

    private static double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    private static double[] subtract(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    // ------------------------------------------------------------------ elements

    private static final class Element {
        final double[] from;
        final double[] to;
        final double[] origin;
        final double[] rotation;
        final Map<String, Face> faces;

        Element(double[] from, double[] to, double[] origin, double[] rotation, Map<String, Face> faces) {
            this.from = from;
            this.to = to;
            this.origin = origin;
            this.rotation = rotation;
            this.faces = faces;
        }

        JsonObject toJson(double k, Geometry geometry) {
            JsonObject json = new JsonObject();
            json.add("from", vector(this.from, k));
            json.add("to", vector(this.to, k));
            JsonObject rotation = GeometryBaker.rotationJson(this.rotation);
            if (rotation != null) {
                rotation.add("origin", vector(this.origin, k));
                json.add("rotation", rotation);
            }
            JsonObject faces = new JsonObject();
            for (String name : FACES) {
                Face face = this.faces.get(name);
                if (face == null) {
                    continue;
                }
                JsonObject f = new JsonObject();
                JsonArray uv = new JsonArray();
                uv.add(round(clampUv(face.u1 * 16 / geometry.textureWidth())));
                uv.add(round(clampUv(face.v1 * 16 / geometry.textureHeight())));
                uv.add(round(clampUv(face.u2 * 16 / geometry.textureWidth())));
                uv.add(round(clampUv(face.v2 * 16 / geometry.textureHeight())));
                f.add("uv", uv);
                f.addProperty("texture", "#" + face.texture);
                if (face.rotation != 0) {
                    f.addProperty("rotation", face.rotation);
                }
                faces.add(name, f);
            }
            json.add("faces", faces);
            return json;
        }

        private static JsonArray vector(double[] v, double k) {
            JsonArray array = new JsonArray();
            for (int axis = 0; axis < 3; axis++) {
                array.add(round(ANCHOR[axis] + k * (v[axis] - ANCHOR[axis])));
            }
            return array;
        }
    }

    private record Face(double u1, double v1, double u2, double v2, int rotation, String texture) {
    }

    private static @Nullable Element element(Geometry.Cube cube, Transform bone, Geometry geometry,
                                             Function<String, @Nullable String> faceTexture) {
        float[] o = cube.origin();
        float[] s = cube.size();
        float inflate = cube.inflate();
        // Java model space (X mirrored), before the (8, 0, 8) offset.
        double[] min = {-(o[0] + s[0]) - inflate, o[1] - inflate, o[2] - inflate};
        double[] max = {-o[0] + inflate, o[1] + s[1] + inflate, o[2] + s[2] + inflate};
        double[] pivot = cube.pivot() != null ? toJava(cube.pivot())
                : new double[]{(min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2};
        double[] cubeRotation = rotation(cube.rotation());
        double[] total = multiply(bone.r(), cubeRotation);
        double[] origin = add(apply(bone.r(), pivot), bone.t());
        double[] from = new double[3];
        double[] to = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            from[axis] = origin[axis] + (min[axis] - pivot[axis]) + ANCHOR[axis];
            to[axis] = origin[axis] + (max[axis] - pivot[axis]) + ANCHOR[axis];
        }
        double[] javaOrigin = {origin[0] + ANCHOR[0], origin[1] + ANCHOR[1], origin[2] + ANCHOR[2]};
        Map<String, Face> faces = cube.boxUv() != null ? boxFaces(cube, faceTexture) : perFace(cube, faceTexture);
        if (faces.isEmpty()) {
            return null;
        }
        return new Element(from, to, javaOrigin, total, faces);
    }

    private static Map<String, Face> boxFaces(Geometry.Cube cube, Function<String, @Nullable String> faceTexture) {
        float u = cube.boxUv()[0];
        float v = cube.boxUv()[1];
        float sx = cube.size()[0];
        float sy = cube.size()[1];
        float sz = cube.size()[2];
        Map<String, double[]> rects = new HashMap<>();
        rects.put("east", new double[]{u, v + sz, u + sz, v + sz + sy});
        rects.put("west", new double[]{u + sz + sx, v + sz, u + 2 * sz + sx, v + sz + sy});
        rects.put("up", new double[]{u + sz + sx, v + sz, u + sz, v});
        rects.put("down", new double[]{u + sz + 2 * sx, v, u + sz + sx, v + sz});
        rects.put("south", new double[]{u + 2 * sz + sx, v + sz, u + 2 * sz + 2 * sx, v + sz + sy});
        rects.put("north", new double[]{u + sz, v + sz, u + sz + sx, v + sz + sy});
        if (cube.mirror()) {
            double[] east = rects.get("east");
            rects.put("east", rects.get("west"));
            rects.put("west", east);
            for (double[] rect : rects.values()) {
                double swap = rect[0];
                rect[0] = rect[2];
                rect[2] = swap;
            }
        }
        Map<String, Face> faces = new HashMap<>();
        for (String name : FACES) {
            String texture = faceTexture.apply(name);
            if (texture != null) {
                double[] r = rects.get(name);
                faces.put(name, new Face(r[0], r[1], r[2], r[3], 0, texture));
            }
        }
        return faces;
    }

    private static Map<String, Face> perFace(Geometry.Cube cube, Function<String, @Nullable String> faceTexture) {
        Map<String, Face> faces = new HashMap<>();
        for (Map.Entry<String, Geometry.FaceUv> entry : cube.faces().entrySet()) {
            String name = entry.getKey();
            Geometry.FaceUv uv = entry.getValue();
            if (!List.of(FACES).contains(name)) {
                continue;
            }
            String texture = faceTexture.apply(uv.materialInstance() != null ? uv.materialInstance() : name);
            if (texture == null) {
                continue;
            }
            int rotation = Math.floorMod(Math.round(uv.rotation() / 90f) * 90, 360);
            if (name.equals("up") || name.equals("down")) {
                faces.put(name, new Face(uv.u() + uv.width(), uv.v() + uv.height(), uv.u(), uv.v(), rotation, texture));
            } else {
                faces.put(name, new Face(uv.u(), uv.v(), uv.u() + uv.width(), uv.v() + uv.height(), rotation, texture));
            }
        }
        return faces;
    }

    /**
     * Java element rotation for a rotation matrix: null for the identity, the single-axis form
     * ({@code axis}, {@code angle}) when possible, else the 26.3 multi-axis form ({@code x}, {@code y},
     * {@code z}, applied X then Y then Z).
     */
    static @Nullable JsonObject rotationJson(double[] m) {
        if (isIdentity(m)) {
            return null;
        }
        JsonObject json = new JsonObject();
        // Single axis: the matrix leaves that axis unchanged.
        if (near(m[0], 1) && near(m[1], 0) && near(m[2], 0) && near(m[3], 0) && near(m[6], 0)) {
            json.addProperty("axis", "x");
            json.addProperty("angle", round(Math.toDegrees(Math.atan2(m[7], m[8]))));
            return json;
        }
        if (near(m[4], 1) && near(m[1], 0) && near(m[3], 0) && near(m[5], 0) && near(m[7], 0)) {
            json.addProperty("axis", "y");
            json.addProperty("angle", round(Math.toDegrees(Math.atan2(m[2], m[0]))));
            return json;
        }
        if (near(m[8], 1) && near(m[2], 0) && near(m[5], 0) && near(m[6], 0) && near(m[7], 0)) {
            json.addProperty("axis", "z");
            json.addProperty("angle", round(Math.toDegrees(Math.atan2(m[3], m[0]))));
            return json;
        }
        // R = Rz(c) Ry(b) Rx(a)
        double b = Math.asin(Math.max(-1, Math.min(1, -m[6])));
        double a;
        double c;
        if (Math.abs(m[6]) < 1 - EPSILON) {
            a = Math.atan2(m[7], m[8]);
            c = Math.atan2(m[3], m[0]);
        } else {
            a = Math.atan2(-m[5], m[4]);
            c = 0;
        }
        json.addProperty("x", round(Math.toDegrees(a)));
        json.addProperty("y", round(Math.toDegrees(b)));
        json.addProperty("z", round(Math.toDegrees(c)));
        return json;
    }

    private static boolean isIdentity(double[] m) {
        return near(m[0], 1) && near(m[4], 1) && near(m[8], 1) && near(m[1], 0) && near(m[2], 0) && near(m[3], 0)
                && near(m[5], 0) && near(m[6], 0) && near(m[7], 0);
    }

    private static boolean near(double value, double target) {
        return Math.abs(value - target) < EPSILON;
    }

    private static double clampUv(double value) {
        return Math.max(0, Math.min(16, value));
    }

    /** Rounds to 1/10000 so equal inputs always serialise identically. */
    private static double round(double value) {
        double rounded = Math.round(value * 10000) / 10000.0;
        return rounded == 0 ? 0 : rounded;
    }
}
