package com.chaotic_loom.video_renderer.core;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.io.IOException;
import java.nio.*;
import java.nio.file.Files;
import java.util.UUID;

/**
 * Simple AudioPlayer that:
 * - Uses bundled JavaCV (ffmpeg) to extract audio from an .mp4 to a temporary .ogg
 * - Decodes the .ogg via STBVorbis to PCM
 * - Uploads PCM to an OpenAL buffer and plays it from a source
 *
 * This loads the whole PCM into memory. For long files implement streaming.
 */
public class AudioPlayer {
    // Static block to silence FFmpeg logging
    /*static {
        avutil.av_log_set_level(avutil.AV_LOG_QUIET);
    }*/

    private final int bufferId;
    private final int sourceId;
    private final File tempOgg;
    private float volume = 1.0f;
    private boolean prepared = false;

    /**
     * @param filePath path to an .mp4 (or .ogg) file
     * @throws RuntimeException on failure (ffmpeg missing, decode error, OpenAL error)
     */
    public AudioPlayer(String filePath) {
        try {
            File input = new File(filePath);
            if (!input.exists()) throw new IllegalArgumentException("file not found: " + filePath);

            // If it's already an OGG file, use it. Otherwise, run ffmpeg to extract audio to a temp .ogg
            if (filePath.toLowerCase().endsWith(".ogg")) {
                tempOgg = input;
            } else {
                tempOgg = File.createTempFile("clm_audio_" + UUID.randomUUID(), ".ogg");
                tempOgg.deleteOnExit(); // best-effort
                runFfmpegExtractAudio(input, tempOgg);
            }

            // Decode OGG -> raw PCM (ShortBuffer)
            ByteBuffer oggData = ioReadFileToByteBuffer(tempOgg);
            IntBuffer channelsBuf = BufferUtils.createIntBuffer(1);
            IntBuffer sampleRateBuf = BufferUtils.createIntBuffer(1);

            ShortBuffer pcm = STBVorbis.stb_vorbis_decode_memory(oggData, channelsBuf, sampleRateBuf);
            if (pcm == null) throw new RuntimeException("Failed to decode OGG data with STBVorbis.");

            int channels = channelsBuf.get(0);
            int sampleRate = sampleRateBuf.get(0);

            // Convert ShortBuffer -> ByteBuffer (little-endian) for OpenAL
            pcm.rewind();
            ByteBuffer pcmBytes = MemoryUtil.memAlloc(pcm.remaining() * 2);
            pcmBytes.order(ByteOrder.nativeOrder());
            while (pcm.hasRemaining()) pcmBytes.putShort(pcm.get());
            pcmBytes.flip();

            // Create OpenAL buffer & fill it
            bufferId = AL10.alGenBuffers();
            int format = (channels == 1) ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
            AL10.alBufferData(bufferId, format, pcmBytes, sampleRate);

            // Create source and attach buffer
            sourceId = AL10.alGenSources();
            AL10.alSourcei(sourceId, AL10.AL_BUFFER, bufferId);
            AL10.alSourcef(sourceId, AL10.AL_GAIN, volume);

            // Free native memory we allocated
            MemoryUtil.memFree(pcmBytes);
            // Note: STBVorbis's returned ShortBuffer is allocated by the native lib; free it:
            MemoryUtil.memFree(pcm);

            // we can free the ByteBuffer holding the ogg file bytes
            MemoryUtil.memFree(oggData);

            prepared = true;
        } catch (IOException e) {
            throw new RuntimeException("IO error preparing audio: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to prepare AudioPlayer: " + e.getMessage(), e);
        }
    }

    /**
     * Play or resume playback.
     */
    public void play() {
        if (!prepared) return;
        AL10.alSourcePlay(sourceId);
    }

    /**
     * Pause playback.
     */
    public void pause() {
        if (!prepared) return;
        AL10.alSourcePause(sourceId);
    }

    /**
     * Stop playback (and rewind).
     */
    public void stop() {
        if (!prepared) return;
        AL10.alSourceStop(sourceId);
        // reset position to start
        AL10.alSourceRewind(sourceId);
    }

    /**
     * Set source volume (0.0 - 1.0+)
     */
    public void setVolume(float volume) {
        this.volume = volume;
        if (prepared) AL10.alSourcef(sourceId, AL10.AL_GAIN, volume);
    }

    public float getVolume() {
        return volume;
    }

    /**
     * Free resources (stops playback, deletes OpenAL objects, removes temp file).
     */
    public void cleanup() {
        if (prepared) {
            AL10.alSourceStop(sourceId);
            AL10.alDeleteSources(sourceId);
            AL10.alDeleteBuffers(bufferId);
        }

        // remove temp file if we created one
        try {
            if (tempOgg != null && tempOgg.exists() && tempOgg.getName().startsWith("clm_audio_")) {
                tempOgg.delete();
            }
        } catch (Exception ignored) {}

        prepared = false;
    }

    // ----------------- Helpers -----------------

    /**
     * Extracts audio from inputMp4 and saves it as OGG Vorbis using bundled JavaCV/FFmpeg.
     */
    private static void runFfmpegExtractAudio(File inputMp4, File outOgg) throws IOException {
        // Setup Grabber, Reader
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(inputMp4);
        try {
            grabber.start();
        } catch (FFmpegFrameGrabber.Exception e) {
            throw new IOException("Failed to start FFmpeg grabber: " + e.getMessage(), e);
        }

        // Setup Recorder, Writer
        // We force 2 channels, Stereo
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(outOgg, 2);

        recorder.setSampleRate(44100);
        recorder.setFormat("ogg");
        recorder.setAudioCodec(avcodec.AV_CODEC_ID_VORBIS); // Codec "libvorbis"

        // Set bitrate (study this because I don't know what the heck I am doing with my life)
        recorder.setAudioBitrate(128000);

        try {
            recorder.start();
        } catch (FFmpegFrameRecorder.Exception e) {
            grabber.stop();
            grabber.release();
            throw new IOException("Failed to start FFmpeg recorder: " + e.getMessage(), e);
        }

        // Processing Loop
        try {
            Frame frame;
            // Iterate through frames
            // grabSamples() fetches audio frames only (skips video processing)
            while ((frame = grabber.grabSamples()) != null) {
                recorder.record(frame);
            }
        } catch (Exception e) {
            throw new IOException("Error during audio transcoding: " + e.getMessage(), e);
        } finally {
            // Cleanup
            try {
                recorder.stop();
                recorder.release();
            } catch (Exception ignored) {}

            try {
                grabber.stop();
                grabber.release();
            } catch (Exception ignored) {}
        }
    }

    /**
     * Read file bytes into a direct ByteBuffer suitable for stb_vorbis_decode_memory.
     */
    private static ByteBuffer ioReadFileToByteBuffer(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        ByteBuffer bb = MemoryUtil.memAlloc(bytes.length);
        bb.put(bytes);
        bb.flip();
        return bb;
    }
}