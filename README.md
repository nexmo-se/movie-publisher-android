# Movie Publisher 

This projects lets you publish an mp4 file in to a Vonage video session. User can also speak while the video is being played
Please see resources/raw folder for sample videos.
## How it works

### Movie player

1. MoviePlayer class implements the logic to decode mp4 file in to separate audio and video tracks
2. Audio track in mp4 can contain multiple channels (stereo or 5.1 surround). We mix all the channels in to single channel as video SDK works with mono by default.
3. We use presentation time stamp provided by the decoder to synchronize audio and video

### Mixed Audio device

1. This is a custom audio device implementing the audio interface provided by video SDK. 
2. Here we take the audio from microphone and mix with the audio coming from mp4 movie
3. We configure the custom audio device to work at the same audio sample rate as the mp4.

### Movie Video Capturer

1. This is a custom capturer implementing the video capture interface provided by video SDK. 
2. We fetch the video frames from Movie Player class and feed to the SDK.

This sample is tested with two sample mp4 files.

1. MP4 with audio at 48KHz and stereo
2. MP4 with audio at 44.1KHz and 5.1 audio
