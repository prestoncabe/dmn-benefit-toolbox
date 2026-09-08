import { Button } from "@/components/shared/Button";

interface Props {
  onCancel: () => void;
  onArchive: () => Promise<void>;
}

export const ArchiveCheck = (props: Props) => {
  return (
    <div>
      <div class="text-2xl font-bold mb-3">Archive Check</div>
      <div class="mb-4">
        Are you sure you want to archive this Eligibility Check? You can restore
        it later from the archived checks section.
      </div>
      <div class="flex justify-end space-x-2">
        <Button variant="outline-secondary" onClick={props.onCancel}>
          Cancel
        </Button>
        <Button
          variant="outline-danger"
          onClick={() => {
            props.onArchive();
            props.onCancel();
          }}
        >
          Confirm
        </Button>
      </div>
    </div>
  );
};
