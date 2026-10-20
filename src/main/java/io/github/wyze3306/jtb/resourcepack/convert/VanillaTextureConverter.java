package io.github.wyze3306.jtb.resourcepack.convert;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Server packs that re-texture vanilla content: each Bedrock vanilla texture they replace is written
 * over the Java vanilla texture(s) it corresponds to ({@code mappings/resourcepack/textures.json}:
 * only pairs whose vanilla images have the same size, so layouts match), with its flipbook animation
 * as {@code .png.mcmeta}. A pack that points a vanilla short name of {@code terrain_texture.json} or
 * {@code item_texture.json} to a new image ({@code "stone": {"textures": "textures/blocks/my_stone"}})
 * re-textures the Java targets of that short name's vanilla texture too.
 * <p>
 * An override is skipped when its aspect ratio differs from the Java texture (a different layout);
 * higher resolutions are kept. Images are converted in parallel.
 */
final class VanillaTextureConverter {

    private VanillaTextureConverter() {
    }

    private record Job(String javaTexture, ResourceTables.JavaTexture target, String sourcePath, String vanillaPath,
                       PackFiles.PackFile image) {
    }

    static void convert(ConversionContext context) {
        ResourceTables tables = context.tables();
        PackStack stack = context.stack();
        Map<String, Job> jobs = new TreeMap<>();
        for (Map.Entry<String, List<String>> entry : tables.sourcesByTarget().entrySet()) {
            for (String source : entry.getValue()) {
                PackFiles.PackFile image = stack.image("textures/" + source);
                if (image != null) {
                    jobs.put(entry.getKey(), new Job(entry.getKey(), target(tables, source, entry.getKey()), source, source, image));
                    break;
                }
            }
        }
        redirect(context, context.definitions().terrainTextures(), tables.terrainAtlas(), jobs);
        redirect(context, context.definitions().itemTextures(), tables.itemAtlas(), jobs);

        List<Job> list = new ArrayList<>(jobs.values());
        list.parallelStream().forEach(job -> write(context, job));
    }

    /** Short names a server pack points to another image: that image replaces the vanilla targets. */
    private static void redirect(ConversionContext context, Map<String, List<String>> serverAtlas,
                                 Map<String, List<String>> vanillaAtlas, Map<String, Job> jobs) {
        ResourceTables tables = context.tables();
        for (Map.Entry<String, List<String>> entry : serverAtlas.entrySet()) {
            List<String> vanilla = vanillaAtlas.get(entry.getKey());
            if (vanilla == null) {
                continue;
            }
            List<String> server = entry.getValue();
            for (int i = 0; i < Math.min(server.size(), vanilla.size()); i++) {
                String path = server.get(i);
                String serverPath = path.startsWith("textures/") ? path.substring("textures/".length()) : path;
                String vanillaPath = vanilla.get(i);
                if (serverPath.equals(vanillaPath) || serverPath.isEmpty()) {
                    continue;
                }
                List<ResourceTables.JavaTexture> targets = tables.textures().get(vanillaPath);
                PackFiles.PackFile image = targets == null ? null : context.stack().image("textures/" + serverPath);
                if (image == null) {
                    continue;
                }
                for (ResourceTables.JavaTexture target : targets) {
                    jobs.put(target.path(), new Job(target.path(), target, serverPath, vanillaPath, image));
                }
            }
        }
    }

    private static ResourceTables.JavaTexture target(ResourceTables tables, String source, String javaTexture) {
        for (ResourceTables.JavaTexture target : tables.textures().get(source)) {
            if (target.path().equals(javaTexture)) {
                return target;
            }
        }
        throw new IllegalStateException("Inconsistent texture table for " + source);
    }

    private static void write(ConversionContext context, Job job) {
        byte[] png = PackImages.toPng(job.image().path(), job.image().data());
        int[] size = png == null ? null : PackImages.size(job.image().path(), png);
        if (png == null || size == null || size[0] <= 0 || size[1] <= 0) {
            context.count("unreadable images");
            return;
        }
        ResourceTables.Flipbook flipbook = context.definitions().flipbook("textures/" + job.sourcePath());
        if (flipbook == null) {
            flipbook = context.definitions().flipbook("textures/" + job.vanillaPath());
        }
        JsonObject animation = ConversionContext.animation(flipbook, size[0], size[1]);
        int frameWidth = size[0];
        int frameHeight = animation != null ? size[0] : size[1];
        ResourceTables.JavaTexture target = job.target();
        if ((long) frameWidth * target.height() != (long) frameHeight * target.width()) {
            context.count("texture overrides skipped (layout differs)");
            return;
        }
        String base = "assets/minecraft/textures/" + job.javaTexture() + ".png";
        context.writer().put(base, png);
        if (animation != null) {
            context.writer().putJson(base + ".mcmeta", animation);
        }
        context.count("vanilla texture overrides");
    }
}
