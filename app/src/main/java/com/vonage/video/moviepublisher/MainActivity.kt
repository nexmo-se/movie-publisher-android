package com.vonage.video.moviepublisher
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.opentok.android.*


class MainActivity : AppCompatActivity() {
    private var token: String = "T1==cGFydG5lcl9pZD00NjE4MzQ1MiZzaWc9YjI5MTE4MjU3NjI3NDIwOWMyZTY2ODI4NGZmZWE1ZjBhNTkxMmUyNDpzZXNzaW9uX2lkPTJfTVg0ME5qRTRNelExTW41LU1UWTNNamc0T0RZMk9ERTNOMzVEWWxBNWVHOUJaa1ZQYWl0WFlVUnRRbmhQTUhaaE0wSi1mbjQmY3JlYXRlX3RpbWU9MTY3NDAyMzUyNyZub25jZT0wLjM3MjAzOTIzMjM0NTIxODImcm9sZT1tb2RlcmF0b3ImZXhwaXJlX3RpbWU9MTY3NjYxNTUyNyZpbml0aWFsX2xheW91dF9jbGFzc19saXN0PQ=="
    private var sessionId: String = "2_MX40NjE4MzQ1Mn5-MTY3Mjg4ODY2ODE3N35DYlA5eG9BZkVPaitXYURtQnhPMHZhM0J-fn4"
    private var apiKey: String = "46183452"
    private var pubLayout: FrameLayout? = null
    private var pub: Publisher? = null
    private var sub: Subscriber? = null
    private var session: Session? = null
    private var TAG: String = "MoviePublisherMainActivity"

    private var moviePlayer: MoviePlayer? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        pubLayout = findViewById(R.id.publisherLayout)

        moviePlayer = MoviePlayer(R.raw.sample_video,this)

        var aDevice = MixedAudioDevice(this,moviePlayer,moviePlayer!!.audioSampleRate)
        AudioDeviceManager.setAudioDevice(aDevice)
        initializeSession(apiKey,sessionId,token)
    }

    private fun initializeSession(apiKey: String, sessionId: String, token: String) {
        Log.i(TAG, "apiKey: $apiKey")
        Log.i(TAG, "sessionId: $sessionId")
        Log.i(TAG, "token: $token")
       // com.opentok.android.OpenTokConfig.setWebRTCLogs(true)
        //com.opentok.android.OpenTokConfig.setOTKitLogs(true)
        //com.opentok.android.OpenTokConfig.setJNILogs(true)
        session = Session.Builder(this, apiKey, sessionId).build().also {
            it.setSessionListener(sessionListener)
            it.connect(token)
        }
    }

    private val sessionListener: Session.SessionListener = object : Session.SessionListener {
        override fun onConnected(session: Session) {
            Log.d(TAG, "onConnected: Connected to session: ${session.sessionId}")
            var movieVideoCapturer: MovieVideoCapturer = MovieVideoCapturer(moviePlayer!!)

            pub = Publisher.Builder(this@MainActivity).capturer(movieVideoCapturer).build()
            //pub?.publishAudio = false
            pub?.setPublisherListener(publisherListerner)
            pub?.setStyle(BaseVideoRenderer.STYLE_VIDEO_SCALE, BaseVideoRenderer.STYLE_VIDEO_FILL);
            //pub?.publishVideo = false
            pubLayout?.addView(pub?.view)

            session.publish(pub)
            moviePlayer?.hasStarted = true
        }

        override fun onDisconnected(session: Session) {
            Log.d(TAG, "onDisconnected: Disconnected from session: ${session.sessionId}")
        }

        override fun onStreamReceived(session: Session, stream: Stream) {
            Log.d(TAG, "onStreamReceived: New Stream Received ${stream.streamId} in session: ${session.sessionId}")
            if (sub == null) {
                sub = Subscriber.Builder(this@MainActivity, stream).build().also {
                    it.renderer?.setStyle(
                        BaseVideoRenderer.STYLE_VIDEO_SCALE,
                        BaseVideoRenderer.STYLE_VIDEO_FILL
                    )

                    it.setSubscriberListener(subscriberListener)
                }

                session.subscribe(sub)
            }
        }

        override fun onStreamDropped(session: Session, stream: Stream) {
            Log.d(TAG, "onStreamDropped: Stream Dropped: ${stream.streamId} in session: ${session.sessionId}")
        }

        override fun onError(session: Session, opentokError: OpentokError) {
            finishWithMessage("Session error: ${opentokError.message}")
        }
    }

    var publisherListerner: PublisherKit.PublisherListener = object: PublisherKit.PublisherListener{
        override fun onStreamCreated(p0: PublisherKit?, p1: Stream?) {
            Log.i("PUBLISHERLOG","Stream created")
        }

        override fun onStreamDestroyed(p0: PublisherKit?, p1: Stream?) {
            Log.i("PUBLISHERLOG","Stream destroyed")
        }

        override fun onError(p0: PublisherKit?, p1: OpentokError?) {
            Log.e("PUBLISHERLOG", "Error message: " + p1?.message + " Code:" + p1?.errorCode)
        }

    }
    var subscriberListener: SubscriberKit.SubscriberListener = object : SubscriberKit.SubscriberListener {
        override fun onConnected(subscriberKit: SubscriberKit) {
            Log.d(TAG, "onConnected: Subscriber connected. Stream: ${subscriberKit.stream.streamId}")
        }

        override fun onDisconnected(subscriberKit: SubscriberKit) {
            Log.d(TAG, "onDisconnected: Subscriber disconnected. Stream: ${subscriberKit.stream.streamId}")
        }

        override fun onError(subscriberKit: SubscriberKit, opentokError: OpentokError) {
            finishWithMessage("SubscriberKit onError: ${opentokError.message}")
        }
    }

    private fun finishWithMessage(message: String) {
        Log.e(TAG, message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}