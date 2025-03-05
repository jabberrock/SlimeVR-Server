import { Button } from '@/components/commons/Button';
import { Typography } from '@/components/commons/Typography';
import { DetectStayAlignedRelaxedPoseButton } from '@/components/settings/pages/components/stay-aligned/DetectRelaxedPoseButton';
import { useLocalization } from '@fluent/react';
import { StayAlignedRelaxedPose } from 'solarxr-protocol';

export function SittingRelaxedPoseStep({
  nextStep,
  prevStep,
  variant,
}: {
  nextStep: () => void;
  prevStep: () => void;
  variant: 'onboarding' | 'alone';
}) {
  const { l10n } = useLocalization();

  return (
    <div className="flex mobile:flex-col">
      <div className="flex flex-grow flex-col gap-4 max-w-sm">
        <Typography variant="main-title" bold>
          {l10n.getString(
            'onboarding-stay_aligned-relaxed_body_angles-sitting-title'
          )}
        </Typography>
        <div className="flex flex-col gap-2">
          <Typography color="secondary">
            {l10n.getString(
              'onboarding-stay_aligned-relaxed_body_angles-sitting-step-0'
            )}
          </Typography>
          <Typography color="secondary">
            {l10n.getString(
              'onboarding-stay_aligned-relaxed_body_angles-sitting-step-1'
            )}
          </Typography>
        </div>
        <div className="flex gap-3 mobile:justify-between">
          <Button
            variant={variant === 'onboarding' ? 'secondary' : 'tertiary'}
            onClick={prevStep}
          >
            {l10n.getString('onboarding-automatic_mounting-prev_step')}
          </Button>
          <DetectStayAlignedRelaxedPoseButton
            onClick={nextStep}
            pose={StayAlignedRelaxedPose.SITTING}
          />
        </div>
      </div>
      <div className="flex flex-col pt-1 items-center fill-background-50 justify-center px-12">
        <img
          src="/images/reset-sitting-pose.webp"
          width={200}
          alt="Reset position"
        />
      </div>
    </div>
  );
}
