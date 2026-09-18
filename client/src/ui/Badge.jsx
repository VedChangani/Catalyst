const tones = {
  default: "bg-surface text-ink",
  info: "bg-primary/15 text-primary",
  success: "bg-light-green text-success",
  warning: "bg-light-coral text-coral",
  danger: "bg-danger/15 text-danger",
  muted: "bg-paper text-ink",
};

const Badge = ({ children, tone = "default", className = "" }) => {
  return (
    <span
      className={`inline-flex items-center border-2 border-ink px-2.5 py-0.5 text-xs font-extrabold uppercase tracking-wide ${tones[tone] || tones.default} ${className}`}
    >
      {children}
    </span>
  );
};

export default Badge;
