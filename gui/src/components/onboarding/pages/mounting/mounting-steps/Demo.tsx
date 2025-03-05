import { Button } from '@/components/commons/Button';
import { Typography } from '@/components/commons/Typography';
import { useLocalization } from '@fluent/react';

export function DemoStep({
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
          {l10n.getString('onboarding-automatic_mounting-demo-title')}
        </Typography>
        <iframe
          width="600"
          height="340"
          src="https://www.youtube.com/embed/GSgYsjM4zBs?si=zrK31Z7cpCspPOr0"
        ></iframe>
        <div className="flex gap-3 mobile:justify-between">
          <Button
            variant={variant === 'onboarding' ? 'secondary' : 'tertiary'}
            onClick={prevStep}
          >
            {l10n.getString('onboarding-automatic_mounting-prev_step')}
          </Button>
          <Button variant="primary" onClick={nextStep}>
            {l10n.getString('onboarding-automatic_mounting-next')}
          </Button>
        </div>
      </div>
    </div>
  );
}
