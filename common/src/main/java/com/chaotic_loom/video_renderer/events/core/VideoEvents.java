package com.chaotic_loom.video_renderer.events.core;

import com.chaotic_loom.video_renderer.core.VideoRenderer;
import com.chaotic_loom.video_renderer.events.Event;
import com.chaotic_loom.video_renderer.events.EventFactory;

public class VideoEvents {
    public static final Event<VideoFinishedEvent> FINISHED =
            EventFactory.createArray(VideoFinishedEvent.class,
                    (listeners) -> (videoRenderer) -> {
                        for (VideoFinishedEvent listener : listeners) {
                            listener.invoke(videoRenderer);
                        }
                    }
            );

    @FunctionalInterface
    public interface VideoFinishedEvent {
        void invoke(VideoRenderer videoRenderer);
    }
}
