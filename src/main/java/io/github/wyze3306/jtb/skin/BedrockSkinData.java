package io.github.wyze3306.jtb.skin;

/**
 * A skin in the form the Bedrock login and {@code PlayerSkinPacket} expect.
 *
 * @param skinId     unique skin id
 * @param width      image width (64 or 128)
 * @param height     image height (32, 64 or 128)
 * @param rgba       raw RGBA pixels, {@code width * height * 4} bytes
 * @param slim       Alex-style 3 px arms
 * @param capeRgba   raw RGBA cape pixels (64x32) or an empty array
 */
public record BedrockSkinData(String skinId, int width, int height, byte[] rgba, boolean slim, byte[] capeRgba) {

    public String geometryName() {
        if (this.height == 32) {
            return "geometry.humanoid";
        }
        return this.slim ? "geometry.humanoid.customSlim" : "geometry.humanoid.custom";
    }

    /** {@code {"geometry":{"default":"..."}}}, the patch every vanilla skin carries. */
    public String resourcePatch() {
        return "{\"geometry\":{\"default\":\"" + geometryName() + "\"}}";
    }

    public String armSize() {
        return this.slim ? "slim" : "wide";
    }
}
