# Video Calibration

Video calibration uses a phone camera to capture a person's movement, extract
their pose, and calculate the reset rotations to match a tracker's orientation
to the orientation of the corresponding bone. It also updates the body
proportions so that the SlimeVR skeleton matches the person's skeleton as much
as possible.

## How to use

1. The user runs the "Vision" app onto their phone.
2. The user selects the "Video Calibration" tab in SlimeVR Server.
3. The user presses "Start Calibration":
   1. Camera calibration
   2. Face forward
   3. Lean forward
   4. Do a 360° turn while stepping in place

When the process is complete, the trackers are aligned, and the body proportions
are optimized.

## How it works

## Vision app

The Vision app is an iOS and Android app which provides:
- Video feed
- Human pose estimation (e.g. using [MediaPipe pose landmarker](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker))
- Camera intrinsic (focal lengths and principal point)
- Camera orientation (with respect to some arbitrary Y-vertical coordinate
  system)

(Video calibration doesn't work with just a generic webcam because it is missing
the camera intrinsic and orientation. If desired, camera intrinsic can be
recovered using a checkerboard, and orientation can be recovered using a
built-in IMU, but this is not implemented.)

The Vision app can be used as a simple webcam for SlimeVR and other computer
vision applications. It is open-source.
