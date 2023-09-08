package record.wilson.flutter.com.flutter_plugin_record.utils;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.Environment;
import android.os.Process;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;

public class WavToAccConverter {

    private static final String LOGTAG = "CONVERT AUDIO";
//    private static final int SAMPLING_RATE = 48000;
    //需要和转码前的采样率完全一致，否声音有问题
    private static final int SAMPLING_RATE = 44100;
    private static final int BUFFER_SIZE = 48000;
    private static final int COMPRESSED_AUDIO_FILE_BIT_RATE = 64000; // 64kbps
//private static final int COMPRESSED_AUDIO_FILE_BIT_RATE = 44100; // 64kbps
    private static final int CODEC_TIMEOUT_IN_MS = 5000;

    public static boolean convertAudio(String inputFilePath, String outputFilePath) {
        android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

        try {
            File inputFile = new File(inputFilePath);
            FileInputStream fis = new FileInputStream(inputFile);

            File outputFile = new File(outputFilePath);
            if (outputFile.exists()) outputFile.delete();

            MediaMuxer mux = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            MediaFormat outputFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", SAMPLING_RATE, 1);
            outputFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, COMPRESSED_AUDIO_FILE_BIT_RATE);
            outputFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

            MediaCodec codec = MediaCodec.createEncoderByType("audio/mp4a-latm");
            codec.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();

            ByteBuffer[] codecInputBuffers = codec.getInputBuffers(); // Note: Array of buffers
            ByteBuffer[] codecOutputBuffers = codec.getOutputBuffers();

            MediaCodec.BufferInfo outBuffInfo = new MediaCodec.BufferInfo();
            byte[] tempBuffer = new byte[BUFFER_SIZE];
            boolean hasMoreData = true;
            double presentationTimeUs = 0;
            int audioTrackIdx = 0;
            int totalBytesRead = 0;
            int percentComplete = 0;

            do {
                int inputBufIndex = 0;
                while (inputBufIndex != -1 && hasMoreData) {
                    inputBufIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_IN_MS);

                    if (inputBufIndex >= 0) {
                        ByteBuffer dstBuf = codecInputBuffers[inputBufIndex];
                        dstBuf.clear();

                        int bytesRead = fis.read(tempBuffer, 0, dstBuf.limit());
                        Log.e("bytesRead", "Readed " + bytesRead);
                        if (bytesRead == -1) { // -1 implies EOS
                            hasMoreData = false;
                            codec.queueInputBuffer(inputBufIndex, 0, 0, (long) presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        } else {
                            totalBytesRead += bytesRead;
                            dstBuf.put(tempBuffer, 0, bytesRead);
                            codec.queueInputBuffer(inputBufIndex, 0, bytesRead, (long) presentationTimeUs, 0);
                            presentationTimeUs = 1000000l * (totalBytesRead / 2) / SAMPLING_RATE;
//                            presentationTimeUs = 1000000l * totalBytesRead / SAMPLING_RATE;
                        }
                    }
                }

                // Drain audio
                int outputBufIndex = 0;
                while (outputBufIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    outputBufIndex = codec.dequeueOutputBuffer(outBuffInfo, CODEC_TIMEOUT_IN_MS);
                    if (outputBufIndex >= 0) {
                        ByteBuffer encodedData = codecOutputBuffers[outputBufIndex];
                        encodedData.position(outBuffInfo.offset);
                        encodedData.limit(outBuffInfo.offset + outBuffInfo.size);
                        if ((outBuffInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 && outBuffInfo.size != 0) {
                            codec.releaseOutputBuffer(outputBufIndex, false);
                        } else {
                            mux.writeSampleData(audioTrackIdx, codecOutputBuffers[outputBufIndex], outBuffInfo);
                            codec.releaseOutputBuffer(outputBufIndex, false);
                        }
                    } else if (outputBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        outputFormat = codec.getOutputFormat();
                        Log.v(LOGTAG, "Output format changed - " + outputFormat);
                        audioTrackIdx = mux.addTrack(outputFormat);
                        mux.start();
                    } else if (outputBufIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        Log.e(LOGTAG, "Output buffers changed during encode!");
                    } else if (outputBufIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // NO OP
                    } else {
                        Log.e(LOGTAG, "Unknown return code from dequeueOutputBuffer - " + outputBufIndex);
                    }
                }

                percentComplete = (int) Math.round(((float) totalBytesRead / (float) inputFile.length()) * 100.0);
                Log.v(LOGTAG, "Conversion % - " + percentComplete);
            } while (outBuffInfo.flags != MediaCodec.BUFFER_FLAG_END_OF_STREAM);

            fis.close();
            mux.stop();
            mux.release();
            Log.v(LOGTAG, "Compression done ...");
            return true;
        } catch (FileNotFoundException e) {
            Log.e(LOGTAG, "File not found!", e);
        } catch (IOException e) {
            Log.e(LOGTAG, "IO exception!", e);
        }

        return false;
    }
}
