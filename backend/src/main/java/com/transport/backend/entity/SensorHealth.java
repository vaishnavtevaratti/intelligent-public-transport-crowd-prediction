package com.transport.backend.entity;

public enum SensorHealth {
    /** Registered but never reported. */
    UNKNOWN,
    OK,
    /** Working, but frames are dark/blurred or confidence is low. */
    DEGRADED,
    /** The camera/video source could not be opened or stopped delivering frames. */
    CAMERA_UNAVAILABLE,
    /** The detection model failed. */
    INFERENCE_ERROR
}
