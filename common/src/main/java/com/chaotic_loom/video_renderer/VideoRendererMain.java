package com.chaotic_loom.video_renderer;

import com.chaotic_loom.video_renderer.events.core.EngineEvents;
import com.chaotic_loom.video_renderer.events.core.RenderEvents;
import com.chaotic_loom.video_renderer.core.VideoPlayerController;
import net.minecraft.client.Minecraft;

public class VideoRendererMain {
    private static boolean startupLogicComplete = false;

    public static void init() {
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
    }
}
