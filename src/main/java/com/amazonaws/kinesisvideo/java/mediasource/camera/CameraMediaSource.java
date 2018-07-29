package com.amazonaws.kinesisvideo.java.mediasource.camera;

import static com.amazonaws.kinesisvideo.producer.StreamInfo.NalAdaptationFlags.NAL_ADAPTATION_FLAG_NONE;
import static com.amazonaws.kinesisvideo.producer.Time.HUNDREDS_OF_NANOS_IN_A_MILLISECOND;
import static com.amazonaws.kinesisvideo.producer.Time.HUNDREDS_OF_NANOS_IN_A_SECOND;
import static com.amazonaws.kinesisvideo.producer.Time.HUNDREDS_OF_NANOS_IN_AN_HOUR;
import static com.amazonaws.kinesisvideo.producer.Time.NANOS_IN_A_TIME_UNIT;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.DEFAULT_BITRATE;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.DEFAULT_BUFFER_DURATION_IN_SECONDS;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.DEFAULT_GOP_DURATION;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.DEFAULT_REPLAY_DURATION_IN_SECONDS;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.DEFAULT_STALENESS_DURATION_IN_SECONDS;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.DEFAULT_TIMESCALE;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.KEYFRAME_FRAGMENTATION;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.MAX_LATENCY_ZERO;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.NOT_ADAPTIVE;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.NO_KMS_KEY_ID;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.RECALCULATE_METRICS;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.RECOVER_ON_FAILURE;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.RELATIVE_TIMECODES;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.REQUEST_FRAGMENT_ACKS;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.RETENTION_ONE_HOUR;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.USE_FRAME_TIMECODES;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.VERSION_ZERO;
import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.SDK_GENERATES_TIMECODES;

import com.amazonaws.kinesisvideo.client.mediasource.MediaSource;
import com.amazonaws.kinesisvideo.client.mediasource.MediaSourceConfiguration;
import com.amazonaws.kinesisvideo.client.mediasource.MediaSourceSink;
import com.amazonaws.kinesisvideo.client.mediasource.MediaSourceState;
import com.amazonaws.kinesisvideo.common.exception.KinesisVideoException;
import com.amazonaws.kinesisvideo.java.logging.SysOutLogChannel;
import com.amazonaws.kinesisvideo.java.mediasource.file.ImageFileMediaSourceConfiguration;
import com.amazonaws.kinesisvideo.java.mediasource.file.ImageFrameSource;
import com.amazonaws.kinesisvideo.mediasource.OnFrameDataAvailable;
import com.amazonaws.kinesisvideo.producer.KinesisVideoFrame;
import com.amazonaws.kinesisvideo.producer.StreamInfo;
import com.amazonaws.kinesisvideo.producer.Tag;

import com.github.sarxos.webcam.Webcam;
import com.github.sarxos.webcam.WebcamResolution;

import java.awt.Dimension;
import java.nio.ByteBuffer;

import org.openimaj.image.ImageUtilities;
import org.openimaj.image.processing.face.detection.DetectedFace;
import org.openimaj.image.processing.face.detection.HaarCascadeDetector;
import org.openimaj.math.geometry.shape.Rectangle;

public class CameraMediaSource implements MediaSource {

    private static final int FRAME_FLAG_KEY_FRAME = 1;
    private static final int FRAME_FLAG_NONE = 0;
    private static final long HUNDREDS_OF_NANOS_IN_MS = 10 * 1000;
    private static final long FRAME_DURATION_20_MS = 20L;
    
	private CameraMediaSourceConfiguration cameraMediaSourceConfiguration;
    private MediaSourceState mediaSourceState;
    private MediaSourceSink mediaSourceSink;
    private CameraFrameSource cameraFrameSource;
    private int frameIndex;
    Webcam webcam = null;
    
    
    public void setupWebCam(Webcam webcam) {
    	this.webcam = webcam;
    	Dimension size = WebcamResolution.VGA.getSize();
        webcam.setViewSize(size);
        webcam.open(true);
    }

	@Override
	public MediaSourceState getMediaSourceState() {
		// TODO Auto-generated method stub
		return mediaSourceState;
	}

	@Override
	public MediaSourceConfiguration getConfiguration() {
		// TODO Auto-generated method stub
		return cameraMediaSourceConfiguration;
	}

	@Override
	public void initialize(MediaSourceSink mediaSourceSink) throws KinesisVideoException {
		// TODO Auto-generated method stub
		this.mediaSourceSink = mediaSourceSink;
	}

	@Override
	public void configure(MediaSourceConfiguration cameraMediaSourceConfiguration) {
		// TODO Auto-generated method stub
		
		if (!(cameraMediaSourceConfiguration instanceof CameraMediaSourceConfiguration)) {
            throw new IllegalStateException("Configuration must be an instance of OpenCvMediaSourceConfiguration");
        }
        this.cameraMediaSourceConfiguration = (CameraMediaSourceConfiguration) cameraMediaSourceConfiguration;
        this.frameIndex = 0;
		
	}

	@Override
	public void start() throws KinesisVideoException {
		// TODO Auto-generated method stub
		mediaSourceState = MediaSourceState.RUNNING;
		cameraFrameSource = new CameraFrameSource(cameraMediaSourceConfiguration, webcam);
		cameraFrameSource.onBytesAvailable(createKinesisVideoFrameAndPushToProducer());
		cameraFrameSource.start();
		
	}

	private OnFrameDataAvailable createKinesisVideoFrameAndPushToProducer() {
		// TODO Auto-generated method stub
        return new OnFrameDataAvailable() {
            @Override
            public void onFrameDataAvailable(final ByteBuffer data) {
                final long currentTimeMs = System.currentTimeMillis();

                final int flags = isKeyFrame() 
                		? FRAME_FLAG_KEY_FRAME
                		: FRAME_FLAG_NONE;

                if (data != null) {
                    final KinesisVideoFrame frame = new KinesisVideoFrame(
                            frameIndex++,
                            flags,
                            currentTimeMs * HUNDREDS_OF_NANOS_IN_MS,
                            currentTimeMs * HUNDREDS_OF_NANOS_IN_MS,
                            FRAME_DURATION_20_MS * HUNDREDS_OF_NANOS_IN_MS,
                            data);

                    if (frame.getSize() == 0) {
                        return;
                    }

                    putFrame(frame);
                    
                } else {
                	System.out.println("Data not received from frame");
                }

            }
        };
	}
	
    private void putFrame(final KinesisVideoFrame kinesisVideoFrame) {
        try {
            mediaSourceSink.onFrame(kinesisVideoFrame);
        } catch (final KinesisVideoException ex) {
            throw new RuntimeException(ex);
        }
    }

    
    private boolean isKeyFrame() {
        return frameIndex % 19 == 0;
    }
    
	@Override
	public void stop() throws KinesisVideoException {
		// TODO Auto-generated method stub
        if (cameraFrameSource != null) {
        	cameraFrameSource.stop();
        }

        mediaSourceState = MediaSourceState.STOPPED;
        webcam.close();
		
	}

	@Override
	public boolean isStopped() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void free() throws KinesisVideoException {
		// TODO Auto-generated method stub
		
	}

    @Override
    public MediaSourceSink getMediaSourceSink() {
        return mediaSourceSink;
    }

    @Override
    public StreamInfo getStreamInfo(final String streamName) {      
        return new StreamInfo(VERSION_ZERO,
                streamName,
                StreamInfo.StreamingType.STREAMING_TYPE_REALTIME,
                contentType,
                NO_KMS_KEY_ID,
                cameraMediaSourceConfiguration.getRetentionPeriodInHours() * HUNDREDS_OF_NANOS_IN_AN_HOUR,
                NOT_ADAPTIVE,
                MAX_LATENCY_ZERO,
                DEFAULT_GOP_DURATION * HUNDREDS_OF_NANOS_IN_A_MILLISECOND,
                KEYFRAME_FRAGMENTATION,
                SDK_GENERATES_TIMECODES,
                cameraMediaSourceConfiguration.getIsAbsoluteTimecode(),
                REQUEST_FRAGMENT_ACKS,
                RECOVER_ON_FAILURE,
                StreamInfo.codecIdFromContentType(cameraMediaSourceConfiguration.getEncoderMimeType()),
                StreamInfo.createTrackName(cameraMediaSourceConfiguration.getEncoderMimeType()),
                cameraMediaSourceConfiguration.getBitRate(),
                cameraMediaSourceConfiguration.getFrameRate(),
                DEFAULT_BUFFER_DURATION_IN_SECONDS * HUNDREDS_OF_NANOS_IN_A_SECOND,
                DEFAULT_REPLAY_DURATION_IN_SECONDS * HUNDREDS_OF_NANOS_IN_A_SECOND,
                DEFAULT_STALENESS_DURATION_IN_SECONDS * HUNDREDS_OF_NANOS_IN_A_SECOND,
                cameraMediaSourceConfiguration.getTimeScale() / NANOS_IN_A_TIME_UNIT,
                RECALCULATE_METRICS,
                cameraMediaSourceConfiguration.getCodecPrivateData(),
                new Tag[] {
                    new Tag("device", "Test Device"),
                    new Tag("stream", "Test Stream") },
                cameraMediaSourceConfiguration.getNalAdaptationFlags());
    }
}
