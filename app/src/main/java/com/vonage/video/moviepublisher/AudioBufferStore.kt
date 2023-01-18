package com.vonage.video.moviepublisher

import android.util.Log
import java.util.*

class AudioBufferStore(tag: String) {
    var audioSamples: Queue<ShortArray>? = null
    private var curSample: ShortArray? = null
    private var curSampleSize = 0
    private var curSamplePos = 0
    private var TAG = "AudioBufferStore"

    init {
        audioSamples = LinkedList()
        TAG = tag
    }

    public fun addSamples(buffer: ShortArray){
        audioSamples?.add(buffer)
    }

    public fun flush(){
        audioSamples?.clear()
    }
    public fun getAudioSamples(samplesRead: Int, temp: ShortArray): Boolean{
        var bufferFilled = false
        if (curSample != null) {
            if (curSamplePos + samplesRead < curSampleSize) {
                System.arraycopy(curSample, curSamplePos, temp, 0, samplesRead)
                curSamplePos += samplesRead
                bufferFilled = true
            } else {
                System.arraycopy(curSample, curSamplePos, temp, 0, curSampleSize - curSamplePos)
                //Log.d(TAG,"Read " + (curSampleSize-curSamplePos) + "Samples");
                if (audioSamples!!.peek() != null) {
                    if(audioSamples!!.peek().size < (samplesRead-(curSampleSize-curSamplePos))) {
                        bufferFilled = false;
                        curSample = null
                        curSamplePos=0
                        curSampleSize=0
                    }
                    else {
                        curSample = audioSamples!!.remove()
                        System.arraycopy(
                            curSample,
                            0,
                            temp,
                            curSampleSize - curSamplePos,
                            samplesRead - (curSampleSize - curSamplePos)
                        )

                        //Log.d(TAG,"Read " + (samplesRead-(curSampleSize-curSamplePos)) + "Samples from new buffer");
                        curSamplePos = samplesRead - (curSampleSize - curSamplePos)
                        curSampleSize = curSample!!.size
                        bufferFilled = true
                    }
                } else {
                    bufferFilled = false
                    curSample = null
                    curSamplePos=0
                    curSampleSize=0
                }
            }
        } else {
            if ( audioSamples != null && audioSamples!!.peek() != null) {
                curSample = audioSamples!!.remove()
                curSamplePos = 0
                curSampleSize = curSample!!.size
            }
        }
        return bufferFilled
    }
}