package io.github.wyze3306.jtb.world.chunk;

import io.github.wyze3306.jtb.block.BlockMapper;
import io.github.wyze3306.jtb.block.DerivedStates;
import io.github.wyze3306.jtb.block.JavaBlockStates;
import org.jspecify.annotations.Nullable;

/**
 * Applies {@link DerivedStates} to a freshly decoded column, reading only that column: blocks of the
 * neighbouring columns read as air, the session loop fixes the borders once they are loaded. Runs on
 * the decoding worker; the sections are replaced in the array (copy on write).
 */
public final class DerivedStatePass {

    private DerivedStatePass() {
    }

    /** Derives every block of the column that has derived properties; returns how many states changed. */
    public static int deriveColumn(@Nullable ChunkSection[] sections, ChunkLayout layout, BlockMapper mapper) {
        DerivedStates.View view = (x, y, z) -> {
            if ((x | z) >>> 4 != 0) {
                return JavaBlockStates.AIR;
            }
            int index = layout.sectionIndex(y);
            ChunkSection section = index < 0 ? null : sections[index];
            return section == null ? JavaBlockStates.AIR : section.javaAt(BlockStorage.index(x, y & 15, z));
        };
        int air = mapper.bedrockAir();
        int changed = 0;
        int minY = layout.javaMinY();
        for (int s = 0; s < sections.length; s++) {
            ChunkSection section = sections[s];
            if (section == null || !section.java().paletteContains(DerivedStates::isDerived)) {
                continue;
            }
            BlockStorage java = section.java();
            int[] palette = java.palette();
            boolean[] derived = new boolean[palette.length];
            for (int p = 0; p < palette.length; p++) {
                derived[p] = DerivedStates.isDerived(palette[p]);
            }
            ChunkSection.Editor editor = null;
            for (int i = 0; i < BlockStorage.SIZE; i++) {
                if (!derived[java.paletteIndex(i)]) {
                    continue;
                }
                BlockStorage layer1 = section.layer1();
                int raw = mapper.toJava(section.layer0().get(i), layer1 == null ? air : layer1.get(i));
                int state = DerivedStates.derive(raw, view, i & 15, minY + (s << 4) + (i >> 8), (i >> 4) & 15);
                if (state != java.get(i)) {
                    if (editor == null) {
                        editor = section.edit(mapper);
                    }
                    editor.setJava(i, state);
                    changed++;
                }
            }
            if (editor != null) {
                // The view keeps reading the original section: derivation never depends on derived properties.
                sections[s] = editor.build();
            }
        }
        return changed;
    }
}
