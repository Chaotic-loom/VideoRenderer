package com.chaotic_loom.video_renderer;

import com.chaotic_loom.video_renderer.core.RenderEvents;
import com.chaotic_loom.video_renderer.core.VideoPlayerController;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;

public class VideoRendererMain {
    private static boolean startupLogicComplete = false;

    public static void init() {
        RenderEvents.SOUND_ENGINE_LOADED.register(() -> {
            VideoPlayerController.initialize();

            if (!startupLogicComplete) {
                Minecraft.getInstance().execute(() -> {
                    try {
                        // Small delay to ensure everything is loaded
                        Thread.sleep(1000);

                        VideoPlayerController.playVideo(Constants.TEST_VIDEO);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                });

                startupLogicComplete = true;
            }
        });
    }
}
