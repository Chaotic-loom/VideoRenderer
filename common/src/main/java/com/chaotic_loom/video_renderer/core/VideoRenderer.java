package com.chaotic_loom.video_renderer.core;

import com.chaotic_loom.video_renderer.Constants;
import com.chaotic_loom.video_renderer.events.core.VideoEvents;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.lwjgl.system.MemoryUtil;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Video renderer that decodes frames on a background thread and uploads them as a DynamicTexture.
 * Can load videos from absolute path or with ResourceLocations.
 * Uses FFMPEG.
 */
public class VideoRenderer {
    private FFmpegFrameGrabber grabber;
    private DynamicTexture texture;
    private ResourceLocation textureIdentifier;

    private final AtomicBoolean playing = new AtomicBoolean(false);
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private boolean loop = false;

    private int videoWidth;
    private int videoHeight;
    private double frameTime; // Time per frame in seconds
    private long frameTimeNanos; // Time per frame in nanoseconds

    // Threading components
    private Thread decoderThread;

    // Timing and synchronization
    private long baseTimeNanos = 0; // Base time for frame scheduling
    private int framesDecoded = 0; // Frame counter
    private volatile boolean needsCatchUp = false;

    private Path tempFile; // temporary file used when loading from a ResourceLocation

    // Double buffering
    private NativeImage bufferA;
    private NativeImage bufferB;
    private volatile NativeImage currentDecodeBuffer;
    private final AtomicReference<NativeImage> nextFrameImage = new AtomicReference<>();

    // Audio
    private AudioPlayer audioPlayer;
    private String videoFilePath;

    public VideoRenderer(String filePath) {
        loadResource(filePath);
        initializeAudio(filePath);
    }

    public VideoRenderer(ResourceLocation resourceLocation) {
        try {
            Constants.LOG.info("Loading video resource: {}", resourceLocation);

            // Obtain resource input stream from Minecraft's resource manager
            Minecraft client = Minecraft.getInstance();
            Optional<Resource> resource = client.getResourceManager().getResource(resourceLocation);
            if (resource.isEmpty()) {
                Constants.LOG.error("Resource not found: {}", resourceLocation);
                return;
            }

            // Copy resource to a temp file (FFmpeg handles file paths reliably)
            try (InputStream is = resource.get().open()) {
                tempFile = Files.createTempFile("clm_video_", ".mp4");
                Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }

            this.videoFilePath = tempFile.toFile().getAbsolutePath();
            loadResource(videoFilePath);
            initializeAudio(videoFilePath);
        } catch (Exception e) {
            Constants.LOG.error("Failed to initialize video player from ResourceLocation: {}", resourceLocation, e);
        }
    }

    /**
     * Called internally for constructors
     * @param filePath The file path to load the video from.
     */
    private void loadResource(String filePath) {
        try {
            Constants.LOG.info("Loading video from: {}", filePath);
            grabber = new FFmpegFrameGrabber(filePath);
            grabber.setPixelFormat(avutil.AV_PIX_FMT_RGBA);
            grabber.start();

            videoWidth = grabber.getImageWidth();
            videoHeight = grabber.getImageHeight();

            double frameRate = Math.max(grabber.getFrameRate(), 1.0); // Ensure positive frame rate
            frameTime = 1.0 / frameRate;
            frameTimeNanos = (long) (frameTime * 1_000_000_000.0);

            Constants.LOG.info("Video loaded: {}x{}, FPS: {}, Frame time: {}s", videoWidth, videoHeight, frameRate, frameTime);
        } catch (Exception e) {
            Constants.LOG.error("Failed to initialize video player from path: {}", filePath, e);
        }
    }

    /**
     * Must be called on the render thread before calling play().
     */
    public void initializeTexture() {
        if (initialized.get() || grabber == null) return;

        try {
            Minecraft client = Minecraft.getInstance();

            // Initialize texture
            NativeImage nativeImage = new NativeImage(videoWidth, videoHeight, true);
            texture = new DynamicTexture(nativeImage);
            textureIdentifier = client.getTextureManager().register("video_frame", texture);

            // Pre-allocate decode buffers
            bufferA = new NativeImage(videoWidth, videoHeight, true);
            bufferB = new NativeImage(videoWidth, videoHeight, true);
            currentDecodeBuffer = bufferA;

            initialized.set(true);
            Constants.LOG.info("Video texture initialized: {}", textureIdentifier);
        } catch (Exception e) {
            Constants.LOG.error("Failed to initialize video texture", e);
        }
    }

    /**
     * Initialize audio player for the video
     */
    private void initializeAudio(String filePath) {
        try {
            audioPlayer = new AudioPlayer(filePath);
            Constants.LOG.info("Audio player initialized for video");
        } catch (Exception e) {
            Constants.LOG.error("Failed to initialize audio player", e);
            audioPlayer = null;
        }
    }

    public void play() {
        if (!initialized.get()) {
            Constants.LOG.error("Texture not initialized! Call initializeTexture() on render thread first.");
            return;
        }
        if (playing.get() && decoderThread != null && decoderThread.isAlive()) {
            return; // already playing
        }

        playing.set(true);
        baseTimeNanos = System.nanoTime();
        framesDecoded = 0;
        needsCatchUp = false;

        // Audio
        if (audioPlayer != null) {
            audioPlayer.play();
        }

        decoderThread = new Thread(this::decoderLoop, "Video-Decoder-Thread");
        decoderThread.setDaemon(true);
        decoderThread.start();

        Constants.LOG.info("Video playback started");
    }

    public void pause() {
        playing.set(false);

        // Audio
        if (audioPlayer != null) {
            audioPlayer.pause();
        }

        joinDecoderThread();
    }

    public void stop() {
        playing.set(false);

        // Audio
        if (audioPlayer != null) {
            audioPlayer.stop();
            //audioPlayer.cleanup();
            //audioPlayer = null;
        }

        joinDecoderThread();

        try {
            if (grabber != null) {
                grabber.setVideoTimestamp(0);
            }
            nextFrameImage.set(null);
        } catch (Exception e) {
            Constants.LOG.error("Error while stopping video", e);
        }
    }

    private void joinDecoderThread() {
        if (decoderThread != null && decoderThread.isAlive()) {
            try {
                decoderThread.join(1000);
                if (decoderThread.isAlive()) {
                    Constants.LOG.warn("Decoder thread did not stop in time, interrupting.");
                    decoderThread.interrupt();
                }
            } catch (InterruptedException e) {
                Constants.LOG.error("Interrupted while joining decoder thread", e);
                Thread.currentThread().interrupt();
            }
            decoderThread = null;
        }
    }

    private void decoderLoop() {
        Constants.LOG.debug("Decoder thread started.");

        try {
            while (playing.get()) {
                long targetTimeNanos = baseTimeNanos + (framesDecoded * frameTimeNanos);
                long currentTimeNanos = System.nanoTime();
                long waitTimeNanos = targetTimeNanos - currentTimeNanos;

                if (waitTimeNanos < -frameTimeNanos * 2) {
                    needsCatchUp = true;
                } else if (waitTimeNanos > 1000000) {
                    LockSupport.parkNanos(waitTimeNanos);
                }

                //Frame frame = grabber.grab();
                // Only grab video frames
                Frame frame = grabber.grabFrame(false, true, true, false);
                if (frame == null) {
                    handleVideoEnd();
                    continue;
                }

                if (frame.image != null) {
                    // Decode directly into current buffer (no allocation)
                    convertFrameToNativeImage(frame, currentDecodeBuffer);

                    // Swap buffers.
                    NativeImage previousFrame = nextFrameImage.getAndSet(currentDecodeBuffer);

                    // The buffer we just swapped out becomes our next decode target
                    if (previousFrame != null) {
                        currentDecodeBuffer = previousFrame;
                    } else {
                        // First frame: use the other buffer
                        currentDecodeBuffer = (currentDecodeBuffer == bufferA) ? bufferB : bufferA;
                    }

                    framesDecoded++;

                    if (needsCatchUp && waitTimeNanos >= -frameTimeNanos) {
                        needsCatchUp = false;
                    }
                }
            }
        } catch (Exception e) {
            Constants.LOG.error("Exception in decoder loop", e);
            playing.set(false);
        }

        Constants.LOG.debug("Decoder thread stopped.");
    }

    private void handleVideoEnd() throws FFmpegFrameGrabber.Exception {
        if (loop) {
            grabber.setVideoTimestamp(0);
            baseTimeNanos = System.nanoTime();
            framesDecoded = 0;
            needsCatchUp = false;

            if (audioPlayer != null) {
                audioPlayer.stop();
                audioPlayer.play();
            }
        } else {
            playing.set(false);
            if (audioPlayer != null) {
                audioPlayer.stop();
            }
            VideoEvents.FINISHED.invoker().invoke(this);
        }
    }

    private void convertFrameToNativeImage(Frame frame, NativeImage image) {
        ByteBuffer sourceBuffer = (ByteBuffer) frame.image[0];

        // Get direct access to NativeImage's internal buffer
        long imagePointer = image.pixels;

        if (imagePointer != 0) {
            // Direct memory copy
            int totalBytes = videoWidth * videoHeight * 4;

            // Ensure buffer is positioned at the start
            sourceBuffer.position(0);

            // Use unsafe memory copy or NIO bulk operations
            MemoryUtil.memCopy(
                    MemoryUtil.memAddress(sourceBuffer),
                    imagePointer,
                    totalBytes
            );
        } else {
            // Fallback
            bulkConvertFallback(sourceBuffer, image);
        }
    }

    private void bulkConvertFallback(ByteBuffer sourceBuffer, NativeImage image) {
        sourceBuffer.position(0);
        int totalPixels = videoWidth * videoHeight;

        // Process in batches to reduce method call overheadc
        for (int i = 0; i < totalPixels; i++) {
            int r = sourceBuffer.get() & 0xFF;
            int g = sourceBuffer.get() & 0xFF;
            int b = sourceBuffer.get() & 0xFF;
            int a = sourceBuffer.get() & 0xFF;

            int abgrColor = (a << 24) | (b << 16) | (g << 8) | r;

            int x = i % videoWidth;
            int y = i / videoWidth;
            image.setPixelRGBA(x, y, abgrColor);
        }
    }

    /**
     * Called on the render thread to upload the latest decoded frame (if any) to the GPU.
     */
    public void update() {
        if (!playing.get() || !initialized.get()) return;

        NativeImage imageToUpload = nextFrameImage.get();  // Just read, don't clear
        if (imageToUpload != null) {
            try {
                NativeImage textureImage = texture.getPixels();
                if (textureImage != null) {
                    textureImage.copyFrom(imageToUpload);
                    texture.upload();
                }
            } catch (Exception e) {
                Constants.LOG.error("Failed to upload texture frame", e);
            }
        }
    }

    // Getters and Setters
    public ResourceLocation getTexture() {
        return textureIdentifier;
    }

    public int getWidth() {
        return videoWidth;
    }

    public int getHeight() {
        return videoHeight;
    }

    public boolean isPlaying() {
        return playing.get();
    }

    public boolean isInitialized() {
        return initialized.get();
    }

    public void setLoop(boolean loop) {
        this.loop = loop;
    }

    /**
     * Set the audio volume (0.0 to 1.0)
     */
    public void setVolume(float volume) {
        if (audioPlayer != null) {
            audioPlayer.setVolume(volume);
        }
    }

    public float getVolume() {
        if (audioPlayer != null) {
            return audioPlayer.getVolume();
        }
        return 0.0f;
    }

    public boolean hasAudio() {
        return audioPlayer != null;
    }

    public void close() {
        stop();

        // Audio
        if (audioPlayer != null) {
            audioPlayer.cleanup();
            audioPlayer = null;
        }

        try {
            if (grabber != null) {
                grabber.close();
            }
            if (textureIdentifier != null) {
                Minecraft.getInstance().getTextureManager().release(textureIdentifier);
            }
            if (texture != null) {
                texture.close();
            }

            if (bufferA != null) {
                bufferA.close();
                bufferA = null;
            }
            if (bufferB != null) {
                bufferB.close();
                bufferB = null;
            }

            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                    tempFile = null;
                } catch (Exception e) {
                    tempFile.toFile().deleteOnExit();
                    Constants.LOG.warn("Failed to delete temp file, will delete on exit", e);
                }
            }
        } catch (Exception e) {
            Constants.LOG.error("Error while closing VideoPlayer", e);
        }
    }
}