package com.lgmediabridge.transcode;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;

import com.lgmediabridge.catalog.MediaItem;
import com.lgmediabridge.core.LogBus;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converts audio the TV cannot decode (Opus, ALAC, AMR, DTS, …) into AAC in MP4.
 *
 * Two deliberate choices:
 *  1. **Not streamed on the fly.** A progressive ADTS stream would play but could
 *     never be seeked, and seeking is a first-class requirement here. Converting
 *     to a real MP4 file first means the TV gets byte ranges, a duration and a
 *     working seek bar.
 *  2. **Cached and bounded.** Conversion happens once per file (seconds for a
 *     typical track), the result is cached, and the cache is trimmed so it cannot
 *     grow without limit.
 *
 * Only the platform's own MediaCodec/MediaMuxer are used - no ffmpeg, no native
 * libraries, so the APK stays tiny and there is nothing to keep in sync with the
 * device's codecs. If this phone has no decoder for the source codec, or the
 * source exceeds what the platform AAC encoder accepts, the conversion fails
 * with a precise reason instead of producing a broken file.
 */
public final class AudioTranscoder {

    private static final String TAG = "AudioTranscoder";
    private static final long DEQUEUE_TIMEOUT_US = 20_000;
    private static final long MAX_WALL_CLOCK_MS = 10 * 60 * 1000;
    private static final int TARGET_BITRATE = 192_000;
    private static final int MAX_ENCODER_SAMPLE_RATE = 48_000;

    public interface Progress {
        void onProgress(int percent, String stage);
    }

    private final Context context;
    private final File cacheDir;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public AudioTranscoder(Context context) {
        this.context = context.getApplicationContext();
        this.cacheDir = new File(this.context.getCacheDir(), "audio");
    }

    public File cacheFile(MediaItem item) {
        return new File(cacheDir, item.objectId() + "_" + item.sizeBytes + ".m4a");
    }

    public boolean isCached(MediaItem item) {
        File file = cacheFile(item);
        return file.exists() && file.length() > 0;
    }

    /**
     * Converts {@code item} unless a usable cached copy exists.
     *
     * @return the MP4/AAC file, ready to be served with byte-range support.
     */
    public File ensureConverted(MediaItem item, Progress progress) throws IOException {
        File target = cacheFile(item);
        if (target.exists() && target.length() > 0) {
            return target;
        }
        Object lock = locks.computeIfAbsent(item.objectId(), key -> new Object());
        synchronized (lock) {
            if (target.exists() && target.length() > 0) {
                return target;
            }
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                throw new IOException("cannot create audio cache directory");
            }
            File temp = new File(cacheDir, target.getName() + ".part");
            try {
                transcode(item, temp, progress);
                if (!temp.renameTo(target) && !target.exists()) {
                    throw new IOException("could not store converted audio");
                }
                return target;
            } catch (IOException e) {
                temp.delete();
                throw e;
            } finally {
                locks.remove(item.objectId());
            }
        }
    }

    private void transcode(MediaItem item, File output, Progress progress) throws IOException {
        long startedAt = System.currentTimeMillis();
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        long frames = 0;
        try {
            extractor.setDataSource(context, Uri.parse(item.uri), null);
            int audioTrack = -1;
            MediaFormat sourceFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    sourceFormat = format;
                    break;
                }
            }
            if (audioTrack < 0 || sourceFormat == null) {
                throw new IOException("no audio track found");
            }
            String sourceMime = sourceFormat.getString(MediaFormat.KEY_MIME);
            int sampleRate = sourceFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? sourceFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44_100;
            int channels = sourceFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? sourceFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            if (sampleRate > MAX_ENCODER_SAMPLE_RATE) {
                throw new IOException("sample rate " + sampleRate + " Hz exceeds the AAC encoder limit ("
                        + MAX_ENCODER_SAMPLE_RATE + " Hz)");
            }
            if (channels > 2) {
                throw new IOException(channels + " channels cannot be down-mixed safely on this device");
            }
            LogBus.get().i(TAG, "converting " + item.displayName + " (" + sourceMime + ", "
                    + sampleRate + " Hz, " + channels + "ch)");

            decoder = MediaCodec.createDecoderByType(sourceMime);
            decoder.configure(sourceFormat, null, null, 0);
            decoder.start();

            MediaFormat encoderFormat = MediaFormat.createAudioFormat("audio/mp4a-latm",
                    sampleRate, channels);
            encoderFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, TARGET_BITRATE);
            encoderFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024);
            encoder = MediaCodec.createEncoderByType("audio/mp4a-latm");
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxTrack = -1;
            boolean muxStarted = false;
            boolean inputDone = false;
            boolean outputDone = false;
            boolean encoderDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            extractor.selectTrack(audioTrack);
            long totalDurationUs = item.durationMs > 0 ? item.durationMs * 1000L : 0;

            while (!encoderDone) {
                if (System.currentTimeMillis() - startedAt > MAX_WALL_CLOCK_MS) {
                    throw new IOException("conversion timed out");
                }
                if (!inputDone) {
                    int inputIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                    if (inputIndex >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(inputIndex);
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    } else if (inputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // keep looping; decoder may need a moment
                    }
                }

                int decoderIndex = decoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
                if (decoderIndex >= 0) {
                    ByteBuffer pcm = decoder.getOutputBuffer(decoderIndex);
                    boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (pcm != null && info.size > 0 && !config) {
                        pcm.position(info.offset);
                        pcm.limit(info.offset + info.size);
                        int encoderIndex = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US * 5);
                        if (encoderIndex >= 0) {
                            ByteBuffer encoderBuffer = encoder.getInputBuffer(encoderIndex);
                            if (encoderBuffer != null) {
                                encoderBuffer.clear();
                                encoderBuffer.put(pcm);
                                encoder.queueInputBuffer(encoderIndex, 0, info.size,
                                        info.presentationTimeUs, 0);
                                frames++;
                                if (totalDurationUs > 0 && progress != null
                                        && frames % 200 == 0) {
                                    progress.onProgress((int) Math.min(99,
                                            info.presentationTimeUs * 100 / totalDurationUs), "converting");
                                }
                            }
                        }
                    }
                    boolean endOfStream = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    decoder.releaseOutputBuffer(decoderIndex, false);
                    if (endOfStream) {
                        outputDone = true;
                        int endIndex = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US * 5);
                        if (endIndex >= 0) {
                            encoder.queueInputBuffer(endIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        }
                    }
                }

                int encoderIndex = encoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
                if (encoderIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxTrack = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxStarted = true;
                } else if (encoderIndex >= 0) {
                    ByteBuffer encoded = encoder.getOutputBuffer(encoderIndex);
                    boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (encoded != null && info.size > 0 && !config && muxStarted) {
                        encoded.position(info.offset);
                        encoded.limit(info.offset + info.size);
                        muxer.writeSampleData(muxTrack, encoded, info);
                    }
                    boolean endOfStream = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    encoder.releaseOutputBuffer(encoderIndex, false);
                    if (endOfStream) {
                        encoderDone = true;
                    }
                } else if (encoderIndex == MediaCodec.INFO_TRY_AGAIN_LATER && outputDone && inputDone) {
                    // The encoder needs draining; loop again.
                }
            }
            if (!muxStarted) {
                throw new IOException("encoder produced no output (codec may be unsupported)");
            }
            if (progress != null) {
                progress.onProgress(100, "done");
            }
            LogBus.get().i(TAG, "converted " + item.displayName + " in "
                    + (System.currentTimeMillis() - startedAt) + " ms ("
                    + output.length() + " bytes, " + frames + " frames)");
        } catch (MediaCodec.CodecException e) {
            throw new IOException("decoder/encoder error: " + e.getDiagnosticInfo(), e);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new IOException("codec configuration failed: " + e.getMessage(), e);
        } finally {
            closeQuietly(muxer);
            releaseQuietly(encoder);
            releaseQuietly(decoder);
            try {
                extractor.release();
            } catch (Exception ignored) {
                // nothing to do
            }
        }
    }

    private static void releaseQuietly(MediaCodec codec) {
        if (codec == null) {
            return;
        }
        try {
            codec.stop();
        } catch (Exception ignored) {
            // may already be stopped
        }
        try {
            codec.release();
        } catch (Exception ignored) {
            // nothing to do
        }
    }

    private static void closeQuietly(MediaMuxer muxer) {
        if (muxer == null) {
            return;
        }
        try {
            muxer.stop();
        } catch (Exception ignored) {
            // stop() fails when nothing was written; the .part file is deleted
        }
        try {
            muxer.release();
        } catch (Exception ignored) {
            // nothing to do
        }
    }

    public long cacheSize() {
        return PhotoTranscoder.directorySize(cacheDir);
    }

    public void clearCache() {
        PhotoTranscoder.deleteContents(cacheDir);
    }
}
