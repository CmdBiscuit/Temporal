package com.temporal.client.bridge;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.Window;
import com.mojang.logging.LogUtils;
import com.temporal.TemporalConfig;
import com.temporal.client.FullscreenPass;
import com.temporal.client.Upscaler;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/**
 * Frame generation: DLSS Frame Generation (Streamline DLSS-G, with the Reflex/PCL frame markers it depends on) or AMD
 * FSR 3 frame generation ({@link FsrFrameGeneration}) where DLSS-G isn't available or isn't chosen.
 *
 * <p>Both take the same inputs: the HUD-less scene (copied just before the GUI is drawn), depth and motion vectors,
 * handed to Vulkan with each presented frame. For DLSS-G, every Minecraft frame gets one Streamline frame token, shared
 * by DLSS, Reflex and DLSS-G, and one set of constants; while frames are presented through Streamline's swapchain
 * proxy ({@link Presenter}), each frame also gets a Reflex sleep and the PCL markers around simulation, rendering and
 * present. At present the inputs are tagged; Streamline copies them inside our present command buffer, so the shared
 * images are free again as soon as that finishes, and DLSS-G then presents the generated frames from its own queue.
 */
public final class FrameGeneration {
    private static final Logger LOGGER = LogUtils.getLogger();

    // sl::PCLMarker
    private static final int SIMULATION_START = 0;
    private static final int SIMULATION_END = 1;
    private static final int RENDER_SUBMIT_START = 2;
    private static final int RENDER_SUBMIT_END = 3;
    private static final int PRESENT_START = 4;
    private static final int PRESENT_END = 5;
    // sl::DLSSGMode, sl::DLSSGFlags, sl::ReflexMode, sl::Boolean
    private static final int MODE_OFF = 0;
    private static final int MODE_ON = 1;
    private static final int SHOW_ONLY_INTERPOLATED_FRAME = 1;
    private static final int RETAIN_RESOURCES_WHEN_OFF = 1 << 3;
    private static final int REFLEX_OFF = 0;
    private static final int REFLEX_LOW_LATENCY = 1;
    private static final byte BOOLEAN_INVALID = 2;
    private static final int BUFFER_HUDLESS_COLOR = 2;
    private static final int LIFECYCLE_ONLY_VALID_NOW = 0;
    private static final int TAG_COUNT = 3;
    /** Frames generation stays off after its swapchain was (re)created, e.g. when the window changed size. */
    private static final int SETTLE_FRAMES = 3;
    /** Consecutive frames DLSS-G may report a failure while on before it is given up for the session. */
    private static final int FAILURE_LIMIT = 120;
    private static final String[] STATUS_NAMES = {"output resolution too low", "Reflex not detected",
            "HDR format not supported", "constants invalid", "GetCurrentBackBufferIndex not called"};

    /** Debug switch: only generated frames are shown, so a screen capture is guaranteed to be one. */
    public static boolean debugShowOnlyGenerated;
    /** Debug switch: leave the first-person hand's motion as the world's (to see what the hand fix-up does). */
    public static boolean debugNoHandMask;

    /** Which frame generation runs. */
    public enum Method {
        DLSS("DLSS"),
        FSR("FSR 3");

        final String displayName;

        Method(String displayName) {
            this.displayName = displayName;
        }
    }

    /** Why DLSS-G can't run this session, or null. */
    @Nullable private static String unavailableReason = Bridge.osSupported() ? null : Bridge.WINDOWS_ONLY;
    /** Why FSR 3 frame generation can't run this session, or null. */
    @Nullable private static String fsrUnavailableReason = Bridge.osSupported() ? null : Bridge.WINDOWS_ONLY;
    /** The method presenting frames right now (see {@link #running}), or null. */
    @Nullable private static Method runningMethod;
    private static int maxGeneratedFrames;
    private static int minWidthOrHeight;
    /** The setting the player was last told about (see {@link #tellPlayerOnce}). */
    @Nullable private static TemporalConfig.FrameGenerationMode toldAbout;
    @Nullable private static Method toldAboutMethod;

    // One Streamline frame token per Minecraft frame.
    private static int frameIndex;
    private static long token;
    private static int tokenIndex = -1;
    private static boolean constantsSet;
    private static boolean markersRunning;
    private static boolean hudlessCaptured;

    // Native Streamline structs, allocated on first use and kept.
    private static long viewport;
    private static long options;
    private static long state;
    private static long constants;
    private static long resources;
    private static long tags;
    private static long reflexOptions;

    @Nullable private static SharedImage hudless;
    /**
     * Frames are presented through a frame generation swapchain ({@link #runningMethod}'s). For DLSS-G, Reflex is on
     * and the markers run.
     */
    private static boolean running;
    private static boolean activeThisFrame;
    private static boolean taggedLastFrame;
    private static int appliedMode = -1;
    private static int appliedFrames = -1;
    private static int appliedFlags = -1;
    private static int settleFrames;
    /** Reflex frame limit currently applied, in microseconds per rendered frame (0 = none). */
    private static int appliedFrameLimitUs = -1;
    private static int lastStatus;
    private static int failingFrames;
    private static int framesPresented;
    private static long generatedTotal;
    // The window's place when its monitor's refresh rate was last looked up (see monitorRefreshRate)
    private static int monitorWindowX = Integer.MIN_VALUE;
    private static int monitorWindowY;
    private static int monitorWindowWidth;
    private static int monitorWindowHeight;
    private static int refreshRate;
    // FSR 3: what reached the screen, from FidelityFX's present counter
    private static long lastPresentCount = -1;
    private static double shownPerFrameAverage;

    /** DLSS-G can't run this session. */
    static void markUnavailable(String reason) {
        markUnavailable(Method.DLSS, reason);
    }

    static void markUnavailable(Method method, String reason) {
        if (method == Method.DLSS && unavailableReason == null) {
            unavailableReason = reason;
            LOGGER.warn("Temporal: {}", reason);
        } else if (method == Method.FSR && fsrUnavailableReason == null) {
            fsrUnavailableReason = reason;
            LOGGER.warn("Temporal: {}", reason);
        }
    }

    /** The Vulkan side can't start at all: no frame generation of any kind. */
    static void markAllUnavailable(String reason) {
        markUnavailable(Method.DLSS, reason);
        markUnavailable(Method.FSR, reason);
    }

    /** Gives up the frame generation in use (or about to be) for the session, with a chat message saying why. */
    static void disable(String reason, @Nullable Throwable cause) {
        Method method = runningMethod != null ? runningMethod : method();
        disable(method != null ? method : Method.DLSS, reason, cause);
    }

    static void disable(Method method, String reason, @Nullable Throwable cause) {
        if ((method == Method.DLSS ? unavailableReason : fsrUnavailableReason) != null) {
            return;
        }
        LOGGER.error("Temporal: {} frame generation stopped: {}", method.displayName, reason, cause);
        markUnavailable(method, reason);
        String detail = cause != null && cause.getMessage() != null ? ": " + cause.getMessage() : "";
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui != null) {
            minecraft.gui.getChat().addMessage(Component.translatable("temporal.frameGeneration.unavailable", reason + detail));
        }
    }

    /** Frame generation is switched on in the settings and nothing has ruled out the chosen kind on this PC. */
    public static boolean wanted() {
        return requestedInSettings() && method() != null;
    }

    /**
     * The frame generation that runs when it's switched on: the chosen one, or for Automatic DLSS-G where it works and
     * FSR 3 everywhere else; null if the chosen one can't run here. Before the Vulkan side has started, DLSS-G counts
     * as available; starting it settles the question.
     */
    @Nullable
    public static Method method() {
        TemporalConfig.FrameGenerationMethod choice;
        try {
            choice = TemporalConfig.FRAME_GENERATION_METHOD.get();
        } catch (IllegalStateException notLoadedYet) {
            choice = TemporalConfig.FrameGenerationMethod.AUTOMATIC;
        }
        if (choice != TemporalConfig.FrameGenerationMethod.FSR && unavailableReason == null) {
            return Method.DLSS;
        }
        if (choice != TemporalConfig.FrameGenerationMethod.DLSS && fsrUnavailableReason == null) {
            return Method.FSR;
        }
        return null;
    }

    private static int requestedGeneratedFrames() {
        try {
            return Math.max(1, Math.min(TemporalConfig.FRAME_GENERATION.get().generatedFrames(), maxGeneratedFrames));
        } catch (IllegalStateException notLoadedYet) {
            return 1;
        }
    }

    /** Checks DLSS-G and Reflex on our device once Streamline knows it, and reads what this GPU can do. */
    static void probe(Streamline streamline, VulkanContext vulkan) {
        if (unavailableReason != null) {
            return;
        }
        for (int feature : new int[]{Streamline.FEATURE_DLSS_G, Streamline.FEATURE_REFLEX}) {
            int supported = streamline.isFeatureSupported(feature, vulkan.physicalDevice);
            if (supported != 0) {
                markUnavailable("DLSS Frame Generation is not supported here (feature " + feature + ": "
                        + Streamline.resultName(supported) + ")");
                return;
            }
        }
        try {
            streamline.loadFrameGeneration();
        } catch (IllegalStateException e) {
            markUnavailable("DLSS Frame Generation could not be loaded: " + e.getMessage());
            return;
        }
        allocate();
        long reflex = Streamline.allocate(Streamline.SIZE_REFLEX_STATE, Streamline.REFLEX_STATE, 2);
        try {
            int result = getState(streamline);
            int reflexResult = streamline.reflexGetState(reflex);
            maxGeneratedFrames = MemoryUtil.memGetInt(state + 52);
            minWidthOrHeight = MemoryUtil.memGetInt(state + 44);
            LOGGER.info("Temporal: DLSS-G state {} (status {}, up to {} generated frames, min size {}, vsync support {}); "
                            + "Reflex state {} (low latency available {})",
                    Streamline.resultName(result), MemoryUtil.memGetInt(state + 40), maxGeneratedFrames, minWidthOrHeight,
                    MemoryUtil.memGetByte(state + 57), Streamline.resultName(reflexResult), MemoryUtil.memGetByte(reflex + 32));
            if (result != 0 || maxGeneratedFrames < 1) {
                markUnavailable("DLSS Frame Generation is not available on this GPU");
            }
        } finally {
            MemoryUtil.nmemFree(reflex);
        }
    }

    private static void allocate() {
        if (viewport != 0) {
            return;
        }
        viewport = Streamline.allocate(Streamline.SIZE_VIEWPORT_HANDLE, Streamline.VIEWPORT_HANDLE, 1);
        MemoryUtil.memPutInt(viewport + 32, DlssBackend.VIEWPORT_ID);
        options = Streamline.allocate(Streamline.SIZE_DLSSG_OPTIONS, Streamline.DLSSG_OPTIONS, 5);
        state = Streamline.allocate(Streamline.SIZE_DLSSG_STATE, Streamline.DLSSG_STATE, 4);
        constants = Streamline.allocate(Streamline.SIZE_CONSTANTS, Streamline.CONSTANTS, 2);
        resources = MemoryUtil.nmemCalloc(TAG_COUNT, Streamline.SIZE_RESOURCE);
        tags = MemoryUtil.nmemCalloc(TAG_COUNT, Streamline.SIZE_RESOURCE_TAG);
        reflexOptions = Streamline.allocate(Streamline.SIZE_REFLEX_OPTIONS, Streamline.REFLEX_OPTIONS, 1);
    }

    private static int getState(Streamline streamline) {
        MemoryUtil.memSet(state + 32, 0, Streamline.SIZE_DLSSG_STATE - 32);
        Streamline.header(state, Streamline.DLSSG_STATE, 4);
        return streamline.dlssgGetState(viewport, state);
    }

    // ---- frame timeline (all on the render thread) ----

    static int frameIndex() {
        return frameIndex;
    }

    /** Streamline's token for the current frame, created on first use. */
    static long frameToken(Streamline streamline) {
        if (tokenIndex != frameIndex) {
            token = streamline.frameToken(frameIndex);
            tokenIndex = frameIndex;
        }
        return token;
    }

    /** DLSS has set this frame's constants; frame generation reuses them. */
    static void constantsSet() {
        constantsSet = true;
    }

    /** Start of a Minecraft frame ({@code Minecraft.runTick}): Reflex sleep, then the simulation-start marker. */
    public static void beginFrame() {
        frameIndex++;
        constantsSet = false;
        hudlessCaptured = false;
        markersRunning = false;
        tellPlayerOnce();
        Streamline streamline = Bridge.streamline();
        if (running && runningMethod == Method.DLSS && streamline != null) {
            long frame = frameToken(streamline);
            streamline.reflexSleep(frame);
            streamline.pclSetMarker(SIMULATION_START, frame);
            markersRunning = true;
        }
    }

    /**
     * Frame generation was chosen in the settings but this PC can't do it, or not at the chosen multiplier: say so in
     * chat once per choice instead of silently doing nothing (or less).
     */
    private static void tellPlayerOnce() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !requestedInSettings()) {
            return;
        }
        TemporalConfig.FrameGenerationMode requested = TemporalConfig.FRAME_GENERATION.get();
        Method method = method();
        if (requested == toldAbout && method == toldAboutMethod) {
            return;
        }
        if (method == null) {
            minecraft.gui.getChat().addMessage(Component.translatable("temporal.frameGeneration.unavailable", unavailableReason()));
        } else if (method == Method.FSR && requested.generatedFrames() > 1) {
            minecraft.gui.getChat().addMessage(Component.translatable("temporal.frameGeneration.limitedFsr"));
        } else if (method == Method.DLSS && maxGeneratedFrames > 0 && requested.generatedFrames() > maxGeneratedFrames) {
            minecraft.gui.getChat().addMessage(Component.translatable("temporal.frameGeneration.limited", maxGeneratedFrames + 1));
        } else {
            return;
        }
        toldAbout = requested;
        toldAboutMethod = method;
    }

    /** Game logic is done and rendering starts (just before {@code GameRenderer.render}). */
    public static void beforeRender() {
        if (markersRunning) {
            Streamline streamline = Bridge.streamline();
            streamline.pclSetMarker(SIMULATION_END, token);
            streamline.pclSetMarker(RENDER_SUBMIT_START, token);
        }
    }

    /**
     * The world, hand and post effects are finished and the GUI is about to be drawn: keep a top-down copy of this
     * HUD-less frame for DLSS-G (it must match the presented image, minus the HUD).
     */
    public static void captureHudless() {
        if (!running || !wanted() || Bridge.currentFrame() == null) {
            return;
        }
        try {
            RenderTarget main = Upscaler.mainTarget();
            if (hudless == null || hudless.width != main.width || hudless.height != main.height) {
                VulkanContext vulkan = Bridge.vulkan();
                vulkan.waitIdle();
                if (hudless != null) {
                    hudless.close();
                }
                hudless = new SharedImage(vulkan, main.width, main.height, VK_FORMAT_R8G8B8A8_UNORM, GL11.GL_RGBA8);
                Bridge.transitionToGeneral(hudless);
            }
            FullscreenPass.blitFlipped(main, hudless.target, GL11.GL_NEAREST);
            main.bindWrite(true);
            hudlessCaptured = true;
        } catch (Throwable t) {
            disable("the HUD-less frame could not be captured", t);
        }
    }

    /**
     * The level pass is finished, first-person hand included ({@code handTarget} is where it was drawn): keep the
     * hand out of the world's motion so generated frames don't drag it along when the camera turns.
     */
    public static void maskHand(RenderTarget handTarget) {
        if (running && wanted() && !debugNoHandMask) {
            Bridge.maskHand(handTarget);
        }
    }

    // ---- presenting (called by Presenter) ----

    /** Frames are about to be presented through Streamline: switch Reflex on. */
    static void start(Streamline streamline) {
        allocate();
        runningMethod = Method.DLSS;
        if (!running) {
            Streamline.header(reflexOptions, Streamline.REFLEX_OPTIONS, 1);
            MemoryUtil.memPutInt(reflexOptions + 32, REFLEX_LOW_LATENCY);
            int result = streamline.reflexSetOptions(reflexOptions);
            if (result != 0) {
                LOGGER.warn("Temporal: slReflexSetOptions: {}", Streamline.resultName(result));
            }
            running = true;
            appliedMode = -1;
            appliedFrames = -1;
            appliedFlags = -1;
            appliedFrameLimitUs = 0;
            lastStatus = 0;
            failingFrames = 0;
        }
        settleFrames = SETTLE_FRAMES;
    }

    /** The swapchain is about to be recreated (window resized, fullscreen toggled): generation must be off first. */
    static void suspend(Streamline streamline) {
        if (running) {
            applyOptions(streamline, MODE_OFF, appliedFrames, wanted());
            settleFrames = SETTLE_FRAMES;
        }
    }

    /** Presenting through Streamline stops for good (the game is closing, or presenting failed). */
    static void stop(Streamline streamline) {
        if (!running || runningMethod != Method.DLSS) {
            return;
        }
        stopRunning();
        taggedLastFrame = false;
        applyOptions(streamline, MODE_OFF, appliedFrames, false);
        MemoryUtil.memPutInt(reflexOptions + 32, REFLEX_OFF);
        streamline.reflexSetOptions(reflexOptions);
        appliedMode = -1;
        LOGGER.info("Temporal: DLSS Frame Generation stopped");
    }

    /** Frames are about to be presented through FSR 3 frame generation's swapchain. */
    static void startFsr() {
        if (!running) {
            running = true;
            runningMethod = Method.FSR;
            lastPresentCount = -1;
            shownPerFrameAverage = 1.0;
        }
        settleFrames = SETTLE_FRAMES;
    }

    /** FSR 3 frame generation's swapchain is about to be recreated (window resized, fullscreen toggled). */
    static void suspendFsr() {
        settleFrames = SETTLE_FRAMES;
        lastPresentCount = -1;
    }

    /** Presenting through FSR 3 frame generation's swapchain stops (switched off, or presenting failed). */
    static void stopFsr() {
        if (running && runningMethod == Method.FSR) {
            stopRunning();
            LOGGER.info("Temporal: FSR 3 Frame Generation stopped");
        }
    }

    private static void stopRunning() {
        running = false;
        runningMethod = null;
        activeThisFrame = false;
        framesPresented = 0;
        if (hudless != null) {
            hudless.close();
            hudless = null;
        }
    }

    /**
     * Decides whether this frame gets generated frames and returns the shared images Vulkan must receive for it
     * (HUD-less colour, depth, motion vectors), or none. GL side, just before the hand-over to Vulkan.
     */
    static SharedImage[] prepareForPresent(Method method, int width, int height) {
        Frame frame = Bridge.currentFrame();
        Minecraft minecraft = Minecraft.getInstance();
        boolean paused = minecraft.screen != null && minecraft.screen.isPauseScreen() || minecraft.getOverlay() != null;
        // DLSS-G also needs this frame's Reflex markers and a minimum size.
        boolean ready = method == Method.FSR || markersRunning && Math.min(width, height) >= minWidthOrHeight;
        activeThisFrame = wanted() && method() == method && settleFrames == 0 && ready && hudlessCaptured && frame != null
                && !paused && hudless.width == width && hudless.height == height;
        if (settleFrames > 0) {
            settleFrames--;
        }
        if (!activeThisFrame) {
            return new SharedImage[0];
        }
        if (method == Method.FSR) {
            return new SharedImage[]{hudless, frame.depth(), frame.motion()};
        }
        Streamline streamline = Bridge.streamline();
        if (!constantsSet) {
            float[] sign = Bridge.dlssJitterSign();
            SlConstants.write(constants, frame, sign[0], sign[1]);
            int result = streamline.setConstants(constants, frameToken(streamline), viewport);
            if (result != 0) {
                LOGGER.warn("Temporal: slSetConstants for frame generation: {}", Streamline.resultName(result));
                activeThisFrame = false;
                return new SharedImage[0];
            }
            constantsSet = true;
        }
        return new SharedImage[]{hudless, frame.depth(), frame.motion()};
    }

    /**
     * Vulkan side, recording the present command buffer (images already acquired from OpenGL): tags this frame's
     * inputs, which Streamline copies right here, and applies the DLSS-G options for this frame.
     */
    static void recordTags(Streamline streamline, long commandBuffer) {
        long frameToken = frameToken(streamline);
        if (activeThisFrame) {
            Frame frame = Bridge.currentFrame();
            describeTag(0, frame.depth(), Streamline.BUFFER_DEPTH, frame.renderWidth(), frame.renderHeight());
            describeTag(1, frame.motion(), Streamline.BUFFER_MOTION_VECTORS, frame.renderWidth(), frame.renderHeight());
            describeTag(2, hudless, BUFFER_HUDLESS_COLOR, hudless.width, hudless.height);
            int result = streamline.setTagForFrame(frameToken, viewport, tags, TAG_COUNT, commandBuffer);
            if (result != 0) {
                disable("slSetTagForFrame failed: " + Streamline.resultName(result), null);
                activeThisFrame = false;
            }
            taggedLastFrame = activeThisFrame;
        } else if (taggedLastFrame) {
            // Inputs aren't valid while paused or in menus: clear the tags.
            for (int i = 0; i < TAG_COUNT; i++) {
                MemoryUtil.memPutAddress(tags + (long) i * Streamline.SIZE_RESOURCE_TAG + 32, MemoryUtil.NULL);
            }
            streamline.setTagForFrame(frameToken, viewport, tags, TAG_COUNT, commandBuffer);
            taggedLastFrame = false;
        }
        // Paused or in a menu: keep DLSS-G's memory so resuming doesn't stutter. Switched off in the settings: let it go.
        applyOptions(streamline, activeThisFrame ? MODE_ON : MODE_OFF, requestedGeneratedFrames(), wanted());
        applyFrameLimit(streamline);
    }

    /**
     * V-Sync can't be used while Streamline presents, so with Minecraft's V-Sync option on, Reflex caps what reaches
     * the screen a little under the monitor's refresh rate instead (the margin keeps variable-refresh monitors in
     * range). While DLSS-G runs, Reflex's limit applies to the frames shown, generated ones included (measured: a
     * 27.5 ms limit gave 18 rendered fps at 2x), so the rendered rate follows by itself.
     */
    private static void applyFrameLimit(Streamline streamline) {
        int limit = 0;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.enableVsync().get()) {
            limit = (int) Math.round(1.0e6 / shownFrameCap());
        }
        if (limit != appliedFrameLimitUs) {
            Streamline.header(reflexOptions, Streamline.REFLEX_OPTIONS, 1);
            MemoryUtil.memPutInt(reflexOptions + 32, REFLEX_LOW_LATENCY);
            MemoryUtil.memPutInt(reflexOptions + 36, limit);
            int result = streamline.reflexSetOptions(reflexOptions);
            if (result != 0) {
                LOGGER.warn("Temporal: slReflexSetOptions (frame limit): {}", Streamline.resultName(result));
            }
            appliedFrameLimitUs = limit;
            LOGGER.info("Temporal: Reflex frame limit {}", limit == 0 ? "off" : String.format("%.1f fps shown", 1.0e6 / limit));
        }
    }

    /** The most frames per second worth showing: the refresh rate of the monitor the game is on, less 3%. */
    private static double shownFrameCap() {
        return 0.97 * monitorRefreshRate();
    }

    /**
     * The refresh rate of the monitor showing the game (the one the window covers most), looked up again whenever the
     * window moves or changes size. Minecraft's Window.getRefreshRate() gives the primary monitor's whenever the game
     * isn't fullscreen, and findBestMonitor() logs each call, so not every frame.
     */
    private static int monitorRefreshRate() {
        Window window = Minecraft.getInstance().getWindow();
        if (window.getX() != monitorWindowX || window.getY() != monitorWindowY || window.getScreenWidth() != monitorWindowWidth
                || window.getScreenHeight() != monitorWindowHeight) {
            monitorWindowX = window.getX();
            monitorWindowY = window.getY();
            monitorWindowWidth = window.getScreenWidth();
            monitorWindowHeight = window.getScreenHeight();
            Monitor monitor = window.findBestMonitor();
            GLFWVidMode mode = monitor != null ? GLFW.glfwGetVideoMode(monitor.getMonitor()) : null;
            refreshRate = mode != null ? mode.refreshRate() : 0;
        }
        return refreshRate > 0 ? refreshRate : 60;
    }

    /** {@code width} x {@code height}: the part of the image this frame uses, from its top-left corner. */
    private static void describeTag(int index, SharedImage image, int bufferType, int width, int height) {
        long resource = resources + (long) index * Streamline.SIZE_RESOURCE;
        image.describe(resource);
        long tag = tags + (long) index * Streamline.SIZE_RESOURCE_TAG;
        Streamline.header(tag, Streamline.RESOURCE_TAG, 1);
        MemoryUtil.memPutAddress(tag + 32, resource);
        MemoryUtil.memPutInt(tag + 40, bufferType);
        MemoryUtil.memPutInt(tag + 44, LIFECYCLE_ONLY_VALID_NOW);
        MemoryUtil.memPutInt(tag + 48, 0);                  // extent
        MemoryUtil.memPutInt(tag + 52, 0);
        MemoryUtil.memPutInt(tag + 56, width);
        MemoryUtil.memPutInt(tag + 60, height);
    }

    /**
     * Sets DLSS-G's options when they change; they take effect at the next present. With {@code retain}, switching
     * it off keeps its memory (short pauses); without, DLSS-G frees everything it allocated.
     */
    private static void applyOptions(Streamline streamline, int mode, int frames, boolean retain) {
        int flags = (retain ? RETAIN_RESOURCES_WHEN_OFF : 0) | (debugShowOnlyGenerated ? SHOW_ONLY_INTERPOLATED_FRAME : 0);
        if (mode == appliedMode && flags == appliedFlags && (mode == MODE_OFF || frames == appliedFrames)) {
            return;
        }
        MemoryUtil.memSet(options + 32, 0, Streamline.SIZE_DLSSG_OPTIONS - 32);
        Streamline.header(options, Streamline.DLSSG_OPTIONS, 5);
        MemoryUtil.memPutInt(options + 32, mode);
        MemoryUtil.memPutInt(options + 36, Math.max(1, frames));       // numFramesToGenerate
        MemoryUtil.memPutInt(options + 40, flags);
        MemoryUtil.memPutByte(options + 104, BOOLEAN_INVALID);          // bReserved15 (default)
        // queueParallelismMode (108) = block presenting client queue; UI recomposition (112) off; dynamic target (116) 0
        int result = streamline.dlssgSetOptions(viewport, options);
        if (result != 0) {
            disable("slDLSSGSetOptions failed: " + Streamline.resultName(result), null);
            return;
        }
        appliedMode = mode;
        appliedFrames = frames;
        appliedFlags = flags;
        LOGGER.info("Temporal: DLSS Frame Generation {}{}", mode == MODE_ON ? (frames + 1) + "x" : "off",
                debugShowOnlyGenerated ? " (showing generated frames only)" : "");
    }

    static void presentStart(Streamline streamline) {
        if (markersRunning) {
            streamline.pclSetMarker(RENDER_SUBMIT_END, token);
            streamline.pclSetMarker(PRESENT_START, token);
        }
    }

    static void presentEnd(Streamline streamline) {
        if (markersRunning) {
            streamline.pclSetMarker(PRESENT_END, token);
        }
    }

    /** After each present: how many frames DLSS-G showed, and whether it reports a problem. */
    static void afterPresent(Streamline streamline) {
        if (getState(streamline) != 0) {
            return;
        }
        int status = MemoryUtil.memGetInt(state + 40);
        framesPresented = MemoryUtil.memGetInt(state + 48);
        if (appliedMode == MODE_ON && framesPresented > 1) {
            generatedTotal += framesPresented - 1;
        }
        if (status != lastStatus) {
            LOGGER.info("Temporal: DLSS-G status {}", statusName(status));
            lastStatus = status;
        }
        failingFrames = status != 0 && appliedMode == MODE_ON ? failingFrames + 1 : 0;
        if (failingFrames >= FAILURE_LIMIT) {
            disable("DLSS Frame Generation reports " + statusName(status), null);
        }
    }

    /**
     * Vulkan side, recording the present command buffer (images already acquired from OpenGL): FSR 3 takes this
     * frame's inputs if it gets a generated frame, and is configured for the coming present either way.
     */
    static void recordFsr(MemoryStack stack, long commandBuffer, FsrFrameGeneration fsr) {
        fsr.record(stack, commandBuffer, activeThisFrame ? Bridge.currentFrame() : null, activeThisFrame ? hudless : null,
                debugShowOnlyGenerated);
    }

    /**
     * After each present through FSR 3: how many frames reached the screen per rendered frame. FidelityFX presents
     * from its own thread, a frame or so behind, so this is an average over recent frames.
     */
    static void afterFsrPresent(FsrFrameGeneration fsr) {
        long count = fsr.presentCount();
        if (lastPresentCount >= 0 && count >= lastPresentCount) {
            long shown = count - lastPresentCount;
            shownPerFrameAverage += 0.1 * (shown - shownPerFrameAverage);
            if (activeThisFrame && shown > 1) {
                generatedTotal += shown - 1;
            }
        }
        lastPresentCount = count;
        framesPresented = (int) Math.round(shownPerFrameAverage);
        int failed = fsr.takeFailedDispatches();
        failingFrames = failed > 0 ? failingFrames + 1 : 0;
        if (failingFrames >= FAILURE_LIMIT) {
            disable(Method.FSR, "FSR 3 Frame Generation keeps failing", null);
        }
    }

    private static String statusName(int status) {
        if (status == 0) {
            return "ok";
        }
        StringBuilder names = new StringBuilder();
        for (int bit = 0; bit < STATUS_NAMES.length; bit++) {
            if ((status & (1 << bit)) != 0) {
                names.append(names.isEmpty() ? "" : ", ").append(STATUS_NAMES[bit]);
            }
        }
        return names.isEmpty() ? "status " + status : names.toString();
    }

    // ---- for the debug screen ----

    /**
     * F3 screen: Minecraft's FPS counter only counts rendered frames, so show what reaches the display. "Shown"
     * multiplies the rendered rate by the frames DLSS-G presented for the last one.
     */
    public static void appendDebugText(List<String> left) {
        if (!requestedInSettings()) {
            return;
        }
        Method method = method();
        if (method == null) {
            left.add("Temporal Frame Generation: off (" + unavailableReason() + ")");
            return;
        }
        String line;
        if (!running || runningMethod != method) {
            line = "starting";
        } else if (!isActive()) {
            line = "paused" + (method == Method.DLSS && lastStatus != 0 ? " (" + statusName(lastStatus) + ")" : "");
        } else if (method == Method.FSR) {
            line = "2x, ~" + Minecraft.getInstance().getFps() * Math.max(1, framesPresented) + " fps shown";
        } else {
            line = (appliedFrames + 1) + "x, ~" + Minecraft.getInstance().getFps() * Math.max(1, framesPresented)
                    + " fps shown, Reflex on" + (appliedFrameLimitUs > 0 ? ", capped at " + (int) shownFrameCap() + " fps (V-Sync)" : "");
        }
        left.add("Temporal Frame Generation (" + method.displayName + "): " + line);
    }

    private static boolean requestedInSettings() {
        try {
            return TemporalConfig.FRAME_GENERATION.get() != TemporalConfig.FrameGenerationMode.OFF;
        } catch (IllegalStateException notLoadedYet) {
            return false;
        }
    }

    /** Frames shown for the last rendered frame (1 = no generated frame); for FSR 3 a recent average. */
    public static int framesPresentedPerFrame() {
        return framesPresented;
    }

    /** Generated frames shown since the game started. */
    public static long generatedFrames() {
        return generatedTotal;
    }

    /** Whether the last presented frame had generated frames added. */
    public static boolean isActive() {
        return running && activeThisFrame && (runningMethod == Method.FSR || appliedMode == MODE_ON);
    }

    /**
     * The most frames shown per rendered frame with the chosen kind of frame generation on this PC (2 for FSR 3), or 0
     * while that isn't known yet (DLSS-G's limit is read once the Vulkan side has started).
     */
    public static int supportedMultiplier() {
        Method method = method();
        if (method == Method.FSR) {
            return 2;
        }
        return method == Method.DLSS && maxGeneratedFrames > 0 ? maxGeneratedFrames + 1 : 0;
    }

    /** The frame generation presenting right now, or null. */
    @Nullable
    public static Method runningMethod() {
        return running ? runningMethod : null;
    }

    /** Why the chosen frame generation can't run here, or null if it can. */
    @Nullable
    public static String unavailableReason() {
        if (method() != null) {
            return null;
        }
        TemporalConfig.FrameGenerationMethod choice;
        try {
            choice = TemporalConfig.FRAME_GENERATION_METHOD.get();
        } catch (IllegalStateException notLoadedYet) {
            choice = TemporalConfig.FrameGenerationMethod.AUTOMATIC;
        }
        return switch (choice) {
            case DLSS -> unavailableReason;
            case FSR -> fsrUnavailableReason;
            case AUTOMATIC -> unavailableReason.equals(fsrUnavailableReason) ? unavailableReason
                    : unavailableReason + "; FSR 3: " + fsrUnavailableReason;
        };
    }

    private FrameGeneration() {}
}
