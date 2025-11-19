package com.chaotic_loom.video_renderer.events;

import java.util.function.Function;

public class EventFactory {
    public static <T> Event<T> createArray(Class<T> type, Function<T[], T> invokerFactory) {
        return new Event<>(invokerFactory, type);
    }
}