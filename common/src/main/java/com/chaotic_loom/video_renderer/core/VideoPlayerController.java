package com.chaotic_loom.video_renderer.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Supplier;

public class VideoPlayerController {
    private static VideoRenderer currentVideo = null;
    private static boolean initialized = false;
    private static final int backgroundColor = 0xFF000000;

    public static void initialize() {
        if (!initialized) {
            RenderEvents.RENDER.register(VideoPlayerController::onHudRender);

            initialized = true;
            System.out.println("Video Player Controller initialized!");
        }
    }

    public static void playVideo(String absolutePath) {
        playVideoInternal(() -> new VideoRenderer(absolutePath));
    }

    public static void playVideo(ResourceLocation location) {
        playVideoInternal(() -> new VideoRenderer(location));
    }

    private static void playVideoInternal(Supplier<VideoRenderer> supplier) {
        stopCurrentVideo();

        try {
            currentVideo = supplier.get();
            currentVideo.setLoop(false);

            System.out.println("Video loaded, waiting for render thread to initialize texture...");
        } catch (Exception e) {
            System.err.println("Failed to play video: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void stopCurrentVideo() {
        if (currentVideo != null) {
            currentVideo.close();
            currentVideo = null;
        }
    }

    public static boolean isVideoPlaying() {
        return currentVideo != null && currentVideo.isPlaying();
    }

    private static void onHudRender(GuiGraphics drawContext, float tickDelta) {
        if (currentVideo != null) {
            // Initialize texture on first render call (guaranteed to be on render thread)
            if (!currentVideo.isInitialized()) {
                currentVideo.initializeTexture();
                // Start playing after texture is initialized
                if (currentVideo.isInitialized()) {
                    currentVideo.play();
                    System.out.println("Video playback started!");
                }
            }

            // Update and render if playing
            if (currentVideo.isPlaying()) {
                currentVideo.update();
                renderVideoFullscreen(drawContext, currentVideo);
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