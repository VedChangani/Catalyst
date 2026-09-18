const LoadingState = ({ label = "Loading..." }) => {
  return (
    <div className="flex flex-col gap-4 p-2" role="status" aria-live="polite">
      <p className="text-sm font-bold uppercase tracking-widest text-muted">{label}</p>
      <div className="grid gap-3">
        <div className="h-16 animate-pulse border-2 border-ink/30 bg-primary/10" />
        <div className="h-16 animate-pulse border-2 border-ink/30 bg-surface" />
        <div className="h-16 animate-pulse border-2 border-ink/30 bg-coral/8" />
      </div>
    </div>
  );
};

export default LoadingState;
