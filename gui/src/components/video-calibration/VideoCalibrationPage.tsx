import { ResetButton } from '@/components/home/ResetButton';
import { Button } from '@/components/commons/Button';
import { Typography } from '@/components/commons/Typography';
import { MainLayout } from '@/components/MainLayout';
import { Localized, useLocalization } from '@fluent/react';
import QRCode from 'qrcode';
import {
  SkeletonPreviewView,
  SkeletonVisualizerWidget,
} from '@/components/widgets/SkeletonVisualizerWidget';
import { QuaternionFromQuatT } from '@/maths/quaternion';
import { useWebsocketAPI } from '@/hooks/websocket-api';
import { useConfig } from '@/hooks/config';
import { openUrl } from '@/hooks/crossplatform';
import { EYE_HEIGHT_TO_HEIGHT_RATIO } from '@/hooks/height';
import { useLocaleConfig } from '@/i18n/config';
import { resetChimeSound, restartAndPlay } from '@/sounds/sounds';
import classNames from 'classnames';
import { RefObject, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  BodyPart,
  ConnectToVideoCalibrationRequestT,
  ConnectToVideoCalibrationResponseT,
  QuatT,
  ResetType,
  RpcMessage,
  SkeletonConfigRequestT,
  SkeletonConfigResponseT,
  StartVideoCalibrationRequestT,
  Vec3fT,
  VideoCalibrationProcess,
  VideoCalibrationCameraT,
  VideoCalibrationErrorT,
  VideoCalibrationProgressT,
  VideoCalibrationStatus,
  VideoCalibrationTrackerStatusT,
} from 'solarxr-protocol';
import { Matrix4, Quaternion, Vector3 } from 'three';

const VC_WEBRTC_LOG = '[video-calibration:webrtc]';
const VC_CALIB_LOG = '[video-calibration:calibration]';
const VIDEO_CALIB_STATUS_NOT_STARTED = 0 as VideoCalibrationStatus;
const SIMPLE_WEBCAM_RELEASES_URL =
  'https://github.com/jabberrock/SimpleWebcamAndroid/releases';
const VIDEO_CALIBRATION_SESSION_STORAGE_KEY = 'slimevr-gui-video-calibration-session-v1';

/** Loads from `gui/public/videos/video_calibration/` (URL prefix respects Vite `base`). */
function calibrationDemoVideoPublicBase(): string {
  const prefix = import.meta.env.BASE_URL;
  const base = prefix.endsWith('/') ? prefix.slice(0, -1) : prefix;
  return `${base}/videos/video_calibration`;
}

/** Expected asset stems (omit extension): prefer `{stem}.webm`, fallback `{stem}.mp4`; use `default` for other phases. */
function calibrationDemoStemForStatus(calibrationStatus: VideoCalibrationStatus): string {
  switch (calibrationStatus) {
    case VideoCalibrationStatus.SOLVING_CAMERA_EXTRINSIC:
      return 'calibrate_camera';
    case VideoCalibrationStatus.CAPTURING_FORWARD_POSE:
      return 'capture_forward_pose';
    case VideoCalibrationStatus.CAPTURING_LEANING_FORWARD_POSE:
      return 'capture_leaning_forward_pose';
    case VideoCalibrationStatus.ALIGNING_UPPER_BODY_TRACKERS:
      return 'align_upper_body_trackers';
    case VideoCalibrationStatus.ALIGNING_REMAINING_TRACKERS:
      return 'align_remaining_trackers';
    case VideoCalibrationStatus.OPTIMIZING_BODY_PROPORTIONS:
      return 'optimize_body_proportions';
    case VideoCalibrationStatus.COMPLETE:
      return 'complete';
    default:
      return 'default';
  }
}

try {
  localStorage.removeItem('slimevr-gui-video-calibration-state-v1');
} catch {
  /* migrated off localStorage - camera no longer survives app restart */
}

type PersistedVideoCalibrationV1 = {
  v: 1;
  calibrationStatus: VideoCalibrationStatus;
  camera: {
    worldToCamera: { x: number; y: number; z: number; w: number } | null;
    worldOriginInCamera: { x: number; y: number; z: number } | null;
    fx: number;
    fy: number;
    tx: number;
    ty: number;
    width: number;
    height: number;
  };
};

function calibrationCameraToPersisted(
  camera: VideoCalibrationCameraT
): PersistedVideoCalibrationV1['camera'] {
  const q = camera.worldToCamera;
  const o = camera.worldOriginInCamera;
  return {
    worldToCamera: q ? { x: q.x, y: q.y, z: q.z, w: q.w } : null,
    worldOriginInCamera: o ? { x: o.x, y: o.y, z: o.z } : null,
    fx: camera.fx,
    fy: camera.fy,
    tx: camera.tx,
    ty: camera.ty,
    width: camera.width,
    height: camera.height,
  };
}

function persistedCameraToCalibrationCamera(
  raw: PersistedVideoCalibrationV1['camera']
): VideoCalibrationCameraT {
  const q = raw.worldToCamera;
  const o = raw.worldOriginInCamera;
  return new VideoCalibrationCameraT(
    q ? new QuatT(q.x, q.y, q.z, q.w) : null,
    o ? new Vec3fT(o.x, o.y, o.z) : null,
    raw.fx,
    raw.fy,
    raw.tx,
    raw.ty,
    raw.width,
    raw.height
  );
}

function loadPersistedVideoCalibration(): {
  calibrationStatus: VideoCalibrationStatus;
  calibrationCamera: VideoCalibrationCameraT | null;
} {
  try {
    const raw = sessionStorage.getItem(VIDEO_CALIBRATION_SESSION_STORAGE_KEY);
    if (!raw) {
      return {
        calibrationStatus: VIDEO_CALIB_STATUS_NOT_STARTED,
        calibrationCamera: null,
      };
    }
    const parsed = JSON.parse(raw) as PersistedVideoCalibrationV1;
    if (parsed?.v !== 1 || !parsed.camera) {
      return {
        calibrationStatus: VIDEO_CALIB_STATUS_NOT_STARTED,
        calibrationCamera: null,
      };
    }
    const camera = persistedCameraToCalibrationCamera(parsed.camera);
    if (camera.width <= 0 || camera.height <= 0) {
      return {
        calibrationStatus: VIDEO_CALIB_STATUS_NOT_STARTED,
        calibrationCamera: null,
      };
    }
    return {
      calibrationStatus: parsed.calibrationStatus ?? VIDEO_CALIB_STATUS_NOT_STARTED,
      calibrationCamera: camera,
    };
  } catch {
    return {
      calibrationStatus: VIDEO_CALIB_STATUS_NOT_STARTED,
      calibrationCamera: null,
    };
  }
}

function savePersistedVideoCalibration(
  calibrationCamera: VideoCalibrationCameraT | null,
  calibrationStatus: VideoCalibrationStatus
) {
  try {
    if (!calibrationCamera) {
      sessionStorage.removeItem(VIDEO_CALIBRATION_SESSION_STORAGE_KEY);
      return;
    }
    const payload: PersistedVideoCalibrationV1 = {
      v: 1,
      calibrationStatus,
      camera: calibrationCameraToPersisted(calibrationCamera),
    };
    sessionStorage.setItem(
      VIDEO_CALIBRATION_SESSION_STORAGE_KEY,
      JSON.stringify(payload)
    );
  } catch {
    /* ignore quota / private mode */
  }
}

type VideoStreamStatus = 'loading' | 'connecting' | 'ready' | 'error';
type VideoCalibrationErrorNotification = {
  message: string;
  fading: boolean;
};

function waitForIceGatheringComplete(peerConnection: RTCPeerConnection) {
  console.log(
    VC_WEBRTC_LOG,
    'ICE gathering initial state:',
    peerConnection.iceGatheringState
  );

  if (peerConnection.iceGatheringState === 'complete') {
    console.log(VC_WEBRTC_LOG, 'ICE gathering already complete');
    return Promise.resolve();
  }

  return new Promise<void>((resolve) => {
    const onIceGatheringStateChange = () => {
      console.log(
        VC_WEBRTC_LOG,
        'ICE gathering state:',
        peerConnection.iceGatheringState
      );
      if (peerConnection.iceGatheringState !== 'complete') return;

      peerConnection.removeEventListener(
        'icegatheringstatechange',
        onIceGatheringStateChange
      );
      console.log(VC_WEBRTC_LOG, 'ICE gathering finished');
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

/** Total `bytesReceived` across inbound RTP statistics for video (WebRTC). */
function sumVideoInboundBytesReceived(stats: RTCStatsReport): number | null {
  let sum = 0;
  let found = false;
  stats.forEach((r) => {
    if (r.type !== 'inbound-rtp') return;
    if (!('kind' in r) || r.kind !== 'video') return;
    const br =
      'bytesReceived' in r
        ? (r as { bytesReceived?: unknown }).bytesReceived
        : undefined;
    const n =
      typeof br === 'number'
        ? br
        : typeof br === 'bigint'
          ? Number(br)
          : NaN;
    if (!Number.isFinite(n)) return;
    found = true;
    sum += n;
  });
  return found ? sum : null;
}

/** Bitrate in bits per second (SI-style kb/Mb suffixes). */
function formatIncomingBitrateBps(bitsPerSecond: number): string {
  if (!Number.isFinite(bitsPerSecond) || bitsPerSecond < 0) return '…';
  if (bitsPerSecond >= 1e6) return `${(bitsPerSecond / 1e6).toFixed(1)} Mb/s`;
  if (bitsPerSecond >= 1e4) return `${Math.round(bitsPerSecond / 1e3)} kb/s`;
  if (bitsPerSecond >= 1e3) return `${(bitsPerSecond / 1e3).toFixed(1)} kb/s`;
  return `${Math.round(bitsPerSecond)} b/s`;
}

function getCalibrationInstructionId(
  status: VideoCalibrationStatus
): string | null {
  if (status === VIDEO_CALIB_STATUS_NOT_STARTED) return null;

  switch (status) {
    case VideoCalibrationStatus.SOLVING_CAMERA_EXTRINSIC:
      return 'video-calibration-instruction-calibrate_camera';
    case VideoCalibrationStatus.CAPTURING_FORWARD_POSE:
      return 'video-calibration-instruction-capture_forward_pose';
    case VideoCalibrationStatus.CAPTURING_LEANING_FORWARD_POSE:
      return 'video-calibration-instruction-capture_bent_over_pose';
    case VideoCalibrationStatus.ALIGNING_UPPER_BODY_TRACKERS:
    case VideoCalibrationStatus.ALIGNING_REMAINING_TRACKERS:
    case VideoCalibrationStatus.OPTIMIZING_BODY_PROPORTIONS:
      return 'video-calibration-instruction-calibrate_trackers';
    default:
      return null;
  }
}

function isAligningTrackersStatus(status: VideoCalibrationStatus) {
  return (
    status === VideoCalibrationStatus.ALIGNING_UPPER_BODY_TRACKERS ||
    status === VideoCalibrationStatus.ALIGNING_REMAINING_TRACKERS
  );
}

function shouldHideCalibrationGuidanceVisuals(status: VideoCalibrationStatus) {
  return (
    status === VideoCalibrationStatus.NOT_STARTED ||
    status === VideoCalibrationStatus.CONNECTING_TO_SERVER ||
    status === VideoCalibrationStatus.CONNECTING_TO_WEBCAM ||
    status === VideoCalibrationStatus.WAITING_FOR_USER_START
  );
}

function shouldShowSimpleWebcamQrSidebar(status: VideoCalibrationStatus) {
  return (
    status === VideoCalibrationStatus.NOT_STARTED ||
    status === VideoCalibrationStatus.CONNECTING_TO_SERVER ||
    status === VideoCalibrationStatus.CONNECTING_TO_WEBCAM
  );
}

function applyCalibrationCameraToView(
  view: SkeletonPreviewView,
  camera: VideoCalibrationCameraT
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

function SimpleWebcamReleasesQrImage() {
  const { l10n } = useLocalization();
  const [dataUrl, setDataUrl] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    QRCode.toDataURL(SIMPLE_WEBCAM_RELEASES_URL, {
      width: 200,
      margin: 2,
      color: { dark: '#000000ff', light: '#ffffffff' },
    })
      .then((url) => {
        if (!cancelled) setDataUrl(url);
      })
      .catch(() => {
        /* ignore */
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (!dataUrl) {
    return (
      <div className="flex h-[200px] w-[200px] shrink-0 items-center justify-center rounded-lg bg-background-60">
        <Typography color="secondary" id="video-calibration-qr-loading" />
      </div>
    );
  }

  return (
    <img
      src={dataUrl}
      alt={l10n.getString('video-calibration-sidebar-qr-alt')}
      className="h-[200px] w-[200px] shrink-0 rounded-lg bg-white p-2"
      width={200}
      height={200}
      decoding="async"
    />
  );
}

function VideoCalibrationSidebarDisconnected() {
  return (
    <>
      <Typography
        id="video-calibration-sidebar-simple-webcam-setup"
        whitespace="whitespace-pre-line"
      />
      <div className="flex flex-col items-center gap-3">
        <SimpleWebcamReleasesQrImage />
        <a
          href={SIMPLE_WEBCAM_RELEASES_URL}
          onClick={(e) => {
            e.preventDefault();
            void openUrl(SIMPLE_WEBCAM_RELEASES_URL);
          }}
          className="break-all text-center text-sm text-accent-background-10 underline"
        >
          {SIMPLE_WEBCAM_RELEASES_URL}
        </a>
      </div>
    </>
  );
}

function VideoCalibrationSidebarConnected({
  onAlignCamera,
  onAlignTrackers,
  onOptimizeProportions,
}: {
  onAlignCamera: () => void;
  onAlignTrackers: () => void;
  onOptimizeProportions: () => void;
}) {
  return (
    <>
      <div className="flex flex-col gap-2">
        <Typography id="video-calibration-sidebar-skeleton-match-intro" />
        <Button
          variant="primary"
          id="video-calibration-sidebar-action-align-camera"
          className="w-full"
          onClick={onAlignCamera}
        >
          <Localized id="video-calibration-sidebar-action-align-camera-label" />
        </Button>
      </div>

      <div className="flex flex-col gap-2">
        <Typography id="video-calibration-sidebar-automated-intro" />
        <Button
          variant="primary"
          id="video-calibration-sidebar-action-align-trackers"
          className="w-full"
          onClick={onAlignTrackers}
        >
          <Localized id="video-calibration-sidebar-action-align-trackers-label" />
        </Button>
        <Button
          variant="primary"
          id="video-calibration-sidebar-action-optimize-proportions"
          className="w-full"
          onClick={onOptimizeProportions}
        >
          <Localized id="video-calibration-sidebar-action-optimize-proportions-label" />
        </Button>
      </div>

      <div className="flex flex-col gap-2">
        <Typography id="video-calibration-sidebar-manual-calibration-intro" />
        <ResetButton type={ResetType.Full} className="w-full">
          <Localized id="reset-full" />
        </ResetButton>
        <ResetButton type={ResetType.Mounting} group="default" className="w-full">
          <Localized id="video-calibration-sidebar-action-mounting-reset" />
        </ResetButton>
        <ResetButton type={ResetType.Mounting} group="feet" className="w-full">
          <Localized id="video-calibration-sidebar-action-feet-reset" />
        </ResetButton>
      </div>
    </>
  );
}

function VideoCalibrationSidebar({
  calibrationStatus,
  onAlignCamera,
  onAlignTrackers,
  onOptimizeProportions,
  estimatedUserHeightSummary,
}: {
  calibrationStatus: VideoCalibrationStatus;
  onAlignCamera: () => void;
  onAlignTrackers: () => void;
  onOptimizeProportions: () => void;
  estimatedUserHeightSummary: string;
}) {
  return (
    <div className="my-2 flex h-[calc(100%-16px)] min-h-0 flex-col overflow-hidden rounded-lg bg-background-70">
      <div className="flex min-h-0 flex-1 flex-col gap-5 overflow-y-auto p-4">
        <Typography variant="section-title" id="video-calibration-sidebar-title" />
        {shouldShowSimpleWebcamQrSidebar(calibrationStatus) ? (
          <VideoCalibrationSidebarDisconnected />
        ) : (
          <VideoCalibrationSidebarConnected
            onAlignCamera={onAlignCamera}
            onAlignTrackers={onAlignTrackers}
            onOptimizeProportions={onOptimizeProportions}
          />
        )}
      </div>
      <div className="shrink-0 space-y-2 border-t border-background-40 px-4 py-5">
        <Typography
          variant="main-title"
          className="break-words text-center leading-tight"
        >
          {estimatedUserHeightSummary}
        </Typography>
        <Typography
          id="video-calibration-sidebar-user-height-body-proportions-hint"
          color="secondary"
          variant="standard"
          className="break-words text-center text-[11px] leading-snug xs:text-[12px]"
        />
      </div>
    </div>
  );
}

function VideoCalibrationGuidancePanel({
  calibrationStatus,
  instructionId,
  showAligningTrackerStatus,
  alignedTrackers,
  pendingTrackers,
  hideCalibrationGuidanceVisuals,
}: {
  calibrationStatus: VideoCalibrationStatus;
  instructionId: string | null;
  showAligningTrackerStatus: boolean;
  alignedTrackers: BodyPart[];
  pendingTrackers: BodyPart[];
  hideCalibrationGuidanceVisuals: boolean;
}) {
  const demoVideoRef = useRef<HTMLVideoElement>(null);
  const [demoPlaybackFailed, setDemoPlaybackFailed] = useState(false);
  const demoStem = calibrationDemoStemForStatus(calibrationStatus);
  const demoPublicBase = calibrationDemoVideoPublicBase();

  useEffect(() => {
    setDemoPlaybackFailed(false);
  }, [demoStem]);

  useEffect(() => {
    const el = demoVideoRef.current;
    if (!el) return;
    if (hideCalibrationGuidanceVisuals) {
      el.pause();
    } else {
      void el.play().catch(() => {});
    }
  }, [demoStem, hideCalibrationGuidanceVisuals]);

  const instructionTextScaleClass =
    '[&_h2.text-section-title]:!text-[length:calc(var(--font-size-vr)*2/16)] [&_p.text-standard]:!text-[length:calc(var(--font-size-standard)*2/16)]';

  return (
    <div className="flex w-[320px] max-w-full shrink-0 flex-col gap-5">
      <div
        aria-hidden
        className={classNames(
          'box-border w-[320px] max-w-full shrink-0 overflow-hidden rounded-lg border border-background-50 bg-black',
          hideCalibrationGuidanceVisuals && 'invisible pointer-events-none'
        )}
      >
        {demoPlaybackFailed ? (
          <div className="flex min-h-[200px] items-center justify-center px-4 py-8">
            <Typography
              id="video-calibration-demo-video-placeholder"
              color="secondary"
            />
          </div>
        ) : (
          <video
            key={demoStem}
            ref={demoVideoRef}
            autoPlay
            loop
            muted
            playsInline
            preload="auto"
            className="block h-auto w-full max-w-full"
            onError={() => setDemoPlaybackFailed(true)}
          >
            <source src={`${demoPublicBase}/${demoStem}.mp4`} type="video/mp4" />
            <source src={`${demoPublicBase}/${demoStem}.webm`} type="video/webm" />
          </video>
        )}
      </div>

      <div className="flex flex-col gap-3">
        <div
          aria-hidden={hideCalibrationGuidanceVisuals}
          className={classNames(
            instructionTextScaleClass,
            hideCalibrationGuidanceVisuals && 'invisible pointer-events-none'
          )}
        >
          {instructionId ? (
            <Typography
              id={instructionId}
              variant="section-title"
              whitespace="whitespace-pre-line"
            />
          ) : (
            <Typography
              color="secondary"
              id="video-calibration-guidance-no-instruction"
            />
          )}
        </div>

        {showAligningTrackerStatus && (
          <div className="flex flex-col gap-4 border-t border-background-40 pt-4">
            <div className="flex flex-col gap-2">
              <Typography color="secondary" id="video-calibration-done-trackers" />
              <TrackerList trackers={alignedTrackers} />
            </div>
            <div className="flex flex-col gap-2">
              <Typography
                color="secondary"
                id="video-calibration-pending-trackers"
              />
              <TrackerList trackers={pendingTrackers} />
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

function VideoCalibrationContent({
  videoRef,
  skeletonViewRef,
  calibrationCamera,
  calibrationStatus,
  trackerStatuses,
  showVideo,
  calibrationErrorNotification,
  status,
  errorMessage,
  inboundVideoBitrateBps,
}: {
  videoRef: RefObject<HTMLVideoElement>;
  skeletonViewRef: React.MutableRefObject<SkeletonPreviewView | null>;
  calibrationCamera: VideoCalibrationCameraT | null;
  calibrationStatus: VideoCalibrationStatus;
  trackerStatuses: VideoCalibrationTrackerStatusT[];
  showVideo: boolean;
  calibrationErrorNotification: VideoCalibrationErrorNotification | null;
  status: VideoStreamStatus;
  errorMessage: string;
  inboundVideoBitrateBps: number | null;
}) {
  const showSkeleton =
    calibrationCamera != null ||
    (calibrationStatus !== VIDEO_CALIB_STATUS_NOT_STARTED &&
      calibrationStatus !== VideoCalibrationStatus.CONNECTING_TO_SERVER &&
      calibrationStatus !== VideoCalibrationStatus.CONNECTING_TO_WEBCAM &&
      calibrationStatus !== VideoCalibrationStatus.WAITING_FOR_USER_START &&
      calibrationStatus !== VideoCalibrationStatus.SOLVING_CAMERA_EXTRINSIC);

  const instructionId = getCalibrationInstructionId(calibrationStatus);
  const alignedTrackers = trackerStatuses
    .filter((t) => t.aligned)
    .map((t) => t.bodyPart);
  const pendingTrackers = trackerStatuses
    .filter((t) => !t.aligned)
    .map((t) => t.bodyPart);
  const showAligningTrackerStatus = isAligningTrackersStatus(calibrationStatus);

  const [intrinsicVideoSize, setIntrinsicVideoSize] = useState<{ w: number; h: number }>(
    () => ({ w: 720, h: 1280 })
  );
  const [videoPresentationFps, setVideoPresentationFps] = useState<number | null>(
    null
  );

  useEffect(() => {
    if (status !== 'ready') {
      setIntrinsicVideoSize({ w: 720, h: 1280 });
      return;
    }

    const el = videoRef.current;
    if (!el) return;

    const apply = () => {
      const w = el.videoWidth;
      const h = el.videoHeight;
      if (w > 0 && h > 0) {
        setIntrinsicVideoSize((prev) => (prev.w === w && prev.h === h ? prev : { w, h }));
      }
    };

    apply();
    el.addEventListener('loadedmetadata', apply);
    el.addEventListener('resize', apply);

    return () => {
      el.removeEventListener('loadedmetadata', apply);
      el.removeEventListener('resize', apply);
    };
  }, [status, videoRef]);

  useEffect(() => {
    const video = videoRef.current;
    if (status !== 'ready' || !video) {
      setVideoPresentationFps(null);
      return undefined;
    }

    let canceled = false;
    const timestamps: number[] = [];
    const windowMs = 1000;
    let lastEmittedFpsRound = -1;

    const bumpFramesAt = (t: DOMHighResTimeStamp) => {
      timestamps.push(t);
      while (timestamps.length > 0 && t - timestamps[0] > windowMs) {
        timestamps.shift();
      }
      if (timestamps.length < 2) return;

      const spanS = Math.max((t - timestamps[0]) / 1000, 1e-6);
      const fps = (timestamps.length - 1) / spanS;
      const rounded = Math.round(fps);
      if (rounded !== lastEmittedFpsRound) {
        lastEmittedFpsRound = rounded;
        setVideoPresentationFps(rounded);
      }
    };

    let rvfcHandle: number | undefined;
    let rafLoopId = 0;
    let lastSeenCurrentTime = video.currentTime;

    const tearDownRvfc = () => {
      if (
        rvfcHandle !== undefined &&
        typeof video.cancelVideoFrameCallback === 'function'
      ) {
        try {
          video.cancelVideoFrameCallback(rvfcHandle);
        } catch {
          /* ignore stale handle */
        }
      }
      rvfcHandle = undefined;
    };

    const onRvfcFrame: VideoFrameRequestCallback = (now) => {
      if (canceled) return;
      bumpFramesAt(now);
      rvfcHandle = video.requestVideoFrameCallback(onRvfcFrame);
    };

    const onRafFrame = (_ts: DOMHighResTimeStamp) => {
      if (canceled) return;
      if (
        video.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA &&
        !video.paused &&
        !video.ended
      ) {
        const ct = video.currentTime;
        if (ct !== lastSeenCurrentTime) {
          lastSeenCurrentTime = ct;
          bumpFramesAt(performance.now());
        }
      }
      rafLoopId = requestAnimationFrame(onRafFrame);
    };

    const useRvfc = typeof video.requestVideoFrameCallback === 'function';

    if (useRvfc) {
      rvfcHandle = video.requestVideoFrameCallback(onRvfcFrame);
      return () => {
        canceled = true;
        tearDownRvfc();
      };
    }

    lastSeenCurrentTime = video.currentTime;
    rafLoopId = requestAnimationFrame(onRafFrame);
    return () => {
      canceled = true;
      cancelAnimationFrame(rafLoopId);
    };
  }, [status, videoRef]);

  return (
    <div className="flex h-full min-h-0 w-full flex-col overflow-hidden p-4">
      <div className="flex min-h-0 flex-1 w-full flex-col items-center gap-6 overflow-hidden lg:flex-row lg:items-stretch lg:justify-center">
        <div className="flex min-h-0 min-w-0 flex-1 flex-col items-center justify-center overflow-hidden lg:h-full">
          <div
            className="relative mx-auto box-border h-full max-h-full min-h-0 w-auto max-w-[min(720px,100%)] shrink-0 overflow-hidden rounded-lg bg-black"
            style={{
              aspectRatio: `${intrinsicVideoSize.w} / ${intrinsicVideoSize.h}`,
            }}
          >
          {!!calibrationErrorNotification && (
            <div className="pointer-events-none absolute left-0 top-0 z-30 w-full p-4">
              <div
                className={classNames(
                  'mx-auto w-full rounded-md bg-red-700/90 px-4 py-3 text-center transition-opacity duration-[1500ms]',
                  calibrationErrorNotification.fading ? 'opacity-0' : 'opacity-100'
                )}
              >
                <Typography color="text-white">
                  {calibrationErrorNotification.message}
                </Typography>
              </div>
            </div>
          )}
          <video
            ref={videoRef}
            autoPlay
            playsInline
            muted
            className={classNames(
              // Above dim overlay (z-5) so the camera feed stays visible; skeleton stays on top (z-10).
              // Horizontal mirror on video + skeleton (same transform) so overlay matches the feed.
              'absolute left-0 top-0 z-[6] h-full w-full object-contain transition-opacity [transform:scaleX(-1)]',
              showVideo ? 'opacity-100' : 'opacity-0'
            )}
          />
          {status === 'ready' &&
            (videoPresentationFps !== null || inboundVideoBitrateBps !== null) && (
            <div
              aria-label={[
                videoPresentationFps != null &&
                  `${videoPresentationFps} frames per second`,
                inboundVideoBitrateBps != null &&
                  `${formatIncomingBitrateBps(inboundVideoBitrateBps)} incoming bitrate`,
              ]
                .filter(Boolean)
                .join('; ')}
              aria-live="polite"
              role="status"
              className="pointer-events-none absolute right-2 top-2 z-[40] flex flex-col items-end gap-0.5 rounded bg-black/55 px-1.5 py-1 font-mono tabular-nums text-[11px] leading-none text-white shadow-sm backdrop-blur-[2px]"
            >
              {videoPresentationFps !== null && (
                <div>
                  {videoPresentationFps}
                  <span className="ml-1 opacity-80">FPS</span>
                </div>
              )}
              {inboundVideoBitrateBps !== null && (
                <div className="text-[10px] opacity-95">
                  {formatIncomingBitrateBps(inboundVideoBitrateBps)}
                </div>
              )}
            </div>
          )}
          {showVideo && (
            <div className="pointer-events-none absolute left-0 top-0 z-[5] h-full w-full bg-black/40" />
          )}
          {showSkeleton && (
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
          )}
          {status !== 'ready' && (
            <div className="pointer-events-none absolute inset-0 z-30 flex items-center justify-center p-6">
              <div className="flex max-w-xl flex-col items-center gap-1 rounded-md bg-black/60 px-4 py-3 text-center backdrop-blur-sm">
                {status === 'loading' && (
                  <Typography id="video-calibration-loading" />
                )}
                {status === 'connecting' && (
                  <Typography id="video-calibration-connecting" />
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
            </div>
          )}
        </div>
        </div>
        <VideoCalibrationGuidancePanel
          calibrationStatus={calibrationStatus}
          instructionId={instructionId}
          showAligningTrackerStatus={showAligningTrackerStatus}
          alignedTrackers={alignedTrackers}
          pendingTrackers={pendingTrackers}
          hideCalibrationGuidanceVisuals={shouldHideCalibrationGuidanceVisuals(
            calibrationStatus
          )}
        />
      </div>
    </div>
  );
}

export function VideoCalibrationPage({ isMobile }: { isMobile?: boolean }) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const skeletonViewRef = useRef<SkeletonPreviewView | null>(null);
  const peerConnectionRef = useRef<RTCPeerConnection | null>(null);
  const controlDataChannelRef = useRef<RTCDataChannel | null>(null);
  const remoteMediaStreamRef = useRef<MediaStream | null>(null);
  const attemptRef = useRef(0);
  const webrtcConnectTxRef = useRef(0);
  const webrtcPendingTxIdRef = useRef<number | null>(null);
  const videoPlayRafRef = useRef<number | null>(null);
  const videoFrameLogHandleRef = useRef<number | null>(null);
  const videoFrameLogStreamRef = useRef<MediaStream | null>(null);
  const videoDiagPollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const videoDiagLastKeyRef = useRef<string | null>(null);
  const videoTrackDebugIdsRef = useRef<Set<string>>(new Set());
  const [status, setStatus] = useState<VideoStreamStatus>('loading');
  const [errorMessage, setErrorMessage] = useState('');
  const [webrtcInboundVideoBitrateBps, setWebrtcInboundVideoBitrateBps] =
    useState<number | null>(null);
  /** `SkeletonConfigResponse.userHeight` (HMD-eye height, meters). */
  const [skeletonConfigUserHeightM, setSkeletonConfigUserHeightM] = useState<number | null>(
    null
  );

  const initialPersistedRef = useRef<ReturnType<
    typeof loadPersistedVideoCalibration
  > | null>(null);
  const readInitialPersisted = () => {
    if (initialPersistedRef.current === null) {
      initialPersistedRef.current = loadPersistedVideoCalibration();
    }
    return initialPersistedRef.current;
  };
  const [calibrationStatus, setCalibrationStatus] = useState<VideoCalibrationStatus>(
    () => readInitialPersisted().calibrationStatus
  );
  const [calibrationCamera, setCalibrationCamera] = useState<
    VideoCalibrationCameraT | null
  >(() => readInitialPersisted().calibrationCamera);
  const [trackerStatuses, setTrackerStatuses] = useState<
    VideoCalibrationTrackerStatusT[]
  >([]);
  const [calibrationErrorNotification, setCalibrationErrorNotification] =
    useState<VideoCalibrationErrorNotification | null>(null);
  const calibrationErrorFadeTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(
    null
  );
  const calibrationErrorClearTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(
    null
  );
  const { sendRPCPacket, useRPCPacket, isConnected } = useWebsocketAPI();
  const { config } = useConfig();
  const { l10n } = useLocalization();
  const { currentLocales } = useLocaleConfig();
  const lastProgressStatusRef = useRef<VideoCalibrationStatus>(
    readInitialPersisted().calibrationStatus
  );

  const estimatedFullHeightMeterFormat = useMemo(
    () =>
      new Intl.NumberFormat(currentLocales, {
        style: 'unit',
        unit: 'meter',
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
      }),
    [currentLocales]
  );

  const sidebarEstimatedHeightSummary = useMemo(() => {
    const h = skeletonConfigUserHeightM;
    if (h == null || h <= 0) {
      return l10n.getString('video-calibration-sidebar-user-height-unknown');
    }
    /** Full-body height (m): same as manual proportions `(userHeight_cm) / ratio / 100` → `userHeight_m / ratio`. */
    const fullHeightM = h / EYE_HEIGHT_TO_HEIGHT_RATIO;
    const formatted = estimatedFullHeightMeterFormat.format(fullHeightM);
    return l10n.getString('video-calibration-sidebar-user-height', {
      height: formatted,
    });
  }, [
    skeletonConfigUserHeightM,
    estimatedFullHeightMeterFormat,
    l10n,
  ]);

  useRPCPacket(RpcMessage.SkeletonConfigResponse, (data: SkeletonConfigResponseT) => {
    if (data.userHeight != null && data.userHeight > 0) {
      setSkeletonConfigUserHeightM(data.userHeight);
    }
  });

  useEffect(() => {
    if (!isConnected) return;
    sendRPCPacket(RpcMessage.SkeletonConfigRequest, new SkeletonConfigRequestT());
  }, [calibrationStatus, isConnected, sendRPCPacket]);

  const showCalibrationErrorNotification = useCallback((message: string) => {
    if (calibrationErrorFadeTimeoutRef.current != null) {
      clearTimeout(calibrationErrorFadeTimeoutRef.current);
      calibrationErrorFadeTimeoutRef.current = null;
    }
    if (calibrationErrorClearTimeoutRef.current != null) {
      clearTimeout(calibrationErrorClearTimeoutRef.current);
      calibrationErrorClearTimeoutRef.current = null;
    }

    setCalibrationErrorNotification({ message, fading: false });
    calibrationErrorFadeTimeoutRef.current = setTimeout(() => {
      setCalibrationErrorNotification((value) =>
        value ? { ...value, fading: true } : null
      );
      calibrationErrorFadeTimeoutRef.current = null;
    }, 5000);
    calibrationErrorClearTimeoutRef.current = setTimeout(() => {
      setCalibrationErrorNotification(null);
      calibrationErrorClearTimeoutRef.current = null;
    }, 6500);
  }, []);

  const scheduleVideoPlay = useCallback(() => {
    if (videoPlayRafRef.current != null) {
      cancelAnimationFrame(videoPlayRafRef.current);
    }
    videoPlayRafRef.current = requestAnimationFrame(() => {
      videoPlayRafRef.current = null;
      const el = videoRef.current;
      if (!el?.srcObject) return;
      void el.play().catch((e) => {
        console.warn(VC_WEBRTC_LOG, 'video.play() rejected', e);
      });
    });
  }, []);

  const stopVideoFrameLogging = useCallback(() => {
    const el = videoRef.current;
    const handle = videoFrameLogHandleRef.current;
    if (
      el != null &&
      handle != null &&
      typeof el.cancelVideoFrameCallback === 'function'
    ) {
      try {
        el.cancelVideoFrameCallback(handle);
      } catch {
        /* ignore */
      }
    }
    videoFrameLogHandleRef.current = null;
    videoFrameLogStreamRef.current = null;
    if (videoDiagPollRef.current != null) {
      clearInterval(videoDiagPollRef.current);
      videoDiagPollRef.current = null;
    }
    videoDiagLastKeyRef.current = null;
  }, []);

  const ensureVideoFrameLogging = useCallback(() => {
    const el = videoRef.current;
    if (!el) return;

    const src = el.srcObject;
    if (
      !(src instanceof MediaStream) ||
      videoFrameLogHandleRef.current != null
    ) {
      return;
    }

    if (typeof el.requestVideoFrameCallback !== 'function') {
      el.addEventListener(
        'loadeddata',
        () => {
          console.log(
            VC_WEBRTC_LOG,
            'video decoded data ready (first frame path; no requestVideoFrameCallback)'
          );
        },
        { once: true }
      );
      return;
    }

    videoFrameLogStreamRef.current = src;

    // requestVideoFrameCallback only runs when the compositor presents a NEW frame — not on a timer.
    // A single 2×2 "frame" usually means one placeholder decode (often track.muted / waiting for keyframe).
    if (import.meta.env.DEV && videoDiagPollRef.current == null) {
      videoDiagLastKeyRef.current = null;
      videoDiagPollRef.current = setInterval(() => {
        const v = videoRef.current;
        const expected = videoFrameLogStreamRef.current;
        if (!v?.srcObject || v.srcObject !== expected) {
          if (videoDiagPollRef.current != null) {
            clearInterval(videoDiagPollRef.current);
            videoDiagPollRef.current = null;
          }
          return;
        }
        const vt = v.srcObject.getVideoTracks()[0];
        const settings = vt?.getSettings?.();
        const key = [
          v.videoWidth,
          v.videoHeight,
          v.readyState,
          v.paused,
          vt?.muted,
          vt?.readyState,
          settings?.width ?? '',
          settings?.height ?? '',
          settings?.frameRate ?? '',
        ].join('|');

        if (key === videoDiagLastKeyRef.current) return;
        videoDiagLastKeyRef.current = key;
      }, 2000);
    }

    const onFrame: VideoFrameRequestCallback = (_now, _metadata) => {
      const v = videoRef.current;
      const expectedStream = videoFrameLogStreamRef.current;

      if (v?.srcObject === expectedStream && expectedStream) {
        videoFrameLogHandleRef.current = v.requestVideoFrameCallback(onFrame);
      } else {
        videoFrameLogHandleRef.current = null;
      }
    };

    videoFrameLogHandleRef.current = el.requestVideoFrameCallback(onFrame);
  }, []);

  const syncRemoteVideoFromPeer = useCallback(() => {
    const peerConnection = peerConnectionRef.current;
    const remoteStream = remoteMediaStreamRef.current;
    if (!peerConnection || !remoteStream) {
      console.log(
        VC_WEBRTC_LOG,
        'syncRemoteVideoFromPeer: skip (no peer or stream)',
        {
          hasPeer: !!peerConnection,
          hasStream: !!remoteStream,
        }
      );
      return;
    }

    const receivers = peerConnection.getReceivers();
    let added = 0;
    for (const receiver of receivers) {
      const track = receiver.track;
      if (!track || track.kind !== 'video') continue;
      if (!remoteStream.getTracks().some(({ id }) => id === track.id)) {
        remoteStream.addTrack(track);
        added += 1;
      }
    }

    const videoTracks = remoteStream.getVideoTracks();
    console.log(VC_WEBRTC_LOG, 'syncRemoteVideoFromPeer', {
      receiverCount: receivers.length,
      videoReceiversWithTrack: receivers.filter(
        (r) => r.track?.kind === 'video'
      ).length,
      tracksAddedThisCall: added,
      streamVideoTrackCount: videoTracks.length,
      signalingState: peerConnection.signalingState,
      connectionState: peerConnection.connectionState,
      iceConnectionState: peerConnection.iceConnectionState,
    });

    if (videoTracks.length > 0 && videoRef.current) {
      const el = videoRef.current;
      // Re-assigning the same stream still counts as a "new load" and aborts an in-flight play().
      if (el.srcObject !== remoteStream) {
        stopVideoFrameLogging();
        el.srcObject = remoteStream;
      }
      scheduleVideoPlay();
      ensureVideoFrameLogging();

      for (const track of videoTracks) {
        if (videoTrackDebugIdsRef.current.has(track.id)) continue;
        videoTrackDebugIdsRef.current.add(track.id);
        console.log(VC_WEBRTC_LOG, 'MediaStreamTrack (video)', {
          id: track.id,
          label: track.label,
          muted: track.muted,
          enabled: track.enabled,
          readyState: track.readyState,
          settings: track.getSettings(),
        });
        track.addEventListener('unmute', () => {
          console.log(VC_WEBRTC_LOG, 'video track unmute', {
            id: track.id,
            settings: track.getSettings(),
          });
        });
        track.addEventListener('mute', () => {
          console.log(VC_WEBRTC_LOG, 'video track mute', { id: track.id });
        });
      }

      setStatus('ready');
      // console.log(VC_WEBRTC_LOG, 'Video element bound; UI status -> ready');
    }
  }, [ensureVideoFrameLogging, scheduleVideoPlay, stopVideoFrameLogging]);

  const cleanupConnection = useCallback(() => {
    console.log(VC_WEBRTC_LOG, 'cleanupConnection');
    if (videoPlayRafRef.current != null) {
      cancelAnimationFrame(videoPlayRafRef.current);
      videoPlayRafRef.current = null;
    }
    stopVideoFrameLogging();
    webrtcPendingTxIdRef.current = null;
    remoteMediaStreamRef.current = null;
    const controlDataChannel = controlDataChannelRef.current;
    if (controlDataChannel) {
      controlDataChannel.onopen = null;
      controlDataChannel.onclose = null;
      controlDataChannel.onerror = null;
      controlDataChannel.onmessage = null;
      try {
        controlDataChannel.close();
      } catch {
        /* ignore */
      }
      controlDataChannelRef.current = null;
    }

    const peerConnection = peerConnectionRef.current;
    if (peerConnection) {
      peerConnection.ontrack = null;
      peerConnection.onconnectionstatechange = null;
      peerConnection.onicegatheringstatechange = null;
      peerConnection.oniceconnectionstatechange = null;
      peerConnection.onsignalingstatechange = null;
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

    videoTrackDebugIdsRef.current.clear();
  }, [stopVideoFrameLogging]);

  const connectVideoViaWebRTC = useCallback(async () => {
    const attempt = ++attemptRef.current;
    console.log(VC_WEBRTC_LOG, 'connectVideoViaWebRTC start', { attempt });
    const peerConnection = new RTCPeerConnection();
    const remoteStream = new MediaStream();

    cleanupConnection();
    peerConnectionRef.current = peerConnection;
    remoteMediaStreamRef.current = remoteStream;
    setStatus('connecting');
    setErrorMessage('');
    const controlDataChannel = peerConnection.createDataChannel('keepalive');
    controlDataChannelRef.current = controlDataChannel;
    controlDataChannel.onopen = () => {
      console.log(VC_WEBRTC_LOG, 'control datachannel open');
    };
    controlDataChannel.onclose = () => {
      console.log(VC_WEBRTC_LOG, 'control datachannel close');
    };
    controlDataChannel.onerror = (event) => {
      console.warn(VC_WEBRTC_LOG, 'control datachannel error', event);
    };

    peerConnection.onicegatheringstatechange = () => {
      console.log(
        VC_WEBRTC_LOG,
        'peer iceGatheringState:',
        peerConnection.iceGatheringState
      );
    };
    peerConnection.oniceconnectionstatechange = () => {
      console.log(
        VC_WEBRTC_LOG,
        'peer iceConnectionState:',
        peerConnection.iceConnectionState
      );
    };
    peerConnection.onsignalingstatechange = () => {
      console.log(
        VC_WEBRTC_LOG,
        'peer signalingState:',
        peerConnection.signalingState
      );
    };

    peerConnection.ontrack = (event) => {
      const incomingTracks = event.streams[0]?.getTracks() ?? [event.track];
      console.log(VC_WEBRTC_LOG, 'ontrack', {
        streams: event.streams.length,
        trackCount: incomingTracks.length,
        kinds: incomingTracks.map((t) => t.kind),
      });

      incomingTracks.forEach((track) => {
        if (track.kind !== 'video') return;
        if (!remoteStream.getTracks().some(({ id }) => id === track.id)) {
          remoteStream.addTrack(track);
        }
      });

      syncRemoteVideoFromPeer();
    };

    peerConnection.onconnectionstatechange = () => {
      console.log(
        VC_WEBRTC_LOG,
        'peer connectionState:',
        peerConnection.connectionState
      );
      if (peerConnection.connectionState === 'connected') {
        syncRemoteVideoFromPeer();
      }
    };

    try {
      peerConnection.addTransceiver('video', { direction: 'recvonly' });
      console.log(VC_WEBRTC_LOG, 'recvonly video transceiver added');

      const offer = await peerConnection.createOffer();
      console.log(VC_WEBRTC_LOG, 'createOffer done', {
        type: offer.type,
        sdpChars: offer.sdp?.length ?? 0,
      });
      await peerConnection.setLocalDescription(offer);
      console.log(VC_WEBRTC_LOG, 'setLocalDescription done');
      await waitForIceGatheringComplete(peerConnection);

      const localSdp = peerConnection.localDescription?.sdp;
      if (!localSdp) {
        throw new Error('Peer connection did not produce a local SDP offer');
      }

      if (attempt !== attemptRef.current) {
        console.log(
          VC_WEBRTC_LOG,
          'aborted before send (stale attempt)',
          attempt,
          'current',
          attemptRef.current
        );
        return;
      }

      const txId = ++webrtcConnectTxRef.current >>> 0;
      webrtcPendingTxIdRef.current = txId;

      console.log(VC_WEBRTC_LOG, 'sending ConnectToVideoCalibrationRequest', {
        txId,
        offerSdpChars: localSdp.length,
      });
      sendRPCPacket(
        RpcMessage.ConnectToVideoCalibrationRequest,
        new ConnectToVideoCalibrationRequestT(localSdp),
        txId
      );
      console.log(
        VC_WEBRTC_LOG,
        'ConnectToVideoCalibrationRequest dispatched; awaiting ConnectToVideoCalibrationResponse'
      );
    } catch (error) {
      if (attempt !== attemptRef.current) {
        console.log(
          VC_WEBRTC_LOG,
          'connect error ignored (stale attempt)',
          attempt
        );
        return;
      }

      console.error(VC_WEBRTC_LOG, 'connectVideoViaWebRTC failed', error);
      cleanupConnection();
      setStatus('error');
      setErrorMessage(
        error instanceof Error ? error.message : 'Unknown WebRTC error'
      );
    }
  }, [cleanupConnection, sendRPCPacket, syncRemoteVideoFromPeer]);

  const onConnectToVideoCalibrationResponse = useCallback(
    (response: ConnectToVideoCalibrationResponseT) => {
      void (async () => {
        console.log(VC_WEBRTC_LOG, 'ConnectToVideoCalibrationResponse received', {
          pendingTxId: webrtcPendingTxIdRef.current,
        });

        const answerSdp = asText(response.answerSdp);

        console.log(VC_WEBRTC_LOG, 'ConnectToVideoCalibrationResponse payload', {
          answerSdpChars: answerSdp.length,
        });

        if (!answerSdp) {
          console.error(VC_WEBRTC_LOG, 'no answer SDP in response');
          cleanupConnection();
          setStatus('error');
          setErrorMessage('Server did not return an SDP answer');
          return;
        }

        const peerConnection = peerConnectionRef.current;
        if (!peerConnection) {
          console.warn(
            VC_WEBRTC_LOG,
            'no peerConnection when applying answer (already cleaned up?)'
          );
          return;
        }

        try {
          console.log(VC_WEBRTC_LOG, 'setRemoteDescription(answer) …', {
            sdpChars: answerSdp.length,
          });
          await peerConnection.setRemoteDescription({
            type: 'answer',
            sdp: answerSdp,
          });
          console.log(VC_WEBRTC_LOG, 'setRemoteDescription(answer) OK');
          webrtcPendingTxIdRef.current = null;
          syncRemoteVideoFromPeer();
        } catch (error) {
          console.error(
            VC_WEBRTC_LOG,
            'setRemoteDescription(answer) failed',
            error
          );
          cleanupConnection();
          setStatus('error');
          setErrorMessage(
            error instanceof Error
              ? error.message
              : 'Failed to apply SDP answer'
          );
        }
      })();
    },
    [cleanupConnection, syncRemoteVideoFromPeer]
  );

  useRPCPacket(
    RpcMessage.ConnectToVideoCalibrationResponse,
    onConnectToVideoCalibrationResponse
  );

  useEffect(() => {
    if (status !== 'ready') {
      setWebrtcInboundVideoBitrateBps(null);
      return undefined;
    }

    let canceled = false;
    let baselineBytes: number | null = null;
    let baselineTime = performance.now();

    const sample = async () => {
      if (canceled) return;
      const pc = peerConnectionRef.current;
      if (!pc || pc.connectionState === 'closed') return;

      let stats: RTCStatsReport;
      try {
        stats = await pc.getStats();
      } catch {
        return;
      }
      const totalBytes = sumVideoInboundBytesReceived(stats);
      const now = performance.now();
      if (totalBytes == null) {
        baselineBytes = null;
        baselineTime = now;
        return;
      }

      if (baselineBytes !== null && totalBytes < baselineBytes) {
        baselineBytes = totalBytes;
        baselineTime = now;
        return;
      }

      if (baselineBytes !== null) {
        const dtSec = Math.max((now - baselineTime) / 1000, 1e-3);
        const bps = ((totalBytes - baselineBytes) * 8) / dtSec;
        baselineBytes = totalBytes;
        baselineTime = now;
        if (Number.isFinite(bps) && bps >= 0) {
          setWebrtcInboundVideoBitrateBps(bps);
        }
      } else {
        baselineBytes = totalBytes;
        baselineTime = now;
      }
    };

    const id = window.setInterval(() => {
      void sample();
    }, 500);
    void sample();

    return () => {
      canceled = true;
      clearInterval(id);
      setWebrtcInboundVideoBitrateBps(null);
    };
  }, [status]);

  const onVideoCalibrationProgress = useCallback(
    (response: VideoCalibrationProgressT) => {
      const currentStatus = lastProgressStatusRef.current;
      const statusIsPresent = response.status !== VIDEO_CALIB_STATUS_NOT_STARTED;
      const cameraIsPresent = response.camera != null;
      const trackersArePresent = response.trackers.length > 0;

      console.log(VC_CALIB_LOG, 'VideoCalibrationProgress', {
        status: statusIsPresent ? response.status : '(unchanged)',
        camera: cameraIsPresent
          ? `${response.camera!.width}x${response.camera!.height}`
          : '(unchanged)',
        trackers: trackersArePresent ? response.trackers.length : '(unchanged)',
      });
      if (statusIsPresent) {
        setCalibrationStatus(response.status);
        if (
          currentStatus !== response.status &&
          response.status !== VideoCalibrationStatus.CONNECTING_TO_SERVER &&
          response.status !== VideoCalibrationStatus.CONNECTING_TO_WEBCAM &&
          config?.feedbackSound
        ) {
          restartAndPlay(resetChimeSound, config.feedbackSoundVolume ?? 1);
        }
        lastProgressStatusRef.current = response.status;
      }

      if (cameraIsPresent) {
        setCalibrationCamera(response.camera);
      }

      if (trackersArePresent) {
        setTrackerStatuses([...response.trackers]);
      }
    },
    [config?.feedbackSound, config?.feedbackSoundVolume]
  );

  useRPCPacket(
    RpcMessage.VideoCalibrationProgress,
    onVideoCalibrationProgress
  );

  const onVideoCalibrationError = useCallback(
    (error: VideoCalibrationErrorT) => {
      const asBodyPartNames = (parts: BodyPart[]) =>
        parts
          .map((part) => l10n.getString('body_part-' + BodyPart[part]))
          .join(', ');

      if (
        error.missingPositionalTrackers != null &&
        error.missingPositionalTrackers.missingTrackers.length > 0
      ) {
        showCalibrationErrorNotification(
          `Missing positional trackers: ${asBodyPartNames(
            error.missingPositionalTrackers.missingTrackers
          )}`
        );
        return;
      }

      if (
        error.missingRequiredImuTrackers != null &&
        error.missingRequiredImuTrackers.missingTrackers.length > 0
      ) {
        showCalibrationErrorNotification(
          `Missing required IMU trackers: ${asBodyPartNames(
            error.missingRequiredImuTrackers.missingTrackers
          )}`
        );
        return;
      }

      if (error.forwardAndLeaningForwardNotAligned != null) {
        showCalibrationErrorNotification(
          `Forward and leaning-forward poses are not aligned (yaw difference: ${error.forwardAndLeaningForwardNotAligned.yawDifference.toFixed(
            1
          )} deg).`
        );
      }
    },
    [l10n, showCalibrationErrorNotification]
  );

  useRPCPacket(RpcMessage.VideoCalibrationError, onVideoCalibrationError);

  const startVideoCalibration = useCallback(
    (process: VideoCalibrationProcess) => {
      console.log(VC_CALIB_LOG, 'StartVideoCalibrationRequest', process);
      sendRPCPacket(
        RpcMessage.StartVideoCalibrationRequest,
        new StartVideoCalibrationRequestT(process)
      );
    },
    [sendRPCPacket]
  );

  useEffect(() => {
    if (!isConnected) {
      console.log(VC_WEBRTC_LOG, 'WebSocket not connected');
      attemptRef.current += 1;
      skeletonViewRef.current = null;
      cleanupConnection();
      setStatus('loading');
      return;
    }

    console.log(VC_WEBRTC_LOG, 'WebSocket connected; starting calibration WebRTC');
    setTrackerStatuses([]);
    void connectVideoViaWebRTC();

    return () => {
      console.log(
        VC_WEBRTC_LOG,
        'effect cleanup (WebSocket disconnected or page unmount)'
      );
      attemptRef.current += 1;
      skeletonViewRef.current = null;
      cleanupConnection();
    };
  }, [isConnected, cleanupConnection, connectVideoViaWebRTC]);

  useEffect(() => {
    if (!calibrationCamera || !skeletonViewRef.current) return;

    applyCalibrationCameraToView(skeletonViewRef.current, calibrationCamera);
  }, [calibrationCamera]);

  useEffect(() => {
    savePersistedVideoCalibration(calibrationCamera, calibrationStatus);
  }, [calibrationCamera, calibrationStatus]);

  useEffect(() => {
    return () => {
      if (calibrationErrorFadeTimeoutRef.current != null) {
        clearTimeout(calibrationErrorFadeTimeoutRef.current);
      }
      if (calibrationErrorClearTimeoutRef.current != null) {
        clearTimeout(calibrationErrorClearTimeoutRef.current);
      }
    };
  }, []);

  return (
    <MainLayout
      isMobile={isMobile}
      full
      showToolbar={false}
      scrollContent={false}
      rightSidebar={
        <VideoCalibrationSidebar
          calibrationStatus={calibrationStatus}
          onAlignCamera={() =>
            startVideoCalibration(VideoCalibrationProcess.ALIGN_CAMERA)
          }
          onAlignTrackers={() =>
            startVideoCalibration(VideoCalibrationProcess.ALIGN_TRACKERS)
          }
          onOptimizeProportions={() =>
            startVideoCalibration(VideoCalibrationProcess.OPTIMIZE_BODY_PROPORTIONS)
          }
          estimatedUserHeightSummary={sidebarEstimatedHeightSummary}
        />
      }
    >
      <div className="h-full min-h-0 min-w-0">
        <VideoCalibrationContent
          videoRef={videoRef}
          skeletonViewRef={skeletonViewRef}
          calibrationCamera={calibrationCamera}
          calibrationStatus={calibrationStatus}
          trackerStatuses={trackerStatuses}
          showVideo={true}
          calibrationErrorNotification={calibrationErrorNotification}
          status={status}
          errorMessage={errorMessage}
          inboundVideoBitrateBps={webrtcInboundVideoBitrateBps}
        />
      </div>
    </MainLayout>
  );
}
