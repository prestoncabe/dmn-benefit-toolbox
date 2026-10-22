import { Accessor } from "solid-js";
import { useParams } from "@solidjs/router";

import BenefitList from "./benefitList/BenefitList";
import ConfigureBenefit from "./configureBenefit/ConfigureBenefit";
import type { BenefitDetail } from "@/types";

const ManageBenefits = ({
  benefitIdToConfigure,
  setBenefitIdToConfigure,
  onBenefitsChange,
}: {
  benefitIdToConfigure: Accessor<string | null>;
  setBenefitIdToConfigure: (benefitId: string | null) => void;
  onBenefitsChange?: (benefits: BenefitDetail[]) => void;
}) => {
  const params = useParams();

  return (
    <div>
      {benefitIdToConfigure() === null && (
        <BenefitList
          screenerId={() => params.screenerId}
          setBenefitIdToConfigure={setBenefitIdToConfigure}
          onBenefitsChange={onBenefitsChange}
        />
      )}
      {benefitIdToConfigure() !== null && (
        <ConfigureBenefit
          screenerId={() => params.screenerId}
          benefitId={benefitIdToConfigure}
          setBenefitId={setBenefitIdToConfigure}
        />
      )}
    </div>
  );
};
export default ManageBenefits;
