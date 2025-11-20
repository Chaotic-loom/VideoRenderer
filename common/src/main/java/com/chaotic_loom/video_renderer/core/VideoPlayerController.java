package com.chaotic_loom.video_renderer.core;

import com.chaotic_loom.video_renderer.Constants;
import com.chaotic_loom.video_renderer.events.core.EngineEvents;
import com.chaotic_loom.video_renderer.events.core.VideoEvents;
import com.chaotic_loom.video_renderer.events.core.RenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

public class VideoPlayerController {
    private static final List<VideoRenderer> activeVideos = new CopyOnWriteArrayList<>();

    private static boolean initialized = false;
    private static boolean soundLoaded = false;

    private static final int backgroundColor = 0xFF000000;

    public static void initialize() {
        if (!initialized) {
            RenderEvents.RENDER.register(VideoPlayerController::render);
            VideoEvents.FINISHED.register(VideoPlayerController::onVideoFinished);

            EngineEvents.SOUND_ENGINE_LOADED.register(() -> {
                soundLoaded = true;
                VideoEvents.VIDEO_CONTROLLER_LOADED.invoker().invoke();
            });

            initialized = true;
            Constants.LOG.info("Video Player Controller initialized!");
        }
    }

    public static void playVideo(String absolutePath) {
        playVideoInternal(() -> new VideoRenderer(absolutePath));
    }

    public static void playVideo(ResourceLocation location) {
        playVideoInternal(() -> new VideoRenderer(location));
    }

    private static void playVideoInternal(Supplier<VideoRenderer> supplier) {
        try {
            VideoRenderer newVideo = supplier.get();
            newVideo.setLoop(false);

            activeVideos.add(newVideo);

            Constants.LOG.info("Video loaded, waiting for render thread to initialize texture...");
        } catch (Exception e) {
            Constants.LOG.error("Failed to play video", e);
        }
    }

    public static void stopAllVideos() {
        for (VideoRenderer video : activeVideos) {
            video.close();
        }
        activeVideos.clear();
    }

    public static void stopVideo(VideoRenderer video) {
        if (activeVideos.contains(video)) {
            video.close();
            activeVideos.remove(video);
        }
    }

    public static boolean isAnyVideoPlaying() {
        // Returns true if at least one video is initialized and playing
        for (VideoRenderer video : activeVideos) {
            if (video != null && video.isPlaying()) {
                return true;
            }
        }
        return false;
    }

    public static List<VideoRenderer> getActiveVideos() {
        return activeVideos;
    }

    private static void onVideoFinished(VideoRenderer videoRenderer) {
        Constants.LOG.info("Video finished!");
        stopVideo(videoRenderer);
    }

    private static void render(GuiGraphics drawContext, float tickDelta) {
        if (!soundLoaded) return;
        if (activeVideos.isEmpty()) return;

        // Iterate through all active videos
        for (int i = 0; i < activeVideos.size(); i++) {
            VideoRenderer video = activeVideos.get(i);
            if (video == null) continue;

            // Initialize texture on first render call
            if (!video.isInitialized()) {
                video.initializeTexture();

                // Start playing after texture is initialized
                if (video.isInitialized()) {
                    video.play();
                    Constants.LOG.info("Video playback started for: {}", video);
                }
            }

            // Update and render if playing
            if (video.isPlaying()) {
                video.update();
                // TODO: More rendering methods
                renderVideoFullscreen(drawContext, video);
            }
        }
    }

    private static void renderVideoFullscreen(GuiGraphics drawContext, VideoRenderer videoRenderer) {
        Minecraft client = Minecraft.getInstance();
        if (!videoRenderer.isInitialized()) return;

        int screenWidth = client.getWindow().getGuiScaledWidth();
        int screenHeight = client.getWindow().getGuiScaledHeight();

        // Calculate aspect ratio preserving dimensions
        float videoAspect = (float) videoRenderer.getWidth() / videoRenderer.getHeight();
        float screenAspect = (float) screenWidth / screenHeight;

        int width, height;
        if (videoAspect > screenAspect) {
            width = screenWidth;
            height = (int) (screenWidth / videoAspect);
        } else {
            height = screenHeight;
            width = (int) (screenHeight * videoAspect);
        }

        int x = (screenWidth - width) / 2;
        int y = (screenHeight - height) / 2;

        // Render black background
        drawContext.fill(0, 0, screenWidth, screenHeight, backgroundColor);

        // Render the video texture
        drawContext.blit(
                videoRenderer.getTexture(),
                x, y,
                0, 0,
                width, height,
                width, height
        );
    }
}