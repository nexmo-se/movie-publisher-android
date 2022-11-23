package com.vonage.video.moviepublisher

import android.Manifest
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Environment
import android.util.Log
import androidx.core.app.ActivityCompat
import java.io.File
import java.io.FileOutputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.util.*
import kotlin.concurrent.thread


/* MediaDecoder

   Author: Andrew Stubbs (based on some examples from the docs)

   This class opens a file, reads the first audio channel it finds, and returns raw audio data.

   Usage:
      MediaDecoder decoder = new MediaDecoder("myfile.m4a");
      short[] data;
      while ((data = decoder.readShortData()) != null) {
         // process data here
      }
  */



class AudioDecoder(inputFilename: Int, ctx: Context, audioSamples: Queue<ShortArray>) {
    private val extractor = MediaExtractor()
    private var decoder: MediaCodec? = null
    private var inputFormat: MediaFormat? = null
    private val inputBuffers: Array<ByteBuffer>
    private var end_of_input_file: Boolean
    private var outputBuffers: Array<ByteBuffer>
    private var outputBufferIndex = -1
    private var TAG = "AudioDecoder"
    private var pcmFile : File? = null
    private var pcmStream : FileOutputStream? = null
    private var samplesForAudioDevice:Queue<ShortArray>? = null
    // Read the raw data from MediaCodec.
    // The caller should copy the data out of the ByteBuffer before calling this again
    // or else it may get overwritten.
    public fun readData(info: MediaCodec.BufferInfo): ByteBuffer? {
        if (decoder == null) return null
        while (true) {

            // Read data from the file into the codec.
            if (!end_of_input_file) {
                val inputBufferIndex = decoder!!.dequeueInputBuffer(10000)
                if (inputBufferIndex >= 0) {
                    val size = extractor.readSampleData(inputBuffers[inputBufferIndex], 0)
                    if (size < 0) {
                        // End Of File
                        decoder!!.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        end_of_input_file = true
                    } else {
                        decoder!!.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            size,
                            extractor.sampleTime,
                            0
                        )
                        extractor.advance()
                    }
                }
            }

            // Read the output from the codec.
            if (outputBufferIndex >= 0) // Ensure that the data is placed at the start of the buffer
                outputBuffers[outputBufferIndex].position(0)
            //Log.d(TAG,"A_PRESENTTIME: ${info.presentationTimeUs}")
            outputBufferIndex = decoder!!.dequeueOutputBuffer(info, 10000)
            if (outputBufferIndex >= 0) {
                // Handle EOF
                if (info.flags != 0) {
                    decoder!!.stop()
                    decoder!!.release()
                    decoder = null
                    return null
                }
                val samples: ShortBuffer =
                    outputBuffers[outputBufferIndex].order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val numChannels: Int = inputFormat!!.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val res = ShortArray(samples.remaining() / numChannels)
                for (i in 0 until res.size) {
                    for(k in 0 until numChannels-1) {
                        if(k==0)
                            res[i] = (0.8* samples.get(i * numChannels + k)).toInt().toShort()
                        else
                            res[i] =
                                (res[i] + 0.8*samples.get(i * numChannels + k)).toInt().toShort()
                        if (res[i] > Short.MAX_VALUE) {
                            res[i] = Short.MAX_VALUE;
                        }
                        if (res[i] < Short.MIN_VALUE) {
                            res[i] = Short.MIN_VALUE;
                        }
                    }
                }
                samplesForAudioDevice?.add(res)
                //pcmStream?.write(ShortToByte_ByteBuffer_Method(res))
                // Release the buffer so MediaCodec can use it again.
                // The data should stay there until the next time we are called.
                decoder!!.releaseOutputBuffer(outputBufferIndex, false)
                return outputBuffers[outputBufferIndex]
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // This usually happens once at the start of the file.
                outputBuffers = decoder!!.outputBuffers
            }
        }
    }
    fun ShortToByte_ByteBuffer_Method(input: ShortArray): ByteBuffer? {
        var index: Int
        val iterations = input.size
        val bb = ByteBuffer.allocateDirect(input.size * 2)
        index = 0
        while (index != iterations) {
            bb.order(ByteOrder.LITTLE_ENDIAN).putShort(input[index])
            ++index
        }
        return bb
    }
    // Return the Audio sample rate, in samples/sec.
    val sampleRate: Int
        get() = inputFormat!!.getInteger(MediaFormat.KEY_SAMPLE_RATE)

    fun startProcessing(){
        thread {
            while(true) {
                var info:MediaCodec.BufferInfo = MediaCodec.BufferInfo()
                var ret = readData(info)
                if(ret == null || end_of_input_file){
                    break
                }
                ret = null;
                Thread.sleep(3)
            }
        }
    }

    init {
        samplesForAudioDevice = audioSamples
        pcmFile = File(ctx!!.filesDir,"out.pcm")
        pcmStream = FileOutputStream(pcmFile)
        var afd: AssetFileDescriptor = ctx!!.getResources().openRawResourceFd(inputFilename);
        extractor.setDataSource(afd.getFileDescriptor(),afd.getStartOffset(),afd.getLength())

        // Select the first audio track we find.
        val numTracks = extractor.trackCount
        for (i in 0 until numTracks) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime!!.startsWith("audio/")) {
                extractor.selectTrack(i)
                decoder = MediaCodec.createDecoderByType(mime)
                decoder!!.configure(format, null, null, 0)
                inputFormat = format
                Log.d(TAG,"input format $inputFormat")
                break
            }
        }
        requireNotNull(decoder) { "No decoder for file format" }
        decoder!!.start()
        inputBuffers = decoder!!.inputBuffers
        outputBuffers = decoder!!.outputBuffers
        end_of_input_file = false
    }
}