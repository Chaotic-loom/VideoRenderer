package com.chaotic_loom.video_renderer.events.core;

import com.chaotic_loom.video_renderer.events.Event;
import com.chaotic_loom.video_renderer.events.EventFactory;

public class EngineEvents {
    public static final Event<SoundEngineLoadedEvent> SOUND_ENGINE_LOADED =
            EventFactory.createArray(SoundEngineLoadedEvent.class,
                    (listeners) -> () -> {
                        for (SoundEngineLoadedEvent listener : listeners) {
                            listener.invoke();
                        }
                    }
            );

    @FunctionalInterface
    public interface SoundEngineLoadedEvent {
        void invoke();
    }
}
