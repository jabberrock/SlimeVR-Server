import { Button } from '@/components/commons/Button';
import { Typography } from '@/components/commons/Typography';
import { MainLayout } from '@/components/MainLayout';
import {
  SkeletonPreviewView,
  SkeletonVisualizerWidget,
} from '@/components/widgets/SkeletonVisualizerWidget';
import { useElectron } from '@/hooks/electron';
import { QuaternionFromQuatT } from '@/maths/quaternion';
import { useWebsocketAPI } from '@/hooks/websocket-api';
import classNames from 'classnames';
import { RefObject, useCallback, useEffect, useRef, useState } from 'react';
import { useLocalization } from '@fluent/react';
import {
  BodyPart,
  FindWebcamRequestT,
  FindWebcamResponseT,
  RpcMessage,
  StartVideoTrackerCalibrationRequestT,
  VideoTrackerCalibrationCameraT,
  VideoTrackerCalibrationProgressResponseT,
  VideoTrackerCalibrationStatus,
} from 'solarxr-protocol';
import { Matrix4, Quaternion, Vector3 } from 'three';

type VideoStreamStatus =
  | 'loading'
  | 'no-webcam'
  | 'connecting'
  | 'ready'
  | 'error';

function buildWebcamOfferUrl(host: string, port: number) {
  const normalizedHost =
    host.includes(':') && !host.startsWith('[') ? `[${host}]` : host;

  return `http://${normalizedHost}:${port}/offer`;
}

async function requestWebcamAnswer(
  host: string,
  port: number,
  sdp: string,
  isElectron: boolean
) {
  if (isElectron) {
    return window.electronAPI.webcamOffer({ host, port, sdp });
  }

  if (import.meta.env.DEV) {
    const response = await fetch('/video-calibration-api/offer', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        host,
        port,
        sdp,
      }),
    });

    if (!response.ok) {
      throw new Error(`Offer request failed with status ${response.status}`);
    }

    return (await response.json()) as { sdp?: unknown };
  }

  const response = await fetch(buildWebcamOfferUrl(host, port), {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      sdp,
    }),
  });

  if (!response.ok) {
    throw new Error(`Offer request failed with status ${response.status}`);
  }

  return (await response.json()) as { sdp?: unknown };
}

function waitForIceGatheringComplete(peerConnection: RTCPeerConnection) {
  if (peerConnection.iceGatheringState === 'complete') {
    return Promise.resolve();
  }

  return new Promise<void>((resolve) => {
    const onIceGatheringStateChange = () => {
      if (peerConnection.iceGatheringState !== 'complete') return;

      peerConnection.removeEventListener(
        'icegatheringstatechange',
        onIceGatheringStateChange
      );
      resolve();
    };

    peerConnection.addEventListener(
      'icegatheringstatechange',
      onIceGatheringStateChange
    );
  });
}

function asText(value: string | Uint8Array | null | undefined) {
  if (typeof value === 'string') return value;
  if (value instanceof Uint8Array) return new TextDecoder().decode(value);
  return '';
}

function getCalibrationStatusId(
  status: VideoTrackerCalibrationStatus | null,
  startRequested: boolean
) {
  if (status === null) {
    return startRequested
      ? 'video-calibration-status-starting'
      : 'video-calibration-status-idle';
  }

  switch (status) {
    case VideoTrackerCalibrationStatus.CALIBRATE_CAMERA:
      return 'video-calibration-status-calibrate_camera';
    case VideoTrackerCalibrationStatus.CAPTURE_FORWARD_POSE:
      return 'video-calibration-status-capture_forward_pose';
    case VideoTrackerCalibrationStatus.CAPTURE_BENT_OVER_POSE:
      return 'video-calibration-status-capture_bent_over_pose';
    case VideoTrackerCalibrationStatus.CALIBRATE_TRACKERS:
      return 'video-calibration-status-calibrate_trackers';
    case VideoTrackerCalibrationStatus.DONE:
      return 'video-calibration-status-done';
    default:
      return 'video-calibration-status-idle';
  }
}

function applyCalibrationCameraToView(
  view: SkeletonPreviewView,
  camera: VideoTrackerCalibrationCameraT
) {
  const near = 0.01;
  const far = 1000;

  const worldToCamera = QuaternionFromQuatT(camera.worldToCamera).normalize();
  const cameraToWorld = worldToCamera.clone().invert();
  const cvCameraToThreeCamera = new Quaternion().setFromAxisAngle(
    new Vector3(1, 0, 0),
    Math.PI
  );
  const cameraRotation = cameraToWorld.clone().multiply(cvCameraToThreeCamera);

  const worldOriginInCamera = camera.worldOriginInCamera;
  const cameraPosition = worldOriginInCamera
    ? new Vector3(
        worldOriginInCamera.x,
        worldOriginInCamera.y,
        worldOriginInCamera.z
      )
        .applyQuaternion(cameraToWorld)
        .multiplyScalar(-1)
    : new Vector3();

  const projectionMatrix = new Matrix4().set(
    (2 * camera.fx) / camera.width,
    0,
    1 - (2 * camera.tx) / camera.width,
    0,
    0,
    (2 * camera.fy) / camera.height,
    (2 * camera.ty) / camera.height - 1,
    0,
    0,
    0,
    -(far + near) / (far - near),
    (-2 * far * near) / (far - near),
    0,
    0,
    -1,
    0
  );

  view.interactive = false;
  view.manualProjectionMatrix = true;
  view.controls.enabled = false;
  view.controls.enableRotate = false;
  view.controls.enablePan = false;
  view.controls.enableZoom = false;
  view.controls.target.copy(cameraPosition);
  view.camera.near = near;
  view.camera.far = far;
  view.camera.zoom = 1;
  view.camera.position.copy(cameraPosition);
  view.camera.quaternion.copy(cameraRotation);
  view.camera.projectionMatrix.copy(projectionMatrix);
  view.camera.projectionMatrixInverse.copy(projectionMatrix).invert();
  view.camera.updateMatrixWorld(true);
}

function TrackerList({ trackers }: { trackers: BodyPart[] }) {
  const { l10n } = useLocalization();

  if (!trackers.length) {
    return <Typography id="video-calibration-none" color="secondary" />;
  }

  return (
    <div className="flex flex-col gap-1">
      {trackers.map((tracker) => (
        <Typography key={tracker}>
          {l10n.getString('body_part-' + BodyPart[tracker])}
        </Typography>
      ))}
    </div>
  );
}

function CameraDetails({
  camera,
}: {
  camera: VideoTrackerCalibrationCameraT | null;
}) {
  if (!camera) {
    return (
      <Typography id="video-calibration-camera-unavailable" color="secondary" />
    );
  }

  return (
    <div className="flex flex-col gap-2">
      <Typography id="video-calibration-camera-available" />
      <div className="flex flex-col gap-1">
        <Typography
          color="secondary"
          id="video-calibration-camera-resolution"
        />
        <Typography>
          {camera.width} x {camera.height}
        </Typography>
      </div>
      <div className="flex flex-col gap-1">
        <Typography
          color="secondary"
          id="video-calibration-camera-focal_length"
        />
        <Typography>
          {camera.fx.toFixed(1)} / {camera.fy.toFixed(1)}
        </Typography>
      </div>
      <div className="flex flex-col gap-1">
        <Typography
          color="secondary"
          id="video-calibration-camera-principal_point"
        />
        <Typography>
          {camera.tx.toFixed(1)} / {camera.ty.toFixed(1)}
        </Typography>
      </div>
    </div>
  );
}

function VideoCalibrationSidebar({
  progress,
  startRequested,
  onStartCalibration,
  showVideo,
  onToggleVideo,
}: {
  progress: VideoTrackerCalibrationProgressResponseT | null;
  startRequested: boolean;
  onStartCalibration: () => void;
  showVideo: boolean;
  onToggleVideo: () => void;
}) {
  const error = asText(progress?.error);

  return (
    <div className="my-2 flex h-[calc(100%-16px)] flex-col gap-4 rounded-lg bg-background-70 p-4">
      <div className="flex flex-col gap-1">
        <Typography
          variant="section-title"
          id="video-calibration-sidebar-title"
        />
        <Typography
          color="secondary"
          id="video-calibration-sidebar-description"
        />
      </div>

      <div className="flex flex-col gap-1">
        <Typography color="secondary" id="video-calibration-status-label" />
        <Typography
          id={getCalibrationStatusId(progress?.status ?? null, startRequested)}
        />
      </div>

      <div className="flex flex-col gap-2">
        <Typography color="secondary" id="video-calibration-camera-label" />
        <CameraDetails camera={progress?.camera ?? null} />
      </div>

      <div className="flex flex-col gap-2">
        <Typography color="secondary" id="video-calibration-done-trackers" />
        <TrackerList trackers={progress?.trackersDone ?? []} />
      </div>

      <div className="flex flex-col gap-2">
        <Typography color="secondary" id="video-calibration-pending-trackers" />
        <TrackerList trackers={progress?.trackersPending ?? []} />
      </div>

      {!!error && (
        <div className="flex flex-col gap-1">
          <Typography color="secondary" id="video-calibration-error-label" />
          <Typography color="text-status-critical">{error}</Typography>
        </div>
      )}

      <Button
        variant="primary"
        className="mt-auto w-full"
        onClick={onStartCalibration}
        loading={startRequested}
        id="video-calibration-start"
      />
      <Button variant="secondary" className="w-full" onClick={onToggleVideo}>
        {showVideo ? 'Hide Video' : 'Show Video'}
      </Button>
    </div>
  );
}

function VideoCalibrationContent({
  videoRef,
  skeletonViewRef,
  calibrationCamera,
  showVideo,
  status,
  errorMessage,
}: {
  videoRef: RefObject<HTMLVideoElement>;
  skeletonViewRef: React.MutableRefObject<SkeletonPreviewView | null>;
  calibrationCamera: VideoTrackerCalibrationCameraT | null;
  showVideo: boolean;
  status: VideoStreamStatus;
  errorMessage: string;
}) {
  return (
    <div className="flex h-full w-full items-center justify-center overflow-hidden p-4">
      <div className="flex h-full w-full items-center justify-center">
        <div className="relative h-full max-h-[1280px] w-auto max-w-[720px] aspect-[720/1280] overflow-hidden rounded-lg bg-black">
          <video
            ref={videoRef}
            autoPlay
            playsInline
            muted
            className={classNames(
              'absolute left-0 top-0 z-0 h-full w-full object-fill transition-opacity',
              showVideo ? 'opacity-100' : 'opacity-0'
            )}
          />
          {showVideo && (
            <div className="absolute left-0 top-0 z-[5] h-full w-full bg-black/40" />
          )}
          <SkeletonVisualizerWidget
            className="pointer-events-none absolute left-0 top-0 z-10 h-full w-full [filter:drop-shadow(0_0_8px_rgba(255,255,255,0.55))] [transform:scaleX(-1)]"
            showGrid={false}
            stabilizeSkeleton={false}
            anchorToHmdPosition
            onInit={(context) => {
              skeletonViewRef.current =
                context.addView({
                  left: 0,
                  bottom: 0,
                  width: 1,
                  height: 1,
                  position: new Vector3(3, 2.5, -3),
                  interactive: false,
                  manualProjectionMatrix: false,
                  onHeightChange(v, newHeight) {
                    if (v.manualProjectionMatrix) return;

                    v.controls.target.set(0, newHeight / 2, 0);
                    const scale = Math.max(1, newHeight) / 1.5;
                    v.camera.zoom = 1 / scale;
                    v.controls.update();
                    v.camera.updateProjectionMatrix();
                  },
                }) ?? null;

              if (skeletonViewRef.current) {
                skeletonViewRef.current.controls.target.set(0, 1, 0);
                skeletonViewRef.current.controls.update();
              }

              if (calibrationCamera && skeletonViewRef.current) {
                applyCalibrationCameraToView(
                  skeletonViewRef.current,
                  calibrationCamera
                );
              }
            }}
          />
        </div>
        {status !== 'ready' && (
          <div className="flex max-w-xl flex-col items-center gap-1 text-center">
            {status === 'loading' && (
              <Typography id="video-calibration-loading" />
            )}
            {status === 'connecting' && (
              <Typography id="video-calibration-connecting" />
            )}
            {status === 'no-webcam' && (
              <Typography id="video-calibration-no_webcam" />
            )}
            {status === 'error' && (
              <>
                <Typography id="video-calibration-error" />
                {!!errorMessage && (
                  <Typography color="text-status-critical">
                    {errorMessage}
                  </Typography>
                )}
              </>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

export function VideoCalibrationPage({ isMobile }: { isMobile?: boolean }) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const skeletonViewRef = useRef<SkeletonPreviewView | null>(null);
  const peerConnectionRef = useRef<RTCPeerConnection | null>(null);
  const attemptRef = useRef(0);
  const [status, setStatus] = useState<VideoStreamStatus>('loading');
  const [errorMessage, setErrorMessage] = useState('');
  const [startRequested, setStartRequested] = useState(false);
  const [showVideo, setShowVideo] = useState(true);
  const [progress, setProgress] =
    useState<VideoTrackerCalibrationProgressResponseT | null>(null);
  const electron = useElectron();
  const { sendRPCPacket, useRPCPacket } = useWebsocketAPI();

  const cleanupConnection = useCallback(() => {
    const peerConnection = peerConnectionRef.current;
    if (peerConnection) {
      peerConnection.ontrack = null;
      peerConnection
        .getReceivers()
        .forEach((receiver) => receiver.track?.stop());
      peerConnection.close();
      peerConnectionRef.current = null;
    }

    const currentStream = videoRef.current?.srcObject;
    if (currentStream instanceof MediaStream) {
      currentStream.getTracks().forEach((track) => track.stop());
    }

    if (videoRef.current) {
      videoRef.current.srcObject = null;
    }
  }, []);

  const connectToWebcam = useCallback(
    async (webcam: FindWebcamResponseT) => {
      const host = typeof webcam.host === 'string' ? webcam.host : null;

      if (!host) {
        cleanupConnection();
        setStatus('no-webcam');
        setErrorMessage('');
        return;
      }

      const attempt = ++attemptRef.current;
      const peerConnection = new RTCPeerConnection();
      const remoteStream = new MediaStream();

      cleanupConnection();
      peerConnectionRef.current = peerConnection;
      setStatus('connecting');
      setErrorMessage('');

      peerConnection.ontrack = (event) => {
        const incomingTracks = event.streams[0]?.getTracks() ?? [event.track];

        incomingTracks.forEach((track) => {
          if (!remoteStream.getTracks().some(({ id }) => id === track.id)) {
            remoteStream.addTrack(track);
          }
        });

        if (videoRef.current) {
          videoRef.current.srcObject = remoteStream;
        }

        setStatus('ready');
      };

      try {
        peerConnection.addTransceiver('video', { direction: 'recvonly' });

        const offer = await peerConnection.createOffer();
        await peerConnection.setLocalDescription(offer);
        await waitForIceGatheringComplete(peerConnection);

        const localSdp = peerConnection.localDescription?.sdp;
        if (!localSdp) {
          throw new Error('Peer connection did not produce a local SDP offer');
        }

        const body = await requestWebcamAnswer(
          host,
          webcam.port,
          localSdp,
          electron.isElectron
        );
        if (typeof body.sdp !== 'string' || body.sdp.length === 0) {
          throw new Error('Webcam response did not contain an SDP answer');
        }

        if (attempt !== attemptRef.current) return;

        await peerConnection.setRemoteDescription({
          type: 'answer',
          sdp: body.sdp,
        });
      } catch (error) {
        if (attempt !== attemptRef.current) return;

        cleanupConnection();
        setStatus('error');
        setErrorMessage(
          error instanceof Error ? error.message : 'Unknown webcam error'
        );
      }
    },
    [cleanupConnection, electron.isElectron]
  );

  useRPCPacket(
    RpcMessage.FindWebcamResponse,
    (response: FindWebcamResponseT) => {
      void connectToWebcam(response);
    }
  );

  useRPCPacket(
    RpcMessage.VideoTrackerCalibrationProgressResponse,
    (response: VideoTrackerCalibrationProgressResponseT) => {
      setStartRequested(false);
      setProgress(response);
    }
  );

  const startCalibration = useCallback(() => {
    setStartRequested(true);
    setProgress(null);
    sendRPCPacket(
      RpcMessage.StartVideoTrackerCalibrationRequest,
      new StartVideoTrackerCalibrationRequestT()
    );
  }, [sendRPCPacket]);

  useEffect(() => {
    sendRPCPacket(RpcMessage.FindWebcamRequest, new FindWebcamRequestT());

    return () => {
      attemptRef.current += 1;
      skeletonViewRef.current = null;
      cleanupConnection();
    };
  }, []);

  useEffect(() => {
    if (!progress?.camera || !skeletonViewRef.current) return;

    applyCalibrationCameraToView(skeletonViewRef.current, progress.camera);
  }, [progress?.camera]);

  return (
    <MainLayout
      isMobile={isMobile}
      full
      showToolbar={false}
      scrollContent={false}
      rightSidebar={
        <VideoCalibrationSidebar
          progress={progress}
          startRequested={startRequested}
          onStartCalibration={startCalibration}
          showVideo={showVideo}
          onToggleVideo={() => setShowVideo((value) => !value)}
        />
      }
    >
      <VideoCalibrationContent
        videoRef={videoRef}
        skeletonViewRef={skeletonViewRef}
        calibrationCamera={progress?.camera ?? null}
        showVideo={showVideo}
        status={status}
        errorMessage={errorMessage}
      />
    </MainLayout>
  );
}
