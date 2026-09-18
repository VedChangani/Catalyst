const StatCard = ({ label, value, icon, description, accent = false }) => {
  return (
    <div className={`flex items-center gap-4 border-2 border-ink bg-surface p-5 shadow-[3px_3px_0_#111827] ${accent ? "border-l-[4px] border-l-primary" : ""}`}>
      <div className="flex-1">
        <p className="text-xs font-extrabold uppercase tracking-[0.16em] text-muted">{label}</p>
        <p className="mt-1 text-3xl font-extrabold leading-none text-ink">{value}</p>
        {description && (
          <p className="mt-1.5 text-sm text-muted">{description}</p>
        )}
      </div>
      {icon && (
        <div className={`flex h-10 w-10 shrink-0 items-center justify-center border-2 border-ink text-lg ${accent ? "bg-primary/10 text-primary" : "bg-paper"}`}>
          {icon}
        </div>
      )}
    </div>
  );
};

export default StatCard;
