const Card = ({ children, className = "", ...props }) => {
  return (
    <div
      className={`bg-surface border-2 border-ink shadow-[3px_3px_0_#111827] ${className}`}
      {...props}
    >
      {children}
    </div>
  );
};

export default Card;
