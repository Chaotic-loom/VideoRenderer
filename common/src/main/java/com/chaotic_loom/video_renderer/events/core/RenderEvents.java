package com.chaotic_loom.video_renderer.events.core;

import com.chaotic_loom.video_renderer.events.Event;
import com.chaotic_loom.video_renderer.events.EventFactory;
import net.minecraft.client.gui.GuiGraphics;

public class RenderEvents {
    public static final Event<RenderEvent> RENDER =
            EventFactory.createArray(RenderEvent.class,
                    (listeners) -> (drawContext, tickDelta) -> {
                        for (RenderEvent listener : listeners) {
                            listener.invoke(drawContext, tickDelta);
                        }
                    }
            );

    @FunctionalInterface
    public interface RenderEvent {
        void invoke(GuiGraphics drawContext, float tickDelta);
    }
}
