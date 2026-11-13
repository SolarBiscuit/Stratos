package com.solarbiscuit.atmosphera.stratos.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.solarbiscuit.atmosphera.lib.client.AtmosClient;
import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;
import com.solarbiscuit.atmosphera.lib.feature.Capability;
import com.solarbiscuit.atmosphera.lib.gpu.FrameUniforms;
import com.solarbiscuit.atmosphera.lib.gpu.GlStateSnapshot;
import com.solarbiscuit.atmosphera.lib.gpu.GlslPrograms;
import com.solarbiscuit.atmosphera.lib.gpu.GpuResources;
import com.solarbiscuit.atmosphera.lib.gpu.GpuTimers;
import com.solarbiscuit.atmosphera.lib.gpu.PostPasses;
import com.solarbiscuit.atmosphera.lib.gpu.RenderTargets;
import com.solarbiscuit.atmosphera.lib.wind.AtmosClock;
import com.solarbiscuit.atmosphera.lib.wind.Wind;
import com.solarbiscuit.atmosphera.lib.wind.WindVector;
import com.solarbiscuit.atmosphera.lib.world.ColumnField;
import com.solarbiscuit.atmosphera.lib.world.ColumnFieldShader;
import com.solarbiscuit.atmosphera.lib.world.Weather;
import com.solarbiscuit.atmosphera.stratos.Stratos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.system.MemoryStack;

/**
 * The rain (0.2.0 "Fall"): one instanced draw of thin streaks, built and animated in the vertex shader, so the CPU
 * builds no vertices (vanilla rebuilds up to about 1,800 quads a frame on the render thread).
 * <ul>
 *   <li>Each streak's place is a hash of its index in a 48x48-block tile that wraps round the camera, fixed in the
 *   world, so the rain does not slide as you walk; it falls through a 44-block column above and below the eye.</li>
 *   <li>How many streaks: the Weather service's intensity (drizzle a few, a downpour many), times the density option.</li>
 *   <li>Slant: the shared wind ({@code Wind.sample}, Gale's when installed), so Gale's tilt of vanilla's rain is not
 *   needed for these streaks.</li>
 *   <li>Roofs: a streak below the column's rain top (the library's column field) is not drawn; the scene's depth
 *   hides streaks behind terrain.</li>
 *   <li>The streak's alpha profile comes from the resource pack's {@code rain.png}, so rain packs still change it.</li>
 * </ul>
 * Vanilla still draws snow, and its rain where Stratos does not (the {@code LevelRendererPrecipitationMixin} hides
 * vanilla's rain columns only while this pass drew the rain last frame). The far rain is Atmosphera's rain shafts under
 * raining clouds. Splash particles and sounds stay vanilla's. Off under a shaderpack (the feature defers).
 */
public final class RainRenderer {
    private static final ResourceLocation RAIN = new ResourceLocation("textures/environment/rain.png");
    /** 0.4.0: heavy rain and storms draw more streaks (12000 before). */
    private static final int MAX_STREAKS = 16000;
    /** 0.4.0: sand streaks at full sandstorm, before the density option. */
    private static final int SAND_STREAKS = 6000;
    /** 0.4.0: the sandstorm's strength here, eased over about 5 s. */
    private static float sandLevel;
    private static AtmosFeature feature;
    private static int program;
    private static int[] u;
    private static int vao;
    private static boolean failed;
    private static long drawnFrame = -1;
    private static final Weather.Sample WEATHER = new Weather.Sample();
    private static final WindVector WIND = new WindVector();
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();
    private static final Object COLUMN_OWNER = new Object();

    private RainRenderer() {
    }

    static void register() {
        feature = AtmosFeature.builder(Stratos.MOD_ID, "rain")
                .provides(Capability.POST_PROCESS)
                .uses(AtmosFeature.Uses.WORLD_PASS)
                .switchedBy(StratosClient.rain)
                .register();
        PostPasses.register(feature, PostPasses.Stage.CLOUDS, 20, "stratos_rain", RainRenderer::render);
        com.solarbiscuit.atmosphera.lib.options.AtmosReload.register(Stratos.MOD_ID, RainRenderer::release);
    }

    /**
     * Whether vanilla's rain columns should be left out this frame: the pass drew the rain last frame or this one.
     * Render thread.
     */
    public static boolean replacesVanilla() {
        return feature != null && feature.isActive() && AtmosClient.frame() - drawnFrame <= 1;
    }

    private static void render(PostPasses.Context ctx) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || failed || Minecraft.useShaderTransparency()) {
            return;
        }
        float partial = ctx.partialTick();
        float rain = level.getRainLevel(partial);
        Vec3 camera = ctx.camera();
        float dt = (float) Math.min(0.25, Math.max(0.0, com.solarbiscuit.atmosphera.lib.wind.AtmosClock.deltaSeconds()));
        POS.set(camera.x, camera.y, camera.z);
        net.minecraft.core.Holder<Biome> biome = level.getBiome(POS);
        Biome.Precipitation precipitation = biome.value().getPrecipitationAt(POS);
        Weather.current(level, partial, WEATHER);
        Weather.Type kind = WEATHER.modelled ? WEATHER.type : Weather.Type.RAIN;
        // 0.4.0: a rain spell over a dry sandy biome blows sand (Sandstorm), eased in and out over about 5 s.
        boolean sandy = precipitation == Biome.Precipitation.NONE && com.solarbiscuit.atmosphera.stratos.Sandstorm.sandy(biome, POS);
        Wind.sample(level, camera.x, camera.y, camera.z, partial, WIND);
        float sandTarget = sandy && rain > 0.01f
                ? com.solarbiscuit.atmosphera.stratos.Sandstorm.strength(kind) * (0.6f + 0.4f * WIND.strength) * Math.min(1f, rain * 1.5f)
                : 0f;
        sandLevel += (sandTarget - sandLevel) * (1f - (float) Math.exp(-dt / 5f));
        boolean sand = sandy && sandLevel > 0.01f;
        if ((rain < 0.01f && !sand) || mc.gameRenderer.getMainCamera().getFluidInCamera() != FogType.NONE) {
            return;
        }
        if (precipitation != Biome.Precipitation.RAIN && !sand) {
            // Snow (or a dry biome with no storm) here: vanilla draws it.
            return;
        }
        RenderTargets.Scene scene = RenderTargets.scene();
        if (!scene.usable() || program == 0 && !compile()) {
            return;
        }
        ColumnField.use(COLUMN_OWNER, level, 256);
        float intensity = Math.max(WEATHER.intensity, 0.05f);
        // 0.4.0: the look of each kind of rain (count, length, width, opacity, darkness, fall speed, slant).
        float countK = 1f, lenK = 1f, widthK = 1f, alphaK = 1f, dark = 1f, speedK = 1f, slantK = 1f;
        switch (kind) {
            case DRIZZLE -> {
                countK = 0.6f;
                lenK = 0.6f;
                widthK = 0.8f;
                alphaK = 0.7f;
                speedK = 0.85f;
            }
            case LIGHT_RAIN -> {
                countK = 0.8f;
                lenK = 0.85f;
                alphaK = 0.85f;
            }
            case HEAVY_RAIN, STORM -> {
                countK = 1.35f;
                lenK = 1.25f;
                widthK = 1.1f;
                alphaK = 1.15f;
                dark = 0.8f;
                slantK = kind == Weather.Type.STORM ? 1.3f : 1f;
            }
            default -> {
            }
        }
        float density = StratosClient.density.get().floatValue();
        int count = sand ? (int) (SAND_STREAKS * sandLevel * density)
                : (int) Math.min(MAX_STREAKS, 9000f * intensity * countK * density);
        if (count <= 0) {
            return;
        }
        float slant = (0.15f + 0.55f * WIND.strength + 0.2f * WIND.gust) * slantK;
        float[] fog = RenderSystem.getShaderFogColor();
        float sky = level.getSkyDarken(partial);
        FrameUniforms.update(AtmosClient.frame());
        GlStateSnapshot saved = GlStateSnapshot.save();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!RenderTargets.bindColorOnly(scene)) {
                return;
            }
            GpuTimers.begin("stratos_rain_draw");
            GL13C.glActiveTexture(GL13C.GL_TEXTURE12);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, scene.depthTexture());
            GL13C.glActiveTexture(GL13C.GL_TEXTURE13);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, mc.getTextureManager().getTexture(RAIN).getId());
            GL13C.glActiveTexture(GL13C.GL_TEXTURE14);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, ColumnField.ready() ? ColumnField.texture() : 0);
            GL20C.glUseProgram(program);
            GL20C.glUniform1i(u[0], 12);
            GL20C.glUniform1i(u[1], 13);
            GL20C.glUniform1i(u[2], 14);
            GL20C.glUniformMatrix4fv(u[3], false, ctx.viewProjection().get(stack.mallocFloat(16)));
            double t = AtmosClock.worldSeconds(level, partial);
            // The camera's place within the 48-block tile (double precision here, small floats in the shader).
            GL20C.glUniform4f(u[4], (float) floorMod(camera.x, 48.0), (float) camera.y, (float) floorMod(camera.z, 48.0),
                    (float) (t % 3600.0));
            float opacity = StratosClient.opacity.get().floatValue();
            if (sand) {
                // Sand: the wind's direction (not the slant), the sand's colour in the day's light, faster in a storm.
                float[] col = com.solarbiscuit.atmosphera.stratos.Sandstorm.colour(biome);
                float light = 0.25f + 0.75f * sky;
                GL20C.glUniform4f(u[5], WIND.dirX, WIND.dirZ, ColumnField.ready() ? 1f : 0f,
                        0.42f * opacity * Math.min(1f, 0.4f + sandLevel));
                GL20C.glUniform4f(u[6], col[0] * light, col[1] * light, col[2] * light, 1f);
                GL20C.glUniform4f(u[7], 1f, 1f, 8f + 12f * sandLevel, 1f);
            } else {
                GL20C.glUniform4f(u[5], WIND.dirX * slant, WIND.dirZ * slant, ColumnField.ready() ? 1f : 0f,
                        0.5f * opacity * alphaK * Math.min(1f, rain * 1.5f));
                GL20C.glUniform4f(u[6], fog[0] * sky, fog[1] * sky, fog[2] * sky, dark);
                GL20C.glUniform4f(u[7], lenK, widthK, speedK, 0f);
            }
            GL11C.glEnable(GL11C.GL_BLEND);
            GL14C.glBlendFuncSeparate(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA, GL11C.GL_ZERO, GL11C.GL_ONE);
            GL30C.glBindVertexArray(vao);
            GL31C.glDrawArraysInstanced(GL11C.GL_TRIANGLES, 0, 6, count);
            GL11C.glDisable(GL11C.GL_BLEND);
            GpuTimers.end("stratos_rain_draw");
            drawnFrame = AtmosClient.frame();
        } finally {
            for (int unit = 12; unit <= 14; unit++) {
                GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
            }
            saved.restore();
        }
    }

    private static double floorMod(double v, double m) {
        return v - Math.floor(v / m) * m;
    }

    private static boolean compile() {
        program = GlslPrograms.compile("Stratos rain", VERTEX, GlslPrograms.header() + ColumnFieldShader.GLSL + FRAGMENT);
        if (program == 0) {
            Stratos.LOGGER.error("[Atmosphera] Stratos rain is off: its program did not compile (see above)");
            failed = true;
            return false;
        }
        String[] names = {"u_Depth", "u_Rain", "atmos_ColumnField", "u_ViewProj", "u_Cam", "u_Wind", "u_Col", "u_Kind"};
        u = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            u[i] = GL20C.glGetUniformLocation(program, names[i]);
        }
        vao = GpuResources.vertexArray("Stratos rain");
        return true;
    }

    /** Frees the program (the lib's shader reload). */
    public static void release() {
        if (program != 0) {
            GpuResources.deleteProgram(program);
            program = 0;
        }
        if (vao != 0) {
            GpuResources.deleteVertexArray(vao);
            vao = 0;
        }
        failed = false;
    }

    /** Streaks from gl_InstanceID and gl_VertexID alone (no vertex buffer). */
    static final String VERTEX = """
            #version 330 core
            uniform mat4 u_ViewProj;
            uniform vec4 u_Cam;    // xyz: the camera's place in the 48-block tile (x, z) and its world y; w: seconds
            uniform vec4 u_Wind;   // xy: slant (horizontal blocks per block of fall), z: 1 = column field bound, w: alpha
            uniform vec4 u_Kind;   // 0.4.0: x length, y width, z fall speed (sand: blowing speed), w 1 = sand
            out vec2 v_Uv;
            out vec3 v_Rel;
            out float v_Fade;
            out float v_Variant;

            uint rain_hash(uint x) {
                x ^= x >> 16; x *= 0x7feb352du; x ^= x >> 15; x *= 0x846ca68bu; x ^= x >> 16;
                return x;
            }
            float rain_unit(uint h) {
                return float(h >> 8) / 16777216.0;
            }

            void main() {
                uint id = uint(gl_InstanceID);
                uint h = rain_hash(id * 2654435761u + 17u);
                // A fixed place in the world's 48-block tiles, wrapped into the tile round the camera.
                vec2 p = vec2(rain_unit(h), rain_unit(rain_hash(h))) * 48.0;
                int corner = gl_VertexID % 6;
                vec2 q = corner == 0 ? vec2(0.0, 0.0) : corner == 1 ? vec2(1.0, 0.0) : corner == 2 ? vec2(1.0, 1.0)
                        : corner == 3 ? vec2(0.0, 0.0) : corner == 4 ? vec2(1.0, 1.0) : vec2(0.0, 1.0);
                if (u_Kind.w > 0.5) {
                    // 0.4.0 sandstorm: short soft streaks blowing nearly level along the wind, most of them low over the
                    // ground, each at its own speed and height, swirling a little.
                    vec2 wdir = normalize(u_Wind.xy + vec2(1e-4, 0.0));
                    float spd = u_Kind.z * (0.7 + 0.6 * rain_unit(rain_hash(h + 1u)));
                    vec2 srel = mod(p + wdir * (u_Cam.w * spd) - u_Cam.xz + 24.0, 48.0) - 24.0;
                    float hy = rain_unit(rain_hash(h + 2u));
                    float sy = -3.0 + 14.0 * hy * hy + 0.6 * sin(u_Cam.w * 1.7 + float(h & 255u));
                    vec3 along = normalize(vec3(wdir.x, 0.12 * (rain_unit(rain_hash(h + 5u)) - 0.5), wdir.y));
                    vec3 sbase = vec3(srel.x, sy, srel.y);
                    float slen = u_Kind.x * (0.35 + 0.5 * rain_unit(rain_hash(h + 3u)));
                    vec3 sside = normalize(cross(along, normalize(sbase) + vec3(0.0, 1e-4, 0.0)));
                    float swidth = u_Kind.y * (0.03 + 0.002 * length(sbase));
                    vec3 spos = sbase + sside * (q.x - 0.5) * swidth + along * q.y * slen;
                    v_Uv = q;
                    v_Rel = spos;
                    v_Variant = float(h & 3u);
                    v_Fade = smoothstep(0.6, 2.0, length(sbase)) * (1.0 - smoothstep(18.0, 24.0, length(sbase.xz)));
                    gl_Position = u_ViewProj * vec4(spos, 1.0);
                    return;
                }
                vec2 rel = mod(p - u_Cam.xz + 24.0, 48.0) - 24.0;
                // Falling at 8-11 blocks a second through a 44-block column round the eye.
                float speed = (8.0 + 3.0 * rain_unit(rain_hash(h + 1u))) * u_Kind.z;
                float phase = rain_unit(rain_hash(h + 2u));
                float y = 22.0 - mod(u_Cam.w * speed + phase * 44.0, 44.0);
                float len = (0.55 + 0.4 * rain_unit(rain_hash(h + 3u))) * u_Kind.x;
                // 0.2.2: each streak has its own slant (+-25%), and a slow gust swell per 16-block patch of the tile
                // tilts the rain there more or less over time, so the angle varies across the view.
                vec2 cell = floor(p / 16.0);
                float cellPhase = fract(sin(dot(cell, vec2(12.9898, 78.233))) * 43758.5453) * 6.2832;
                float swell = 1.0 + 0.3 * sin(u_Cam.w * 0.7 + cellPhase);
                vec2 slant = u_Wind.xy * (0.75 + 0.5 * rain_unit(rain_hash(h + 4u))) * swell;
                // The tail points upwind (up and back along the fall); the drop moves downwind as it falls (0.2.1 had
                // the sign the other way: streaks drifted upwind and looked as if falling backwards).
                vec3 fall = normalize(vec3(-slant.x, 1.0, -slant.y));
                vec3 base = vec3(rel.x, y, rel.y) - vec3(slant.x, 0.0, slant.y) * y;
                // A thin quad along the fall, turned to face the eye.
                vec3 side = normalize(cross(fall, normalize(base) + vec3(0.0, 1e-4, 0.0)));
                float width = (0.022 + 0.0025 * length(base)) * u_Kind.y;
                vec3 pos = base + side * (q.x - 0.5) * width + fall * q.y * len;
                v_Uv = q;
                v_Rel = pos;
                v_Variant = float(h & 3u);
                // Thin out near the camera (vanilla's splashes and the screen edge) and far away.
                float d = length(base.xz);
                v_Fade = smoothstep(0.6, 2.0, length(base)) * (1.0 - smoothstep(18.0, 24.0, d));
                gl_Position = u_ViewProj * vec4(pos, 1.0);
            }
            """;

    static final String FRAGMENT = """
            uniform sampler2D u_Depth;
            uniform sampler2D u_Rain;
            uniform vec4 u_Wind;
            uniform vec4 u_Col;    // rgb: the light of the air (fog colour times daylight); w: 0.4.0 darkness (rain)
            uniform vec4 u_Kind;   // 0.4.0: w 1 = sand
            in vec2 v_Uv;
            in vec3 v_Rel;
            in float v_Fade;
            in float v_Variant;
            out vec4 fragColor;

            void main() {
                // Behind the scene: hidden.
                float scene = texelFetch(u_Depth, ivec2(gl_FragCoord.xy), 0).r;
                if (gl_FragCoord.z > scene) {
                    discard;
                }
                // Under a roof (below the column's rain top): no rain.
                if (u_Wind.z > 0.5) {
                    float top = atmos_rainTop(v_Rel);
                    if (top > ATMOS_NO_COLUMN && v_Rel.y + atmos_CameraPos.y < top) {
                        discard;
                    }
                }
                if (u_Kind.w > 0.5) {
                    // Sand: a soft streak, fading at both ends.
                    float sa = (1.0 - abs(v_Uv.x * 2.0 - 1.0)) * smoothstep(0.0, 0.35, v_Uv.y) * smoothstep(1.0, 0.6, v_Uv.y)
                            * u_Wind.w * v_Fade;
                    if (sa < 0.003) {
                        discard;
                    }
                    fragColor = vec4(u_Col.rgb, sa);
                    return;
                }
                // The streak's own profile, modulated by the resource pack's rain texture (0.2.1: the texture alone is
                // mostly transparent between its drops, so most streaks drew nothing).
                vec2 uv = vec2((v_Variant + v_Uv.x) * 0.25, v_Uv.y * 0.25);
                float a = mix(0.55, 1.0, texture(u_Rain, uv).a) * (1.0 - abs(v_Uv.x * 2.0 - 1.0));
                a *= u_Wind.w * v_Fade * smoothstep(0.0, 0.2, v_Uv.y) * smoothstep(1.0, 0.7, v_Uv.y);
                if (a < 0.003) {
                    discard;
                }
                // 0.4.0: heavy rain and storms a little darker (u_Col.w).
                fragColor = vec4(mix(u_Col.rgb, vec3(0.75, 0.8, 0.88), 0.35) * u_Col.w, a);
            }
            """;
}
