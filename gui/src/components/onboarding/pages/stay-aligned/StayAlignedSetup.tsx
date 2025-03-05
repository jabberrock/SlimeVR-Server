import { useOnboarding } from '@/hooks/onboarding';
import { Typography } from '@/components/commons/Typography';
import { Step, StepperSlider } from '@/components/onboarding/StepperSlider';
import { SittingResetStep } from '@/components/onboarding/pages/mounting/mounting-steps/SittingReset';
import { PreparationStep } from '@/components/onboarding/pages/mounting/mounting-steps/Preparation';
import { SittingLegsRaisedResetStep } from '@/components/onboarding/pages/mounting/mounting-steps/SittingLegsRaisedReset';
import { SittingLegsTogetherResetStep } from '@/components/onboarding/pages/mounting/mounting-steps/SittingLegsTogetherReset';
import { StandingResetStep } from '@/components/onboarding/pages/mounting/mounting-steps/StandingReset';
import { DoneStep } from './stay-aligned-steps/Done';
import { useLocalization } from '@fluent/react';
import { StandingRelaxedPoseStep } from './stay-aligned-steps/StandingRelaxedPose';
import { SittingRelaxedPoseStep } from './stay-aligned-steps/SittingRelaxedPose';
import { FlatRelaxedPoseStep } from './stay-aligned-steps/FlatRelaxedPose';
import { DemoStep } from '@/components/onboarding/pages/mounting/mounting-steps/Demo';

const steps: Step[] = [
  { type: 'numbered', component: DemoStep },
  { type: 'numbered', component: PreparationStep },
  { type: 'numbered', component: StandingResetStep },
  { type: 'numbered', component: SittingResetStep },
  { type: 'numbered', component: SittingLegsTogetherResetStep },
  { type: 'numbered', component: SittingLegsRaisedResetStep },
  { type: 'numbered', component: StandingRelaxedPoseStep },
  { type: 'numbered', component: SittingRelaxedPoseStep },
  { type: 'numbered', component: FlatRelaxedPoseStep },
  { type: 'fullsize', component: DoneStep },
];
export function StayAlignedSetupPage() {
  const { l10n } = useLocalization();
  const { applyProgress, state } = useOnboarding();

  applyProgress(0.7);

  return (
    <>
      <div className="flex flex-col gap-2 h-full items-center w-full xs:justify-center relative overflow-y-auto overflow-x-hidden px-4 pb-4">
        <div className="flex flex-col w-full h-full xs:justify-center xs:max-w-3xl gap-5">
          <div className="flex flex-col xs:max-w-lg gap-3">
            <Typography variant="main-title">
              {l10n.getString('onboarding-stay_aligned-title')}
            </Typography>
            <Typography color="secondary">
              {l10n.getString('onboarding-stay_aligned-description')}
            </Typography>
          </div>
          <div className="flex pb-4">
            <StepperSlider
              variant={state.alonePage ? 'alone' : 'onboarding'}
              steps={steps}
            />
          </div>
        </div>
      </div>
    </>
  );
}
