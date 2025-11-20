package com.chaotic_loom.video_renderer;

import com.chaotic_loom.video_renderer.core.VideoPlayerController;
import com.chaotic_loom.video_renderer.events.core.VideoEvents;
import net.minecraft.client.Minecraft;

public class VideoRendererMain {
    public static void init() {
        VideoPlayerController.initialize();

        VideoEvents.VIDEO_CONTROLLER_LOADED.register(() -> {
            Minecraft.getInstance().execute(() -> {
                VideoPlayerController.playVideo(Constants.TEST_VIDEO);
            });
        });
    }
}
