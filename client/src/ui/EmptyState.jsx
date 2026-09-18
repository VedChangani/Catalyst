const EmptyState = ({ title, description, action }) => {
  return (
    <div className="flex flex-col items-start gap-2 border-2 border-dashed border-ink/40 bg-paper px-6 py-10">
      <p className="text-2xl font-extrabold text-ink">{title}</p>
      {description && <p className="max-w-md text-muted">{description}</p>}
      {action}
    </div>
  );
};

export default EmptyState;
