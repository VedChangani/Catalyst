const Input = ({ label, id, className = "", error, ...props }) => {
  return (
    <div className="flex w-full flex-col gap-1.5">
      {label && (
        <label htmlFor={id} className="text-sm font-bold text-ink">
          {label}
        </label>
      )}
      <input id={id} className={`nb-input ${className}`} {...props} />
      {error && (
        <p className="border-2 border-ink bg-danger/15 px-2 py-1 text-sm font-semibold text-danger">
          {error}
        </p>
      )}
    </div>
  );
};

export default Input;
