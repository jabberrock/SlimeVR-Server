import { Canvas, Object3DNode, extend, useFrame, useLoader, useThree } from '@react-three/fiber';
import { Bone } from 'three';
import { useMemo, useEffect, useState } from 'react';
import {
  OrbitControls,
  OrthographicCamera,
  PerspectiveCamera,
  useGLTF,
} from '@react-three/drei';
import {
  BoneKind,
  createChildren,
  BasedSkeletonHelper,
} from '@/utils/skeletonHelper';
import * as THREE from 'three';
import { BodyPart, BoneT } from 'solarxr-protocol';
import { QuaternionFromQuatT, isIdentity } from '@/maths/quaternion';
import classNames from 'classnames';
import { Button } from '@/components/commons/Button';
import { useLocalization } from '@fluent/react';
import { ErrorBoundary } from 'react-error-boundary';
import { Typography } from '@/components/commons/Typography';
import { useAtomValue } from 'jotai';
import { bonesAtom } from '@/store/app-store';
import { Vector3FromVec3fT } from '@/maths/vector3';

extend({ BasedSkeletonHelper });

declare module '@react-three/fiber' {
  interface ThreeElements {
    basedSkeletonHelper: Object3DNode<
      BasedSkeletonHelper,
      typeof BasedSkeletonHelper
    >;
  }
}

const GROUND_COLOR = '#4444aa';
const FRUSTUM_SIZE = 10;
const FACTOR = 2;
// Not currently used but nice to have
export function OrthographicCameraWrapper() {
  const { size } = useThree();
  const aspect = useMemo(() => size.width / size.height, [size]);

  return (
    <OrthographicCamera
      makeDefault
      zoom={200}
      top={FRUSTUM_SIZE / FACTOR}
      bottom={FRUSTUM_SIZE / -FACTOR}
      left={(0.5 * FRUSTUM_SIZE * aspect) / -FACTOR}
      right={(0.5 * FRUSTUM_SIZE * aspect) / FACTOR}
      near={0.1}
      far={1000}
      position={[25, 75, 50]}
    />
  );
}

export function SkeletonHelper({ object }: { object: Bone }) {
  const { size } = useThree();
  const res = useMemo(() => new THREE.Vector2(size.width, size.height), [size]);

  return (
    <basedSkeletonHelper
      frustumCulled={false}
      resolution={res}
      args={[object]}
    />
  );
}

// Just need to know the length of the total body, so don't need right legs
const Y_PARTS = [
  BodyPart.NECK,
  BodyPart.UPPER_CHEST,
  BodyPart.CHEST,
  BodyPart.WAIST,
  BodyPart.HIP,
  BodyPart.LEFT_UPPER_LEG,
  BodyPart.LEFT_LOWER_LEG,
];

interface SkeletonVisualizerWidgetProps {
  height?: number | string;
  maxHeight?: number | string;
}

export function ToggleableSkeletonVisualizerWidget({
  height,
  maxHeight,
}: SkeletonVisualizerWidgetProps) {
  const { l10n } = useLocalization();
  const [enabled, setEnabled] = useState(false);

  useEffect(() => {
    const state = localStorage.getItem('skeletonModelPreview');
    if (state) setEnabled(state === 'true');
  }, []);

  return (
    <>
      {!enabled && (
        <Button
          variant="secondary"
          className="w-full"
          onClick={() => {
            setEnabled(true);
            localStorage.setItem('skeletonModelPreview', 'true');
          }}
        >
          {l10n.getString('widget-skeleton_visualizer-preview')}
        </Button>
      )}
      {enabled && (
        <div className="flex flex-col gap-2">
          <Button
            className="w-full"
            variant="secondary"
            onClick={() => {
              setEnabled(false);
              localStorage.setItem('skeletonModelPreview', 'false');
            }}
          >
            {l10n.getString('widget-skeleton_visualizer-hide')}
          </Button>
          <div
            style={{ height, maxHeight }}
            className="bg-background-60 p-1 rounded-md"
          >
            <SkeletonVisualizerWidget />
          </div>
        </div>
      )}
    </>
  );
}

type MannequinBone = {
  bodyPart: BodyPart,
  mBone: THREE.Object3D,
  mBoneLength: number,
  invert: boolean
};

type MannequinBones = Map<BodyPart, MannequinBone>;

function Mannequin({
  bones,
  headPosition,
}: {
  bones: Map<BodyPart, BoneT>,
  headPosition: THREE.Vector3,
}) {
  const mannequin = useGLTF('/models/mannequin.gltf');

  const mannequinBones = useMemo(() => {
    const m: MannequinBones = new Map();

    function addBone(bodyPart: BodyPart, boneName: string, boneLength: number, invert: boolean) {
      const mBone = mannequin.scene.getObjectByName(boneName);
      if (mBone) {
        m.set(bodyPart, { bodyPart, mBone, mBoneLength: boneLength, invert });
      }
    }

    addBone(BodyPart.HIP, 'mixamorigHips', 0.105592, true);
    addBone(BodyPart.WAIST, 'mixamorigSpine', 0.100027, true);
    addBone(BodyPart.CHEST, 'mixamorigSpine1', 0.0932207, true);
    addBone(BodyPart.UPPER_CHEST, 'mixamorigSpine2', 0.137015, true);
    addBone(BodyPart.NECK, 'mixamorigNeck', 0.0976436, true);
    addBone(BodyPart.LEFT_UPPER_LEG, 'mixamorigLeftUpLeg', 0.443714, false);
    addBone(BodyPart.LEFT_LOWER_LEG, 'mixamorigLeftLeg', 0.445279, false);
    addBone(BodyPart.LEFT_FOOT, 'mixamorigLeftFoot', 0.138169, false);
    addBone(BodyPart.RIGHT_UPPER_LEG, 'mixamorigRightUpLeg', 0.443714, false);
    addBone(BodyPart.RIGHT_LOWER_LEG, 'mixamorigRightLeg', 0.445279, false);
    addBone(BodyPart.RIGHT_FOOT, 'mixamorigRightFoot', 0.138169, false);

    return m;

  }, [mannequin]);

  function matchBone({bodyPart, mBone, mBoneLength, invert}: MannequinBone) {
    const bone = bones.get(bodyPart)
    if (!bone) { return; }
    const p = Vector3FromVec3fT(bone.headPositionG);
    const q = QuaternionFromQuatT(bone.rotationG);

    // The SlimeVR bone position is always at the top of the bone, and the
    // quaternion points "up" (why??).
    if (invert) {
      // The mannequin bone wants to point the other way, so shift the position
      // to the tail of the SlimeVR bone.
      p.sub(new THREE.Vector3(0, mBoneLength, 0).applyQuaternion(q));
    } else {
      // We need the mannequin bone rotation to point in the direction of the
      // bone, so rotate the bone around its x axis.
      q.multiply(new THREE.Quaternion(1, 0, 0, 0))
    }

    mBone.position.copy(headPosition).add(p);
    mBone.setRotationFromQuaternion(q);
    mBone.scale.set(1, bone.boneLength / mBoneLength, 1);
  }

  useFrame(() => {
    mannequinBones.forEach(b => matchBone(b))
  });

  return (
    <primitive object={mannequin.scene} />
  );
}

export function SkeletonVisualizerWidget() {
  const _bones = useAtomValue(bonesAtom);

  const { l10n } = useLocalization();
  const bones = useMemo(() => {
    return new Map(_bones.map((b) => [b.bodyPart, b]));
  }, [_bones]);

  const skeleton = useMemo(
    () => createChildren(bones, BoneKind.root),
    [bones.size]
  );

  useEffect(() => {
    skeleton.forEach(
      (bone) => bone instanceof BoneKind && bone.updateData(bones)
    );
  }, [bones]);

  const heightOffset = useMemo(() => {
    const hmd = bones.get(BodyPart.HEAD);
    // If I know the head position, don't use an offset
    if (hmd?.headPositionG?.y !== undefined && hmd.headPositionG?.y > 0) {
      return 0;
    }
    const yLength = Y_PARTS.map((x) => bones.get(x));
    if (yLength.some((x) => x === undefined)) return 0;
    return (yLength as BoneT[]).reduce((prev, cur) => prev + cur.boneLength, 0);
  }, [bones]);

  const targetCamera = useMemo(() => {
    const hmd = bones.get(BodyPart.HEAD);
    if (hmd?.headPositionG?.y && hmd.headPositionG.y > 0) {
      return hmd.headPositionG.y / 2;
    }
    return heightOffset / 2;
  }, [bones]);

  const yawReset = useMemo(() => {
    const hmd = bones.get(BodyPart.HEAD);
    const chest = bones.get(BodyPart.UPPER_CHEST);
    // Check if HMD is identity, if it's then use upper chest's rotation
    const quat = isIdentity(hmd?.rotationG)
      ? QuaternionFromQuatT(chest?.rotationG).normalize().invert()
      : QuaternionFromQuatT(hmd?.rotationG).normalize().invert();

    // Project quat to (0x, 1y, 0z)
    const VEC_Y = new THREE.Vector3(0, 1, 0);
    const vec = VEC_Y.multiplyScalar(
      new THREE.Vector3(quat.x, quat.y, quat.z).dot(VEC_Y) / VEC_Y.lengthSq()
    );
    return new THREE.Quaternion(vec.x, vec.y, vec.z, quat.w).normalize();
  }, [bones.size]);

  const scale = useMemo(
    () => Math.max(1.8, heightOffset) / 1.8,
    [heightOffset]
  );

  const headPosition = new THREE.Vector3(0, heightOffset, 0);

  if (!skeleton) return <></>;
  return (
    <ErrorBoundary
      fallback={
        <Typography color="primary" textAlign="text-center">
          {l10n.getString('tips-failed_webgl')}
        </Typography>
      }
    >
      <Canvas className={classNames('container mx-auto')}>
        <gridHelper args={[10, 50, GROUND_COLOR, GROUND_COLOR]} />
        <group position={headPosition} quaternion={yawReset}>
          <SkeletonHelper object={skeleton[0]}></SkeletonHelper>
        </group>
        <primitive object={skeleton[0]} />
        <Mannequin bones={bones} headPosition={headPosition} />
        <hemisphereLight color="gray" />
        <spotLight position={headPosition} />
        <PerspectiveCamera
          makeDefault
          position={[3, 2.5, -3]}
          fov={20}
          zoom={1 / scale}
        />
        <OrbitControls
          target={[0, targetCamera, 0]}
          maxDistance={20}
          maxPolarAngle={Math.PI / 2}
        />
      </Canvas>
    </ErrorBoundary>
  );
}
