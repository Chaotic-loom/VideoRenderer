package com.chaotic_loom.video_renderer;

import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Constants {
    public static final String MOD_ID = "video_renderer";
    public static final String MOD_NAME = "VideoRenderer";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);
    public static final ResourceLocation TEST_VIDEO = new ResourceLocation(MOD_ID, "videos/test.mp4");
}
