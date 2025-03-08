import { FlatDeviceTracker, useAppContext } from '@/hooks/app';
import { normalizeAngleAroundZero, RAD_TO_DEG } from '@/maths/angle';
import { QuaternionFromQuatT } from '@/maths/quaternion';
import { Control } from 'react-hook-form';
import {
  BodyPart,
  StayAlignedPose,
  StayAlignedRelaxedPose,
} from 'solarxr-protocol';
import { SettingsForm } from '@/components/settings/pages/GeneralSettings';
import { CheckBox } from '@/components/commons/Checkbox';
import { WrenchIcon } from '@/components/commons/icon/WrenchIcons';
import { NumberSelector } from '@/components/commons/NumberSelector';
import { Typography } from '@/components/commons/Typography';
import { SettingsPagePaneLayout } from '@/components/settings/SettingsPageLayout';
import { useLocalization } from '@fluent/react';
import { useTrackers } from '@/hooks/tracker';
import { useLocaleConfig } from '@/i18n/config';
import { Euler } from 'three';
import { DetectStayAlignedRelaxedPoseButton } from './stay-aligned/DetectRelaxedPoseButton';
import { ResetStayAlignedRelaxedPoseButton } from './stay-aligned/ResetRelaxedPoseButton';

function poseStringId(pose?: StayAlignedPose) {
  switch (pose) {
    case StayAlignedPose.STANDING:
      return 'settings-general-stay_aligned-pose-standing';
    case StayAlignedPose.SITTING_IN_CHAIR:
      return 'settings-general-stay_aligned-pose-sitting_in_chair';
    case StayAlignedPose.SITTING_ON_GROUND:
      return 'settings-general-stay_aligned-pose-sitting_on_ground';
    case StayAlignedPose.LYING_ON_BACK:
      return 'settings-general-stay_aligned-pose-lying_on_back';
    case StayAlignedPose.KNEELING:
      return 'settings-general-stay_aligned-pose-kneeling';
    default:
      return 'settings-general-stay_aligned-pose-unknown';
  }
}

export function StayAlignedSettings({
  getValues,
  control,
}: {
  getValues: () => SettingsForm;
  control: Control<SettingsForm, any>;
}) {
  const { l10n } = useLocalization();
  const { currentLocales } = useLocaleConfig();
  const degreePerSecFormat = new Intl.NumberFormat(currentLocales, {
    style: 'unit',
    unit: 'degree-per-second',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
  const degreeFormat = new Intl.NumberFormat(currentLocales, {
    style: 'unit',
    unit: 'degree',
    maximumFractionDigits: 0,
  });

  const { state } = useAppContext();
  const { useConnectedIMUTrackers } = useTrackers();
  const trackers = useConnectedIMUTrackers();

  //
  // FIXME: REMOVE DEBUG CODE
  //
  //
  //
  //
  const values = getValues();

  const yawBetweenInDeg = (
    leftTracker: FlatDeviceTracker,
    rightTracker: FlatDeviceTracker
  ) => {
    const leftTrackerYaw = new Euler().setFromQuaternion(
      QuaternionFromQuatT(leftTracker.tracker.rotationReferenceAdjusted),
      'YZX'
    ).y;
    const rightTrackerYaw = new Euler().setFromQuaternion(
      QuaternionFromQuatT(rightTracker.tracker.rotationReferenceAdjusted),
      'YZX'
    ).y;
    const yawDelta = normalizeAngleAroundZero(leftTrackerYaw - rightTrackerYaw);
    return yawDelta * RAD_TO_DEG;
  };

  function findTracker(bodyPart: BodyPart): FlatDeviceTracker | undefined {
    return trackers.find((t) => t.tracker.info?.bodyPart === bodyPart);
  }

  const detectAngles = () => {
    let upperLegAngle = 0.0;
    const leftUpperLegTracker = findTracker(BodyPart.LEFT_UPPER_LEG);
    const rightUpperLegTracker = findTracker(BodyPart.RIGHT_UPPER_LEG);
    if (leftUpperLegTracker && rightUpperLegTracker) {
      const upperLegToBodyAngleInDeg =
        yawBetweenInDeg(leftUpperLegTracker, rightUpperLegTracker) / 2.0;
      upperLegAngle = Math.round(upperLegToBodyAngleInDeg);
    }

    let lowerLegAngle = 0.0;
    const leftLowerLegTracker = findTracker(BodyPart.LEFT_LOWER_LEG);
    const rightLowerLegTracker = findTracker(BodyPart.RIGHT_LOWER_LEG);
    if (leftLowerLegTracker && rightLowerLegTracker) {
      const lowerLegToBodyAngleInDeg =
        yawBetweenInDeg(leftLowerLegTracker, rightLowerLegTracker) / 2.0;
      lowerLegAngle = Math.round(lowerLegToBodyAngleInDeg);
    }

    let footValue = 0.0;
    const leftFootTracker = findTracker(BodyPart.LEFT_FOOT);
    const rightFootTracker = findTracker(BodyPart.RIGHT_FOOT);
    if (leftFootTracker && rightFootTracker) {
      const footToBodyAngleInDeg =
        yawBetweenInDeg(leftFootTracker, rightFootTracker) / 2.0;
      footValue = Math.round(footToBodyAngleInDeg);
    }

    return [upperLegAngle, lowerLegAngle, footValue];
  };

  const angles = detectAngles();

  //
  // ---------------------------------------------------------------
  //
  //
  //

  return (
    <SettingsPagePaneLayout icon={<WrenchIcon />} id="stayaligned">
      <Typography variant="main-title">
        {l10n.getString('settings-general-stay_aligned')}
      </Typography>
      <div className="flex flex-col pt-2 pb-4">
        {l10n.getString('settings-general-stay_aligned-description')}
        {values.yawCorrectionSettings.enabled && (
          <>
            {!!values.driftCompensation.enabled && (
              <div className="pt-2">
                {l10n.getString(
                  'settings-general-stay_aligned-warnings-drift_compensation'
                )}
              </div>
            )}
          </>
        )}
      </div>
      <div className="grid sm:grid-cols-1 gap-3 pb-4">
        <CheckBox
          variant="toggle"
          outlined
          control={control}
          name="yawCorrectionSettings.enabled"
          label={l10n.getString('settings-general-stay_aligned-enabled-label')}
        />
        <div className="flex flex-col pt-2 pb-4">
          <Typography bold>
            {l10n.getString('settings-general-stay_aligned-amount-label')}
          </Typography>
          <Typography color="secondary">
            {l10n.getString('settings-general-stay_aligned-amount-description')}
          </Typography>
          <NumberSelector
            control={control}
            name="yawCorrectionSettings.amountInDegPerSec"
            valueLabelFormat={(value) => degreePerSecFormat.format(value)}
            min={0.02}
            max={2.0}
            step={0.02}
          />
        </div>
        <div className="flex flex-col pt-2">
          <Typography bold>
            {l10n.getString(
              'settings-general-stay_aligned-relaxed_body_angles-label'
            )}
          </Typography>
          <Typography color="secondary">
            {l10n.getString(
              'settings-general-stay_aligned-relaxed_body_angles-description',
              {
                pose: l10n.getString(
                  poseStringId(state.datafeed?.stayAlignedPose)
                ),
                upperLeg: degreeFormat.format(angles[0]),
                lowerLeg: degreeFormat.format(angles[1]),
                foot: degreeFormat.format(angles[2]),
              }
            )}
          </Typography>
        </div>
        <div>
          <Typography color="secondary">
            {l10n.getString(
              'settings-general-stay_aligned-relaxed_body_angles-standing-label'
            )}
          </Typography>
          <div className="grid sm:grid-cols-5 gap-3 pb-3">
            <NumberSelector
              control={control}
              name="yawCorrectionSettings.standingUpperLegAngle"
              valueLabelFormat={(value) =>
                `${l10n.getString(
                  'settings-general-stay_aligned-relaxed_body_angles-upper_leg_angle'
                )}: ${degreeFormat.format(value)}`
              }
              min={-90.0}
              max={90.0}
              step={1.0}
            />
            <NumberSelector
              control={control}
              name="yawCorrectionSettings.standingLowerLegAngle"
              valueLabelFormat={(value) =>
                `${l10n.getString(
                  'settings-general-stay_aligned-relaxed_body_angles-lower_leg_angle'
                )}: ${degreeFormat.format(value)}`
              }
              min={-90.0}
              max={90.0}
              step={1.0}
            />
            <NumberSelector
              control={control}
              name="yawCorrectionSettings.standingFootAngle"
              valueLabelFormat={(value) =>
                `${l10n.getString(
                  'settings-general-stay_aligned-relaxed_body_angles-foot_angle'
                )}: ${degreeFormat.format(value)}`
              }
              min={-90.0}
              max={90.0}
              step={1.0}
            />
            <DetectStayAlignedRelaxedPoseButton
              pose={StayAlignedRelaxedPose.STANDING}
            />
            <ResetStayAlignedRelaxedPoseButton
              pose={StayAlignedRelaxedPose.STANDING}
            />
          </div>
        </div>
        <div>
          <Typography color="secondary">
            {l10n.getString(
              'settings-general-stay_aligned-relaxed_body_angles-sitting-label'
            )}
          </Typography>
          <div className="grid sm:grid-cols-5 gap-3 pb-3">
            <NumberSelector
              control={control}
              name="yawCorrectionSettings.sittingUpperLegAngle"
              valueLabelFormat={(value) =>
                `${l10n.getString(
                  'settings-general-stay_aligned-relaxed_body_angles-upper_leg_angle'
                )}: ${degreeFormat.format(value)}`
              }
              min={-90.0}
              max={90.0}
              step={1.0}
            />
            <NumberSelector
              control={control}
              name="yawCorrectionSettings.sittingLowerLegAngle"
              valueLabelFormat={(value) =>
                `${l10n.getString(
                  'settings-general-stay_aligned-relaxed_body_angles-lower_leg_angle'
                )}: ${degreeFormat.format(value)}`
              }
              min={-90.0}
              max={90.0}
              step={1.0}
            />
            <NumberSelector
              control={control}
              name="yawCorrectionSettings.sittingFootAngle"
              valueLabelFormat={(value) =>
                `${l10n.getString(
                  'settings-general-stay_aligned-relaxed_body_angles-foot_angle'
                )}: ${degreeFormat.format(value)}`
              }
              min={-90.0}
              max={90.0}
              step={1.0}
            />
            <DetectStayAlignedRelaxedPoseButton
              pose={StayAlignedRelaxedPose.SITTING}
            />
            <ResetStayAlignedRelaxedPoseButton
              pose={StayAlignedRelaxedPose.SITTING}
            />
          </div>
        </div>
        <div>
          <Typography color="secondary">
            {l10n.getString(
              'settings-general-stay_aligned-relaxed_body_angles-flat-label'
            )}
          </Typography>
          <div className="grid sm:grid-cols-5 gap-3 pb-3">
            <NumberSelector
              control={control}
              name="yawCorrectionSettings.flatUpperLegAngle"
              valueLabelFormat={(value) =>
                `${l10n.getString(
                  'settings-general-stay_aligned-relaxed_body_angles-upper_leg_angle'
                )}: ${degreeFormat.format(value)}`
              }
              min={-90.0}
              max={90.0}
              step={1.0}
            />
            <NumberSelector
              control={control}
              name="yawCorrectionSettings.flatLowerLegAngle"
              valueLabelFormat={(value) =>
                `${l10n.getString(
                  'settings-general-stay_aligned-relaxed_body_angles-lower_leg_angle'
                )}: ${degreeFormat.format(value)}`
              }
              min={-90.0}
              max={90.0}
              step={1.0}
            />
            <NumberSelector
              control={control}
              name="yawCorrectionSettings.flatFootAngle"
              valueLabelFormat={(value) =>
                `${l10n.getString(
                  'settings-general-stay_aligned-relaxed_body_angles-foot_angle'
                )}: ${degreeFormat.format(value)}`
              }
              min={-90.0}
              max={90.0}
              step={1.0}
            />
            <DetectStayAlignedRelaxedPoseButton
              pose={StayAlignedRelaxedPose.FLAT}
            />
            <ResetStayAlignedRelaxedPoseButton
              pose={StayAlignedRelaxedPose.FLAT}
            />
          </div>
        </div>
      </div>
    </SettingsPagePaneLayout>
  );
}
