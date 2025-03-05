import { useOnboarding } from '@/hooks/onboarding';
import { Typography } from '@/components/commons/Typography';
import { Step, StepperSlider } from '@/components/onboarding/StepperSlider';
import { DoneStep } from './mounting-steps/Done';
import { SittingResetStep } from './mounting-steps/SittingReset';
import { PreparationStep } from './mounting-steps/Preparation';
import { SittingLegsRaisedResetStep } from './mounting-steps/SittingLegsRaisedReset';
import { SittingLegsTogetherResetStep } from './mounting-steps/SittingLegsTogetherReset';
import { useLocalization } from '@fluent/react';
import { StandingResetStep } from './mounting-steps/StandingReset';
import { DemoStep } from './mounting-steps/Demo';

const steps: Step[] = [
  { type: 'numbered', component: DemoStep },
  { type: 'numbered', component: PreparationStep },
  { type: 'numbered', component: StandingResetStep },
  { type: 'numbered', component: SittingResetStep },
  { type: 'numbered', component: SittingLegsTogetherResetStep },
  { type: 'numbered', component: SittingLegsRaisedResetStep },
  { type: 'fullsize', component: DoneStep },
];
export function AutomaticMountingPage() {
  const { l10n } = useLocalization();
  const { applyProgress, state } = useOnboarding();

  applyProgress(0.7);

  return (
    <>
      <div className="flex flex-col gap-2 h-full items-center w-full xs:justify-center relative overflow-y-auto overflow-x-hidden px-4 pb-4">
        <div className="flex flex-col w-full h-full xs:justify-center xs:max-w-3xl gap-5">
          <div className="flex flex-col xs:max-w-lg gap-3">
            <Typography variant="main-title">
              {l10n.getString('onboarding-automatic_mounting-title')}
            </Typography>
            <Typography color="secondary">
              {l10n.getString('onboarding-automatic_mounting-description')}
            </Typography>
          </div>
          <div className="flex pb-4">
            <StepperSlider
              variant={state.alonePage ? 'alone' : 'onboarding'}
              steps={steps}
            ></StepperSlider>
          </div>
        </div>
      </div>
    </>
  );
}
